package ai.vox.app.ui.components

import ai.vox.app.core.model.Capability
import ai.vox.app.core.model.Role
import ai.vox.app.core.model.Turn
import ai.vox.app.core.session.SessionState
import ai.vox.app.ui.theme.VoxColors
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Camera
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.automirrored.filled.ScreenShare
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp

fun Capability.icon(): ImageVector = when (this) {
    Capability.VOICE -> Icons.Filled.Mic
    Capability.SCREEN -> Icons.AutoMirrored.Filled.ScreenShare
    Capability.CAMERA -> Icons.Filled.Camera
    Capability.LIVE_VIDEO -> Icons.Filled.Visibility
    Capability.NOTIFICATIONS -> Icons.Filled.Notifications
}

/** A glowing primary action. */
@Composable
fun GlowButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, danger: Boolean = false) {
    val shape = RoundedCornerShape(50)
    val brush = if (danger) androidx.compose.ui.graphics.Brush.horizontalGradient(listOf(VoxColors.Danger, Color(0xFFB0003A))) else VoxColors.Accent
    Box(
        modifier
            .alpha(if (enabled) 1f else 0.4f)
            .drawBehind {
                drawRoundRect(
                    brush = androidx.compose.ui.graphics.Brush.radialGradient(listOf((if (danger) VoxColors.Danger else VoxColors.Cyan).copy(alpha = 0.35f), Color.Transparent), center, size.maxDimension * 0.7f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.height),
                )
            }
            .clip(shape)
            .background(brush)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 28.dp, vertical = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge, color = if (danger) Color.White else Color(0xFF00141A))
    }
}

@Composable
fun GhostButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, danger: Boolean = false) {
    OutlinedButton(
        onClick = onClick, modifier = modifier, enabled = enabled,
        border = BorderStroke(1.dp, if (danger) VoxColors.Danger else VoxColors.Outline),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = if (danger) VoxColors.Danger else VoxColors.Cyan),
    ) { Text(text, style = MaterialTheme.typography.labelLarge) }
}

@Composable
fun Panel(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(VoxColors.Surface).border(1.dp, VoxColors.Outline, RoundedCornerShape(20.dp)).padding(16.dp),
    ) { content() }
}

@Composable
fun ScreenScaffold(
    title: String,
    onBack: () -> Unit,
    actions: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {},
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = VoxColors.Text) }
            Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            actions()
        }
        content()
    }
}

/**
 * Always-visible access indicator. When a session is active it lists exactly what Vox can currently use,
 * and always offers END SESSION.
 */
@Composable
fun SessionBar(state: SessionState, onEnd: () -> Unit, modifier: Modifier = Modifier) {
    val t = rememberInfiniteTransition(label = "dot")
    val dot by t.animateFloat(0.35f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "dot")
    Column(
        modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(16.dp)).background(VoxColors.Surface).border(1.dp, if (state.active) VoxColors.Cyan.copy(alpha = 0.5f) else VoxColors.Outline, RoundedCornerShape(16.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(9.dp).alpha(if (state.active) dot else 1f).clip(CircleShape).background(if (state.active) VoxColors.Ok else VoxColors.Muted))
            Spacer(Modifier.width(8.dp))
            Text(
                if (state.active) "SESSION ACTIVE" else "VOX IS OFF",
                style = MaterialTheme.typography.labelSmall, color = if (state.active) VoxColors.Ok else VoxColors.Muted, modifier = Modifier.weight(1f),
            )
            if (state.active) {
                Text(
                    "END SESSION", style = MaterialTheme.typography.labelSmall, color = VoxColors.Danger,
                    modifier = Modifier.clip(RoundedCornerShape(50)).border(1.dp, VoxColors.Danger, RoundedCornerShape(50)).clickable(onClick = onEnd).padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        if (!state.active) {
            Text("Nothing is being listened to, captured, or read.", style = MaterialTheme.typography.bodyMedium, color = VoxColors.Muted)
        } else if (state.enabled.isEmpty()) {
            Text("Active, but not accessing mic, screen, camera or notifications.", style = MaterialTheme.typography.bodyMedium, color = VoxColors.Muted)
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Capability.entries.filter { it in state.enabled }.forEach { cap ->
                    Row(
                        Modifier.clip(RoundedCornerShape(50)).background(VoxColors.SurfaceHigh).padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(cap.icon(), null, tint = VoxColors.Cyan, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(cap.statusText(), style = MaterialTheme.typography.labelSmall, color = VoxColors.Text)
                    }
                }
            }
        }
    }
}

private fun Capability.statusText() = when (this) {
    Capability.VOICE -> "Mic on"
    Capability.SCREEN -> "Screen awareness active"
    Capability.CAMERA -> "Camera on"
    Capability.LIVE_VIDEO -> "Live analysis"
    Capability.NOTIFICATIONS -> "Notifications"
}

/** Tiny Markdown renderer: **bold**, `code`, # headings and bullet markers - enough for model output. */
fun markdownToAnnotated(src: String): AnnotatedString = buildAnnotatedString {
    src.lines().forEachIndexed { idx, raw ->
        if (idx > 0) append("\n")
        var line = raw
        val heading = Regex("^\\s{0,3}#{1,6}\\s+").find(line)
        if (heading != null) {
            line = line.removeRange(heading.range)
            withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = VoxColors.Cyan)) { appendInline(line) }
            return@forEachIndexed
        }
        line = line.replace(Regex("^(\\s*)[-*]\\s+"), "$1• ")
        appendInline(line)
    }
}

