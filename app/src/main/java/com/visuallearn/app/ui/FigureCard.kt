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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.visuallearn.app.jobs.FigureJob
import com.visuallearn.app.jobs.JobState
import java.util.Locale

private val STAGES = listOf("research", "design", "parts", "geometry", "build", "motion", "verify", "polish")

@Composable
fun FigureCard(
    job: FigureJob,
    onOpen: () -> Unit,
    onCancel: () -> Unit,
    onResume: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        onClick = { if (job.state == JobState.DONE) onOpen() },
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "FIG",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.secondary,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    job.title ?: job.prompt,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
            when (job.state) {
                JobState.QUEUED, JobState.RUNNING -> {
                    val stageIndex = STAGES.indexOf(job.stage)
                    if (stageIndex >= 0) {
                        LinearProgressIndicator(
                            progress = { (stageIndex + 1f) / (STAGES.size + 1) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                    Text(
                        job.message ?: "Working…",
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        buildString {
                            job.stage?.let { append(it.replaceFirstChar(Char::uppercase)); append(" · ") }
                            append(formatElapsed(job.elapsedSec))
                            if (job.costUsd > 0) append(" · ${formatCost(job.costUsd)}")
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = onCancel) { Text("Cancel") }
                    }
                }
                JobState.DONE -> {
                    job.summary?.takeIf { it.isNotBlank() }?.let {
                        Text(it, style = MaterialTheme.typography.bodyMedium, maxLines = 4, overflow = TextOverflow.Ellipsis)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (job.costUsd > 0) "Ready · ${formatCost(job.costUsd)}" else "Ready",
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
                JobState.FAILED, JobState.INTERRUPTED, JobState.CANCELLED -> {
                    Text(
                        when (job.state) {
                            JobState.CANCELLED -> "Cancelled."
                            else -> job.error ?: "Stopped before it finished."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (job.state == JobState.FAILED) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = onResume) {
                            Icon(Icons.Filled.Refresh, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Resume")
                        }
                    }
                }
            }
        }
    }
}

fun formatElapsed(seconds: Int): String =
    if (seconds < 60) "${seconds}s" else "${seconds / 60} min"

fun formatCost(usd: Double): String = String.format(Locale.US, "$%.2f", usd)
