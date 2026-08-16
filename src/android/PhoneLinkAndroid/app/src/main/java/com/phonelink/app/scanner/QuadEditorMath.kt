package com.phonelink.app.scanner

/**
 * 图片在编辑器中的显示区域（View px，含 letterbox；ContentScale.Fit 语义）。
 * 与 pointer 位置同一坐标空间（px）。
 */
data class ImageRectF(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
}

/**
 * 四角编辑器的坐标/拖动数学（纯 Kotlin，无 Android 依赖，可 JVM 单测）。
 *
 * 原则：
 * - pointer 位置与 imageRect 均为 View px，禁止混用 dp / 归一化做增量。
 * - 拖动用"绝对指针位置 → 归一化"，绝不用累计 dragAmount 做归一化增量。
 * - 拖动期间保持 corner semantic identity，不做重排序。
 * - 非法四边形（自交/非凸/面积不足）→ 返回 null，由调用方保持上一合法位置。
 */
object QuadEditorMath {

    /**
     * 绝对 View px 指针位置 → 归一化 0..1（相对实际图片显示区域）。
     * 越界 clamp 到 [0,1]。
     */
    fun pointerToNormalized(pointer: PointF, imageRect: ImageRectF): PointF {
        require(imageRect.width > 0f && imageRect.height > 0f) { "imageRect must be positive" }
        val nx = (pointer.x - imageRect.left) / imageRect.width
        val ny = (pointer.y - imageRect.top) / imageRect.height
        return PointF(nx.coerceIn(0f, 1f), ny.coerceIn(0f, 1f))
    }

    /**
     * 把单个角移动到指定归一化位置（semantic identity 保持：index 0/1/2/3 分别
     * 对应 TL/TR/BR/BL，不重排序、不换名）。
     *
     * @return 更新后的四边形；若非法（自交/非凸/面积不足）返回 null（拒绝这一小步）。
     */
    fun applyCornerDrag(
        quad: Quadrilateral,
        cornerIndex: Int,
        normalized: PointF,
        minAreaFraction: Float = 0.02f,
    ): Quadrilateral? {
        require(cornerIndex in 0..3) { "cornerIndex must be 0..3" }
        val points = quad.points.toMutableList()
        points[cornerIndex] = normalized
        val candidate = Quadrilateral(points[0], points[1], points[2], points[3])
        return if (QuadrilateralMath.isValid(candidate, minAreaFraction)) candidate else null
    }

    /** corner index → 语义名（诊断显示用）。 */
    fun cornerName(index: Int): String = when (index) {
        0 -> "TL"
        1 -> "TR"
        2 -> "BR"
        3 -> "BL"
        else -> "?"
    }
}