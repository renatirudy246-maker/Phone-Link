package com.phonelink.app.scanner

import android.graphics.Bitmap
import android.util.Log
import com.phonelink.app.BuildConfig
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfFloat
import org.opencv.core.MatOfInt
import org.opencv.imgproc.Imgproc

/** 扫描增强模式。 */
enum class EnhanceMode { ORIGINAL, AUTO, GRAY, BLACK_WHITE }

/**
 * 文档增强 V2（OpenCV，本地执行，非破坏性）：
 * 始终从 perspective-corrected base 生成，绝不基于上一次增强结果再次处理。
 *
 * 显式色彩与通道转换契约（Enhance V2 确定性管线）：
 * Android Bitmap (ARGB_8888)
 *   ↓ Utils.bitmapToMat
 * RGBA (CV_8UC4)
 *   ↓ COLOR_RGBA2BGR
 * BGR (CV_8UC3)
 *   ↓ COLOR_BGR2Lab
 * Lab (CV_8UC3)
 *   ├─ a, b 通道保持不变（保留图表与彩色标记，色相 100% 真实不失真）
 *   └─ L 通道（纯明度）：
 *       1. 百分位数对比度拉伸（P1% -> 15, P98% -> 245），使暗淡发灰的底纸平整白净，文字黑度加深
 *       2. CLAHE 局部对比度提升 (clipLimit = 2.5, tileGridSize = 8x8)
 *       3. 微量反锐化掩模 (GaussianBlur 3x3 + addWeighted 1.20, -0.20)，强化笔画与公式边缘
 *   ↓ 合并 L, a, b
 * Lab (CV_8UC3)
 *   ↓ COLOR_Lab2BGR
 * BGR (CV_8UC3)
 *   ↓ COLOR_BGR2RGBA
 * RGBA (CV_8UC4) [强制 Alpha = 255]
 *   ↓ 尺寸严格不变性校验 (processed.width == input.width && processed.height == input.height)
 * Utils.matToBitmap
 * Android Bitmap (ARGB_8888)
 */
object DocumentEnhancer {

    private const val TAG = "DocumentEnhancer"

