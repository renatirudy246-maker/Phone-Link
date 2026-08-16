package com.phonelink.app.scanner

import android.graphics.Bitmap
import android.util.Log
import com.phonelink.app.BuildConfig
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc

/** 扫描增强模式。 */
enum class EnhanceMode { ORIGINAL, AUTO, GRAY, BLACK_WHITE }

/**
 * 文档增强（OpenCV，本地执行，非破坏性）：
 * 始终从 perspective-corrected base 生成，绝不基于上一次增强结果再次处理。
 *
 * 显式色彩与通道转换契约（Enhance V1 稳定实现）：
 * Android Bitmap (ARGB_8888)
 *   ↓ Utils.bitmapToMat
 * RGBA (CV_8UC4)
 *   ↓ COLOR_RGBA2BGR
 * BGR (CV_8UC3)
 *   ↓ COLOR_BGR2Lab
 * Lab (CV_8UC3) [仅在 L 通道执行 CLAHE 适度局部对比度 + 轻度反锐化掩模]
 *   ↓ COLOR_Lab2BGR
 * BGR (CV_8UC3)
 *   ↓ COLOR_BGR2RGBA
 * RGBA (CV_8UC4)
 *   ↓ 安全校验 (Safety Guard)
 * Utils.matToBitmap
 * Android Bitmap (ARGB_8888)
 */
object DocumentEnhancer {

    private const val TAG = "DocumentEnhancer"

    /**
     * 应用增强模式。
     * - ORIGINAL：原样返回
     * - AUTO ("增强")：Lab 空间 CLAHE 提升文字对比度 + 轻度反锐化掩模，保留色彩与细节
     * - GRAY ("灰度")：标准灰度
     * - BLACK_WHITE ("黑白")：自适应局部二值化
     */
    fun enhance(bitmap: Bitmap, mode: EnhanceMode): Bitmap {
        return try {
            when (mode) {
                EnhanceMode.ORIGINAL -> bitmap
                EnhanceMode.BLACK_WHITE -> toBlackWhite(bitmap)
                EnhanceMode.GRAY -> toGray(bitmap)
                EnhanceMode.AUTO -> toEnhanceV1(bitmap)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Enhancement failed for mode $mode: ${e.message}", e)
            bitmap
        }
    }

    /**
     * Enhance V1 稳定管线：
     * 1. 显式 RGBA -> BGR -> Lab；
     * 2. 仅对 L 通道应用 CLAHE (clipLimit ~ 2.0, tileGrid 8x8)；
     * 3. 仅对 L 通道应用轻度反锐化 (GaussianBlur 3x3 + addWeighted 1.2, -0.2)；
     * 4. 合并回 Lab (a, b 保持原色彩)，转回 BGR -> RGBA；
     * 5. 输出安全校验：防止塌陷至全黑/全白。
     */
    private fun toEnhanceV1(bitmap: Bitmap): Bitmap {
        val inputRgba = Mat()
        val bgr = Mat()
        val lab = Mat()
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
                Log.d(TAG, "ENHANCE_V1 input: w=${bitmap.width} h=${bitmap.height} type=${inputRgba.type()} " +
                        "channels=${inputRgba.channels()} min=${minMaxIn.minVal} max=${minMaxIn.maxVal} " +
                        "mean=(${meanIn.`val`[0].toInt()}, ${meanIn.`val`[1].toInt()}, ${meanIn.`val`[2].toInt()})")
            }

            // 1. 显式 RGBA -> BGR -> Lab
            Imgproc.cvtColor(inputRgba, bgr, Imgproc.COLOR_RGBA2BGR)
            Imgproc.cvtColor(bgr, lab, Imgproc.COLOR_BGR2Lab)
            Core.split(lab, channels)
            val lChannel = channels[0]

            // 2. 仅在 L 通道执行 CLAHE (适度对比度，不激进破坏灰阶)
            val clahe = Imgproc.createCLAHE(2.0, org.opencv.core.Size(8.0, 8.0))
            clahe.apply(lChannel, clahedL)

            // 3. 仅在 L 通道执行微量反锐化掩模（增强小字边缘）
            Imgproc.GaussianBlur(clahedL, blurredL, org.opencv.core.Size(3.0, 3.0), 0.0)
            Core.addWeighted(clahedL, 1.2, blurredL, -0.2, 0.0, enhancedL)

            // 4. 合并回 Lab (a, b 保持原色彩)，转回 BGR -> RGBA
            enhancedL.copyTo(channels[0])
            Core.merge(channels, lab)
            Imgproc.cvtColor(lab, bgrOut, Imgproc.COLOR_Lab2BGR)
            Imgproc.cvtColor(bgrOut, rgbaOut, Imgproc.COLOR_BGR2RGBA)

            // 5. 校验输出
            val meanOut = Core.mean(rgbaOut)
            val avgBrightness = (meanOut.`val`[0] + meanOut.`val`[1] + meanOut.`val`[2]) / 3.0

            if (BuildConfig.DEBUG) {
                val minMaxOut = Core.minMaxLoc(enhancedL)
                Log.d(TAG, "ENHANCE_V1 output before bitmap: min=${minMaxOut.minVal} max=${minMaxOut.maxVal} avgBrightness=${"%.2f".format(avgBrightness)}")
            }

            if (!avgBrightness.isFinite() || avgBrightness < 5.0 || avgBrightness > 252.0) {
                Log.w(TAG, "ENHANCE_V1_INVALID_FALLBACK: avgBrightness=${avgBrightness}, falling back to original bitmap")
                return bitmap
            }

            return toBitmap(rgbaOut, bitmap.width, bitmap.height)
        } finally {
            inputRgba.release()
            bgr.release()
            lab.release()
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
            outRgba.release()
        }
    }

    private fun toBitmap(mat: Mat, width: Int, height: Int): Bitmap {
        val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(mat, out)
        return out
    }
}