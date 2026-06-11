// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.utils

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import helium314.keyboard.latin.R
import helium314.keyboard.settings.screens.brandTeal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.sqrt

// ─────────────────────────────────────────────────────────────
//  File-level constants (accessible to both object and composable)
// ─────────────────────────────────────────────────────────────

private const val CROP_TAG = "OcrCropDialog"
private const val MIN_CROP_SIZE = 0.08f  // minimum 8% of image dimension
private const val HANDLE_HIT_RADIUS_PX = 36f  // generous touch target for handles

/** Which handle on the crop rectangle is being dragged, if any. */
private enum class CropHandle {
    TOP_LEFT, TOP, TOP_RIGHT,
    LEFT, RIGHT,
    BOTTOM_LEFT, BOTTOM, BOTTOM_RIGHT
}

/**
 * In-app image crop dialog for OCR.
 *
 * Shows the screenshot with a draggable/resizable crop rectangle overlay.
 * Drag corner handles to resize diagonally, edge handles to resize one axis,
 * or drag inside the rectangle to move it.
 * Tap "Scan" to run ML Kit OCR on the cropped region.
 */
object OcrCropDialog {

    /**
     * Shows the crop dialog for OCR on [imageUri].
     * [ime] must be the [helium314.keyboard.latin.LatinIME] service instance.
     */
    fun show(
        ime: helium314.keyboard.latin.LatinIME,
        imageUri: Uri,
        onDismiss: () -> Unit = {}
    ) {
        Log.d(CROP_TAG, "show() called with URI: $imageUri")
        kotlinx.coroutines.GlobalScope.launch(Dispatchers.IO) {
            Log.d(CROP_TAG, "Loading bitmap from URI on IO thread...")
            val bitmap = try {
                val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
                ime.contentResolver.openInputStream(imageUri)?.use { stream ->
                    BitmapFactory.decodeStream(stream, null, opts)
                }
            } catch (e: Exception) {
                Log.e(CROP_TAG, "Failed to load image for crop", e)
                null
            }

            withContext(Dispatchers.Main) {
                if (bitmap == null) {
                    Log.w(CROP_TAG, "Bitmap is null, showing error toast")
                    Toast.makeText(ime, R.string.ocr_no_screenshot, Toast.LENGTH_SHORT).show()
                    onDismiss()
                    return@withContext
                }
                Log.d(CROP_TAG, "Bitmap loaded: ${bitmap.width}x${bitmap.height}, showing crop dialog")
                showCropDialog(ime, bitmap, onDismiss)
            }
        }
    }

    private fun showCropDialog(ime: helium314.keyboard.latin.LatinIME, bitmap: Bitmap, onDismiss: () -> Unit) {
        Log.d(CROP_TAG, "Showing crop dialog with ${bitmap.width}x${bitmap.height} bitmap")
        showImeComposeDialog(
            ime = ime,
            chromeless = true,
            focusable = true,
            onDismiss = onDismiss,
            content = {
                CropDialogContent(
                    bitmap = bitmap,
                    onCrop = { croppedBitmap ->
                        ime.getActiveDialog()?.dismiss()
                        runOcrOnBitmap(ime, croppedBitmap)
                    },
                    onCancel = {
                        ime.getActiveDialog()?.dismiss()
                    }
                )
            }
        )
    }

