package com.phonelink.app.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.Uri
import android.provider.Settings
import android.util.Log
import android.view.Surface
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import java.io.File
import java.time.OffsetDateTime
import java.util.concurrent.Executor

/**
 * 相机首页：CameraX Preview（3:4 画幅）+ 快门 + Gallery。
 *
 * WYSIWYG：Preview 与 ImageCapture 通过 ViewPort + UseCaseGroup 绑定在同一
 * sensor crop 语义下（Preview SCALE_TYPE_FILL_CENTER 对齐 ViewPort crop），
 * 保证"看到什么就拍到什么"，不允许四周多拍进一圈内容。
 */
@Composable
fun CameraScreen(
    shutterEnabled: Boolean,
    onCapture: (File, OffsetDateTime) -> Unit,
    onGalleryPicked: (Uri) -> Unit,
) {
    val context = LocalContext.current
    val hasPermission = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
        PackageManager.PERMISSION_GRANTED

    if (!hasPermission) {
        CameraPermissionGate()
        return
    }

    CameraPreview(
        shutterEnabled = shutterEnabled,
        onCapture = onCapture,
        onGalleryPicked = onGalleryPicked,
    )
}

@Composable
private fun CameraPermissionGate() {
    val context = LocalContext.current
    var showRationale by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        showRationale = !granted
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0F1115))
            .padding(horizontal = 32.dp)
            .navigationBarsPadding(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("需要相机权限才能拍题发送", style = MaterialTheme.typography.titleMedium, color = Color.White)
        Spacer(Modifier.height(8.dp))
        Text(
            if (showRationale) "权限被拒绝。可在系统设置中重新开启相机权限。" else "拍摄题目照片需要相机权限。",
            style = MaterialTheme.typography.bodyMedium,
            color = Color(0xFF9AA0A6),
        )
        Spacer(Modifier.height(24.dp))
        if (showRationale) {
            androidx.compose.material3.OutlinedButton(
                onClick = {
                    context.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = Uri.parse("package:${context.packageName}")
                        }
                    )
                },
                modifier = Modifier.fillMaxWidth().height(48.dp),
            ) { Text("前往设置", color = Color.White) }
            Spacer(Modifier.height(8.dp))
            androidx.compose.material3.Button(
                onClick = { launcher.launch(Manifest.permission.CAMERA) },
                modifier = Modifier.fillMaxWidth().height(48.dp),
            ) { Text("再次请求权限") }
        } else {
            androidx.compose.material3.Button(
                onClick = { launcher.launch(Manifest.permission.CAMERA) },
                modifier = Modifier.fillMaxWidth().height(48.dp),
            ) { Text("允许相机权限") }
        }
    }
}

