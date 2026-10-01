package com.feiyu.notes.settings

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.util.AtomicFile
import androidx.exifinterface.media.ExifInterface
import android.graphics.BitmapFactory
import android.graphics.Matrix
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** A private, bounded copy. No photo-library permission or long-lived external URI. */
class AvatarFiles(root: File, name: String = "deepseek.png") {
    private val _revision = kotlinx.coroutines.flow.MutableStateFlow(0L)
    val revision: kotlinx.coroutines.flow.StateFlow<Long> = _revision
    val file = File(root, "appearance/$name")

    suspend fun import(resolver: ContentResolver, uri: Uri) = withContext(Dispatchers.IO) {
        file.parentFile!!.mkdirs()
        val source = File.createTempFile("avatar-", ".tmp", file.parentFile)
        try {
            resolver.openInputStream(uri).use { input ->
                requireNotNull(input)
                source.outputStream().use { output ->
                    val buffer = ByteArray(8192)
                    var total = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        require(total <= 32L * 1024 * 1024)
                        output.write(buffer, 0, count)
                    }
                }
            }
            val bitmap = if (Build.VERSION.SDK_INT >= 28) {
                ImageDecoder.decodeBitmap(ImageDecoder.createSource(source)) { decoder, info, _ ->
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    val scale = minOf(1f, 512f / maxOf(info.size.width, info.size.height))
                    decoder.setTargetSize(maxOf(1, (info.size.width * scale).toInt()), maxOf(1, (info.size.height * scale).toInt()))
                }
            } else {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(source.path, bounds)
                require(bounds.outWidth > 0 && bounds.outHeight > 0)
                var sample = 1
                while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 1024) sample *= 2
                val decoded = requireNotNull(BitmapFactory.decodeFile(source.path, BitmapFactory.Options().apply { inSampleSize = sample }))
                val exif = ExifInterface(source)
                val matrix = Matrix().apply {
                    if (exif.isFlipped) postScale(-1f, 1f)
                    postRotate(exif.rotationDegrees.toFloat())
                }
                Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
            }
            val atomic = AtomicFile(file)
            val stream = atomic.startWrite()
            try {
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream))
                atomic.finishWrite(stream)
                _revision.value++
            } catch (e: Exception) {
                atomic.failWrite(stream)
                throw e
            } finally { bitmap.recycle() }
        } finally { source.delete() }
    }

    fun reset() { AtomicFile(file).delete(); _revision.value++ }
}
