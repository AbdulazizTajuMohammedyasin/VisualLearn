package com.visuallearn.app.chat

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.core.http.StreamResponse
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.errors.AnthropicIoException
import com.anthropic.errors.UnauthorizedException
import com.anthropic.helpers.MessageAccumulator
import com.anthropic.models.messages.CacheControlEphemeral
import com.anthropic.models.messages.ContentBlockParam
import com.anthropic.models.messages.Message
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.RawMessageStreamEvent
import com.anthropic.models.messages.StopReason
import com.anthropic.models.messages.Tool
import com.anthropic.models.messages.ToolResultBlockParam
import com.anthropic.models.messages.ToolUseBlock

/** A figure the model asked for through the create_figure tool. */
data class FigureRequest(val prompt: String, val theme: String)

class ChatException(message: String) : Exception(message)

/**
 * Streams chat replies from Claude. Claude can call create_figure to start a long
 * figure job; [onFigure] starts it and returns a short status that goes back to
 * Claude as the tool result, so the same reply can tell the user what happens next.
 */
class ChatEngine(private val apiKey: () -> String?) {

    private var client: AnthropicClient? = null
    private var clientKey: String? = null
    @Volatile private var activeStream: StreamResponse<RawMessageStreamEvent>? = null
    @Volatile private var stopped = false

    /** Blocking; call from a background thread. Returns the full reply text. */
    fun reply(
        history: List<ChatMessage>,
        figureMode: Boolean,
        onText: (String) -> Unit,
        onFigure: (FigureRequest) -> String,
    ): String {
        stopped = false
        val api = client()
        val params = baseParams(history, figureMode)
        val reply = StringBuilder()

        val message = stream(api, params.build(), reply, onText)
        val toolUses = message.content().mapNotNull { it.toolUse().orElse(null) }
        if (stopped || toolUses.isEmpty() || message.stopReason().orElse(null) == StopReason.MAX_TOKENS) {
            return reply.toString()
        }

        val results = toolUses.map { use -> ContentBlockParam.ofToolResult(runTool(use, onFigure)) }
        params.addMessage(message).addUserMessageOfContentBlockParams(results)
        if (reply.isNotEmpty()) reply.append("\n\n")
        stream(api, params.build(), reply, onText)
        return reply.toString()
    }

    fun stop() {
        stopped = true
        activeStream?.close()
    }

    private fun runTool(use: ToolUseBlock, onFigure: (FigureRequest) -> String): ToolResultBlockParam {
        val input = runCatching { use._input().convert(Map::class.java) }.getOrNull()
        val prompt = input?.get("prompt") as? String
        val builder = ToolResultBlockParam.builder().toolUseId(use.id())
        if (use.name() != "create_figure" || prompt.isNullOrBlank()) {
            return builder.content("INVALID_INPUT: create_figure needs a non-empty prompt.").isError(true).build()
        }
        val theme = (input["theme"] as? String)?.takeIf { it == "light" || it == "dark" } ?: "dark"
        return builder.content(onFigure(FigureRequest(prompt, theme))).build()
    }

    private fun stream(
        api: AnthropicClient,
        params: MessageCreateParams,
        reply: StringBuilder,
        onText: (String) -> Unit,
    ): Message {
        val accumulator = MessageAccumulator.create()
        try {
            api.messages().createStreaming(params).use { response ->
                activeStream = response
                response.stream().forEach { event ->
                    accumulator.accumulate(event)
                    event.contentBlockDelta().flatMap { it.delta().text() }.ifPresent { delta ->
                        reply.append(delta.text())
                        onText(reply.toString())
                    }
                }
            }
        } catch (e: UnauthorizedException) {
            throw ChatException("Your Claude API key was rejected. Check it in Settings.")
        } catch (e: AnthropicServiceException) {
            if (!stopped) throw ChatException("Claude API error ${e.statusCode()}: ${e.message}")
        } catch (e: AnthropicIoException) {
            if (!stopped) throw ChatException("Couldn't reach Claude. Check your connection.")
        } catch (e: IllegalStateException) {
            if (!stopped) throw e // closing the stream from stop() surfaces here
        } finally {
            activeStream = null
        }
        return accumulator.message()
    }

    private fun baseParams(history: List<ChatMessage>, figureMode: Boolean): MessageCreateParams.Builder {
        val builder = MessageCreateParams.builder()
            .model(MODEL)
            .maxTokens(32000L)
            .system(SYSTEM_PROMPT)
            .addTool(FIGURE_TOOL)
            .outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.LOW).build())
            .cacheControl(CacheControlEphemeral.builder().build())
            // Server-side refusal fallbacks: a declined turn is retried on a fallback model.
            .putAdditionalHeader("anthropic-beta", "server-side-fallback-2026-07-01")
            .putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
        history.forEachIndexed { index, m ->
            val isLast = index == history.lastIndex
            when (m.role) {
                Role.USER -> builder.addUserMessage(
                    if (isLast && figureMode) m.text + FIGURE_MODE_NOTE else m.text
                )
                Role.ASSISTANT -> {
                    val text = buildString {
                        append(m.text)
                        if (m.figureJobId != null) append("\n\n[A figure job was started for this request.]")
                    }.trim()
                    if (text.isNotEmpty()) builder.addAssistantMessage(text)
                }
            }
        }
        return builder
    }

    private fun client(): AnthropicClient {
        val key = apiKey() ?: throw ChatException("Add your Claude API key in Settings to start chatting.")
        if (client == null || key != clientKey) {
            client?.close()
            client = AnthropicOkHttpClient.builder().apiKey(key).build()
            clientKey = key
        }
        return client!!
    }

    companion object {
        const val MODEL = "claude-opus-5-5"

        private const val FIGURE_MODE_NOTE =
            "\n\n(The user turned on figure mode for this message: call create_figure for it.)"

        private val SYSTEM_PROMPT = """
            You are VisualLearn, a patient tutor in an Android app. You explain technical and scientific ideas clearly, in short paragraphs with Markdown where it helps.

            The app can also build an interactive, isometric figure: a small, detailed machine whose working is the idea, with moving parts and a live readout. You request one with the create_figure tool. A figure takes roughly 20 to 75 minutes to build on the user's phone and costs real API credit, so:
            - Call create_figure when the user asks to see, visualize, draw or build something, or agrees when you offer.
            - Otherwise, answer in text first, and offer a figure only when a mechanism would be much clearer as one.
            - Write the figure prompt as a precise brief: the idea to explain, the real numbers involved (sizes, counts, rates, thresholds), what should move, and the object if the user named one.
            After starting a figure, tell the user it is being built and that a card in the chat shows its progress.
        """.trimIndent()

        private val FIGURE_TOOL: Tool = Tool.builder()
            .name("create_figure")
            .description(
                "Start building an interactive isometric figure that explains an idea as a working machine. " +
                    "Runs in the background for 20-75 minutes; returns a status message immediately."
            )
            .eagerInputStreaming(true)
            .inputSchema(
                Tool.InputSchema.builder()
                    .properties(
                        Tool.InputSchema.Properties.builder()
                            .putAdditionalProperty(
                                "prompt",
                                JsonValue.from(mapOf("type" to "string", "description" to "The figure brief.")),
                            )
                            .putAdditionalProperty(
                                "theme",
                                JsonValue.from(mapOf("type" to "string", "enum" to listOf("dark", "light"))),
                            )
                            .build()
                    )
                    .required(listOf("prompt"))
                    .build()
            )
            .build()
    }
}
