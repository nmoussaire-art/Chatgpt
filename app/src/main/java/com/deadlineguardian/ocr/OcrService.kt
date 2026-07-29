package com.deadlineguardian.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * On-device OCR. The ML Kit Latin model is bundled into the APK, so this works with
 * no network and no Play Services — nothing about a user's receipts leaves the phone.
 */
class OcrService(private val context: Context) {

    private val recognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    /** Reads [uri], copies it into app storage, and returns the text plus the saved path. */
    suspend fun scan(uri: Uri): Pair<String, String?> = withContext(Dispatchers.IO) {
        val bitmap = loadBitmap(uri) ?: return@withContext "" to null
        val savedPath = persist(bitmap)
        val text = recognize(bitmap)
        bitmap.recycle()
        text to savedPath
    }

    private suspend fun recognize(bitmap: Bitmap): String =
        suspendCancellableCoroutine { cont ->
            recognizer.process(InputImage.fromBitmap(bitmap, 0))
                .addOnSuccessListener { cont.resume(it.text) }
                .addOnFailureListener { cont.resumeWithException(it) }
        }

    /**
     * Receipts are photographed close-up and often sideways. Downscaling keeps memory
     * sane on long receipts, and honouring EXIF matters because OCR on a 90°-rotated
     * image returns nothing at all.
     */
    private fun loadBitmap(uri: Uri): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, bounds)
        }
        val maxDim = maxOf(bounds.outWidth, bounds.outHeight)
        val target = 2200
        val opts = BitmapFactory.Options().apply {
            inSampleSize = if (maxDim > target) Integer.highestOneBit(maxDim / target) else 1
        }
        val bitmap = context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, opts)
        } ?: return null

        val rotation = context.contentResolver.openInputStream(uri)?.use { stream ->
            when (ExifInterface(stream).getAttributeInt(
                ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL
            )) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        } ?: 0f

        if (rotation == 0f) bitmap else Bitmap.createBitmap(
            bitmap, 0, 0, bitmap.width, bitmap.height,
            Matrix().apply { postRotate(rotation) }, true
        )
    }.getOrNull()

    /** Keeps a copy so the detail screen can still show the receipt later. */
    private fun persist(bitmap: Bitmap): String? = runCatching {
        val dir = File(context.filesDir, "captures").apply { mkdirs() }
        val file = File(dir, "scan-${System.currentTimeMillis()}.jpg")
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 82, it) }
        file.absolutePath
    }.getOrNull()
}
