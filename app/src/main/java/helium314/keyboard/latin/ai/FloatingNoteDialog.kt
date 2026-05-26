// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.ai

import android.content.Intent
import android.view.ViewGroup
import android.widget.EditText
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
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
            FloatingNoteConfigContent(ime,
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
    ime: LatinIME,
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
        ConfigRow(ime, "X Position", x, onXChange, autoFocus = true)
        ConfigRow(ime, "Y Position", y, onYChange)
        ConfigRow(ime, "Width (dp)", width, onWidthChange)
        ConfigRow(ime, "Height (dp)", height, onHeightChange)
        ConfigRow(ime, "Spawn Delay (ms)", delayMs, onDelayMsChange)
        ConfigRow(ime, "Camouflage (ms)", camouflageDurationMs, onCamouflageDurationMsChange)
    }
}

@Composable
private fun ConfigRow(
    ime: LatinIME,
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    autoFocus: Boolean = false
) {
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

        // Use AndroidView(EditText) for proper IME keyboard input routing
        // This enables keyboard input via LatinIME's setDialogEditText mechanism
        AndroidView(
            factory = { ctx ->
                EditText(ctx).apply {
                    setText(value)
                    setSingleLine(true)
                    inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                            android.text.InputType.TYPE_NUMBER_FLAG_SIGNED
                    setHint("0")
                    setHintTextColor(android.graphics.Color.argb(128, 128, 128, 128))
                    background = null // Remove underline for clean look
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                    textSize = 14f
                    setPadding(8, 8, 8, 8)
                    // When this EditText gets focus, tell the IME to route keyboard input here
                    setOnFocusChangeListener { v, hasFocus ->
                        if (hasFocus) {
                            ime.setDialogEditText(v as EditText)
                        }
                    }
                    addTextChangedListener(object : android.text.TextWatcher {
                        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                            onValueChange(s?.filter { c -> c.isDigit() || c == '-' }?.toString() ?: "")
                        }
                        override fun afterTextChanged(s: android.text.Editable?) {}
                    })
                    if (autoFocus) {
                        requestFocus()
                    }
                }
            },
            update = { editText ->
                val filtered = value.filter { c -> c.isDigit() || c == '-' }
                if (editText.text.toString() != filtered) {
                    editText.setText(filtered)
                    editText.setSelection(filtered.length)
                }
            },
            modifier = Modifier.weight(0.6f)
        )
    }
}
