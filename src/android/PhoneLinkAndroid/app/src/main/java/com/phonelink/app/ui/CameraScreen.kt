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
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import java.io.File
import java.time.OffsetDateTime
import java.util.concurrent.Executor

/**
 * 相机首页：CameraX Preview + 快门 + Gallery。
 * 权限拒绝 → 解释 + 设置入口；发送进行中禁用快门（防止并发上传）。
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
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("需要相机权限才能拍题发送", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            if (showRationale) "权限被拒绝。可在系统设置中重新开启相机权限。" else "拍摄题目照片需要相机权限。",
            style = MaterialTheme.typography.bodyMedium,
            color = Color(0xFF666666),
        )
        Spacer(Modifier.height(24.dp))
        if (showRationale) {
            OutlinedButton(
                onClick = {
                    context.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = Uri.parse("package:${context.packageName}")
                        }
                    )
                },
                modifier = Modifier.fillMaxWidth().height(48.dp),
            ) { Text("前往设置") }
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = { launcher.launch(Manifest.permission.CAMERA) },
                modifier = Modifier.fillMaxWidth().height(48.dp),
            ) { Text("再次请求权限") }
        } else {
            Button(
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
    val previewView = remember { PreviewView(context) }
    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }
    var cameraError by remember { mutableStateOf<String?>(null) }
    var capturing by remember { mutableStateOf(false) }

    val executor: Executor = remember { ContextCompat.getMainExecutor(context) }

    DisposableEffect(lifecycleOwner) {
        val providerFuture = ProcessCameraProvider.getInstance(context)
        val listener = Runnable {
            try {
                val provider = providerFuture.get()
                val preview = Preview.Builder().build()
                val capture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .build()
                preview.setSurfaceProvider(previewView.surfaceProvider)
                provider.unbindAll()
                provider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    capture,
                )
                imageCapture = capture
                cameraError = null
            } catch (e: Exception) {
                Log.e("PhoneLinkCamera", "Camera bind failed", e)
                cameraError = "相机不可用：${e.message}"
            }
        }
        providerFuture.addListener(listener, executor)

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

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
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
                OutlinedButton(onClick = { cameraError = null }) {
                    Text("重试")
                }
            }
        } else {
            // Gallery（左下）
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(24.dp),
            ) {
                GalleryButton(onGalleryPicked)
            }

            // 快门（右下）
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(24.dp),
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

@Composable
private fun ShutterButton(enabled: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .size(72.dp)
            .border(4.dp, Color(0xCC333333), CircleShape),
        shape = CircleShape,
        contentPadding = PaddingValues(0.dp),
        colors = androidx.compose.material3.ButtonDefaults.buttonColors(
            containerColor = Color.White,
            contentColor = Color.Black,
            disabledContainerColor = Color(0x88FFFFFF),
        ),
    ) {
        // 圆形快门
    }
}

@Composable
private fun GalleryButton(onGalleryPicked: (Uri) -> Unit) {
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        uri?.let(onGalleryPicked)
    }

    OutlinedButton(
        onClick = {
            launcher.launch(
                PickVisualMediaRequest(
                    ActivityResultContracts.PickVisualMedia.ImageOnly
                )
            )
        },
        modifier = Modifier.height(48.dp),
        shape = RoundedCornerShape(24.dp),
    ) {
        Text("相册", color = Color.White)
    }
}