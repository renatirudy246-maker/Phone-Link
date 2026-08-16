package com.phonelink.app.scanner

import android.graphics.Bitmap
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 高分辨率 Sobel 梯度带边缘精修器。
 *
 * 在原图高分辨率像素空间上，沿 DocQuadNet 预测的 4 条多边形边建立垂直梯度采样带，
 * 采用 Huber 鲁棒直线拟合精确咬合纸张物理边界，并在最大允许位移内修正角点。
 */
object HighResEdgeRefiner {

    private const val MAX_PROC_EDGE = 1440
    private const val SAMPLES_PER_EDGE = 35
    private const val SEARCH_DIST_FRAC = 0.025f
    private const val MAX_CORNER_DISPLACEMENT_FRAC = 0.04f

    fun refine(image: Bitmap, quad: Quadrilateral): Quadrilateral {
        val origW = image.width
        val origH = image.height
        if (origW <= 0 || origH <= 0) return quad

        val scale = min(1.0f, MAX_PROC_EDGE.toFloat() / max(origW, origH).toFloat())
        val procW = (origW * scale).roundToInt().coerceAtLeast(2)
        val procH = (origH * scale).roundToInt().coerceAtLeast(2)

        val workingBitmap = if (scale < 0.99f) {
            Bitmap.createScaledBitmap(image, procW, procH, true)
        } else {
            image
        }

        val srcMat = Mat()
        Utils.bitmapToMat(workingBitmap, srcMat)
        val gray = Mat()
        Imgproc.cvtColor(srcMat, gray, Imgproc.COLOR_BGRA2GRAY)
        srcMat.release()

        try {
            val gradX = Mat()
            val gradY = Mat()
            Imgproc.Sobel(gray, gradX, CvType.CV_32F, 1, 0, 3)
            Imgproc.Sobel(gray, gradY, CvType.CV_32F, 0, 1, 3)

            val mag = Mat()
            Core.magnitude(gradX, gradY, mag)
            gradX.release()
            gradY.release()

            val diag = hypot(procW.toFloat(), procH.toFloat())
            val searchDist = (diag * SEARCH_DIST_FRAC).roundToInt().coerceIn(6, 40)

            val quadPx = listOf(
                Point(quad.topLeft.x.toDouble() * procW, quad.topLeft.y.toDouble() * procH),
                Point(quad.topRight.x.toDouble() * procW, quad.topRight.y.toDouble() * procH),
                Point(quad.bottomRight.x.toDouble() * procW, quad.bottomRight.y.toDouble() * procH),
                Point(quad.bottomLeft.x.toDouble() * procW, quad.bottomLeft.y.toDouble() * procH),
            )

            val fittedLines = mutableListOf<LineEq>()

            for (i in 0 until 4) {
                val p1 = quadPx[i]
                val p2 = quadPx[(i + 1) % 4]
                val dx = (p2.x - p1.x).toFloat()
                val dy = (p2.y - p1.y).toFloat()
                val len = hypot(dx, dy)
                if (len < 10f) return quad

                val nx = -dy / len
                val ny = dx / len

                val edgeSamples = mutableListOf<Point>()
                val magBuffer = FloatArray(1)

                for (s in 1 until SAMPLES_PER_EDGE) {
                    val t = s.toFloat() / SAMPLES_PER_EDGE.toFloat()
                    val baseX = p1.x + t * dx
                    val baseY = p1.y + t * dy

                    var bestVal = -1f
                    var bestOffset = 0

                    for (off in -searchDist..searchDist) {
                        val sx = (baseX + off * nx).roundToInt()
                        val sy = (baseY + off * ny).roundToInt()
                        if (sx in 0 until procW && sy in 0 until procH) {
                            mag.get(sy, sx, magBuffer)
                            val v = magBuffer[0]
                            if (v > bestVal) {
                                bestVal = v
                                bestOffset = off
                            }
                        }
                    }

                    if (bestVal > 20.0f) {
                        edgeSamples.add(Point(baseX + bestOffset * nx, baseY + bestOffset * ny))
                    }
                }

                if (edgeSamples.size >= 8) {
                    val matOfPoint = MatOfPoint2f(*edgeSamples.toTypedArray())
                    val lineParams = Mat()
                    Imgproc.fitLine(matOfPoint, lineParams, Imgproc.DIST_HUBER, 0.0, 0.01, 0.01)

                    val vx = lineParams.get(0, 0)[0]
                    val vy = lineParams.get(1, 0)[0]
                    val x0 = lineParams.get(2, 0)[0]
                    val y0 = lineParams.get(3, 0)[0]

                    val a = vy
                    val b = -vx
                    val norm = hypot(a, b) + 1e-9
                    val c = -(a * x0 + b * y0)
                    fittedLines.add(LineEq(a / norm, b / norm, c / norm))

                    matOfPoint.release()
                    lineParams.release()
                } else {
                    val a = -dy / len
                    val b = dx / len
                    val c = -(a * p1.x + b * p1.y)
                    fittedLines.add(LineEq(a.toDouble(), b.toDouble(), c.toDouble()))
                }
            }

            mag.release()
            gray.release()
            if (workingBitmap !== image) workingBitmap.recycle()

            if (fittedLines.size == 4) {
                val tr = intersect(fittedLines[0], fittedLines[1])
                val br = intersect(fittedLines[1], fittedLines[2])
                val bl = intersect(fittedLines[2], fittedLines[3])
                val tl = intersect(fittedLines[3], fittedLines[0])

                if (tl != null && tr != null && br != null && bl != null) {
                    val refinedNorm = Quadrilateral(
                        topLeft = PointF((tl.x / procW).toFloat().coerceIn(0f, 1f), (tl.y / procH).toFloat().coerceIn(0f, 1f)),
                        topRight = PointF((tr.x / procW).toFloat().coerceIn(0f, 1f), (tr.y / procH).toFloat().coerceIn(0f, 1f)),
                        bottomRight = PointF((br.x / procW).toFloat().coerceIn(0f, 1f), (br.y / procH).toFloat().coerceIn(0f, 1f)),
                        bottomLeft = PointF((bl.x / procW).toFloat().coerceIn(0f, 1f), (bl.y / procH).toFloat().coerceIn(0f, 1f)),
                    )

                    // 位移限制与几何有效性保护
                    if (QuadrilateralMath.isConvex(refinedNorm) && !QuadrilateralMath.isSelfIntersecting(refinedNorm)) {
                        val maxDisp = maxCornerDisplacement(quad, refinedNorm)
                        if (maxDisp <= MAX_CORNER_DISPLACEMENT_FRAC) {
                            return refinedNorm
                        }
                    }
                }
            }
        } catch (_: Throwable) {
            gray.release()
            if (workingBitmap !== image) workingBitmap.recycle()
        }

        return quad
    }

    private data class LineEq(val a: Double, val b: Double, val c: Double)

    private fun intersect(l1: LineEq, l2: LineEq): Point? {
        val det = l1.a * l2.b - l2.a * l1.b
        if (abs(det) < 1e-7) return null
        val x = (l1.b * l2.c - l2.b * l1.c) / det
        val y = (l2.a * l1.c - l1.a * l2.c) / det
        return Point(x, y)
    }

    private fun maxCornerDisplacement(q1: Quadrilateral, q2: Quadrilateral): Float {
        val p1 = q1.points
        val p2 = q2.points
        var maxD = 0f
        for (i in 0 until 4) {
            val d = hypot(p1[i].x - p2[i].x, p1[i].y - p2[i].y)
            if (d > maxD) maxD = d
        }
        return maxD
    }
}
