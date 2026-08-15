package com.phonelink.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.phonelink.app.transfer.SendUiState
import com.phonelink.app.transfer.TransferViewModel
import com.phonelink.app.ui.CameraScreen
import com.phonelink.app.ui.CapturePreviewScreen
import com.phonelink.app.ui.HomeScreen
import com.phonelink.app.ui.SendCompletedScreen
import com.phonelink.app.ui.SendFailedScreen
import com.phonelink.app.ui.UploadingScreen
import com.phonelink.app.ui.theme.PhoneLinkTheme
import kotlinx.coroutines.delay

private val Bg = Color(0xFF0F1115)

private enum class AppScreen { Home, Camera }

class MainActivity : ComponentActivity() {

    private val cameraPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                _pendingScan?.invoke()
                _pendingScan = null
            } else {
                Toast.makeText(this, "需要相机权限才能扫码配对", Toast.LENGTH_LONG).show()
            }
        }

    private var _pendingScan: (() -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PhoneLinkTheme {
                AppRoot(
                    onRequestCamera = { action ->
                        val granted = ContextCompat.checkSelfPermission(
                            this, Manifest.permission.CAMERA
                        ) == PackageManager.PERMISSION_GRANTED
                        if (granted) {
                            action()
                        } else {
                            _pendingScan = action
                            cameraPermission.launch(Manifest.permission.CAMERA)
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun AppRoot(
    onRequestCamera: (() -> Unit) -> Unit,
) {
    val homeVm: HomeViewModel = viewModel()
    val transferVm: TransferViewModel = viewModel()
    var state by remember { mutableStateOf(homeVm.uiState) }
    var screen by remember { mutableStateOf(AppScreen.Home) }

    LaunchedEffect(Unit) {
        homeVm.start()
    }

    LaunchedEffect(homeVm.uiState) {
        state = homeVm.uiState
    }

    val sendState = transferVm.sendState

    // 系统 Back：
    //  Camera → Home；Preview → 重拍回来源页；Success → Home；Home → 退出
    BackHandler(enabled = state is HomeUiState.Paired) {
        when {
            sendState is SendUiState.Completed -> {
                transferVm.done()
                screen = AppScreen.Home
            }
            sendState is SendUiState.Preview -> {
                transferVm.retake()
                if (screen == AppScreen.Camera) screen = AppScreen.Home
            }
            screen == AppScreen.Camera -> screen = AppScreen.Home
        }
    }

    // 发送成功：显示约 900ms 后自动返回（相机拍摄回 Camera，相册发送回 Home）
    LaunchedEffect(sendState) {
        if (sendState is SendUiState.Completed) {
            delay(900)
            transferVm.done()
            screen = if (transferVm.previewFromCamera) AppScreen.Camera else AppScreen.Home
        }
    }

    when (val current = state) {
        HomeUiState.NoPairing -> HomeContent(
            status = "未配对",
            statusColor = Color(0xFF9CA3AF),
            onScan = { onRequestCamera { homeVm.startScan() } },
        )
        HomeUiState.Scanning -> ScanScreen(
            onDecoded = { homeVm.onQrDecoded(it) },
            onCancel = { homeVm.cancelScan() },
        )
        HomeUiState.Pairing -> Box(Modifier.fillMaxSize().statusBarsPadding(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator()
                Spacer(Modifier.height(16.dp))
                Text("正在配对…")
            }
        }
        is HomeUiState.Paired -> PairedContent(
            desktopName = current.desktopName,
            connection = current.connection,
            deviceId = homeVm.deviceId,
            vm = transferVm,
            screen = screen,
            onScreenChange = { screen = it },
            onReconnect = { homeVm.reconnect() },
            onUnpair = { homeVm.clearPairing() },
        )
        is HomeUiState.Error -> ErrorContent(
            message = current.message,
            canRetryPair = current.canRetryPair,
            hasPairing = homeVm.hasPairing(),
            onRetryPair = { onRequestCamera { homeVm.startScan() } },
            onReconnect = { homeVm.reconnect() },
            onUnpair = { homeVm.clearPairing() },
        )
    }
}

/** 已配对内容区：Home / Camera 两级导航 + 传输状态页。 */
@Composable
private fun PairedContent(
    desktopName: String,
    connection: ConnectionState,
    deviceId: String,
    vm: TransferViewModel,
    screen: AppScreen,
    onScreenChange: (AppScreen) -> Unit,
    onReconnect: () -> Unit,
    onUnpair: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Bg),
    ) {
        when (val sendState = vm.sendState) {
            SendUiState.Idle -> androidx.compose.animation.Crossfade(
                targetState = screen,
                animationSpec = androidx.compose.animation.core.tween(180),
                label = "homeCamera",
            ) { current ->
                when (current) {
                    AppScreen.Home -> HomeScreen(
                        desktopName = desktopName,
                        connection = connection,
                        onTakePhoto = { onScreenChange(AppScreen.Camera) },
                        onGalleryPicked = { vm.onGalleryPicked(it) },
                        onUnpair = onUnpair,
                        onReconnect = onReconnect,
                    )
                    AppScreen.Camera -> CameraPage(
                        vm = vm,
                        onBack = { onScreenChange(AppScreen.Home) },
                    )
                }
            }
            SendUiState.Preparing -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = Color.White)
                    Spacer(Modifier.height(16.dp))
                    Text("正在处理图片…", color = Color(0xFFB9BDC4))
                }
            }
            is SendUiState.Preview -> CapturePreviewScreen(
                previewFile = sendState.previewFile,
                onRetake = { vm.retake() },
                onSend = { vm.send() },
            )
            is SendUiState.Uploading -> UploadingScreen(sendState.percent)
            is SendUiState.Completed -> SendCompletedScreen(
                desktopName = sendState.desktopName,
                onHome = {
                    vm.done()
                    onScreenChange(AppScreen.Home)
                },
            )
            is SendUiState.Failed -> SendFailedScreen(
                message = sendState.failure.userMessage,
                canRetry = sendState.failure.canRetry,
                onRetry = { vm.retry() },
                onDiscard = { vm.done() },
            )
        }
    }
}

