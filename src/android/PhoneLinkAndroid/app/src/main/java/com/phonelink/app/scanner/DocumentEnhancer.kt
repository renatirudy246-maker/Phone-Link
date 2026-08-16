package com.phonelink.app.scanner

import android.graphics.Bitmap
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Scalar
import org.opencv.imgproc.Imgproc

/** 扫描增强模式。 */
enum class EnhanceMode { ORIGINAL, AUTO, GRAY, BLACK_WHITE }

/**
 * 文档增强（OpenCV，本地执行，非破坏性）：
 * 始终从 perspective-corrected base 生成，绝不基于上一次增强结果再次处理。
 */
object DocumentEnhancer {

    /**
     * 应用增强模式。
     * - ORIGINAL：原样返回（不复制，调用方不得 recycle 返回值外的位图）
     * - AUTO：亮度归一化 + 局部对比度（CLAHE）+ 轻度锐化，保留灰阶与彩色（不抹铅笔字）
     * - GRAY：灰度
     * - BLACK_WHITE：自适应阈值黑白（用户主动选择，非默认）
     */
    fun enhance(bitmap: Bitmap, mode: EnhanceMode): Bitmap {
        if (mode == EnhanceMode.ORIGINAL) return bitmap
        if (mode == EnhanceMode.BLACK_WHITE) return toBlackWhite(bitmap)
        if (mode == EnhanceMode.GRAY) return toGray(bitmap)
        return toAuto(bitmap)
    }

    private fun toAuto(bitmap: Bitmap): Bitmap {
        val input = Mat()
        Utils.bitmapToMat(bitmap, input)
        val result = Mat()
        try {
            // 1. 亮度/光照归一化：大核模糊估计背景光照，除法归一（保留灰阶）
            val blurSize = maxOf(31, minOf(bitmap.width, bitmap.height) / 8 * 2 + 1)
            val background = Mat()
            Imgproc.GaussianBlur(input, background, org.opencv.core.Size(blurSize.toDouble(), blurSize.toDouble()), 0.0)
            val flat = Mat()
            Core.divide(input, background, flat, 255.0 / 128.0, CvType.CV_8UC3)
            background.release()

            // 2. 局部对比度：YCrCb 亮度通道 CLAHE
            val ycrcb = Mat()
            Imgproc.cvtColor(flat, ycrcb, Imgproc.COLOR_BGR2YCrCb)
            flat.release()
            val channels = ArrayList<Mat>()
            Core.split(ycrcb, channels)
            val clahe = Imgproc.createCLAHE(2.0, org.opencv.core.Size(8.0, 8.0))
            clahe.apply(channels[0], channels[0])
            Core.merge(channels, ycrcb)
            for (c in channels) c.release()
            Imgproc.cvtColor(ycrcb, result, Imgproc.COLOR_YCrCb2BGR)
            ycrcb.release()

            // 3. 轻度锐化（unsharp mask，保留小字/符号边缘）
            val blurred = Mat()
            Imgproc.GaussianBlur(result, blurred, org.opencv.core.Size(3.0, 3.0), 0.0)
            Core.addWeighted(result, 1.25, blurred, -0.25, 0.0, result)
            blurred.release()

            return toBitmap(result, bitmap.width, bitmap.height)
        } finally {
            input.release()
            result.release()
        }
    }

    private fun toGray(bitmap: Bitmap): Bitmap {
        val input = Mat()
        Utils.bitmapToMat(bitmap, input)
        val gray = Mat()
        try {
            Imgproc.cvtColor(input, gray, Imgproc.COLOR_BGRA2GRAY)
            val out = Mat()
            Imgproc.cvtColor(gray, out, Imgproc.COLOR_GRAY2BGRA)
            gray.release()
            return toBitmap(out, bitmap.width, bitmap.height).also { out.release() }
        } finally {
            input.release()
        }
    }

    private fun toBlackWhite(bitmap: Bitmap): Bitmap {
        val input = Mat()
        Utils.bitmapToMat(bitmap, input)
        val gray = Mat()
        try {
            Imgproc.cvtColor(input, gray, Imgproc.COLOR_BGRA2GRAY)
            val binary = Mat()
            Imgproc.adaptiveThreshold(
                gray, binary, 255.0,
                Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C, Imgproc.THRESH_BINARY,
                31, 12.0,
            )
            gray.release()
            // 去小噪点
            val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, org.opencv.core.Size(2.0, 2.0))
            Imgproc.morphologyEx(binary, binary, Imgproc.MORPH_OPEN, kernel)
            val out = Mat()
            Imgproc.cvtColor(binary, out, Imgproc.COLOR_GRAY2BGRA)
            binary.release()
            return toBitmap(out, bitmap.width, bitmap.height).also { out.release() }
        } finally {
            input.release()
        }
    }

    private fun toBitmap(mat: Mat, width: Int, height: Int): Bitmap {
        val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(mat, out)
        return out
    }
}