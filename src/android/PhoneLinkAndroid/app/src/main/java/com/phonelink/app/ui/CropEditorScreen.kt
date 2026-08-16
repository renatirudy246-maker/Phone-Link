package com.phonelink.app.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.phonelink.app.crop.CropAnchor
import com.phonelink.app.crop.CropMath
import com.phonelink.app.crop.CropRectF
import java.io.File
import kotlin.math.abs

/**
 * 手动裁切编辑器（深色，图片最大化 + 半透明遮罩）。
 * 拖动只更新 Overlay 几何（归一化 CropRectF），不重解码图片；确认时一次性生成裁切图。
 */
@Composable
fun CropEditorScreen(
    sourceFile: File,
    onCancel: () -> Unit,
    onConfirm: (CropRectF) -> Unit,
) {
    val bitmap = remember(sourceFile) { decodeDisplayBitmap(sourceFile) }
    var rect by remember { mutableStateOf(CropRectF.FULL) }
    var anchor by remember { mutableStateOf(CropAnchor.MOVE) }
    var viewSize by remember { mutableStateOf(IntSize.Zero) }
    val handleHitPx = with(LocalDensity.current) { HandleHitRadius.toPx() }

    if (bitmap == null) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF0F1115)),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("图片加载失败", color = Color(0xFF9AA0A6))
                TextButton(onClick = onCancel) { Text("返回") }
            }
        }
        return
    }

    val imageWidth = bitmap.width.toFloat()
    val imageHeight = bitmap.height.toFloat()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0F1115)),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .height(56.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onCancel) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "取消裁切", tint = Color.White)
                }
                Text("裁切", style = MaterialTheme.typography.titleMedium, color = Color.White)
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .onSizeChanged { viewSize = it },
            ) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "待裁切图片",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit,
                )
                if (viewSize.width > 0 && viewSize.height > 0) {
                    Canvas(
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(viewSize, imageWidth, imageHeight) {
                                detectDragGestures(
                                    onDragStart = { offset ->
                                        anchor = hitTest(offset, rect, viewSize, imageWidth, imageHeight, handleHitPx)
                                    },
                                    onDrag = { change, amount ->
                                        val nv = viewSize
                                        if (nv.width <= 0 || nv.height <= 0) return@detectDragGestures
                                        rect = CropMath.applyDrag(
                                            rect,
                                            anchor,
                                            amount.x / nv.width,
                                            amount.y / nv.height,
                                        )
                                    },
                                )
                            },
                    ) {
                        drawCropOverlay(rect, viewSize, imageWidth, imageHeight)
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = { rect = CropRectF.FULL }) {
                    Text("重置", color = Color(0xFFE8EAED))
                }
                Spacer(Modifier.weight(1f))
                OutlinedButton(
                    onClick = onCancel,
                    modifier = Modifier.height(48.dp),
                ) { Text("取消", color = Color(0xFFE8EAED)) }
                Button(
                    onClick = { onConfirm(rect) },
                    modifier = Modifier.height(48.dp).padding(start = 4.dp),
                ) { Text("完成", fontWeight = FontWeight.SemiBold) }
            }
        }
    }
}

/** 当前拖动锚点由 remember 状态持有（拖动会话内有效）。 */
private val HandleHitRadius = 40.dp

private const val MaskColor = 0x99000000
private const val BorderColor = 0xFFFFFFFF
private const val HandleColor = 0xFFFFFFFF

