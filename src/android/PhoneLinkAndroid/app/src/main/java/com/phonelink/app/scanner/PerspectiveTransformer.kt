package com.phonelink.app.scanner

import android.graphics.Bitmap
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.imgproc.Imgproc
import kotlin.math.max

/**
 * 透视校正：四个源角点 → 矩形目标（getPerspectiveTransform + warpPerspective）。
 * 必须基于原始高分辨率归一化图片执行；输出最长边 ≤ ScannerConfig.WARP_MAX_EDGE。
 */
object PerspectiveTransformer {

    /**
     * 对方向归一化图片做透视校正。
     * @return 校正后 Bitmap（调用方负责 recycle）
     */
    fun warp(bitmap: Bitmap, quad: Quadrilateral): Bitmap {
        val px = ScannerMath.normalizedToPixels(quad, bitmap.width, bitmap.height)
        val (outWidth, outHeight) = ScannerMath.perspectiveOutputSize(quad, bitmap.width, bitmap.height, ScannerConfig.WARP_MAX_EDGE)

        val src = MatOfPoint2f(
            Point(px.topLeft.x.toDouble(), px.topLeft.y.toDouble()),
            Point(px.topRight.x.toDouble(), px.topRight.y.toDouble()),
            Point(px.bottomRight.x.toDouble(), px.bottomRight.y.toDouble()),
            Point(px.bottomLeft.x.toDouble(), px.bottomLeft.y.toDouble()),
        )
        val dst = MatOfPoint2f(
            Point(0.0, 0.0),
            Point(outWidth.toDouble(), 0.0),
            Point(outWidth.toDouble(), outHeight.toDouble()),
            Point(0.0, outHeight.toDouble()),
        )
        val matrix = Imgproc.getPerspectiveTransform(src, dst)
        val input = Mat()
        Utils.bitmapToMat(bitmap, input)
        val output = Mat()
        try {
            Imgproc.warpPerspective(input, output, matrix, org.opencv.core.Size(outWidth.toDouble(), outHeight.toDouble()), Imgproc.INTER_LINEAR)
            val outBitmap = Bitmap.createBitmap(outWidth, outHeight, Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(output, outBitmap)
            return outBitmap
        } finally {
            input.release()
            output.release()
            matrix.release()
        }
    }
}