// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.ai

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.onFocusEvent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import helium314.keyboard.latin.LatinIME
import helium314.keyboard.latin.utils.showImeComposeDialog

fun showFloatingNoteConfigDialog(ime: LatinIME) {
    var x by mutableStateOf("100")
    var y by mutableStateOf("200")
    var width by mutableStateOf("250")
    var height by mutableStateOf("200")
    var delayMs by mutableStateOf("0")
    var camouflageDurationMs by mutableStateOf("0")

    showImeComposeDialog(
        ime = ime,
        title = "Floating Note Config",
        positiveButton = helium314.keyboard.latin.utils.DialogButton("Spawn") { dialog ->
            val xVal = x.toIntOrNull() ?: 100
            val yVal = y.toIntOrNull() ?: 200
            val wVal = width.toIntOrNull() ?: 250
            val hVal = height.toIntOrNull() ?: 200
            val dVal = delayMs.toIntOrNull() ?: 0
            val cVal = camouflageDurationMs.toIntOrNull() ?: 0

            val intent = Intent(ime, FloatingNoteService::class.java).apply {
                putExtra("text", "")
                putExtra("x", xVal)
                putExtra("y", yVal)
                putExtra("width", wVal)
                putExtra("height", hVal)
                putExtra("delayMs", dVal)
                putExtra("camouflageDurationMs", cVal)
            }
            ime.startService(intent)
            dialog.dismiss()
        },
        negativeButton = helium314.keyboard.latin.utils.DialogButton("Cancel") { dialog ->
            dialog.dismiss()
        },
        focusable = true,
        content = {
            FloatingNoteConfigContent(
                x = x, onXChange = { x = it },
                y = y, onYChange = { y = it },
                width = width, onWidthChange = { width = it },
                height = height, onHeightChange = { height = it },
                delayMs = delayMs, onDelayMsChange = { delayMs = it },
                camouflageDurationMs = camouflageDurationMs, onCamouflageDurationMsChange = { camouflageDurationMs = it }
            )
        }
    )
}

@Composable
private fun FloatingNoteConfigContent(
    x: String, onXChange: (String) -> Unit,
    y: String, onYChange: (String) -> Unit,
    width: String, onWidthChange: (String) -> Unit,
    height: String, onHeightChange: (String) -> Unit,
    delayMs: String, onDelayMsChange: (String) -> Unit,
    camouflageDurationMs: String, onCamouflageDurationMsChange: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        ConfigRow("X Position", x, onXChange)
        ConfigRow("Y Position", y, onYChange)
        ConfigRow("Width (dp)", width, onWidthChange)
        ConfigRow("Height (dp)", height, onHeightChange)
        ConfigRow("Spawn Delay (ms)", delayMs, onDelayMsChange)
        ConfigRow("Camouflage (ms)", camouflageDurationMs, onCamouflageDurationMsChange)
    }
}

@Composable
private fun ConfigRow(label: String, value: String, onValueChange: (String) -> Unit) {
    val focusManager = LocalFocusManager.current
    
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(0.4f)
        )
        
        // Use BasicTextField instead of OutlinedTextField to avoid system floating toolbar
        BasicTextField(
            value = value,
            onValueChange = { onValueChange(it.filter { c -> c.isDigit() || c == '-' }) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
            modifier = Modifier
                .weight(0.6f)
                .clip(RoundedCornerShape(4.dp))
                .background(MaterialTheme.colorScheme.surface)
                .onKeyEvent { keyEvent ->
                    if (keyEvent.key == Key.Tab) {
                        focusManager.moveFocus(FocusDirection.Down)
                        true
                    } else {
                        false
                    }
                }
                .onFocusEvent { focusState ->
                    // Handle focus changes without triggering floating toolbar
                },
            textStyle = MaterialTheme.typography.bodyMedium.copy(
                color = MaterialTheme.colorScheme.onSurface
            ),
            decorationBox = { innerTextField ->
                Box(
                    modifier = Modifier.padding(8.dp)
                ) {
                    if (value.isEmpty()) {
                        Text(
                            text = "0",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        )
                    }
                    innerTextField()
                }
            }
        )
    }
}
