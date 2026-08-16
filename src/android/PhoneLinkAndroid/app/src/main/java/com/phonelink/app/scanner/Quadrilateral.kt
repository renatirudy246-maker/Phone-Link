package com.phonelink.app.scanner

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** 归一化点（0..1，相对方向归一化后的图片）。 */
data class PointF(val x: Float, val y: Float)

/**
 * 文档四边形（顺序固定：左上 → 右上 → 右下 → 左下，顺时针）。
 * 坐标为归一化 0..1，相对用户看到的 EXIF 归一化方向。
 */
data class Quadrilateral(
    val topLeft: PointF,
    val topRight: PointF,
    val bottomRight: PointF,
    val bottomLeft: PointF,
) {
    val points: List<PointF> get() = listOf(topLeft, topRight, bottomRight, bottomLeft)

    companion object {
        /** 检测失败时的默认四角（约 5% 内缩）。 */
        val DEFAULT = Quadrilateral(
            topLeft = PointF(0.05f, 0.05f),
            topRight = PointF(0.95f, 0.05f),
            bottomRight = PointF(0.95f, 0.95f),
            bottomLeft = PointF(0.05f, 0.95f),
        )
    }
}

/**
 * 四边形几何（纯 Kotlin，无 Android/OpenCV 依赖，可 JVM 单测）。
 */
object QuadrilateralMath {

    /** 鞋带公式面积（归一化坐标下为相对图片面积）。 */
    fun area(quad: Quadrilateral): Float {
        val p = quad.points
        var sum = 0f
        for (i in 0 until 4) {
            val a = p[i]
            val b = p[(i + 1) % 4]
            sum += a.x * b.y - b.x * a.y
        }
        return abs(sum) / 2f
    }

    /**
     * 把任意顺序的 4 点排序为 TL/TR/BR/BL（OpenCV 经典 sum/diff 法）：
     * 左上 = sum(x+y) 最小，右下 = sum 最大；右上 = diff(x-y) 最小，左下 = diff 最大。
     */
    fun orderPoints(points: List<PointF>): Quadrilateral {
        require(points.size == 4) { "need exactly 4 points" }
        val sum = points.map { it.x + it.y }
        val diff = points.map { it.y - it.x }
        val topLeft = points[sum.indices.minByOrNull { sum[it] }!!]
        val bottomRight = points[sum.indices.maxByOrNull { sum[it] }!!]
        val topRight = points[diff.indices.minByOrNull { diff[it] }!!]
        val bottomLeft = points[diff.indices.maxByOrNull { diff[it] }!!]
        return Quadrilateral(topLeft, topRight, bottomRight, bottomLeft)
    }

    /** 凸性校验：所有相邻边叉积同号。 */
    fun isConvex(quad: Quadrilateral): Boolean {
        val p = quad.points
        var sign = 0
        for (i in 0 until 4) {
            val a = p[i]
            val b = p[(i + 1) % 4]
            val c = p[(i + 2) % 4]
            val cross = (b.x - a.x) * (c.y - b.y) - (b.y - a.y) * (c.x - b.x)
            if (abs(cross) < 1e-6f) continue
            val s = if (cross > 0) 1 else -1
            if (sign == 0) sign = s else if (sign != s) return false
        }
        return true
    }

    /** 自交叉检测：任一对非相邻边相交即自交叉。 */
    fun isSelfIntersecting(quad: Quadrilateral): Boolean {
        val p = quad.points
        for (i in 0 until 4) {
            val a1 = p[i]
            val a2 = p[(i + 1) % 4]
            val b1 = p[(i + 2) % 4]
            val b2 = p[(i + 3) % 4]
            if (segmentsIntersect(a1, a2, b1, b2)) return true
        }
        return false
    }

    private fun segmentsIntersect(p1: PointF, p2: PointF, p3: PointF, p4: PointF): Boolean {
        fun orient(a: PointF, b: PointF, c: PointF): Float =
            (b.x - a.x) * (c.y - a.y) - (b.y - a.y) * (c.x - a.x)
        val d1 = orient(p3, p4, p1)
        val d2 = orient(p3, p4, p2)
        val d3 = orient(p1, p2, p3)
        val d4 = orient(p1, p2, p4)
        if (((d1 > 0 && d2 < 0) || (d1 < 0 && d2 > 0)) &&
            ((d3 > 0 && d4 < 0) || (d3 < 0 && d4 > 0))
        ) {
            return true
        }
        return false
    }

    /** 面积是否 ≥ 最小占比（相对图片面积）。 */
    fun hasMinimumArea(quad: Quadrilateral, minAreaFraction: Float): Boolean =
        area(quad) >= minAreaFraction

    /** 全部点 clamp 到 [0,1]。 */
    fun clampToBounds(quad: Quadrilateral): Quadrilateral {
        fun c(p: PointF) = PointF(p.x.coerceIn(0f, 1f), p.y.coerceIn(0f, 1f))
        return Quadrilateral(c(quad.topLeft), c(quad.topRight), c(quad.bottomRight), c(quad.bottomLeft))
    }

    /** 综合合法性：顺序固定、凸、不自交、面积达标、点不重合。 */
    fun isValid(quad: Quadrilateral, minAreaFraction: Float = 0.02f): Boolean {
        if (!isConvex(quad) || isSelfIntersecting(quad)) return false
        if (!hasMinimumArea(quad, minAreaFraction)) return false
        val p = quad.points
        for (i in 0 until 4) {
            for (j in i + 1 until 4) {
                if (abs(p[i].x - p[j].x) < 1e-4f && abs(p[i].y - p[j].y) < 1e-4f) return false
            }
        }
        return true
    }

    /** 中心点。 */
    fun center(quad: Quadrilateral): PointF {
        val p = quad.points
        return PointF(p.sumOf { it.x.toDouble() }.toFloat() / 4f, p.sumOf { it.y.toDouble() }.toFloat() / 4f)
    }

    /** 四边长度（归一化单位）。 */
    fun sideLengths(quad: Quadrilateral): List<Float> {
        val p = quad.points
        return (0 until 4).map { i ->
            val a = p[i]
            val b = p[(i + 1) % 4]
            kotlin.math.sqrt((b.x - a.x) * (b.x - a.x) + (b.y - a.y) * (b.y - a.y))
        }
    }

    /** 宽高比合理性（短边/长边，1 为正方形，接近 0 为细长条）。 */
    fun aspectRatio(quad: Quadrilateral): Float {
        val sides = sideLengths(quad)
        val s0 = min(sides[0], sides[1])
        val s1 = min(sides[2], sides[3])
        val s2 = max(sides[0], sides[1])
        val s3 = max(sides[2], sides[3])
        val minSide = min(s0, s1)
        val maxSide = max(s2, s3)
        return if (maxSide <= 0f) 0f else minSide / maxSide
    }
}