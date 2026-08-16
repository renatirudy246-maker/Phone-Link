package com.phonelink.app.crop

/**
 * 归一化裁切区域（0..1 浮点坐标，相对图片）。
 * 作为 Crop 编辑器的单一状态源：与视图尺寸/分辨率无关，任何视图缩放下映射稳定。
 */
data class CropRectF(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top

    companion object {
        /** 完整图片范围。 */
        val FULL = CropRectF(0f, 0f, 1f, 1f)
    }
}

/** 像素级裁切区域（图片像素坐标，已方向归一化）。 */
data class CropRect(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
}

/** 拖动操作对应的锚点：角 / 边 / 整体移动。 */
enum class CropAnchor { TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT, LEFT, TOP, RIGHT, BOTTOM, MOVE }

/**
 * 裁切坐标数学（纯 Kotlin，无 Android 依赖，可 JVM 单测）。
 *
 * 坐标系约定：
 * - 归一化坐标 0..1，相对图片（已 EXIF 方向归一化后的像素空间）。
 * - 视图坐标 = Compose Canvas 像素；图片以 ContentScale.Fit 显示，存在 letterbox。
 * - 像素坐标 = 原图（prepared）像素，裁切输出在此空间执行，保证全分辨率输出。
 */
object CropMath {

    /** 交换反转的边，保证 left<=right 且 top<=bottom。 */
    fun normalize(rect: CropRectF): CropRectF {
        val left = minOf(rect.left, rect.right)
        val right = maxOf(rect.left, rect.right)
        val top = minOf(rect.top, rect.bottom)
        val bottom = maxOf(rect.top, rect.bottom)
        return CropRectF(left, top, right, bottom)
    }

    /**
     * 限制在 [0, maxWidth] x [0, maxHeight] 内：
     * - 完全越界（宽度大于边界）→ 收缩到边界。
     * - 部分越界 → 平移回界内，保持尺寸。
     */
    fun clampToBounds(rect: CropRectF, maxWidth: Float = 1f, maxHeight: Float = 1f): CropRectF {
        var left = rect.left
        var top = rect.top
        var right = rect.right
        var bottom = rect.bottom
        if (right - left > maxWidth) {
            right = maxWidth
            left = 0f
        } else {
            if (left < 0f) {
                right -= left
                left = 0f
            } else if (right > maxWidth) {
                left -= right - maxWidth
                right = maxWidth
            }
        }
        if (bottom - top > maxHeight) {
            bottom = maxHeight
            top = 0f
        } else {
            if (top < 0f) {
                bottom -= top
                top = 0f
            } else if (bottom > maxHeight) {
                top -= bottom - maxHeight
                bottom = maxHeight
            }
        }
        return CropRectF(left, top, right, bottom)
    }

    /**
     * 确保最小尺寸：锚点不动，向锚点反方向扩展。
     * 扩展后仍可能越界，由调用方随后 clampToBounds（clamp 保持尺寸平移）。
     */
    fun enforceMinSize(rect: CropRectF, minWidth: Float, minHeight: Float, anchor: CropAnchor): CropRectF {
        var left = rect.left
        var top = rect.top
        var right = rect.right
        var bottom = rect.bottom
        if (right - left < minWidth) {
            when (anchor) {
                CropAnchor.TOP_LEFT, CropAnchor.BOTTOM_LEFT, CropAnchor.LEFT -> right = left + minWidth
                CropAnchor.TOP_RIGHT, CropAnchor.BOTTOM_RIGHT, CropAnchor.RIGHT -> left = right - minWidth
                else -> {
                    val cx = (left + right) / 2f
                    left = cx - minWidth / 2f
                    right = cx + minWidth / 2f
                }
            }
        }
        if (bottom - top < minHeight) {
            when (anchor) {
                CropAnchor.TOP_LEFT, CropAnchor.TOP_RIGHT, CropAnchor.TOP -> bottom = top + minHeight
                CropAnchor.BOTTOM_LEFT, CropAnchor.BOTTOM_RIGHT, CropAnchor.BOTTOM -> top = bottom - minHeight
                else -> {
                    val cy = (top + bottom) / 2f
                    top = cy - minHeight / 2f
                    bottom = cy + minHeight / 2f
                }
            }
        }
        return CropRectF(left, top, right, bottom)
    }

    /** ContentScale.Fit 的显示缩放系数（显示尺寸 / 图片尺寸）。 */
    fun fitScale(viewWidth: Float, viewHeight: Float, imageWidth: Float, imageHeight: Float): Float {
        require(viewWidth > 0 && viewHeight > 0) { "view size must be positive" }
        require(imageWidth > 0 && imageHeight > 0) { "image size must be positive" }
        return minOf(viewWidth / imageWidth, viewHeight / imageHeight)
    }

