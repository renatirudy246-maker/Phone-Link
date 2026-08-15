package com.phonelink.app.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.phonelink.app.transfer.SendUiState
import java.io.File
import kotlinx.coroutines.delay

/** 拍照/Gallery 后的确认页：图片占主要面积，底部固定操作区。 */
@Composable
fun CapturePreviewScreen(
    previewFile: File,
    onRetake: () -> Unit,
    onSend: () -> Unit,
) {
    val bitmap = remember(previewFile) { loadBitmap(previewFile) }

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
                IconButton(onClick = onRetake) {
                    androidx.compose.material3.Icon(
                        Icons.AutoMirrored.Outlined.ArrowBack,
                        contentDescription = "返回重拍",
                        tint = Color.White,
                    )
                }
                Text("预览", style = MaterialTheme.typography.titleMedium, color = Color.White)
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
                        contentDescription = "已拍摄图片",
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
                    .padding(horizontal = 24.dp, vertical = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    formatFileSize(previewFile.length()),
                    style = MaterialTheme.typography.labelMedium,
                    color = Color(0xFF9AA0A6),
                )
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    OutlinedButton(
                        onClick = onRetake,
                        modifier = Modifier.weight(1f).height(52.dp),
                        shape = MaterialTheme.shapes.medium,
                    ) { Text("重拍", color = Color.White) }
                    Button(
                        onClick = onSend,
                        modifier = Modifier.weight(1.4f).height(52.dp),
                        shape = MaterialTheme.shapes.medium,
                    ) { Text("发送", fontWeight = FontWeight.SemiBold) }
                }
            }
        }
    }
}

/** 上传进度（真实 bytes 进度，2% 节流）。 */
@Composable
fun UploadingScreen(percent: Int) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0F1115))
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (percent <= 0) {
            CircularProgressIndicator(color = Color.White)
            Spacer(Modifier.height(16.dp))
            Text("准备中…", style = MaterialTheme.typography.bodyLarge, color = Color(0xFFB9BDC4))
        } else {
            LinearProgressIndicator(
                progress = { percent / 100f },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            Text("上传中 $percent%", style = MaterialTheme.typography.bodyLarge, color = Color(0xFFB9BDC4))
        }
    }
}

/**
 * 发送成功：约 1 秒后由上层自动返回相机（连续拍题无中断），
 * 页面提供"返回首页"与 Back 两种回 Home 的路径。
 */
@Composable
fun SendCompletedScreen(desktopName: String, onHome: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0F1115))
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .background(Color(0x1F22C55E), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text("✓", color = Color(0xFF22C55E), fontSize = MaterialTheme.typography.displayMedium.fontSize)
        }
        Spacer(Modifier.height(16.dp))
        Text(
            "已发送到 $desktopName",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = Color.White,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            "即将自动返回相机",
            style = MaterialTheme.typography.bodyMedium,
            color = Color(0xFF9AA0A6),
        )
        Spacer(Modifier.height(24.dp))
        OutlinedButton(
            onClick = onHome,
            modifier = Modifier.fillMaxWidth().height(48.dp),
        ) { Text("返回首页", color = Color(0xFFE8EAED)) }
    }
}

/** 发送失败：明确文案 + Retry（复用同一 TransferId）+ 重拍。 */
@Composable
fun SendFailedScreen(
    message: String,
    canRetry: Boolean,
    onRetry: () -> Unit,
    onDiscard: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0F1115))
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("发送失败", style = MaterialTheme.typography.titleMedium, color = Color(0xFFF87171))
        Spacer(Modifier.height(12.dp))
        Text(
            message,
            style = MaterialTheme.typography.bodyLarge,
            color = Color(0xFFB9BDC4),
            modifier = Modifier.padding(horizontal = 8.dp),
        )
        Spacer(Modifier.height(24.dp))
        if (canRetry) {
            Button(
                onClick = onRetry,
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) { Text("重试") }
            Spacer(Modifier.height(12.dp))
        }
        OutlinedButton(
            onClick = onDiscard,
            modifier = Modifier.fillMaxWidth().height(48.dp),
        ) { Text("放弃这张照片", color = Color(0xFFE8EAED)) }
    }
}

private fun loadBitmap(file: File): android.graphics.Bitmap? {
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