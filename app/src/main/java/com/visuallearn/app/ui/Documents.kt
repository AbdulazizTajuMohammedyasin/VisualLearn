package com.visuallearn.app.ui

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.visuallearn.app.chat.PdfAttachment
import java.util.Locale

/** A page with a folded corner: how a document is drawn wherever one appears. */
@Composable
fun PageGlyph(modifier: Modifier = Modifier, size: Dp = 22.dp, color: Color = MaterialTheme.colorScheme.onSurface) {
    Canvas(modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val left = w * 0.18f
        val right = w * 0.82f
        val fold = w * 0.22f
        val stroke = Stroke(width = w * 0.075f, join = StrokeJoin.Round)
        val outline = Path().apply {
            moveTo(left, h * 0.06f)
            lineTo(right - fold, h * 0.06f)
            lineTo(right, h * 0.06f + fold)
            lineTo(right, h * 0.94f)
            lineTo(left, h * 0.94f)
            close()
        }
        drawPath(outline, color, style = stroke)
        drawPath(Path().apply { moveTo(right - fold, h * 0.06f); lineTo(right - fold, h * 0.06f + fold); lineTo(right, h * 0.06f + fold) }, color, style = stroke)
        // Two lines of text.
        for (y in listOf(0.52f, 0.7f)) drawLine(color, Offset(left + w * 0.14f, h * y), Offset(right - w * 0.14f, h * y), strokeWidth = w * 0.06f)
    }
}

/** A document in a sent message. */
@Composable
fun DocumentChip(name: String, detail: String, modifier: Modifier = Modifier, onRemove: (() -> Unit)? = null) {
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = modifier.widthIn(max = 300.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 12.dp, end = if (onRemove != null) 2.dp else 14.dp, top = 10.dp, bottom = 10.dp)) {
            PageGlyph(size = 26.dp)
            Column(Modifier.padding(start = 10.dp).weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
                Text(detail, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (onRemove != null) {
                IconButton(onClick = onRemove, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Filled.Close, "Remove $name", Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

fun pagesLabel(pages: Int) = if (pages == 1) "PDF, 1 page" else "PDF, $pages pages"

fun sizeLabel(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> String.format(Locale.US, "PDF, %.1f MB", bytes / (1024.0 * 1024.0))
    else -> "PDF, ${(bytes / 1024).coerceAtLeast(1)} KB"
}

/** A picked file as an attachment: its name and size now, its bytes when it is sent. */
fun pdfAttachment(context: Context, uri: Uri): PdfAttachment {
    var name = "Document.pdf"
    var size = 0L
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
        if (c.moveToFirst()) {
            c.getString(0)?.let { name = it }
            if (!c.isNull(1)) size = c.getLong(1)
        }
    }
    return PdfAttachment(name, size) {
        context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: error("Couldn't open $name")
    }
}
