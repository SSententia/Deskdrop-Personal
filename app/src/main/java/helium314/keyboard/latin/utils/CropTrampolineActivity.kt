// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.utils

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import helium314.keyboard.latin.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Invisible trampoline activity that:
 * 1. Copies the selected screenshot to the app cache directory
 * 2. Launches a system image editor (crop) intent on that copy
 * 3. After the user finishes editing, runs ML Kit OCR on the result
 * 4. Copies recognized text to the clipboard and shows a toast
 * 5. Finishes immediately
 *
 * This is necessary because IME services cannot use startActivityForResult directly.
 */
class CropTrampolineActivity : ComponentActivity() {

    companion object {
        private const val TAG = "CropTrampoline"
        const val EXTRA_IMAGE_URI = "crop_image_uri"
        const val EXTRA_IMAGE_MIME = "crop_image_mime"
    }

    private val editLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        handleEditResult(result.resultCode, result.data)
    }

    private var outputFile: File? = null
    private var outputUri: Uri? = null
    private var fileLastModified: Long = 0L
    private var ocrJob: kotlinx.coroutines.Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val sourceUriString = intent.getStringExtra(EXTRA_IMAGE_URI)
        val sourceMime = intent.getStringExtra(EXTRA_IMAGE_MIME) ?: "image/png"

        if (sourceUriString == null) {
            showToast(R.string.ocr_no_screenshot)
            finish()
            return
        }

        val sourceUri = try {
            Uri.parse(sourceUriString)
        } catch (e: Exception) {
            showToast(R.string.ocr_no_screenshot)
            finish()
            return
        }

        // Copy the image to app cache so we can write to it via the editor
        val cacheDir = File(cacheDir, "crop_ocr")
        cacheDir.mkdirs()
        outputFile = File(cacheDir, "crop_${System.currentTimeMillis()}.png")

        try {
            contentResolver.openInputStream(sourceUri)?.use { input ->
                outputFile!!.outputStream().use { output ->
                    input.copyTo(output)
                }
            } ?: run {
                showToast(R.string.ocr_no_screenshot)
                finish()
                return
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to copy image to cache", e)
            showToast(R.string.ocr_no_screenshot)
            finish()
            return
        }
        fileLastModified = outputFile!!.lastModified()

        // Get a content URI via FileProvider for the editor
        val authority = getString(R.string.gesture_data_provider_authority)
        outputUri = try {
            FileProvider.getUriForFile(this, authority, outputFile!!)
        } catch (e: Exception) {
            Log.e(TAG, "FileProvider failed", e)
            showToast(R.string.ocr_no_screenshot)
            finish()
            return
        }

        // Launch the photo editor / crop intent
        launchEditor(sourceMime)
    }

    private fun launchEditor(mimeType: String) {
        // Try the system photo editor first (Android 10+)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            val editIntent = Intent(Intent.ACTION_EDIT).apply {
                setDataAndType(outputUri, mimeType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }
            if (editIntent.resolveActivity(packageManager) != null) {
                try {
                    editLauncher.launch(editIntent)
                    return
                } catch (e: Exception) {
                    Log.w(TAG, "ACTION_EDIT failed, trying crop fallback", e)
                }
            }
        }

        // Fallback: legacy crop intent (works on many devices)
        val cropIntent = Intent("com.android.camera.action.CROP").apply {
            setDataAndType(outputUri, mimeType)
            putExtra("crop", "true")
            putExtra("aspectX", 0)
            putExtra("aspectY", 0)
            putExtra("scale", true)
            putExtra("return-data", false)
            putExtra(android.provider.MediaStore.EXTRA_OUTPUT, outputUri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }
        if (cropIntent.resolveActivity(packageManager) != null) {
            try {
                editLauncher.launch(cropIntent)
                return
            } catch (e: Exception) {
                Log.w(TAG, "Crop intent failed", e)
            }
        }

        // Nothing available — just run OCR on the original
        Log.w(TAG, "No crop/editor app available, running OCR on original image")
        runOcrOnOutput()
    }

    private fun handleEditResult(resultCode: Int, data: Intent?) {
        // Only run OCR if the user actually saved or the file was modified
        if (resultCode != RESULT_OK && (outputFile == null || outputFile!!.lastModified() == fileLastModified)) {
            // User cancelled without making changes — clean up and exit quietly
            cleanupTempFiles()
            finish()
            return
        }
        runOcrOnOutput()
    }

    private fun runOcrOnOutput() {
        val file = outputFile
        if (file == null || !file.exists() || file.length() == 0L) {
            showToast(R.string.ocr_no_text_found)
            finish()
            return
        }

        ocrJob = GlobalScope.launch(Dispatchers.Main) {
            val text = withContext(Dispatchers.IO) {
                try {
                    val fileUri = outputUri ?: Uri.fromFile(file)
                    val inputImage = InputImage.fromFilePath(this@CropTrampolineActivity, fileUri)
                    val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
                    val visionText = com.google.android.gms.tasks.Tasks.await(recognizer.process(inputImage))
                    visionText.text
                } catch (e: Exception) {
                    Log.e(TAG, "OCR failed on cropped image", e)
                    null
                }
            }

            if (text.isNullOrEmpty()) {
                showToast(R.string.ocr_no_text_found)
            } else {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                if (clipboard != null) {
                    clipboard.setPrimaryClip(ClipData.newPlainText("OCR Crop", text))
                    showToast(getString(R.string.ocr_success, text.length))
                } else {
                    showToast(R.string.ocr_clipboard_error)
                }
            }

            cleanupTempFiles()
            finish()
        }
    }

    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun showToast(resId: Int) {
        Toast.makeText(this, resId, Toast.LENGTH_SHORT).show()
    }

    private fun cleanupTempFiles() {
        outputFile?.delete()
        outputFile?.parentFile?.takeIf { it.list()?.isEmpty() == true }?.delete()
    }

    override fun onDestroy() {
        ocrJob?.cancel()
        cleanupTempFiles()
        super.onDestroy()
    }
}
