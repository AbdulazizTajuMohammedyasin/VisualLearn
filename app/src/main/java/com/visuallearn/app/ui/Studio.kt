package com.visuallearn.app.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.visuallearn.app.api.Figure
import com.visuallearn.app.api.FigureStatus
import kotlinx.coroutines.delay
import java.io.File
import java.time.Duration
import java.time.Instant
import kotlin.math.tan

// The figure plate is the one dark object in either theme: a screen the figure is lit on.
private val PlateColor = Color(0xFF121214)
private val PlateLine = Color(0xFF26262A)
private val PlateText = Color(0xFFF4F4F1)
private val PlateFog = Color(0xFF8B8B92)

/** A heading in two tones: what it is, then a quieter line on what to do. */
@Composable
fun TwoLineHeading(first: String, second: String, modifier: Modifier = Modifier, style: TextStyle = MaterialTheme.typography.headlineLarge) {
    Column(modifier) {
        Text(first, style = style, color = MaterialTheme.colorScheme.onBackground)
        Text(second, style = style, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun StatusDot(color: Color, modifier: Modifier = Modifier, size: Dp = 8.dp) {
    Box(modifier.size(size).background(color, CircleShape))
}

fun statusColor(figure: Figure): Color = when (figure.status) {
    FigureStatus.QUEUED -> StatusColors.Waiting
    FigureStatus.RUNNING -> StatusColors.Building
    FigureStatus.SUCCEEDED -> StatusColors.Ready
    FigureStatus.FAILED -> StatusColors.Failed
    FigureStatus.CANCELLED -> PlateFog
}

private val STAGES = listOf("research", "design", "parts", "geometry", "build", "motion", "verify", "polish")

private fun stageName(stage: String?) = when (stage) {
    "research" -> "Researching"
    "design" -> "Designing"
    "parts" -> "Listing parts"
    "geometry" -> "Shaping parts"
    "build" -> "Building"
    "motion" -> "Adding motion"
    "verify" -> "Checking"
    "polish" -> "Polishing"
    else -> "Starting"
}

/**
 * An isometric drafting grid, the paper Anatomy's figures are drawn on.
 * While a figure is being built it drifts slowly, the only idle motion in the app.
 */
@Composable
fun IsoGrid(modifier: Modifier = Modifier, moving: Boolean = false, color: Color = PlateLine) {
    val spacing = 22.dp
    val drift = if (moving) {
        val transition = rememberInfiniteTransition(label = "grid")
        transition.animateFloat(0f, 1f, infiniteRepeatable(tween(6000, easing = LinearEasing), RepeatMode.Restart), label = "drift").value
    } else 0f
    Canvas(modifier) {
        val step = spacing.toPx()
        val run = size.height / tan(Math.toRadians(30.0)).toFloat()
        // Lines at +30 and -30 degrees: one family climbs right, the other falls right.
        var x = -run - step * 2 + drift * step * 2
        while (x < size.width + step * 2) {
            drawLine(color, Offset(x, size.height), Offset(x + run, 0f), strokeWidth = 1f)
            drawLine(color, Offset(x, 0f), Offset(x + run, size.height), strokeWidth = 1f)
            x += step * 2
        }
    }
}

/** Eight segments, one per build stage; done ones solid, the current one in the status colour. */
@Composable
private fun StageTrack(stage: String?, color: Color, modifier: Modifier = Modifier) {
    val current = STAGES.indexOf(stage)
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        STAGES.forEachIndexed { i, _ ->
            val tint = when {
                i < current -> PlateText.copy(alpha = 0.7f)
                i == current -> color
                else -> PlateLine
            }
            Box(Modifier.weight(1f).height(3.dp).background(tint, CircleShape))
        }
    }
}

/** A figure in a chat reply: its build progress, then its preview to open. */
@Composable
fun FigurePlate(
    figure: Figure,
    preview: File?,
    onOpen: () -> Unit,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ready = figure.status == FigureStatus.SUCCEEDED
    val title = figure.title ?: figure.prompt
    if (ready && preview != null) {
        // Finished: the drawing on top, untouched, and what it is below it.
        Column(
            modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .background(PlateColor)
                .clickable(onClickLabel = "Open figure", onClick = onOpen),
        ) {
            Preview(preview, Modifier.fillMaxWidth().aspectRatio(4f / 3f))
            Row(Modifier.padding(start = 20.dp, end = 16.dp, top = 4.dp, bottom = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = PlateText, maxLines = 2,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(end = 12.dp))
                PlateAction("Open", primary = true, onClick = onOpen)
            }
        }
        return
    }
    Box(
        modifier
            .fillMaxWidth()
            .aspectRatio(4f / 3f)
            .clip(RoundedCornerShape(24.dp))
            .background(PlateColor)
            .then(if (ready) Modifier.clickable(onClickLabel = "Open figure", onClick = onOpen) else Modifier),
    ) {
        IsoGrid(Modifier.fillMaxSize(), moving = figure.status == FigureStatus.RUNNING)
        Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            when (figure.status) {
                FigureStatus.QUEUED, FigureStatus.RUNNING -> {
                    StatusLine(figure, if (figure.status == FigureStatus.QUEUED) "Waiting" else stageName(figure.stage),
                        trailing = if (figure.status == FigureStatus.RUNNING) "${STAGES.indexOf(figure.stage).coerceAtLeast(0) + 1} of ${STAGES.size}" else null)
                    PlateTitle(title)
                    Text(
                        if (figure.status == FigureStatus.QUEUED) queueLabel(figure) else figure.message ?: "Working on it.",
                        style = MaterialTheme.typography.bodySmall, color = PlateFog, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                    StageTrack(figure.stage, statusColor(figure))
                    PlateAction("Cancel", primary = false, onClick = onCancel)
                }
                FigureStatus.SUCCEEDED -> {
                    StatusLine(figure, "Ready")
                    PlateTitle(title)
                    PlateAction("Open figure", primary = true, onClick = onOpen)
                }
                FigureStatus.FAILED, FigureStatus.CANCELLED -> {
                    StatusLine(figure, if (figure.status == FigureStatus.CANCELLED) "Cancelled" else "Stopped")
                    PlateTitle(title)
                    if (figure.status == FigureStatus.FAILED) {
                        Text(figure.error?.message ?: "This figure couldn't be finished.", style = MaterialTheme.typography.bodySmall, color = PlateFog)
                    }
                    PlateAction("Try again", primary = true, onClick = onRetry)
                }
            }
        }
    }
}

/** The server's screenshot of the figure, zoomed past the page margin so the drawing fills the frame. */
@Composable
private fun Preview(file: File, modifier: Modifier) {
    AsyncImage(
        model = file,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = modifier.clipToBounds().graphicsLayer { scaleX = 1.12f; scaleY = 1.12f; translationY = size.height * 0.04f },
    )
}

@Composable
private fun StatusLine(figure: Figure, label: String, trailing: String? = null) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        StatusDot(statusColor(figure))
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = PlateText, modifier = Modifier.weight(1f))
        trailing?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = PlateFog) }
    }
}