    private fun runOcrOnBitmap(context: Context, bitmap: Bitmap) {
        kotlinx.coroutines.GlobalScope.launch(Dispatchers.IO) {
            val text = try {
                val inputImage = InputImage.fromBitmap(bitmap, 0)
                val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
                val visionText = com.google.android.gms.tasks.Tasks.await(recognizer.process(inputImage))
                visionText.text
            } catch (e: Exception) {
                Log.e(CROP_TAG, "OCR failed", e)
                null
            }

            withContext(Dispatchers.Main) {
                if (text.isNullOrEmpty()) {
                    Toast.makeText(context, R.string.ocr_no_text_found, Toast.LENGTH_SHORT).show()
                } else {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                    if (clipboard != null) {
                        clipboard.setPrimaryClip(ClipData.newPlainText("OCR Crop", text))
                        Toast.makeText(context, context.getString(R.string.ocr_success, text.length), Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(context, R.string.ocr_clipboard_error, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────
//  Helpers
// ─────────────────────────────────────────────────────────────

/** Euclidean distance between two points. */
private fun dist(x1: Float, y1: Float, x2: Float, y2: Float): Float {
    val dx = x1 - x2; val dy = y1 - y2
    return sqrt(dx * dx + dy * dy)
}

/** Returns which handle (if any) is near [position] within [HANDLE_HIT_RADIUS_PX]. */
private fun hitTestHandle(position: Offset, cropRect: Rect): CropHandle? {
    val r = HANDLE_HIT_RADIUS_PX
    val cx = position.x; val cy = position.y

    // Corners (check first — higher priority since they overlap with edges)
    if (dist(cx, cy, cropRect.left, cropRect.top) < r)       return CropHandle.TOP_LEFT
    if (dist(cx, cy, cropRect.right, cropRect.top) < r)      return CropHandle.TOP_RIGHT
    if (dist(cx, cy, cropRect.left, cropRect.bottom) < r)    return CropHandle.BOTTOM_LEFT
    if (dist(cx, cy, cropRect.right, cropRect.bottom) < r)   return CropHandle.BOTTOM_RIGHT

    // Edge midpoints
    if (dist(cx, cy, cropRect.center.x, cropRect.top) < r)    return CropHandle.TOP
    if (dist(cx, cy, cropRect.center.x, cropRect.bottom) < r) return CropHandle.BOTTOM
    if (dist(cx, cy, cropRect.left, cropRect.center.y) < r)   return CropHandle.LEFT
    if (dist(cx, cy, cropRect.right, cropRect.center.y) < r)  return CropHandle.RIGHT

    return null
}

// ─────────────────────────────────────────────────────────────
//  Composable: Crop Dialog Content
// ─────────────────────────────────────────────────────────────

@Composable
private fun CropDialogContent(
    bitmap: Bitmap,
    onCrop: (Bitmap) -> Unit,
    onCancel: () -> Unit
) {
    // Container size in pixels
    var containerSize by remember { mutableStateOf(IntSize.Zero) }

    // Compute the fitted image position/size within the container (ContentScale.Fit centering)
    val imageAspect = bitmap.width.toFloat() / bitmap.height.toFloat()
    val containerAspect = if (containerSize.width > 0 && containerSize.height > 0)
        containerSize.width.toFloat() / containerSize.height.toFloat() else 1f

    val (imageDrawWidth, imageDrawHeight) = if (containerSize.width == 0) {
        0f to 0f
    } else if (imageAspect > containerAspect) {
        containerSize.width.toFloat() to (containerSize.width / imageAspect)
    } else {
        (containerSize.height * imageAspect) to containerSize.height.toFloat()
    }
    val imageOffsetX = (containerSize.width - imageDrawWidth) / 2f
    val imageOffsetY = (containerSize.height - imageDrawHeight) / 2f

    // Crop rectangle in normalized coords [0..1] within the image
    var cropLeft by remember { mutableFloatStateOf(0.1f) }
    var cropTop by remember { mutableFloatStateOf(0.1f) }
    var cropRight by remember { mutableFloatStateOf(0.9f) }
    var cropBottom by remember { mutableFloatStateOf(0.9f) }

    // Track active handle during drag
    var activeHandle by remember { mutableStateOf<CropHandle?>(null) }
    var dragInsideCrop by remember { mutableStateOf(false) }

    fun clampCrop() {
        if (cropRight - cropLeft < MIN_CROP_SIZE) {
            val mid = (cropLeft + cropRight) / 2f
            cropLeft = (mid - MIN_CROP_SIZE / 2f).coerceIn(0f, 1f - MIN_CROP_SIZE)
            cropRight = (cropLeft + MIN_CROP_SIZE).coerceIn(MIN_CROP_SIZE, 1f)
        }
        if (cropBottom - cropTop < MIN_CROP_SIZE) {
            val mid = (cropTop + cropBottom) / 2f
            cropTop = (mid - MIN_CROP_SIZE / 2f).coerceIn(0f, 1f - MIN_CROP_SIZE)
            cropBottom = (cropTop + MIN_CROP_SIZE).coerceIn(MIN_CROP_SIZE, 1f)
        }
        cropLeft = cropLeft.coerceIn(0f, 1f - MIN_CROP_SIZE)
        cropTop = cropTop.coerceIn(0f, 1f - MIN_CROP_SIZE)
        cropRight = cropRight.coerceIn(MIN_CROP_SIZE, 1f)
        cropBottom = cropBottom.coerceIn(MIN_CROP_SIZE, 1f)
    }

    // Convert normalized crop to pixel coords within the drawn image area
    fun cropRectInPixels() = Rect(
        left = imageOffsetX + cropLeft * imageDrawWidth,
        top = imageOffsetY + cropTop * imageDrawHeight,
        right = imageOffsetX + cropRight * imageDrawWidth,
        bottom = imageOffsetY + cropBottom * imageDrawHeight
    )

    val accentColor = brandTeal()
    val dimColor = Color.Black.copy(alpha = 0.55f)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Title
        Text(
            text = stringResource(R.string.ocr_crop_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        // Image + crop overlay
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 350.dp)
                .weight(1f, fill = false)
                .background(Color.DarkGray, RoundedCornerShape(8.dp))
                .onSizeChanged { containerSize = it }
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { offset ->
                            if (imageDrawWidth <= 0f) return@detectDragGestures
                            val rect = cropRectInPixels()
                            activeHandle = hitTestHandle(offset, rect)
                            dragInsideCrop = activeHandle != null ||
                                    (offset.x in rect.left..rect.right &&
                                     offset.y in rect.top..rect.bottom)
                        },
                        onDrag = { change, dragAmount ->
                            if (imageDrawWidth <= 0f) return@detectDragGestures
                            val dx = dragAmount.x / imageDrawWidth
                            val dy = dragAmount.y / imageDrawHeight
                            var handled = false

                            when (activeHandle) {
                                // Corner handles — resize diagonally
                                CropHandle.TOP_LEFT     -> { cropLeft += dx;   cropTop += dy;    handled = true }
                                CropHandle.TOP_RIGHT    -> { cropRight += dx;  cropTop += dy;    handled = true }
                                CropHandle.BOTTOM_LEFT  -> { cropLeft += dx;   cropBottom += dy; handled = true }
                                CropHandle.BOTTOM_RIGHT -> { cropRight += dx;  cropBottom += dy; handled = true }

                                // Edge handles — resize one axis
                                CropHandle.TOP    -> { cropTop += dy;    handled = true }
                                CropHandle.BOTTOM -> { cropBottom += dy; handled = true }
                                CropHandle.LEFT   -> { cropLeft += dx;   handled = true }
                                CropHandle.RIGHT  -> { cropRight += dx;  handled = true }

                                // No handle — move the entire rectangle
                                null -> {
                                    if (dragInsideCrop) {
                                        val w = cropRight - cropLeft
                                        val h = cropBottom - cropTop
                                        cropLeft = (cropLeft + dx).coerceIn(0f, 1f - w)
                                        cropTop = (cropTop + dy).coerceIn(0f, 1f - h)
                                        cropRight = cropLeft + w
                                        cropBottom = cropTop + h
                                        handled = true
                                    }
                                }
                            }
                            if (handled) {
                                clampCrop()
                                change.consume()
                            }
                        },
                        onDragEnd = {
                            activeHandle = null
                            dragInsideCrop = false
                        },
                        onDragCancel = {
                            activeHandle = null
                            dragInsideCrop = false
                        }
                    )
                },
            contentAlignment = Alignment.TopStart
        ) {
            // Image composable for proper scaling
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit
            )

            // Crop overlay drawn on top
            if (imageDrawWidth > 0f) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val crop = cropRectInPixels()
                    val w = size.width
                    val h = size.height

                    // Dim outside the crop rectangle (4 regions)
                    drawRect(dimColor, topLeft = Offset.Zero, size = Size(w, crop.top))
                    drawRect(dimColor, topLeft = Offset(0f, crop.bottom), size = Size(w, h - crop.bottom))
                    drawRect(dimColor, topLeft = Offset(0f, crop.top), size = Size(crop.left, crop.height))
                    drawRect(dimColor, topLeft = Offset(crop.right, crop.top), size = Size(w - crop.right, crop.height))

                    // Crop border
                    drawRect(
                        color = accentColor,
                        topLeft = crop.topLeft,
                        size = crop.size,
                        style = Stroke(width = 3f)
                    )

                    // Corner handles (large circles)
                    val cornerRadius = 14f
                    val cornerPositions = listOf(
                        crop.topLeft,
                        Offset(crop.right, crop.top),
                        Offset(crop.left, crop.bottom),
                        Offset(crop.right, crop.bottom)
                    )
                    for (pos in cornerPositions) {
                        drawCircle(Color.White, cornerRadius, pos)
                        drawCircle(accentColor, cornerRadius, pos, style = Stroke(2.5f))
                    }

                    // Edge midpoint handles (smaller circles)
                    val edgeRadius = cornerRadius * 0.55f
                    val edgePositions = listOf(
                        Offset(crop.center.x, crop.top),
                        Offset(crop.center.x, crop.bottom),
                        Offset(crop.left, crop.center.y),
                        Offset(crop.right, crop.center.y)
                    )
                    for (pos in edgePositions) {
                        drawCircle(Color.White, edgeRadius, pos)
                        drawCircle(accentColor, edgeRadius, pos, style = Stroke(2f))
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Buttons row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedButton(
                onClick = onCancel,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(stringResource(R.string.ocr_crop_cancel))
            }

            Button(
                onClick = {
                    val leftPx = (cropLeft * bitmap.width).toInt().coerceIn(0, bitmap.width - 1)
                    val topPx = (cropTop * bitmap.height).toInt().coerceIn(0, bitmap.height - 1)
                    val wPx = ((cropRight - cropLeft) * bitmap.width).toInt().coerceIn(1, bitmap.width - leftPx)
                    val hPx = ((cropBottom - cropTop) * bitmap.height).toInt().coerceIn(1, bitmap.height - topPx)

                    val cropped = try {
                        Bitmap.createBitmap(bitmap, leftPx, topPx, wPx, hPx)
                    } catch (e: Exception) {
                        Log.e(CROP_TAG, "Crop failed", e)
                        bitmap
                    }
                    onCrop(cropped)
                },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(containerColor = accentColor)
            ) {
                Text(
                    stringResource(R.string.ocr_crop_scan),
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}