/** 命中检测：角（40dp 圆）→ 边（20dp 带）→ 内部移动。view 坐标输入。 */
private fun hitTest(
    offset: Offset,
    rect: CropRectF,
    viewSize: IntSize,
    imageWidth: Float,
    imageHeight: Float,
    hitPx: Float,
): CropAnchor {
    fun cornerView(x: Float, y: Float): Offset {
        val (vx, vy) = CropMath.imageToView(
            x * imageWidth, y * imageHeight,
            viewSize.width.toFloat(), viewSize.height.toFloat(), imageWidth, imageHeight,
        )
        return Offset(vx, vy)
    }

    val corners = listOf(
        CropAnchor.TOP_LEFT to cornerView(rect.left, rect.top),
        CropAnchor.TOP_RIGHT to cornerView(rect.right, rect.top),
        CropAnchor.BOTTOM_RIGHT to cornerView(rect.right, rect.bottom),
        CropAnchor.BOTTOM_LEFT to cornerView(rect.left, rect.bottom),
    )
    for ((anchor, pos) in corners) {
        if (abs(offset.x - pos.x) <= hitPx && abs(offset.y - pos.y) <= hitPx) {
            return anchor
        }
    }

    val edgeHit = hitPx / 2f
    val tl = cornerView(rect.left, rect.top)
    val br = cornerView(rect.right, rect.bottom)
    if (offset.x >= tl.x - edgeHit && offset.x <= br.x + edgeHit &&
        abs(offset.y - tl.y) <= edgeHit && offset.y <= br.y + edgeHit && offset.y >= tl.y - edgeHit
    ) {
        return CropAnchor.TOP
    }
    if (offset.x >= tl.x - edgeHit && offset.x <= br.x + edgeHit &&
        abs(offset.y - br.y) <= edgeHit && offset.y >= tl.y - edgeHit && offset.y <= br.y + edgeHit
    ) {
        return CropAnchor.BOTTOM
    }
    if (abs(offset.x - tl.x) <= edgeHit && offset.y >= tl.y - edgeHit && offset.y <= br.y + edgeHit) {
        return CropAnchor.LEFT
    }
    if (abs(offset.x - br.x) <= edgeHit && offset.y >= tl.y - edgeHit && offset.y <= br.y + edgeHit) {
        return CropAnchor.RIGHT
    }

    if (offset.x in tl.x..br.x && offset.y in tl.y..br.y) {
        return CropAnchor.MOVE
    }
    return CropAnchor.MOVE
}

/** 遮罩（框外半透明黑）+ 白色边框 + 角/边手柄。全部由 rect 归一化几何绘制。 */
private fun DrawScope.drawCropOverlay(rect: CropRectF, viewSize: IntSize, imageWidth: Float, imageHeight: Float) {
    val (tlX, tlY) = CropMath.imageToView(
        rect.left * imageWidth, rect.top * imageHeight,
        viewSize.width.toFloat(), viewSize.height.toFloat(), imageWidth, imageHeight,
    )
    val (brX, brY) = CropMath.imageToView(
        rect.right * imageWidth, rect.bottom * imageHeight,
        viewSize.width.toFloat(), viewSize.height.toFloat(), imageWidth, imageHeight,
    )
    val crop = Rect(tlX, tlY, brX, brY)
    val full = Rect(0f, 0f, size.width, size.height)

    // 四块遮罩
    drawRect(Color(MaskColor), topLeft = Offset(full.left, full.top), size = Size(full.width, crop.top - full.top))
    drawRect(Color(MaskColor), topLeft = Offset(full.left, crop.bottom), size = Size(full.width, full.bottom - crop.bottom))
    drawRect(Color(MaskColor), topLeft = Offset(full.left, crop.top), size = Size(crop.left - full.left, crop.height))
    drawRect(Color(MaskColor), topLeft = Offset(crop.right, crop.top), size = Size(full.right - crop.right, crop.height))

    // 边框
    drawRect(color = Color(BorderColor), topLeft = crop.topLeft, size = crop.size, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 5f))

    // 手柄（视觉 12dp，命中区域由 hitTest 的 40dp 提供）
    val handle = 12.dp.toPx()
    for (pos in listOf(crop.topLeft, Offset(crop.right, crop.top), crop.bottomRight, Offset(crop.left, crop.bottom))) {
        drawRect(
            color = Color(HandleColor),
            topLeft = Offset(pos.x - handle / 2f, pos.y - handle / 2f),
            size = Size(handle, handle),
        )
    }
    // 四边中点手柄
    val midHandle = 8.dp.toPx()
    val midPoints = listOf(
        Offset((crop.left + crop.right) / 2f, crop.top),
        Offset((crop.left + crop.right) / 2f, crop.bottom),
        Offset(crop.left, (crop.top + crop.bottom) / 2f),
        Offset(crop.right, (crop.top + crop.bottom) / 2f),
    )
    for (pos in midPoints) {
        drawRect(
            color = Color(HandleColor),
            topLeft = Offset(pos.x - midHandle / 2f, pos.y - midHandle / 2f),
            size = Size(midHandle, midHandle),
        )
    }
}

/** 解码显示用位图（最长边 ≤2048，编辑交互足够；最终裁切在原始分辨率执行）。 */
private fun decodeDisplayBitmap(file: File): Bitmap? {
    return try {
        val options = android.graphics.BitmapFactory.Options().apply { inSampleSize = computeCropSample(file) }
        android.graphics.BitmapFactory.decodeFile(file.absolutePath, options)
    } catch (_: Exception) {
        null
    }
}

private fun computeCropSample(file: File): Int {
    val options = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
    android.graphics.BitmapFactory.decodeFile(file.absolutePath, options)
    var sample = 1
    val longest = maxOf(options.outWidth, options.outHeight)
    while (longest / (sample * 2) >= 2048) {
        sample *= 2
    }
    return sample
}