    /** ContentScale.Fit 下图片在视图中的显示区域（view 坐标，含 letterbox 偏移）。 */
    fun fitRect(viewWidth: Float, viewHeight: Float, imageWidth: Float, imageHeight: Float): CropRectF {
        val scale = fitScale(viewWidth, viewHeight, imageWidth, imageHeight)
        val displayWidth = imageWidth * scale
        val displayHeight = imageHeight * scale
        val offsetX = (viewWidth - displayWidth) / 2f
        val offsetY = (viewHeight - displayHeight) / 2f
        return CropRectF(offsetX, offsetY, offsetX + displayWidth, offsetY + displayHeight)
    }

    /** View 坐标 → 图片坐标（Fit 显示，自动处理 letterbox）。 */
    fun viewToImage(x: Float, y: Float, viewWidth: Float, viewHeight: Float, imageWidth: Float, imageHeight: Float): Pair<Float, Float> {
        val area = fitRect(viewWidth, viewHeight, imageWidth, imageHeight)
        val scale = fitScale(viewWidth, viewHeight, imageWidth, imageHeight)
        return ((x - area.left) / scale) to ((y - area.top) / scale)
    }

    /** 图片坐标 → View 坐标。 */
    fun imageToView(x: Float, y: Float, viewWidth: Float, viewHeight: Float, imageWidth: Float, imageHeight: Float): Pair<Float, Float> {
        val area = fitRect(viewWidth, viewHeight, imageWidth, imageHeight)
        val scale = fitScale(viewWidth, viewHeight, imageWidth, imageHeight)
        return (x * scale + area.left) to (y * scale + area.top)
    }

    /** 归一化区域 → 图片像素区域（四舍五入 + 边界夹紧，最小 1 像素）。 */
    fun normalizedToPixels(rect: CropRectF, imageWidth: Int, imageHeight: Int): CropRect {
        val clamped = clampToBounds(normalize(rect))
        val left = (clamped.left * imageWidth).toInt().coerceIn(0, imageWidth - 1)
        val top = (clamped.top * imageHeight).toInt().coerceIn(0, imageHeight - 1)
        val right = (clamped.right * imageWidth).toInt().coerceIn(left + 1, imageWidth)
        val bottom = (clamped.bottom * imageHeight).toInt().coerceIn(top + 1, imageHeight)
        return CropRect(left, top, right, bottom)
    }

    /** 图片像素区域 → 归一化区域。 */
    fun pixelsToNormalized(rect: CropRect, imageWidth: Int, imageHeight: Int): CropRectF {
        return CropRectF(
            left = rect.left.toFloat() / imageWidth,
            top = rect.top.toFloat() / imageHeight,
            right = rect.right.toFloat() / imageWidth,
            bottom = rect.bottom.toFloat() / imageHeight,
        )
    }

    /**
     * 拖动语义更新：锚点（角/边/整体）不动，被拖动边随 delta 移动，
     * 每条边夹紧在 [0,1] 且保持最小尺寸（拖动边与锚定边合并在 coerce 中）。
     */
    fun applyDrag(
        rect: CropRectF,
        anchor: CropAnchor,
        deltaX: Float,
        deltaY: Float,
        minWidth: Float = DEFAULT_MIN_FRACTION,
        minHeight: Float = DEFAULT_MIN_FRACTION,
    ): CropRectF {
        var left = rect.left
        var top = rect.top
        var right = rect.right
        var bottom = rect.bottom
        when (anchor) {
            CropAnchor.LEFT -> left = (left + deltaX).coerceIn(0f, right - minWidth)
            CropAnchor.RIGHT -> right = (right + deltaX).coerceIn(left + minWidth, 1f)
            CropAnchor.TOP -> top = (top + deltaY).coerceIn(0f, bottom - minHeight)
            CropAnchor.BOTTOM -> bottom = (bottom + deltaY).coerceIn(top + minHeight, 1f)
            CropAnchor.TOP_LEFT -> {
                left = (left + deltaX).coerceIn(0f, right - minWidth)
                top = (top + deltaY).coerceIn(0f, bottom - minHeight)
            }
            CropAnchor.TOP_RIGHT -> {
                right = (right + deltaX).coerceIn(left + minWidth, 1f)
                top = (top + deltaY).coerceIn(0f, bottom - minHeight)
            }
            CropAnchor.BOTTOM_LEFT -> {
                left = (left + deltaX).coerceIn(0f, right - minWidth)
                bottom = (bottom + deltaY).coerceIn(top + minHeight, 1f)
            }
            CropAnchor.BOTTOM_RIGHT -> {
                right = (right + deltaX).coerceIn(left + minWidth, 1f)
                bottom = (bottom + deltaY).coerceIn(top + minHeight, 1f)
            }
            CropAnchor.MOVE -> {
                val width = right - left
                val height = bottom - top
                left = (left + deltaX).coerceIn(0f, 1f - width)
                top = (top + deltaY).coerceIn(0f, 1f - height)
                right = left + width
                bottom = top + height
            }
        }
        return CropRectF(left, top, right, bottom)
    }

    /** 最小裁切区域默认值（归一化，约视图宽/高的 8%）。 */
    const val DEFAULT_MIN_FRACTION = 0.08f
}