@Composable
private fun PlateTitle(title: String) {
    Text(title, style = MaterialTheme.typography.titleLarge, color = PlateText, maxLines = 2, overflow = TextOverflow.Ellipsis)
}

@Composable
private fun PlateAction(label: String, primary: Boolean, onClick: () -> Unit) {
    if (primary) {
        Button(
            onClick = onClick,
            colors = ButtonDefaults.buttonColors(containerColor = PlateText, contentColor = Color.Black),
        ) { Text(label) }
    } else {
        TextButton(onClick = onClick, colors = ButtonDefaults.textButtonColors(contentColor = PlateFog)) { Text(label) }
    }
}

/** A figure as a small picture: in the home strip and the figures gallery. */
@Composable
fun FigureTile(figure: Figure, preview: File?, onClick: () -> Unit, modifier: Modifier = Modifier, onLongClick: (() -> Unit)? = null) {
    Column(
        modifier.clip(RoundedCornerShape(18.dp)).combinedClickable(onClick = onClick, onLongClick = onLongClick, onLongClickLabel = "More actions"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(4f / 3f)
                .clip(RoundedCornerShape(18.dp))
                .background(PlateColor)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(18.dp)),
        ) {
            if (preview != null) {
                Preview(preview, Modifier.fillMaxSize())
            } else {
                IsoGrid(Modifier.fillMaxSize(), moving = figure.status == FigureStatus.RUNNING)
            }
            if (figure.status != FigureStatus.SUCCEEDED) {
                Row(
                    Modifier.align(Alignment.TopStart).padding(10.dp).background(Color.Black.copy(alpha = 0.6f), CircleShape)
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    StatusDot(statusColor(figure), size = 6.dp)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        when (figure.status) {
                            FigureStatus.QUEUED -> "Waiting"
                            FigureStatus.RUNNING -> stageName(figure.stage)
                            FigureStatus.FAILED -> "Stopped"
                            else -> "Cancelled"
                        },
                        style = MaterialTheme.typography.labelSmall, color = PlateText,
                    )
                }
            }
        }
        Text(
            figure.title ?: figure.prompt,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
    }
}

/** "3rd in line, starts in about 4 min", ticking down between server updates. */
@Composable
private fun queueLabel(figure: Figure): String {
    val position = figure.queuePosition ?: return "Waiting for a free worker."
    val now by produceState(Instant.now()) {
        while (true) {
            delay(15_000)
            value = Instant.now()
        }
    }
    val place = if (position <= 1) "Next in line" else "${ordinal(position)} in line"
    val start = figure.estimatedStartAt?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: return place
    val minutes = Duration.between(now, start).toMinutes()
    val eta = when {
        minutes < 1 -> "starts in under a minute"
        minutes < 60 -> "starts in about $minutes min"
        else -> "starts in about ${(minutes + 30) / 60} h"
    }
    return "$place, $eta"
}

internal fun ordinal(n: Int): String {
    val suffix = if (n % 100 in 11..13) "th" else when (n % 10) {
        1 -> "st"
        2 -> "nd"
        3 -> "rd"
        else -> "th"
    }
    return "$n$suffix"
}
