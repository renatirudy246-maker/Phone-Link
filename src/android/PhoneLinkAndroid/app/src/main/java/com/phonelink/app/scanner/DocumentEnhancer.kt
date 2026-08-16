package com.phonelink.app.scanner

import android.graphics.Bitmap
import android.util.Log
import com.phonelink.app.BuildConfig
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
 *
 * 显式色彩与通道转换契约：
 * Android Bitmap (ARGB_8888)
 *   ↓ Utils.bitmapToMat
 * RGBA (CV_8UC4)
 *   ↓ COLOR_RGBA2BGR
 * BGR (CV_8UC3)
 *   ↓ COLOR_BGR2Lab
 * Lab (CV_8UC3) [L 通道归一化 + CLAHE + 锐化]
 *   ↓ COLOR_Lab2BGR
 * BGR (CV_8UC3)
 *   ↓ COLOR_BGR2RGBA
 * RGBA (CV_8UC4)
 *   ↓ Utils.matToBitmap
 * Android Bitmap (ARGB_8888)
 */
object DocumentEnhancer {

    private const val TAG = "DocumentEnhancer"

    /**
     * 应用增强模式。
     * - ORIGINAL：原样返回（不复制，调用方不得 recycle 返回值外的位图）
     * - AUTO：亮度/阴影归一化 + 局部对比度（CLAHE）+ 轻度锐化，保留灰阶与色彩（不抹铅笔字与图表）
     * - GRAY：标准灰度
     * - BLACK_WHITE：自适应阈值二值化（用户主动选择，非默认）
     */
    fun enhance(bitmap: Bitmap, mode: EnhanceMode): Bitmap {
        return try {
            when (mode) {
                EnhanceMode.ORIGINAL -> bitmap
                EnhanceMode.BLACK_WHITE -> toBlackWhite(bitmap)
                EnhanceMode.GRAY -> toGray(bitmap)
                EnhanceMode.AUTO -> toAuto(bitmap)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Enhancement failed for mode $mode: ${e.message}", e)
            bitmap
        }
    }

    private fun toAuto(bitmap: Bitmap): Bitmap {
        val inputRgba = Mat()
        val bgr = Mat()
        val lab = Mat()
        val backgroundL = Mat()
        val normalizedL = Mat()
        val clahedL = Mat()
        val blurredL = Mat()
        val enhancedL = Mat()
        val bgrOut = Mat()
        val rgbaOut = Mat()
        val channels = ArrayList<Mat>()

        try {
            Utils.bitmapToMat(bitmap, inputRgba)

            if (BuildConfig.DEBUG) {
                val meanIn = Core.mean(inputRgba)
                val minMaxIn = Core.minMaxLoc(inputRgba)
                Log.d(TAG, "AUTO input: w=${bitmap.width} h=${bitmap.height} type=${inputRgba.type()} " +
                        "channels=${inputRgba.channels()} min=${minMaxIn.minVal} max=${minMaxIn.maxVal} " +
                        "mean=(${meanIn.`val`[0].toInt()}, ${meanIn.`val`[1].toInt()}, ${meanIn.`val`[2].toInt()})")
            }

            // 1. 显式 RGBA -> BGR -> Lab (提取纯明度通道 L)
            Imgproc.cvtColor(inputRgba, bgr, Imgproc.COLOR_RGBA2BGR)
            Imgproc.cvtColor(bgr, lab, Imgproc.COLOR_BGR2Lab)
            Core.split(lab, channels)
            val lChannel = channels[0]

            if (BuildConfig.DEBUG) {
                val minMaxL = Core.minMaxLoc(lChannel)
                val meanL = Core.mean(lChannel)
                Log.d(TAG, "AUTO luminance: min=${minMaxL.minVal} max=${minMaxL.maxVal} mean=${"%.2f".format(meanL.`val`[0])}")
            }

            // 2. 光照背景估计：大核高斯模糊（自适应分辨率）
            val blurSize = maxOf(31, minOf(bitmap.width, bitmap.height) / 8 * 2 + 1)
            Imgproc.GaussianBlur(
                lChannel,
                backgroundL,
                org.opencv.core.Size(blurSize.toDouble(), blurSize.toDouble()),
                0.0,
            )
            // 避免除以 0
            Core.max(backgroundL, Scalar(1.0), backgroundL)

            // 3. 亮度归一化：除法背景校正（scale 必须为 255.0，使背景拉伸至纯净白纸，文字与笔迹保留相对灰度）
            Core.divide(lChannel, backgroundL, normalizedL, 255.0, CvType.CV_8UC1)

            if (BuildConfig.DEBUG) {
                val minMaxNorm = Core.minMaxLoc(normalizedL)
                val meanNorm = Core.mean(normalizedL)
                Log.d(TAG, "AUTO normalized: min=${minMaxNorm.minVal} max=${minMaxNorm.maxVal} mean=${"%.2f".format(meanNorm.`val`[0])}")
            }

            // 4. 局部对比度增强：CLAHE 适度提升文字锐利度
            val clahe = Imgproc.createCLAHE(1.8, org.opencv.core.Size(8.0, 8.0))
            clahe.apply(normalizedL, clahedL)

            // 5. 轻度反锐化掩模（unsharp mask），增强细字与公式边缘
            Imgproc.GaussianBlur(clahedL, blurredL, org.opencv.core.Size(3.0, 3.0), 0.0)
            Core.addWeighted(clahedL, 1.2, blurredL, -0.2, 0.0, enhancedL)

            // 6. 合并 Lab 并转回 BGR -> RGBA
            enhancedL.copyTo(channels[0])
            Core.merge(channels, lab)
            Imgproc.cvtColor(lab, bgrOut, Imgproc.COLOR_Lab2BGR)
            Imgproc.cvtColor(bgrOut, rgbaOut, Imgproc.COLOR_BGR2RGBA)

            // 7. AUTO 安全守卫（Safety Guard）：防止崩塌至纯黑/纯白或 NaN
            val meanOut = Core.mean(rgbaOut)
            val avgBrightness = (meanOut.`val`[0] + meanOut.`val`[1] + meanOut.`val`[2]) / 3.0
            if (BuildConfig.DEBUG) {
                val minMaxOut = Core.minMaxLoc(enhancedL)
                Log.d(TAG, "AUTO output before bitmap: min=${minMaxOut.minVal} max=${minMaxOut.maxVal} avgBrightness=${"%.2f".format(avgBrightness)}")
            }

            if (!avgBrightness.isFinite() || avgBrightness < 8.0 || avgBrightness > 252.0) {
                Log.w(TAG, "AUTO_ENHANCEMENT_INVALID_OUTPUT: avgBrightness=${avgBrightness}, falling back to original bitmap")
                return bitmap
            }

            return toBitmap(rgbaOut, bitmap.width, bitmap.height)
        } finally {
            inputRgba.release()
            bgr.release()
            lab.release()
            backgroundL.release()
            normalizedL.release()
            clahedL.release()
            blurredL.release()
            enhancedL.release()
            bgrOut.release()
            rgbaOut.release()
            for (c in channels) c.release()
        }
    }

    private fun toGray(bitmap: Bitmap): Bitmap {
        val inputRgba = Mat()
        val gray = Mat()
        val outRgba = Mat()
        try {
            Utils.bitmapToMat(bitmap, inputRgba)
            Imgproc.cvtColor(inputRgba, gray, Imgproc.COLOR_RGBA2GRAY)
            Imgproc.cvtColor(gray, outRgba, Imgproc.COLOR_GRAY2RGBA)
            return toBitmap(outRgba, bitmap.width, bitmap.height)
        } finally {
            inputRgba.release()
            gray.release()
            outRgba.release()
        }
    }

    private fun toBlackWhite(bitmap: Bitmap): Bitmap {
        val inputRgba = Mat()
        val gray = Mat()
        val binary = Mat()
        val kernel = Mat()
        val outRgba = Mat()
        try {
            Utils.bitmapToMat(bitmap, inputRgba)
            Imgproc.cvtColor(inputRgba, gray, Imgproc.COLOR_RGBA2GRAY)
            Imgproc.adaptiveThreshold(
                gray, binary, 255.0,
                Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C, Imgproc.THRESH_BINARY,
                31, 12.0,
            )
            // 去小噪点
            val k = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, org.opencv.core.Size(2.0, 2.0))
            Imgproc.morphologyEx(binary, binary, Imgproc.MORPH_OPEN, k)
            k.release()

            Imgproc.cvtColor(binary, outRgba, Imgproc.COLOR_GRAY2RGBA)
            return toBitmap(outRgba, bitmap.width, bitmap.height)
        } finally {
            inputRgba.release()
            gray.release()
            binary.release()
            kernel.release()
            outRgba.release()
        }
    }

    private fun toBitmap(mat: Mat, width: Int, height: Int): Bitmap {
        val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(mat, out)
        return out
    }
}