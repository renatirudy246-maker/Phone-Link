package com.phonelink.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
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
import com.phonelink.app.ui.SendCompletedScreen
import com.phonelink.app.ui.SendFailedScreen
import com.phonelink.app.ui.UploadingScreen
import com.phonelink.app.ui.theme.PhoneLinkTheme

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

    LaunchedEffect(Unit) {
        homeVm.start()
    }

    val s = homeVm.uiState
    LaunchedEffect(s) {
        state = s
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
        HomeUiState.Pairing -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator()
                Spacer(Modifier.height(16.dp))
                Text("正在配对…")
            }
        }
        is HomeUiState.Paired -> CameraRoot(
            desktopName = current.desktopName,
            connectionOnline = current.connection == ConnectionState.Online,
            vm = transferVm,
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

/** 已配对首页：顶部连接状态 + 相机/发送流程。 */
@Composable
private fun CameraRoot(
    desktopName: String,
    connectionOnline: Boolean,
    vm: TransferViewModel,
    onReconnect: () -> Unit,
    onUnpair: () -> Unit,
) {
    val sendState = vm.sendState
    var showSettings by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(10.dp)
                    .background(
                        if (connectionOnline) Color(0xFF22C55E) else Color(0xFFF59E0B),
                        CircleShape,
                    )
            )
            Spacer(Modifier.size(8.dp))
            Text(
                "$desktopName  ${if (connectionOnline) "在线" else "离线"}",
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.weight(1f))
            OutlinedButton(onClick = { showSettings = true }, modifier = Modifier.height(36.dp)) {
                Text("设备")
            }
        }

        when (sendState) {
            SendUiState.Idle -> CameraScreen(
                shutterEnabled = true,
                onCapture = { file, capturedAt -> vm.onCaptured(file, capturedAt) },
                onGalleryPicked = { uri -> vm.onGalleryPicked(uri) },
            )
            SendUiState.Preparing -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(16.dp))
                    Text("正在处理图片…")
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
                onDone = { vm.done() },
            )
            is SendUiState.Failed -> SendFailedScreen(
                message = sendState.failure.userMessage,
                canRetry = sendState.failure.canRetry,
                onRetry = { vm.retry() },
                onDiscard = { vm.done() },
            )
        }
    }

    if (showSettings) {
        DeviceSettingsSheet(
            desktopName = desktopName,
            connectionOnline = connectionOnline,
            onReconnect = onReconnect,
            onUnpair = onUnpair,
            onDismiss = { showSettings = false },
        )
    }
}

@Composable
private fun DeviceSettingsSheet(
    desktopName: String,
    connectionOnline: Boolean,
    onReconnect: () -> Unit,
    onUnpair: () -> Unit,
    onDismiss: () -> Unit,
) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("设备") },
        text = {
            Column {
                Text("桌面端：$desktopName")
                Spacer(Modifier.height(4.dp))
                Text(if (connectionOnline) "状态：已连接" else "状态：离线")
            }
        },
        confirmButton = {
            OutlinedButton(onClick = onReconnect) { Text("重新检查连接") }
        },
        dismissButton = {
            Row {
                OutlinedButton(onClick = onUnpair) { Text("解除配对") }
                Spacer(Modifier.size(8.dp))
                Button(onClick = onDismiss) { Text("关闭") }
            }
        },
    )
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
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(24.dp))

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