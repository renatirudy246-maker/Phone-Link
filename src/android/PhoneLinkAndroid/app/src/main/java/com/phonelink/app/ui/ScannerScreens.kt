package com.phonelink.app.ui

import android.graphics.Bitmap
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeGestures
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.phonelink.app.BuildConfig
import com.phonelink.app.crop.CropMath
import com.phonelink.app.scanner.DetectionStatus
import com.phonelink.app.scanner.EnhanceMode
import com.phonelink.app.scanner.ImageRectF
import com.phonelink.app.scanner.PointF
import com.phonelink.app.scanner.QuadEditorMath
import com.phonelink.app.scanner.Quadrilateral
import com.phonelink.app.scanner.QuadrilateralMath
import java.io.File
import kotlin.math.sqrt

private val BrandBlue = Color(0xFF4C9AFF)
private val ScanBg = Color(0xFF0F1115)

/**
 * 调整边缘页：Fit 显示原图 + 四角可拖（44–48dp 触摸目标 / 12dp 视觉标记）。
 * 拖动只更新本地 overlay 状态，[下一步] 才触发透视校正。
 * 四角逻辑坐标允许 0/1（贴边），拖动期间保持四边形合法（凸、不自交、面积达标）。
 */
@Composable
fun AdjustingEdgesScreen(
    sourceFile: File,
    initialQuad: Quadrilateral,
    status: DetectionStatus,
    onBack: () -> Unit,
    onUseFullImage: () -> Unit,
    onNext: (Quadrilateral) -> Unit,
) {
    val bitmap = remember(sourceFile) { loadBitmap(sourceFile) }
    var quad by remember(sourceFile) { mutableStateOf(initialQuad) }

    Box(modifier = Modifier.fillMaxSize().background(ScanBg)) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .height(56.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    androidx.compose.material3.Icon(
                        Icons.AutoMirrored.Outlined.ArrowBack,
                        contentDescription = "返回重拍",
                        tint = Color.White,
                    )
                }
                Text("调整边缘", style = MaterialTheme.typography.titleMedium, color = Color.White)
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.safeGestures.only(WindowInsetsSides.Horizontal))
                    .padding(horizontal = 16.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (bitmap != null) {
                    QuadEditor(
                        bitmap = bitmap,
                        quad = quad,
                        onQuadChange = { quad = it },
                    )
                } else {
                    Text("图片加载失败", color = Color(0xFF9AA0A6))
                }

                StatusChip(
                    text = when (status) {
                        DetectionStatus.DETECTED -> "已检测到页面，可微调四角"
                        DetectionStatus.LOW_CONFIDENCE -> "请检查页面边缘"
                        DetectionStatus.NOT_FOUND -> "未可靠识别页面，请手动调整"
                    },
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = 12.dp),
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 24.dp, vertical = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    OutlinedButton(
                        onClick = onUseFullImage,
                        modifier = Modifier.weight(1f).height(52.dp),
                        shape = MaterialTheme.shapes.medium,
                    ) { Text("使用整张图片", color = Color.White) }
                    Button(
                        onClick = { onNext(quad) },
                        modifier = Modifier.weight(1f).height(52.dp),
                        shape = MaterialTheme.shapes.medium,
                    ) { Text("下一步", fontWeight = FontWeight.SemiBold) }
                }
            }
        }
    }
}