private fun AnnotatedString.Builder.appendInline(text: String) {
    val token = Regex("\\*\\*(.+?)\\*\\*|`([^`]+)`")
    var last = 0
    for (m in token.findAll(text)) {
        append(text.substring(last, m.range.first))
        if (m.groups[1] != null) withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(m.groupValues[1]) }
        else withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = VoxColors.SurfaceHigh)) { append(m.groupValues[2]) }
        last = m.range.last + 1
    }
    append(text.substring(last))
}

@Composable
fun ChatBubble(turn: Turn, modifier: Modifier = Modifier) {
    val mine = turn.role == Role.USER
    Row(modifier.fillMaxWidth(), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
        Column(
            Modifier.widthIn(max = 320.dp).clip(RoundedCornerShape(18.dp))
                .background(when { turn.isError -> VoxColors.Danger.copy(alpha = 0.15f); mine -> VoxColors.Blue.copy(alpha = 0.28f); else -> VoxColors.SurfaceHigh })
                .border(1.dp, if (turn.isError) VoxColors.Danger.copy(alpha = 0.5f) else VoxColors.Outline, RoundedCornerShape(18.dp))
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            if (turn.imageCount > 0 && mine) Text("📷 ${turn.imageCount} image${if (turn.imageCount > 1) "s" else ""} attached", style = MaterialTheme.typography.labelSmall, color = VoxColors.Muted)
            Text(if (mine) AnnotatedString(turn.display) else markdownToAnnotated(turn.display), style = MaterialTheme.typography.bodyLarge, color = if (turn.isError) VoxColors.Danger else VoxColors.Text)
        }
    }
}

/** Scrolling transcript that follows new messages. Hides machine-generated prompts. */
@Composable
fun ChatList(turns: List<Turn>, modifier: Modifier = Modifier, busy: Boolean = false, state: LazyListState = rememberLazyListState()) {
    val visible = turns.filter { !it.auto }
    LaunchedEffect(visible.size, busy) { if (visible.isNotEmpty()) state.animateScrollToItem(visible.size - 1 + if (busy) 1 else 0) }
    LazyColumn(modifier, state = state, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items(visible, key = { it.id }) { ChatBubble(it) }
        if (busy) item { Text("Vox is thinking…", color = VoxColors.Muted, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 6.dp)) }
    }
}

@Composable
fun StatusPill(text: String, color: Color = VoxColors.Cyan, modifier: Modifier = Modifier) {
    Text(
        text, style = MaterialTheme.typography.labelSmall, color = color,
        modifier = modifier.clip(RoundedCornerShape(50)).border(1.dp, color.copy(alpha = 0.6f), RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 5.dp),
    )
}
