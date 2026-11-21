package dev.shelfwatch.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.ScalingLazyListScope
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.Colors
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.PositionIndicator
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import coil.compose.AsyncImage
import dev.shelfwatch.Graph
import dev.shelfwatch.R
import dev.shelfwatch.str
import java.util.Locale

val ShelfColors = Colors(
    primary = Color(0xFFFFB74D),
    primaryVariant = Color(0xFFF57C00),
    secondary = Color(0xFF80CBC4),
    onPrimary = Color.Black,
    onSecondary = Color.Black,
    surface = Color(0xFF202124),
    onSurface = Color.White,
    onSurfaceVariant = Color(0xFFDADCE0),
    background = Color.Black,
)

/** Standard-Listenlayout für runde Displays. */
@Composable
fun ScreenList(content: ScalingLazyListScope.() -> Unit) {
    val state = rememberScalingLazyListState()
    Scaffold(
        timeText = { TimeText() },
        positionIndicator = { PositionIndicator(scalingLazyListState = state) },
    ) {
        ScalingLazyColumn(
            state = state,
            modifier = Modifier.fillMaxSize(),
            content = content,
        )
    }
}

data class BookRow(
    val id: String,
    val title: String,
    val author: String,
    val progress: Double?,
    val finished: Boolean,
    val downloaded: Boolean,
)

@Composable
fun BookChip(row: BookRow, onClick: () -> Unit) {
    Chip(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
        colors = ChipDefaults.secondaryChipColors(),
        icon = {
            Cover(
                row.id,
                Modifier.size(ChipDefaults.LargeIconSize).clip(RoundedCornerShape(4.dp)),
            )
        },
        label = { Text(row.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        secondaryLabel = {
            val parts = buildList {
                if (row.author.isNotBlank()) add(row.author)
                when {
                    row.finished -> add(str(R.string.finished))
                    row.progress != null && row.progress > 0.0 -> add("${(row.progress * 100).toInt()} %")
                }
                if (row.downloaded) add("⬇")
            }
            Text(parts.joinToString(" · "), maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
    )
}

@Composable
fun Cover(itemId: String, modifier: Modifier = Modifier) {
    val local = Graph.downloads.coverFile(itemId)
    val model: Any = if (local.exists()) local else Graph.api.coverUrl(itemId, 160)
    AsyncImage(
        model = model,
        contentDescription = null,
        modifier = modifier,
        contentScale = ContentScale.Crop,
        placeholder = painterResource(R.drawable.ic_book),
        error = painterResource(R.drawable.ic_book),
    )
}

@Composable
fun InputField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    keyboardType: KeyboardType,
    password: Boolean = false,
) {
    Column(Modifier.fillMaxWidth()) {
        Text(
            label,
            style = MaterialTheme.typography.caption2,
            color = MaterialTheme.colors.onSurfaceVariant,
            modifier = Modifier.padding(start = 8.dp, bottom = 2.dp),
        )
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = TextStyle(color = Color.White, fontSize = 14.sp),
            cursorBrush = SolidColor(MaterialTheme.colors.primary),
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = ImeAction.Done),
            visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colors.surface, RoundedCornerShape(16.dp))
                .padding(horizontal = 14.dp, vertical = 10.dp),
        )
    }
}

fun formatClock(sec: Double): String {
    val s = sec.toLong().coerceAtLeast(0)
    val h = s / 3600
    val m = (s % 3600) / 60
    val ss = s % 60
    return if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d", h, m, ss)
    else String.format(Locale.ROOT, "%d:%02d", m, ss)
}

fun formatDuration(sec: Double): String {
    val totalMin = (sec / 60).toLong()
    val h = totalMin / 60
    return if (h > 0) "$h h ${totalMin % 60} min" else "$totalMin min"
}

fun formatBytes(b: Long): String = when {
    b >= 1L shl 30 -> String.format(Locale.GERMANY, "%.1f GB", b / (1L shl 30).toDouble())
    b >= 1L shl 20 -> String.format(Locale.GERMANY, "%.0f MB", b / (1L shl 20).toDouble())
    else -> "${b / 1024} KB"
}

fun formatAgo(ms: Long, now: Long = System.currentTimeMillis()): String {
    if (ms <= 0) return str(R.string.ago_never)
    val d = (now - ms) / 1000
    return when {
        d < 10 -> str(R.string.ago_now)
        d < 60 -> str(R.string.ago_seconds, d.toInt())
        d < 3600 -> str(R.string.ago_minutes, (d / 60).toInt())
        d < 86400 -> str(R.string.ago_hours, (d / 3600).toInt())
        else -> str(R.string.ago_days, (d / 86400).toInt())
    }
}
