package dev.herdr.android.ui.workbench

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.provider.OpenableColumns
import dev.herdr.android.protocol.HcFramedClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

data class PreparedImageUpload(
    val fileName: String,
    val bytes: ByteArray
)

/**
 * Prepares a user-picked gallery/camera image on [Dispatchers.IO] so it stays well within
 * Herdr Core's 8 MiB `terminal.put` raw limit while preserving original bytes when already small
 * and applying EXIF orientation + max-2048px JPEG compression for large / HEIC / HDR photos.
 */
object ImageUploadPreparer {
    private const val MAX_STREAM_READ_BYTES = 32 * 1024 * 1024
    private const val PRESERVE_RAW_BYTES_LIMIT = 5_500_000
    private const val TARGET_REENCODE_BYTES_LIMIT = 6_500_000
    private const val MAX_IMAGE_DIMENSION = 2048

    private val directlyAcceptedMimes = setOf(
        "image/png",
        "image/jpeg",
        "image/jpg",
        "image/webp",
        "image/gif"
    )

    suspend fun prepare(context: Context, uri: Uri): Result<PreparedImageUpload> = withContext(Dispatchers.IO) {
        runCatching {
            val resolver = context.contentResolver
            val mime = resolver.getType(uri)?.lowercase()?.trim().orEmpty()
            val rawName = queryDisplayName(context, uri)
                ?.takeIf { it.isNotBlank() }
                ?: "image_${System.currentTimeMillis()}"

            val rawBytes = resolver.openInputStream(uri)?.use { stream ->
                readBounded(stream, MAX_STREAM_READ_BYTES)
            } ?: throw IllegalStateException("无法读取所选图片文件")

            if (rawBytes.isEmpty()) {
                throw IllegalStateException("所选图片内容为空")
            }

            if (mime in directlyAcceptedMimes && rawBytes.size <= PRESERVE_RAW_BYTES_LIMIT) {
                val ensuredName = ensureExtension(rawName, extensionForMime(mime))
                return@runCatching PreparedImageUpload(ensuredName, rawBytes)
            }

            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                throw IllegalStateException("无法解码图片格式 (${mime.ifBlank { "unknown" }})")
            }

            val decodeOpts = BitmapFactory.Options().apply {
                inSampleSize = computeInSampleSize(bounds.outWidth, bounds.outHeight, MAX_IMAGE_DIMENSION)
            }
            var bitmap = BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size, decodeOpts)
                ?: throw IllegalStateException("图片解码失败")

            try {
                val oriented = applyExifOrientation(bitmap, rawBytes)
                if (oriented !== bitmap) {
                    bitmap.recycle()
                    bitmap = oriented
                }
                val scaled = scaleDownIfNeeded(bitmap, MAX_IMAGE_DIMENSION)
                if (scaled !== bitmap) {
                    bitmap.recycle()
                    bitmap = scaled
                }

                var encoded = compressJpeg(bitmap, quality = 86)
                if (encoded.size > TARGET_REENCODE_BYTES_LIMIT) {
                    encoded = compressJpeg(bitmap, quality = 70)
                }
                if (encoded.size > HcFramedClient.MAX_PUT_RAW_BYTES) {
                    throw IllegalStateException("图片压缩后仍超过 HC 8 MB 上限 (${encoded.size} bytes)")
                }
                PreparedImageUpload(replaceExtensionWithJpg(rawName), encoded)
            } finally {
                if (!bitmap.isRecycled) {
                    bitmap.recycle()
                }
            }
        }
    }

    private fun queryDisplayName(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) cursor.getString(idx) else null
            } else {
                null
            }
        }
    }.getOrNull()

    private fun readBounded(stream: java.io.InputStream, maxBytes: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        var total = 0
        while (true) {
            val read = stream.read(buf)
            if (read < 0) break
            if (read > 0) {
                total += read
                if (total > maxBytes) {
                    throw IllegalStateException("原图超过 32 MB 内存保护上限")
                }
                out.write(buf, 0, read)
            }
        }
        return out.toByteArray()
    }

    private fun computeInSampleSize(width: Int, height: Int, maxDim: Int): Int {
        var sample = 1
        var w = width
        var h = height
        while (w / 2 >= maxDim || h / 2 >= maxDim) {
            sample *= 2
            w /= 2
            h /= 2
        }
        return sample.coerceAtLeast(1)
    }

    private fun applyExifOrientation(bitmap: Bitmap, rawBytes: ByteArray): Bitmap {
        val orientation = runCatching {
            ExifInterface(ByteArrayInputStream(rawBytes)).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

        val degrees = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        if (degrees == 0f) return bitmap
        val matrix = Matrix().apply { postRotate(degrees) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    private fun scaleDownIfNeeded(bitmap: Bitmap, maxDim: Int): Bitmap {
        val longest = maxOf(bitmap.width, bitmap.height)
        if (longest <= maxDim) return bitmap
        val ratio = maxDim.toFloat() / longest.toFloat()
        val targetW = (bitmap.width * ratio).toInt().coerceAtLeast(1)
        val targetH = (bitmap.height * ratio).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, targetW, targetH, true)
    }

    private fun compressJpeg(bitmap: Bitmap, quality: Int): ByteArray {
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
        return out.toByteArray()
    }

    private fun extensionForMime(mime: String): String = when (mime) {
        "image/png" -> ".png"
        "image/webp" -> ".webp"
        "image/gif" -> ".gif"
        else -> ".jpg"
    }

    private fun ensureExtension(name: String, fallbackExt: String): String {
        val clean = name.trim().substringAfterLast('/').substringAfterLast('\\').ifBlank { "image" }
        val dot = clean.lastIndexOf('.')
        return if (dot in 1 until clean.length - 1) clean else "$clean$fallbackExt"
    }

    private fun replaceExtensionWithJpg(name: String): String {
        val clean = name.trim().substringAfterLast('/').substringAfterLast('\\').ifBlank { "image" }
        val dot = clean.lastIndexOf('.')
        val stem = if (dot > 0) clean.substring(0, dot) else clean
        return "$stem.jpg"
    }
}
