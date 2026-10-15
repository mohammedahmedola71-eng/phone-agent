package ai.vox.app.ui.components

import ai.vox.app.ui.theme.VoxColors
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.padding

@Composable
fun MicButton(listening: Boolean, enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, size: androidx.compose.ui.unit.Dp = 56.dp) {
    val t = rememberInfiniteTransition(label = "mic")
    val glow by t.animateFloat(0.3f, 1f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "glow")
    Box(
        modifier.size(size).alpha(if (enabled) 1f else 0.35f).clip(CircleShape)
            .background(if (listening) VoxColors.Danger.copy(alpha = 0.25f) else VoxColors.SurfaceHigh)
            .border(2.dp, if (listening) VoxColors.Danger.copy(alpha = glow) else VoxColors.Cyan.copy(alpha = 0.6f), CircleShape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(if (listening) Icons.Filled.Stop else Icons.Filled.Mic, if (listening) "Stop listening" else "Speak", tint = if (listening) VoxColors.Danger else VoxColors.Cyan)
    }
}

/** Text input with a send button. Text chat always works as the fallback when voice is unavailable. */
@Composable
fun MessageField(
    value: String,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    enabled: Boolean,
    placeholder: String,
    modifier: Modifier = Modifier,
    trailing: @Composable () -> Unit = {},
) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = value, onValueChange = onValueChange, enabled = enabled,
            placeholder = { Text(placeholder, color = VoxColors.Muted) },
            modifier = Modifier.weight(1f), maxLines = 4,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { if (value.isNotBlank()) onSend() }),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = VoxColors.Cyan, unfocusedBorderColor = VoxColors.Outline,
                focusedContainerColor = VoxColors.Surface, unfocusedContainerColor = VoxColors.Surface,
                disabledContainerColor = VoxColors.Surface, disabledBorderColor = VoxColors.Outline,
            ),
            shape = androidx.compose.foundation.shape.RoundedCornerShape(28.dp),
        )
        Box(
            Modifier.size(48.dp).alpha(if (enabled && value.isNotBlank()) 1f else 0.35f).clip(CircleShape).background(VoxColors.Accent)
                .clickable(enabled = enabled && value.isNotBlank(), onClick = onSend),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.AutoMirrored.Filled.Send, "Send", tint = Color(0xFF00141A)) }
        trailing()
    }
}
