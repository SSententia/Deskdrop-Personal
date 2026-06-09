// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.ai

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

/**
 * Trampoline activity that launches the system image picker and forwards
 * the selected image URI to FloatingNoteService via intent action.
 */
class NoteImagePickerActivity : ComponentActivity() {

    private val pickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: Exception) {}
            val serviceIntent = Intent(this, FloatingNoteService::class.java).apply {
                action = "ATTACH_IMAGE"
                putExtra("imageUri", uri.toString())
            }
            startService(serviceIntent)
        }
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pickerLauncher.launch("image/*")
    }
}
