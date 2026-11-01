package com.visuallearn.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private sealed interface Block {
    data class Paragraph(val text: String) : Block
    data class Heading(val level: Int, val text: String) : Block
    data class Bullet(val marker: String, val text: String, val indent: Int) : Block
    data class Code(val text: String) : Block
    data class Quote(val text: String) : Block
}

/** Renders the subset of Markdown chat replies use. */
@Composable
fun MarkdownText(markdown: String, modifier: Modifier = Modifier) {
    val blocks = remember(markdown) { parse(markdown) }
    val codeBg = MaterialTheme.colorScheme.surfaceVariant
    val inlineCode = SpanStyle(fontFamily = FontFamily.Monospace, background = codeBg, fontSize = 14.sp)
    SelectionContainer(modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            blocks.forEach { block ->
                when (block) {
                    is Block.Paragraph -> Text(inline(block.text, inlineCode), style = MaterialTheme.typography.bodyLarge)
                    is Block.Heading -> Text(
                        inline(block.text, inlineCode),
                        style = when (block.level) {
                            1 -> MaterialTheme.typography.titleLarge
                            2 -> MaterialTheme.typography.titleMedium
                            else -> MaterialTheme.typography.titleSmall
                        },
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    is Block.Bullet -> Row(Modifier.padding(start = (block.indent * 16).dp)) {
                        Text(block.marker, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.width(22.dp))
                        Text(inline(block.text, inlineCode), style = MaterialTheme.typography.bodyLarge)
                    }
                    is Block.Code -> Text(
                        block.text,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(codeBg, RoundedCornerShape(10.dp))
                            .horizontalScroll(rememberScrollState())
                            .padding(12.dp),
                    )
                    is Block.Quote -> Text(
                        inline(block.text, inlineCode),
                        style = MaterialTheme.typography.bodyLarge,
                        fontStyle = FontStyle.Italic,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 12.dp),
                    )
                }
            }
        }
    }
}

private fun parse(markdown: String): List<Block> {
    val blocks = mutableListOf<Block>()
    val paragraph = StringBuilder()
    fun flush() {
        if (paragraph.isNotBlank()) blocks += Block.Paragraph(paragraph.toString().trim())
        paragraph.clear()
    }
    val lines = markdown.lines()
    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        val trimmed = line.trimStart()
        val indent = (line.length - trimmed.length) / 2
        when {
            trimmed.startsWith("```") -> {
                flush()
                val code = StringBuilder()
                i++
                while (i < lines.size && !lines[i].trimStart().startsWith("```")) {
                    code.appendLine(lines[i]); i++
                }
                blocks += Block.Code(code.toString().trimEnd())
            }
            trimmed.startsWith("#") -> {
                flush()
                val level = trimmed.takeWhile { it == '#' }.length
                blocks += Block.Heading(level, trimmed.drop(level).trim())
            }
            trimmed.matches(Regex("^[-*+] .*")) -> {
                flush(); blocks += Block.Bullet("•", trimmed.drop(2), indent)
            }
            trimmed.matches(Regex("^\\d+[.)] .*")) -> {
                flush()
                val marker = trimmed.substringBefore(' ')
                blocks += Block.Bullet(marker, trimmed.substringAfter(' '), indent)
            }
            trimmed.startsWith(">") -> {
                flush(); blocks += Block.Quote(trimmed.drop(1).trim())
            }
            trimmed.isEmpty() -> flush()
            else -> paragraph.append(if (paragraph.isEmpty()) trimmed else " $trimmed")
        }
        i++
    }
    flush()
    return blocks
}

private val INLINE = Regex("""\*\*(.+?)\*\*|`([^`]+)`|\*(.+?)\*|_(.+?)_""")

private fun inline(text: String, code: SpanStyle): AnnotatedString = buildAnnotatedString {
    var last = 0
    for (m in INLINE.findAll(text)) {
        append(text.substring(last, m.range.first))
        val (bold, mono, italic, italic2) = m.destructured
        when {
            bold.isNotEmpty() -> withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(bold) }
            mono.isNotEmpty() -> withStyle(code) { append(mono) }
            italic.isNotEmpty() -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(italic) }
            else -> withStyle(SpanStyle(fontStyle = FontStyle.Italic, color = Color.Unspecified)) { append(italic2) }
        }
        last = m.range.last + 1
    }
    append(text.substring(last))
}
