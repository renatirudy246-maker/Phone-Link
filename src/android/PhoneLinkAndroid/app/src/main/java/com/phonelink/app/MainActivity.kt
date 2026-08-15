package com.phonelink.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.RoundedCornerShape
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
    val vm: HomeViewModel = viewModel()
    var state by remember { mutableStateOf(vm.uiState) }

    LaunchedEffect(Unit) {
        vm.start()
    }

    val s = vm.uiState
    LaunchedEffect(s) {
        state = s
    }

    when (val current = state) {
        HomeUiState.NoPairing -> HomeContent(
            status = "未配对",
            statusColor = Color(0xFF9CA3AF),
            onScan = { onRequestCamera { vm.startScan() } },
        )
        HomeUiState.Scanning -> ScanScreen(
            onDecoded = { vm.onQrDecoded(it) },
            onCancel = { vm.cancelScan() },
        )
        HomeUiState.Pairing -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator()
                Spacer(Modifier.height(16.dp))
                Text("正在配对…")
            }
        }
        is HomeUiState.Paired -> HomeContent(
            status = when (current.connection) {
                ConnectionState.Connecting -> "连接中…"
                ConnectionState.Online -> "已连接"
                ConnectionState.Offline -> "离线"
                ConnectionState.Revoked -> "已撤销"
            },
            statusColor = when (current.connection) {
                ConnectionState.Online -> Color(0xFF22C55E)
                ConnectionState.Offline -> Color(0xFFF59E0B)
                ConnectionState.Revoked -> Color(0xFFEF4444)
                ConnectionState.Connecting -> Color(0xFF9CA3AF)
            },
            desktopName = current.desktopName,
            onScan = { onRequestCamera { vm.startScan() } },
            onRefresh = { vm.reconnect() },
            onUnpair = { vm.clearPairing() },
        )
        is HomeUiState.Error -> ErrorContent(
            message = current.message,
            canRetryPair = current.canRetryPair,
            hasPairing = vm.hasPairing(),
            onRetryPair = { onRequestCamera { vm.startScan() } },
            onReconnect = { vm.reconnect() },
            onUnpair = { vm.clearPairing() },
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