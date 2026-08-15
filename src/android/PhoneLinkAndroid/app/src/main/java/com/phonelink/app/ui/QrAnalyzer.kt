package com.phonelink.app.ui

import android.graphics.Bitmap
import android.graphics.Matrix
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer

/**
 * CameraX ImageAnalysis 处理器：ImageProxy.toBitmap() 取帧，
 * 应用 rotationDegrees 旋转（预览方向），ZXing 解码 QR。
 * 解码成功即停止分析。
 */
class QrAnalyzer(
    private val onDecoded: (String) -> Unit,
) : ImageAnalysis.Analyzer {

    private var decoded = false

    override fun analyze(image: ImageProxy) {
        if (decoded) {
            image.close()
            return
        }

        try {
            val text = decodeQr(image)
            if (text != null) {
                decoded = true
                Log.d(TAG, "QR decoded: len=${text.length} head=${text.take(60)}")
                onDecoded(text)
            }
        } catch (e: Exception) {
            Log.w(TAG, "QR analysis failed", e)
        } finally {
            image.close()
        }
    }

    private fun decodeQr(image: ImageProxy): String? {
        val bitmap = image.toBitmap()
        val rotated = applyRotation(bitmap, image.imageInfo.rotationDegrees)
        val width = rotated.width
        val height = rotated.height
        val pixels = IntArray(width * height)
        rotated.getPixels(pixels, 0, width, 0, 0, width, height)
        val source = RGBLuminanceSource(width, height, pixels)
        return reader.decode(BinaryBitmap(HybridBinarizer(source)))?.text
    }

    private fun applyRotation(bitmap: Bitmap, rotationDegrees: Int): Bitmap {
        if (rotationDegrees == 0) {
            return bitmap
        }

        val matrix = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
        val rotated = Bitmap.createBitmap(
            bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        if (rotated !== bitmap) {
            bitmap.recycle()
        }

        return rotated
    }

    companion object {
        private const val TAG = "PhoneLinkQR"

        private val reader = MultiFormatReader().apply {
            setHints(
                mapOf(
                    DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
                    DecodeHintType.TRY_HARDER to true,
                )
            )
        }
    }
}