/** 四角编辑器：图片 Fit 显示 + 蓝色边框 + 可拖四角 + 拖拽放大镜。 */
@Composable
private fun QuadEditor(
    bitmap: Bitmap,
    quad: Quadrilateral,
    onQuadChange: (Quadrilateral) -> Unit,
) {
    val density = LocalDensity.current
    val touchRadiusPx = with(density) { 24.dp.toPx() }
    val magnifierRadiusPx = with(density) { 52.dp.toPx() }
    var viewSize by remember { mutableStateOf(IntSize.Zero) }
    var draggingIndex by remember { mutableStateOf(-1) }
    var lastPointer by remember { mutableStateOf<Offset?>(null) }
    val currentQuad by rememberUpdatedState(quad)

    // 图片实际显示区域（View px，含 letterbox）——pointer 与 imageRect 同一坐标空间
    val imageRect = remember(viewSize, bitmap.width, bitmap.height) {
        if (viewSize.width > 0 && bitmap.width > 0) {
            val fit = CropMath.fitRect(
                viewSize.width.toFloat(), viewSize.height.toFloat(),
                bitmap.width.toFloat(), bitmap.height.toFloat(),
            )
            ImageRectF(fit.left, fit.top, fit.right, fit.bottom)
        } else null
    }

    val scale = if (viewSize.width > 0 && bitmap.width > 0) {
        CropMath.fitScale(viewSize.width.toFloat(), viewSize.height.toFloat(), bitmap.width.toFloat(), bitmap.height.toFloat())
    } else 1f

    fun toView(p: PointF): Offset {
        val (x, y) = CropMath.imageToView(
            p.x * bitmap.width, p.y * bitmap.height,
            viewSize.width.toFloat(), viewSize.height.toFloat(), bitmap.width.toFloat(), bitmap.height.toFloat(),
        )
        return Offset(x, y)
    }

    fun toImage(offset: Offset): PointF {
        val (x, y) = CropMath.viewToImage(
            offset.x, offset.y,
            viewSize.width.toFloat(), viewSize.height.toFloat(), bitmap.width.toFloat(), bitmap.height.toFloat(),
        )
        return PointF(x / bitmap.width, y / bitmap.height)
    }

    fun nearestHandle(offset: Offset): Int {
        var best = -1
        var bestDist = touchRadiusPx
        currentQuad.points.forEachIndexed { index, p ->
            val v = toView(p)
            val dist = sqrt((v.x - offset.x) * (v.x - offset.x) + (v.y - offset.y) * (v.y - offset.y))
            if (dist < bestDist) {
                bestDist = dist
                best = index
            }
        }
        return best
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .systemGestureExclusion()
            .onSizeChanged { viewSize = it }
            .pointerInput(viewSize) {
                detectDragGestures(
                    // Hit test 只在 dragStart 做一次；一旦捕获该角，直到 pointer up 都控制同一个角
                    onDragStart = { offset ->
                        draggingIndex = nearestHandle(offset)
                        lastPointer = offset
                    },
                    onDrag = { change, _ ->
                        change.consume()
                        val index = draggingIndex
                        val rect = imageRect
                        if (index < 0 || rect == null) return@detectDragGestures
                        lastPointer = change.position
                        // 绝对指针位置 → 归一化（非累计增量），天然像素级精确
                        val normalized = QuadEditorMath.pointerToNormalized(
                            PointF(change.position.x, change.position.y),
                            rect,
                        )
                        val updated = QuadEditorMath.applyCornerDrag(currentQuad, index, normalized)
                        // 非法步进直接拒绝，保持上一合法位置（手感连续）
                        if (updated != null) onQuadChange(updated)
                    },
                    onDragEnd = {
                        draggingIndex = -1
                        lastPointer = null
                    },
                    onDragCancel = {
                        draggingIndex = -1
                        lastPointer = null
                    },
                )
            },
    ) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = "待扫描图片",
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Fit,
        )

        Canvas(modifier = Modifier.fillMaxSize()) {
            if (viewSize.width <= 0) return@Canvas
            val view = currentQuad.points.map { toView(it) }
            val strokeWidth = with(density) { 2.dp.toPx() }

            val path = androidx.compose.ui.graphics.Path().apply {
                moveTo(view[0].x, view[0].y)
                lineTo(view[1].x, view[1].y)
                lineTo(view[2].x, view[2].y)
                lineTo(view[3].x, view[3].y)
                close()
            }
            drawPath(path, Color(0x144C9AFF))
            drawPath(path, BrandBlue, style = androidx.compose.ui.graphics.drawscope.Stroke(width = strokeWidth))

            val handleR = with(density) { 6.dp.toPx() }
            val handleRing = with(density) { 2.dp.toPx() }
            view.forEachIndexed { index, v ->
                drawCircle(BrandBlue, handleR, Offset(v.x, v.y))
                drawCircle(
                    Color.White, handleR + handleRing, Offset(v.x, v.y),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = with(density) { 1.5.dp.toPx() }),
                )
            }

            // 拖拽放大镜：以当前角为中心放大显示（圈 + 十字线 + 中心点）
            val dragging = draggingIndex
            if (dragging >= 0 && dragging < view.size) {
                val handle = view[dragging]
                var cx = handle.x + magnifierRadiusPx * 1.6f
                var cy = handle.y - magnifierRadiusPx * 1.6f
                cx = cx.coerceIn(magnifierRadiusPx, size.width - magnifierRadiusPx)
                cy = cy.coerceIn(magnifierRadiusPx, size.height - magnifierRadiusPx)
                val center = Offset(cx, cy)

                drawCircle(Color(0xFF1B1E25), magnifierRadiusPx, center)
                drawCircle(BrandBlue, magnifierRadiusPx, center, style = androidx.compose.ui.graphics.drawscope.Stroke(width = with(density) { 2.dp.toPx() }))

                val handleImage = toImage(handle)
                val px = handleImage.x * bitmap.width
                val py = handleImage.y * bitmap.height
                val half = (magnifierRadiusPx * 1.4f / scale * 0.4f).coerceIn(12f, 60f)
                val srcLeft = (px - half).toInt().coerceIn(0, bitmap.width)
                val srcTop = (py - half).toInt().coerceIn(0, bitmap.height)
                val srcRight = (px + half).toInt().coerceIn(0, bitmap.width)
                val srcBottom = (py + half).toInt().coerceIn(0, bitmap.height)
                if (srcRight > srcLeft && srcBottom > srcTop) {
                    val dstSize = (magnifierRadiusPx * 1.6f).toInt()
                    val dstLeft = (cx - dstSize / 2f).toInt()
                    val dstTop = (cy - dstSize / 2f).toInt()
                    val magPath = androidx.compose.ui.graphics.Path().apply {
                        addOval(
                            androidx.compose.ui.geometry.Rect(
                                center.x - magnifierRadiusPx * 0.8f, center.y - magnifierRadiusPx * 0.8f,
                                center.x + magnifierRadiusPx * 0.8f, center.y + magnifierRadiusPx * 0.8f,
                            ),
                        )
                    }
                    clipPath(magPath) {
                        drawImage(
                            image = bitmap.asImageBitmap(),
                            srcOffset = androidx.compose.ui.unit.IntOffset(srcLeft, srcTop),
                            srcSize = androidx.compose.ui.unit.IntSize(srcRight - srcLeft, srcBottom - srcTop),
                            dstOffset = androidx.compose.ui.unit.IntOffset(dstLeft, dstTop),
                            dstSize = androidx.compose.ui.unit.IntSize(dstSize, dstSize),
                        )
                    }
                }
                val cross = with(density) { 1.dp.toPx() }
                drawLine(
                    Color.White,
                    Offset(cx - magnifierRadiusPx * 0.7f, cy), Offset(cx + magnifierRadiusPx * 0.7f, cy),
                    cross,
                )
                drawLine(
                    Color.White,
                    Offset(cx, cy - magnifierRadiusPx * 0.7f), Offset(cx, cy + magnifierRadiusPx * 0.7f),
                    cross,
                )
                drawCircle(BrandBlue, with(density) { 2.dp.toPx() }, center)
            }
        }

        // 拖拽数值诊断（DEBUG 构建，拖动时显示）
        if (BuildConfig.DEBUG && draggingIndex >= 0) {
            val pointer = lastPointer
            val rect = imageRect
            if (pointer != null && rect != null) {
                val normalized = QuadEditorMath.pointerToNormalized(
                    PointF(pointer.x, pointer.y), rect,
                )
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(8.dp)
                        .background(Color(0xCC000000), RoundedCornerShape(6.dp))
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                ) {
                    Text(
                        "Pointer: x=${"%.1f".format(pointer.x)} y=${"%.1f".format(pointer.y)}\n" +
                            "ImageRect: L=${"%.0f".format(rect.left)} T=${"%.0f".format(rect.top)} " +
                            "W=${"%.0f".format(rect.width)} H=${"%.0f".format(rect.height)}\n" +
                            "Normalized: x=${"%.4f".format(normalized.x)} y=${"%.4f".format(normalized.y)}\n" +
                            "ActiveCorner: ${QuadEditorMath.cornerName(draggingIndex)}",
                        color = Color(0xFF4CFF7A),
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    )
                }
            }
        }
    }
}

