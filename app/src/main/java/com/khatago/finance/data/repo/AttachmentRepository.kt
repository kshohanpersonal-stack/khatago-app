package com.khatago.finance.data.repo

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import com.khatago.finance.data.db.KhataGoDatabase
import com.khatago.finance.data.db.entity.AttachmentEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/**
 * Receipt and product photos, kept entirely on-device.
 *
 * Import pipeline, in this order, because each step protects something:
 *  1. **Bounds-decode first** (`inJustDecodeBounds`) — a 4032×3024 photo decoded naively would ask
 *     for ~48 MB and OOM the app on a budget handset.
 *  2. **Downscale to [MAX_DIMENSION] with an integer sample size**, which the decoder handles
 *     without ever allocating the full image.
 *  3. **Re-encode as JPEG at quality 82** into the app's private `attachments/` directory. Private
 *     storage means no media permission and no camera-write permission; quality 82 keeps a receipt
 *     legible at roughly a tenth of the original bytes.
 *  4. **Store the file *name*, not a content URI.** Content URIs from the photo picker are transient
 *     grants; the one thing a receipt photo must survive is an app restart.
 *
 * Deletion removes the file as well as the row: an orphaned image of someone's loan agreement left
 * in storage after they deleted the record would be a privacy leak.
 */
class AttachmentRepository(private val context: Context, private val database: KhataGoDatabase) {

    private val dir: File
        get() = File(context.filesDir, ATTACHMENTS_DIR).apply { mkdirs() }

    fun observe(targetType: String, targetId: Long) =
        database.catalogDao().observeAttachments(targetType, targetId)

    suspend fun find(targetType: String, targetId: Long): List<AttachmentEntity> =
        database.catalogDao().findAttachments(targetType, targetId)

    /** @return the stored attachment, or null when the image could not be read or decoded. */
    suspend fun import(
        source: Uri,
        targetType: String,
        targetId: Long,
    ): AttachmentEntity? = withContext(Dispatchers.IO) {
        try {
            val bytes = context.contentResolver.openInputStream(source)?.use { it.readBytes() }
                ?: return@withContext null
            if (bytes.size > MAX_SOURCE_BYTES) return@withContext null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withContext null

            val sample = sampleSizeFor(bounds.outWidth, bounds.outHeight)
            val options = BitmapFactory.Options().apply { inSampleSize = sample }
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
                ?: return@withContext null
            val scaled = scaleToFit(bitmap, MAX_DIMENSION).also {
                if (it !== bitmap) bitmap.recycle()
            }
            bitmap.recycle()

            val name = "att-${UUID.randomUUID()}.jpg"
            val file = File(dir, name)
            FileOutputStream(file).use { out ->
                check(scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)) {
                    "Could not write the image."
                }
                out.flush()
            }
            scaled.recycle()

            val entity = AttachmentEntity(
                targetType = targetType,
                targetId = targetId,
                fileName = name,
                originalName = source.lastPathSegment?.take(80),
                mimeType = "image/jpeg",
                sizeBytes = file.length(),
                createdAt = System.currentTimeMillis(),
            )
            val id = database.catalogDao().insertAttachment(entity)
            entity.copy(id = id)
        } catch (error: Throwable) {
            Log.w(TAG, "attachment import failed", error)
            null
        }
    }

    /** Resolves a stored attachment to a content URI the app can display, or null if the file is gone. */
    suspend fun resolve(attachment: AttachmentEntity): File? = withContext(Dispatchers.IO) {
        File(dir, attachment.fileName).takeIf { it.exists() }
    }

    suspend fun delete(attachment: AttachmentEntity) = withContext(Dispatchers.IO) {
        database.catalogDao().deleteAttachment(attachment.id)
        File(dir, attachment.fileName).delete()
        Unit
    }

    /** Directory size, shown on the Data management screen so "attachments" is not a mystery box. */
    suspend fun usedBytes(): Long = withContext(Dispatchers.IO) {
        dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    suspend fun fileCount(): Int = withContext(Dispatchers.IO) {
        dir.listFiles()?.count { it.isFile } ?: 0
    }

    /** Removes every stored image (used by "Delete all data"). */
    suspend fun deleteAllFiles(): Int = withContext(Dispatchers.IO) {
        val files = dir.listFiles().orEmpty()
        files.forEach { it.delete() }
        files.size
    }

    internal fun scaleToFit(bitmap: Bitmap, maxDimension: Int): Bitmap {
        val longest = maxOf(bitmap.width, bitmap.height)
        if (longest <= maxDimension) return bitmap
        val ratio = maxDimension.toFloat() / longest.toFloat()
        val width = (bitmap.width * ratio).toInt().coerceAtLeast(1)
        val height = (bitmap.height * ratio).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, width, height, true)
    }

    companion object {
        const val ATTACHMENTS_DIR = "attachments"
        const val MAX_DIMENSION = 1600
        const val JPEG_QUALITY = 82
        const val MAX_SOURCE_BYTES = 24 * 1024 * 1024
        private const val TAG = "KhataGoAttachment"

        /** Integer power-of-two sampling, which the decoder implements natively and cheaply. */
        fun sampleSizeFor(width: Int, height: Int, maxDimension: Int = MAX_DIMENSION): Int {
            var sample = 1
            val longest = maxOf(width, height)
            while (longest / (sample * 2) >= maxDimension) sample *= 2
            return sample
        }
    }
}
