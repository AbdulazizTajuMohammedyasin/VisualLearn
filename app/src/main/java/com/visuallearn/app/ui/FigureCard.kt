package com.visuallearn.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.visuallearn.app.api.Figure
import com.visuallearn.app.api.FigureStatus
import kotlinx.coroutines.delay
import java.time.Duration
import java.time.Instant

@Composable
fun FigureCard(
    figure: Figure,
    onOpen: () -> Unit,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        onClick = { if (figure.status == FigureStatus.SUCCEEDED) onOpen() },
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("FIG", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
                Spacer(Modifier.width(8.dp))
                Text(
                    figure.title ?: figure.prompt,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
            when (figure.status) {
                FigureStatus.QUEUED, FigureStatus.RUNNING -> {
                    val progress = figure.progress
                    if (progress != null && progress > 0) {
                        LinearProgressIndicator(progress = { progress.toFloat() }, modifier = Modifier.fillMaxWidth())
                    } else {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                    Text(
                        figure.message ?: "Working…",
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            figure.stage?.replaceFirstChar(Char::uppercase) ?: queueLabel(figure),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = onCancel) { Text("Cancel") }
                    }
                }
                FigureStatus.SUCCEEDED -> {
                    figure.summary?.takeIf { it.isNotBlank() }?.let {
                        Text(it, style = MaterialTheme.typography.bodyMedium, maxLines = 4, overflow = TextOverflow.Ellipsis)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Ready",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = onOpen) {
                            Icon(Icons.Filled.PlayArrow, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Open figure")
                        }
                    }
                }
                FigureStatus.FAILED, FigureStatus.CANCELLED -> {
                    Text(
                        if (figure.status == FigureStatus.CANCELLED) "Cancelled." else figure.error?.message ?: "This figure couldn't be finished.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (figure.status == FigureStatus.FAILED) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = onRetry) {
                            Icon(Icons.Filled.Refresh, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Try again")
                        }
                    }
                }
            }
        }
    }
}

/** "3rd in line · starts in about 4 min", ticking down between server updates. */
@Composable
private fun queueLabel(figure: Figure): String {
    val position = figure.queuePosition ?: return "Queued"
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
    return "$place · $eta"
}

private fun ordinal(n: Int): String {
    val suffix = if (n % 100 in 11..13) "th" else when (n % 10) {
        1 -> "st"
        2 -> "nd"
        3 -> "rd"
        else -> "th"
    }
    return "$n$suffix"
}
