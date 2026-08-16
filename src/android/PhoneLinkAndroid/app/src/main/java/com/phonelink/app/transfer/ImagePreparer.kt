package com.phonelink.app.transfer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/**
 * 图片规范化：读取 EXIF 方向并旋转为实际像素方向，统一输出 JPEG（quality 95，最长边 ≤4096）。
 * 输出文件 + 真实尺寸 + 流式 SHA-256（桌面端按同一哈希校验）。
 * 方案 A：规范化实际像素方向后上传（Windows 渲染零 EXIF 依赖，竖拍/横拍必正确）。
 */
object ImagePreparer {

    const val MAX_LONGEST_EDGE = 4096
    const val JPEG_QUALITY = 95

    class PrepareException(message: String, cause: Throwable? = null) : Exception(message, cause)

    data class PreparedImage(
        val file: File,
        val width: Int,
        val height: Int,
        val sha256: String,
        val fileSize: Long,
    )

    /** 从相机文件或 Content URI 读取并规范化。sourceName 用于 originalFileName。 */
    fun prepare(context: Context, source: Uri?, sourceFile: File?, outFile: File, sourceName: String): PreparedImage {
        require(source != null || sourceFile != null) { "No image source" }

        val bytes = try {
            when {
                source != null -> context.contentResolver.openInputStream(source)?.use { it.readBytes() }
                else -> sourceFile!!.readBytes()
            } ?: throw PrepareException("无法读取图片：来源为空。")
        } catch (e: PrepareException) {
            throw e
        } catch (e: Exception) {
            throw PrepareException("无法读取图片：${e.message}", e)
        }

        if (bytes.size > 64L * 1024 * 1024) {
            throw PrepareException("图片过大（>64MB），无法处理。")
        }

        val orientation = readOrientation(bytes)
        val (rawWidth, rawHeight) = decodeBounds(bytes)

        val sample = computeSampleSize(rawWidth, rawHeight)
        val bitmap = try {
            val options = BitmapFactory.Options().apply { inSampleSize = sample }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        } catch (e: Exception) {
            throw PrepareException("无法解码图片：${e.message}", e)
        } ?: throw PrepareException("无法解码图片（格式不支持或文件损坏）。")

        val rotatedBitmap = if (orientation != 0) {
            val matrix = Matrix().apply { postRotate(orientation.toFloat()) }
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                .also { if (it !== bitmap) bitmap.recycle() }
        } else {
            bitmap
        }

        val outWidth = rotatedBitmap.width
        val outHeight = rotatedBitmap.height
        try {
            FileOutputStream(outFile).use { out ->
                rotatedBitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            }
        } catch (e: Exception) {
            throw PrepareException("写入规范化图片失败：${e.message}", e)
        } finally {
            rotatedBitmap.recycle()
        }

        val sha256 = sha256Hex(outFile)
        return PreparedImage(
            file = outFile,
            width = outWidth,
            height = outHeight,
            sha256 = sha256,
            fileSize = outFile.length(),
        )
    }

    /**
     * 从已规范化文件按像素区域裁切（全分辨率，一次 JPEG 编码 quality 95）。
     * 输入为方向归一化像素（prepare 输出），因此用户看到的方向 = 裁切坐标方向。
     */
    fun crop(source: File, rect: com.phonelink.app.crop.CropRect, outFile: File, quality: Int = JPEG_QUALITY): PreparedImage {
        val bitmap = try {
            BitmapFactory.decodeFile(source.absolutePath)
        } catch (e: Exception) {
            throw PrepareException("无法解码图片：${e.message}", e)
        } ?: throw PrepareException("无法解码图片（格式不支持或文件损坏）。")

        return try {
            crop(bitmap, rect, outFile, quality)
        } finally {
            bitmap.recycle()
        }
    }

    /**
     * 从已解码 Bitmap 裁切并编码（全分辨率，一次 JPEG 编码 quality 95）。
     * 调用方负责 recycle 传入的 bitmap（本函数不回收）。
     */
    fun crop(bitmap: Bitmap, rect: com.phonelink.app.crop.CropRect, outFile: File, quality: Int = JPEG_QUALITY): PreparedImage {
        val left = rect.left.coerceIn(0, bitmap.width - 1)
        val top = rect.top.coerceIn(0, bitmap.height - 1)
        val right = rect.right.coerceIn(left + 1, bitmap.width)
        val bottom = rect.bottom.coerceIn(top + 1, bitmap.height)

        val cropped = Bitmap.createBitmap(bitmap, left, top, right - left, bottom - top)
        val outWidth = cropped.width
        val outHeight = cropped.height
        try {
            FileOutputStream(outFile).use { out ->
                cropped.compress(Bitmap.CompressFormat.JPEG, quality, out)
            }
        } catch (e: Exception) {
            throw PrepareException("写入裁切图片失败：${e.message}", e)
        } finally {
            cropped.recycle()
        }
        val sha256 = sha256Hex(outFile)
        return PreparedImage(
            file = outFile,
            width = outWidth,
            height = outHeight,
            sha256 = sha256,
            fileSize = outFile.length(),
        )
    }

    /** 读取 EXIF orientation（0=无需旋转，90/180/270 顺时针旋转角度）。 */
    private fun readOrientation(bytes: ByteArray): Int {
        return try {
            val exif = ExifInterface(java.io.ByteArrayInputStream(bytes))
            when (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90
                ExifInterface.ORIENTATION_ROTATE_180 -> 180
                ExifInterface.ORIENTATION_ROTATE_270 -> 270
                else -> 0
            }
        } catch (_: Exception) {
            0
        }
    }

    private fun decodeBounds(bytes: ByteArray): Pair<Int, Int> {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        return options.outWidth.coerceAtLeast(1) to options.outHeight.coerceAtLeast(1)
    }

    /** 使解码后最长边 ≤ MAX_LONGEST_EDGE（2 的幂采样）。 */
    private fun computeSampleSize(width: Int, height: Int): Int {
        val longest = maxOf(width, height)
        var sample = 1
        while (longest / (sample * 2) >= MAX_LONGEST_EDGE) {
            sample *= 2
        }
        return sample
    }

    /** 流式 SHA-256（64KB 分块，不整体载入内存）。 */
    fun sha256Hex(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02X".format(it) }
    }
}