    /**
     * 应用增强模式。
     * - ORIGINAL：原样返回
     * - AUTO ("增强")：Lab 空间百分位对比度拉伸 + CLAHE 2.5 + 轻度反锐化掩模
     * - GRAY ("灰度")：标准灰度
     * - BLACK_WHITE ("黑白")：自适应局部二值化
     */
    fun enhance(bitmap: Bitmap, mode: EnhanceMode): Bitmap {
        return try {
            when (mode) {
                EnhanceMode.ORIGINAL -> bitmap
                EnhanceMode.BLACK_WHITE -> toBlackWhite(bitmap)
                EnhanceMode.GRAY -> toGray(bitmap)
                EnhanceMode.AUTO -> toEnhanceV2(bitmap)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Enhancement failed for mode $mode: ${e.message}", e)
            bitmap
        }
    }

    /**
     * Enhance V2 稳定管线：
     */
    private fun toEnhanceV2(bitmap: Bitmap): Bitmap {
        val inputRgba = Mat()
        val bgr = Mat()
        val lab = Mat()
        val stretchedL = Mat()
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
                Log.d(TAG, "ENHANCE_V2 input: w=${bitmap.width} h=${bitmap.height} type=${inputRgba.type()} " +
                        "channels=${inputRgba.channels()} min=${minMaxIn.minVal} max=${minMaxIn.maxVal} " +
                        "mean=(${meanIn.`val`[0].toInt()}, ${meanIn.`val`[1].toInt()}, ${meanIn.`val`[2].toInt()})")
            }

            // 1. 显式 RGBA -> BGR -> Lab (提取纯明度通道 L)
            Imgproc.cvtColor(inputRgba, bgr, Imgproc.COLOR_RGBA2BGR)
            Imgproc.cvtColor(bgr, lab, Imgproc.COLOR_BGR2Lab)
            Core.split(lab, channels)
            val lChannel = channels[0]

            // 2. 统计 L 通道 1% 与 98% 百分位数，计算线性对比度拉伸参数
            val (pLow, pHigh) = computeLuminancePercentiles(lChannel)
            val scale = 230.0 / maxOf(10.0, (pHigh - pLow))
            val shift = 15.0 - pLow * scale
            lChannel.convertTo(stretchedL, CvType.CV_8UC1, scale, shift)

            // 3. CLAHE 适度局部文字对比度增强 (clipLimit = 2.5, tileGrid = 8x8)
            val clahe = Imgproc.createCLAHE(2.5, org.opencv.core.Size(8.0, 8.0))
            clahe.apply(stretchedL, clahedL)

            // 4. 仅在 L 通道执行微量反锐化掩模（增强小字与公式边缘）
            Imgproc.GaussianBlur(clahedL, blurredL, org.opencv.core.Size(3.0, 3.0), 0.0)
            Core.addWeighted(clahedL, 1.20, blurredL, -0.20, 0.0, enhancedL)

            // 5. 合并回 Lab (a, b 保持原色彩)，转回 BGR -> RGBA
            enhancedL.copyTo(channels[0])
            Core.merge(channels, lab)
            Imgproc.cvtColor(lab, bgrOut, Imgproc.COLOR_Lab2BGR)
            Imgproc.cvtColor(bgrOut, rgbaOut, Imgproc.COLOR_BGR2RGBA)

            // 6. 校验输出：防止塌陷至全黑/全白
            val meanOut = Core.mean(rgbaOut)
            val avgBrightness = (meanOut.`val`[0] + meanOut.`val`[1] + meanOut.`val`[2]) / 3.0

            if (BuildConfig.DEBUG) {
                val minMaxOut = Core.minMaxLoc(enhancedL)
                Log.d(TAG, "ENHANCE_V2 output before bitmap: min=${minMaxOut.minVal} max=${minMaxOut.maxVal} avgBrightness=${"%.2f".format(avgBrightness)}")
            }

            if (!avgBrightness.isFinite() || avgBrightness < 5.0 || avgBrightness > 252.0) {
                Log.w(TAG, "ENHANCE_V2_INVALID_FALLBACK: avgBrightness=${avgBrightness}, falling back to original bitmap")
                return bitmap
            }

            val outBitmap = toBitmap(rgbaOut, bitmap.width, bitmap.height)
            require(outBitmap.width == bitmap.width && outBitmap.height == bitmap.height) {
                "Enhanced bitmap dimensions mismatch: expected (${bitmap.width}x${bitmap.height}), got (${outBitmap.width}x${outBitmap.height})"
            }
            return outBitmap
        } finally {
            inputRgba.release()
            bgr.release()
            lab.release()
            stretchedL.release()
            clahedL.release()
            blurredL.release()
            enhancedL.release()
            bgrOut.release()
            rgbaOut.release()
            for (c in channels) c.release()
        }
    }

    /**
     * 计算单通道 8-bit 图像的 1% 和 98% 灰度百分位数。
     */
    private fun computeLuminancePercentiles(lMat: Mat): Pair<Double, Double> {
        val hist = Mat()
        try {
            Imgproc.calcHist(
                listOf(lMat),
                MatOfInt(0),
                Mat(),
                hist,
                MatOfInt(256),
                MatOfFloat(0f, 256f),
            )
            val totalPixels = lMat.total().toDouble()
            val lowThreshold = totalPixels * 0.01
            val highThreshold = totalPixels * 0.98

            var cumulative = 0.0
            var pLow = 0.0
            var pHigh = 255.0
            var foundLow = false

            val histData = FloatArray(256)
            hist.get(0, 0, histData)

            for (i in 0 until 256) {
                cumulative += histData[i]
                if (!foundLow && cumulative >= lowThreshold) {
                    pLow = i.toDouble()
                    foundLow = true
                }
                if (cumulative >= highThreshold) {
                    pHigh = i.toDouble()
                    break
                }
            }

            if (pHigh <= pLow + 10.0) {
                pLow = 0.0
                pHigh = 255.0
            }
            return pLow to pHigh
        } finally {
            hist.release()
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
            val out = toBitmap(outRgba, bitmap.width, bitmap.height)
            require(out.width == bitmap.width && out.height == bitmap.height)
            return out
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
            val out = toBitmap(outRgba, bitmap.width, bitmap.height)
            require(out.width == bitmap.width && out.height == bitmap.height)
            return out
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