/** 顶部轻量状态提示（半透明圆角 chip）。 */
@Composable
private fun StatusChip(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = Color.White,
        modifier = modifier
            .background(Color(0xB31B1E25), RoundedCornerShape(20.dp))
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

/**
 * 扫描结果页：透视校正后的预览 + 增强切换（原图/自动/灰度/黑白，非破坏性）
 * + 调整边缘 / 裁切 / 发送（Primary）。
 */
@Composable
fun ScanPreviewScreen(
    previewFile: File,
    cropped: Boolean,
    enhanceMode: EnhanceMode,
    onBack: () -> Unit,
    onAdjustEdges: () -> Unit,
    onEnhance: (EnhanceMode) -> Unit,
    onCrop: () -> Unit,
    onRestoreOriginal: () -> Unit,
    onSend: () -> Unit,
) {
    val bitmap = remember(previewFile) { loadBitmap(previewFile) }

    Box(modifier = Modifier.fillMaxSize().background(ScanBg)) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .height(56.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    androidx.compose.material3.Icon(
                        Icons.AutoMirrored.Outlined.ArrowBack,
                        contentDescription = "返回重拍",
                        tint = Color.White,
                    )
                }
                Text("扫描结果", style = MaterialTheme.typography.titleMedium, color = Color.White)
                if (cropped) {
                    Spacer(Modifier.size(8.dp))
                    Text(
                        "已裁切",
                        style = MaterialTheme.typography.labelMedium,
                        color = Color(0xFF22C55E),
                        modifier = Modifier
                            .background(Color(0x1F22C55E), RoundedCornerShape(6.dp))
                            .padding(horizontal = 8.dp, vertical = 3.dp),
                    )
                }
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = "扫描结果",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit,
                    )
                } else {
                    Text("预览加载失败", color = Color(0xFF9AA0A6))
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (!cropped) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        EnhanceMode.entries.forEach { mode ->
                            val selected = mode == enhanceMode
                            OutlinedButton(
                                onClick = { onEnhance(mode) },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(38.dp),
                                shape = RoundedCornerShape(19.dp),
                                colors = ButtonDefaults.outlinedButtonColors(
                                    containerColor = if (selected) BrandBlue.copy(alpha = 0.16f) else Color(0xFF1E222B),
                                ),
                                border = BorderStroke(
                                    1.dp,
                                    if (selected) BrandBlue else Color(0xFF2E333D),
                                ),
                                contentPadding = PaddingValues(horizontal = 4.dp),
                            ) {
                                Text(
                                    text = mode.label,
                                    color = if (selected) BrandBlue else Color(0xFFB9BDC4),
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                    maxLines = 1,
                                    softWrap = false,
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                } else {
                    Spacer(Modifier.height(4.dp))
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        formatFileSize(previewFile.length()),
                        style = MaterialTheme.typography.labelMedium,
                        color = Color(0xFF9AA0A6),
                    )
                    if (cropped || enhanceMode != EnhanceMode.ORIGINAL) {
                        Spacer(Modifier.size(12.dp))
                        TextButton(onClick = onRestoreOriginal) {
                            Text("使用原图", color = Color(0xFF9AA0A6))
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedButton(
                        onClick = onAdjustEdges,
                        modifier = Modifier
                            .weight(1f)
                            .height(50.dp),
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, Color(0xFF3B414D)),
                        contentPadding = PaddingValues(horizontal = 6.dp),
                    ) {
                        Text(
                            "调整边缘",
                            color = Color.White,
                            style = MaterialTheme.typography.labelLarge,
                            maxLines = 1,
                            softWrap = false,
                        )
                    }
                    OutlinedButton(
                        onClick = onCrop,
                        modifier = Modifier
                            .weight(0.9f)
                            .height(50.dp),
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, Color(0xFF3B414D)),
                        contentPadding = PaddingValues(horizontal = 6.dp),
                    ) {
                        Text(
                            if (cropped) "重新裁切" else "裁切",
                            color = Color.White,
                            style = MaterialTheme.typography.labelLarge,
                            maxLines = 1,
                            softWrap = false,
                        )
                    }
                    Button(
                        onClick = onSend,
                        modifier = Modifier
                            .weight(1.3f)
                            .height(50.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = BrandBlue),
                        contentPadding = PaddingValues(horizontal = 12.dp),
                    ) {
                        Text(
                            "发送",
                            fontWeight = FontWeight.SemiBold,
                            style = MaterialTheme.typography.labelLarge,
                            maxLines = 1,
                            softWrap = false,
                        )
                    }
                }
            }
        }
    }
}

private val EnhanceMode.label: String
    get() = when (this) {
        EnhanceMode.ORIGINAL -> "原图"
        EnhanceMode.AUTO -> "自动"
        EnhanceMode.GRAY -> "灰度"
        EnhanceMode.BLACK_WHITE -> "黑白"
    }

private fun loadBitmap(file: File): Bitmap? {
    return try {
        val options = android.graphics.BitmapFactory.Options().apply {
            inSampleSize = computePreviewSample(file)
        }
        android.graphics.BitmapFactory.decodeFile(file.absolutePath, options)
    } catch (_: Exception) {
        null
    }
}

private fun computePreviewSample(file: File): Int {
    val options = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
    android.graphics.BitmapFactory.decodeFile(file.absolutePath, options)
    var sample = 1
    val longest = maxOf(options.outWidth, options.outHeight)
    while (longest / (sample * 2) >= 2000) {
        sample *= 2
    }
    return sample
}

private fun formatFileSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    if (bytes < 1024 * 1024) return "${"%.1f".format(bytes / 1024.0)} KB"
    return "${"%.1f".format(bytes / 1024.0 / 1024.0)} MB"
}