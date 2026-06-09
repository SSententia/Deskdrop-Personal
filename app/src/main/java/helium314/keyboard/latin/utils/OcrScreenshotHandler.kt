// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.utils

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import helium314.keyboard.latin.ai.AiServiceSync
import helium314.keyboard.latin.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException

/**
 * Handles OCR scanning of the latest screenshot.
 *
 * Uses Google ML Kit Text Recognition v2 (on-device, via Play Services)
 * to extract text from the most recently taken image and copies the
 * recognized text to the system clipboard.
 */
object OcrScreenshotHandler {

    private const val TAG = "OcrScreenshotHandler"

    /**
     * Scan the latest screenshot for text and copy it to the clipboard.
     *
     * Must be called from the main thread — toasts are shown for feedback.
     * The OCR processing runs on [Dispatchers.IO].
     *
     * @param context Application or service context (Toast-safe).
     */
    @JvmStatic
    fun scanLatestScreenshot(context: Context) {
        // 1. Check media permissions
        val hasPermission = if (android.os.Build.VERSION.SDK_INT >= 34) {
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, "android.permission.READ_MEDIA_VISUAL_USER_SELECTED") == PackageManager.PERMISSION_GRANTED
        } else if (android.os.Build.VERSION.SDK_INT >= 33) {
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }

        if (!hasPermission) {
            Toast.makeText(context, R.string.ocr_no_permission, Toast.LENGTH_SHORT).show()
            return
        }

        // 2. Find the latest image candidate (same logic as "Offer last screenshot to AI Assist")
        val candidate = AiServiceSync.findLatestImageCandidate(context)
        if (candidate == null) {
            Toast.makeText(context, R.string.ocr_no_screenshot, Toast.LENGTH_SHORT).show()
            return
        }

        val uri = try {
            Uri.parse(candidate.uriString)
        } catch (e: Exception) {
            Toast.makeText(context, R.string.ocr_no_screenshot, Toast.LENGTH_SHORT).show()
            return
        }

        // 3. Run ML Kit Text Recognition on IO thread
        GlobalScope.launch(Dispatchers.Main) {
            val result = withContext(Dispatchers.IO) {
                recognizeText(context, uri)
            }

            when {
                result == null -> {
                    Toast.makeText(context, R.string.ocr_no_text_found, Toast.LENGTH_SHORT).show()
                }
                result.isEmpty() -> {
                    Toast.makeText(context, R.string.ocr_no_text_found, Toast.LENGTH_SHORT).show()
                }
                else -> {
                    // 4. Copy recognized text to clipboard
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                    if (clipboard != null) {
                        val clip = ClipData.newPlainText("OCR Screenshot", result)
                        clipboard.setPrimaryClip(clip)
                        val charCount = result.length
                        Toast.makeText(
                            context,
                            context.getString(R.string.ocr_success, charCount),
                            Toast.LENGTH_SHORT
                        ).show()
                    } else {
                        Toast.makeText(context, R.string.ocr_clipboard_error, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    /**
     * Run ML Kit text recognition on the image at [uri].
     *
     * @return Recognized text, or null on failure.
     */
    private fun recognizeText(context: Context, uri: Uri): String? {
        return try {
            val inputImage = InputImage.fromFilePath(context, uri)
            val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            val task = recognizer.process(inputImage)
            // Block synchronously on IO thread
            val visionText = com.google.android.gms.tasks.Tasks.await(task)
            visionText.text
        } catch (e: com.google.mlkit.common.MlKitException) {
            Log.e(TAG, "ML Kit error (model not ready?)", e)
            // Return a special marker that the caller can use to show a different message.
            // We use null with a specific log to distinguish from other failures.
            null
        } catch (e: FileNotFoundException) {
            Log.e(TAG, "Image file not found for OCR", e)
            null
        } catch (e: Exception) {
            Log.e(TAG, "OCR failed", e)
            null
        }
    }
}