/** 相机页：左上返回 Home + 3:4 相机。 */
@Composable
private fun CameraPage(
    vm: TransferViewModel,
    onBack: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Bg)
            .statusBarsPadding(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                androidx.compose.material3.Icon(
                    Icons.AutoMirrored.Outlined.ArrowBack,
                    contentDescription = "返回首页",
                    tint = Color.White,
                )
            }
            Text(
                "拍题",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
            )
        }
        CameraScreen(
            shutterEnabled = true,
            onCapture = { file, capturedAt -> vm.onCaptured(file, capturedAt) },
            onGalleryPicked = { uri -> vm.onGalleryPicked(uri) },
        )
    }
}

@Composable
private fun HomeContent(
    status: String,
    statusColor: Color,
    desktopName: String? = null,
    onScan: () -> Unit,
    onRefresh: (() -> Unit)? = null,
    onUnpair: (() -> Unit)? = null,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(24.dp))

        Text("Phone-Link", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(16.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(10.dp)
                    .background(statusColor, CircleShape)
            )
            Spacer(Modifier.size(8.dp))
            Text(status, style = MaterialTheme.typography.titleMedium)
        }

        if (desktopName != null) {
            Spacer(Modifier.height(8.dp))
            Text("已配对桌面：$desktopName", style = MaterialTheme.typography.bodyMedium)
        }

        Spacer(Modifier.height(32.dp))

        Button(
            onClick = onScan,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
        ) {
            Text(if (desktopName == null) "扫描二维码配对" else "重新配对", fontWeight = FontWeight.SemiBold)
        }

        if (onRefresh != null || onUnpair != null) {
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                onRefresh?.let {
                    OutlinedButton(
                        onClick = it,
                        modifier = Modifier.weight(1f).height(48.dp)
                    ) { Text("重新检查连接") }
                }
                onUnpair?.let {
                    OutlinedButton(
                        onClick = it,
                        modifier = Modifier.weight(1f).height(48.dp)
                    ) { Text("解除配对") }
                }
            }
        }
    }
}

@Composable
private fun ErrorContent(
    message: String,
    canRetryPair: Boolean,
    hasPairing: Boolean,
    onRetryPair: () -> Unit,
    onReconnect: () -> Unit,
    onUnpair: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(message, style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(24.dp))
        if (canRetryPair) {
            Button(
                onClick = onRetryPair,
                modifier = Modifier.fillMaxWidth().height(52.dp)
            ) { Text("重新扫码配对") }
        } else {
            Button(
                onClick = onReconnect,
                modifier = Modifier.fillMaxWidth().height(52.dp)
            ) { Text("重试连接") }
        }
        if (hasPairing) {
            Spacer(Modifier.height(12.dp))
            OutlinedButton(
                onClick = onUnpair,
                modifier = Modifier.fillMaxWidth().height(48.dp)
            ) { Text("解除配对") }
        }
    }
}