@Composable
private fun CameraPreview(
    shutterEnabled: Boolean,
    onCapture: (File, OffsetDateTime) -> Unit,
    onGalleryPicked: (Uri) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }
    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }
    var cameraError by remember { mutableStateOf<String?>(null) }
    var capturing by remember { mutableStateOf(false) }

    val executor: Executor = remember { ContextCompat.getMainExecutor(context) }

    DisposableEffect(lifecycleOwner) {
        val providerFuture = ProcessCameraProvider.getInstance(context)

        // 绑定放在布局完成之后执行，确保 previewView.viewPort 可用
        // （ViewPort 是 Preview 与 ImageCapture 共享 crop 的关键）。
        val bind = Runnable {
            try {
                val provider = providerFuture.get()
                val preview = Preview.Builder().build()
                val capture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .setResolutionSelector(
                        ResolutionSelector.Builder()
                            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                            .build()
                    )
                    .build()
                preview.setSurfaceProvider(previewView.surfaceProvider)
                provider.unbindAll()

                val viewPort = previewView.viewPort
                if (viewPort != null) {
                    val group = UseCaseGroup.Builder()
                        .addUseCase(preview)
                        .addUseCase(capture)
                        .setViewPort(viewPort)
                        .build()
                    provider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        group,
                    )
                } else {
                    // 极端情况（预览区未完成布局）回退普通绑定，功能不中断
                    provider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        capture,
                    )
                }
                imageCapture = capture
                cameraError = null
            } catch (e: Exception) {
                Log.e("PhoneLinkCamera", "Camera bind failed", e)
                cameraError = "相机不可用：${e.message}"
            }
        }
        providerFuture.addListener(Runnable { previewView.post(bind) }, executor)

        // 用重力传感器决定照片方向，不依赖系统"自动旋转"设置：
        // 竖拿 -> ROTATION_0，横拿 -> ROTATION_90/270，平放 -> 保持上次值。
        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val sensorListener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val capture = imageCapture ?: return
                val x = event.values[0]
                val y = event.values[1]
                if (kotlin.math.abs(x) < 2f && kotlin.math.abs(y) < 2f) return
                val rotation = when {
                    kotlin.math.abs(x) > kotlin.math.abs(y) ->
                        if (x > 0) Surface.ROTATION_90 else Surface.ROTATION_270
                    else -> Surface.ROTATION_0
                }
                if (rotation != capture.targetRotation) {
                    capture.targetRotation = rotation
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        sensorManager.registerListener(sensorListener, accelerometer, SensorManager.SENSOR_DELAY_UI)

        onDispose {
            sensorManager.unregisterListener(sensorListener)
            try {
                ProcessCameraProvider.getInstance(context).get().unbindAll()
            } catch (_: Exception) {
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0F1115)),
    ) {
        // 3:4 竖屏画幅（拍书/试卷/A4 文档的默认取景比例）
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(3f / 4f)
                .background(Color(0xFF0F1115)),
        ) {
            androidx.compose.ui.viewinterop.AndroidView(
                factory = { previewView },
                modifier = Modifier.fillMaxSize(),
            )

            if (cameraError != null) {
                Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(cameraError!!, color = Color.White)
                    Spacer(Modifier.height(16.dp))
                    androidx.compose.material3.OutlinedButton(onClick = { cameraError = null }) {
                        Text("重试")
                    }
                }
            }
        }

        // 底部控制区：Gallery（左）· Shutter（中）· 占位（右，保持平衡）
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .navigationBarsPadding(),
        ) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 40.dp, bottom = 28.dp),
            ) {
                GalleryButton(onGalleryPicked)
            }

            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 24.dp),
            ) {
                ShutterButton(
                    enabled = !capturing && imageCapture != null && shutterEnabled,
                    onClick = {
                        val capture = imageCapture ?: return@ShutterButton
                        capturing = true
                        val outFile = File(context.cacheDir, "camera/${System.currentTimeMillis()}.jpg")
                        outFile.parentFile?.mkdirs()
                        val options = ImageCapture.OutputFileOptions.Builder(outFile).build()
                        capture.takePicture(
                            options,
                            executor,
                            object : ImageCapture.OnImageSavedCallback {
                                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                                    capturing = false
                                    onCapture(outFile, OffsetDateTime.now())
                                }

                                override fun onError(exception: ImageCaptureException) {
                                    capturing = false
                                    outFile.delete()
                                    Log.e("PhoneLinkCamera", "Capture failed", exception)
                                }
                            },
                        )
                    },
                )
            }
        }
    }
}

/** 快门：外圈 + 内实心圆 + 按压反馈，68dp touch target。 */
@Composable
private fun ShutterButton(enabled: Boolean, onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()

    Box(
        modifier = Modifier
            .size(68.dp)
            .border(
                width = 3.dp,
                color = if (pressed) Color(0xFF8A8F98) else Color.White,
                shape = CircleShape,
            )
            .padding(7.dp)
            .background(
                color = if (pressed) Color(0xFFD7DBE0) else Color.White,
                shape = CircleShape,
            )
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled,
            ) { onClick() },
        contentAlignment = Alignment.Center,
    ) {
    }
}

@Composable
private fun GalleryButton(onGalleryPicked: (Uri) -> Unit) {
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        uri?.let(onGalleryPicked)
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(64.dp)
            .clickable {
                launcher.launch(
                    PickVisualMediaRequest(
                        ActivityResultContracts.PickVisualMedia.ImageOnly
                    )
                )
            },
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .background(Color(0x2EFFFFFF), RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Outlined.PhotoLibrary,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(24.dp),
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "相册",
            color = Color(0xFFB9BDC4),
            style = MaterialTheme.typography.labelMedium,
        )
    }
}