package com.phonelink.app.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
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

/** 拍照/Gallery 后的确认页：真实图片 + Retake/Send。 */
@Composable
fun CapturePreviewScreen(
    previewFile: File,
    onRetake: () -> Unit,
    onSend: () -> Unit,
) {
    val bitmap = remember(previewFile) { loadBitmap(previewFile) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "已拍摄图片",
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentScale = ContentScale.Fit,
            )
        } else {
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Text("预览加载失败", color = Color(0xFF999999))
            }
        }

        Spacer(Modifier.height(16.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedButton(
                onClick = onRetake,
                modifier = Modifier.weight(1f).height(52.dp),
            ) { Text("重拍") }
            Button(
                onClick = onSend,
                modifier = Modifier.weight(1f).height(52.dp),
            ) { Text("发送", fontWeight = FontWeight.SemiBold) }
        }
    }
}

/** 上传进度（真实 bytes 进度，2% 节流）。 */
@Composable
fun UploadingScreen(percent: Int) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (percent <= 0) {
            CircularProgressIndicator()
            Spacer(Modifier.height(16.dp))
            Text("准备中…", style = MaterialTheme.typography.bodyLarge)
        } else {
            LinearProgressIndicator(
                progress = { percent / 100f },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            Text("上传中 $percent%", style = MaterialTheme.typography.bodyLarge)
        }
    }
}

/** 发送成功：约 1 秒后自动返回相机，可点按钮立即返回。 */
@Composable
fun SendCompletedScreen(desktopName: String, onDone: () -> Unit) {
    LaunchedEffect(Unit) {
        delay(1200)
        onDone()
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .padding(0.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text("✓", color = Color(0xFF22C55E), fontSize = MaterialTheme.typography.displayMedium.fontSize)
        }
        Spacer(Modifier.height(16.dp))
        Text(
            "已发送到 $desktopName",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onDone, modifier = Modifier.fillMaxWidth().height(52.dp)) {
            Text("返回相机")
        }
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
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("发送失败", style = MaterialTheme.typography.titleMedium, color = Color(0xFFEF4444))
        Spacer(Modifier.height(12.dp))
        Text(message, style = MaterialTheme.typography.bodyLarge, color = Color(0xFF444444))
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
        ) { Text("放弃这张照片") }
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