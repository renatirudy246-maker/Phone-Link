package com.phonelink.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.phonelink.app.ConnectionState

private val Bg = Color(0xFF0F1115)
private val Surface = Color(0xFF1A1E26)
private val Primary = Color(0xFF2563EB)
private val Green = Color(0xFF22C55E)
private val TextMain = Color(0xFFF2F4F7)
private val TextSub = Color(0xFF9AA0A6)
private val TextFaint = Color(0xFF6B7280)
private val Danger = Color(0xFFF87171)
private val Transparent = Color(0x00000000)

/**
 * 一级首页（已配对启动默认页）：三段式紧凑布局——
 * 顶部标题+连接状态，中部圆形拍题主入口（视觉中心略偏上），
 * 底部相册/设备次级操作。深色、无导航栏、无大块卡片。
 */
@Composable
fun HomeScreen(
    desktopName: String,
    connection: ConnectionState,
    onTakePhoto: () -> Unit,
    onGalleryPicked: (android.net.Uri) -> Unit,
    onUnpair: () -> Unit,
    onReconnect: () -> Unit,
    feedbackEnabled: Boolean = false,
    onFeedbackEnabledChange: (Boolean) -> Unit = {},
) {
    var showSettings by remember { mutableStateOf(false) }
    val galleryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        uri?.let(onGalleryPicked)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Bg)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(28.dp))

        // Top：标题 + 连接状态
        Text(
            "Phone-Link",
            fontSize = 26.sp,
            fontWeight = FontWeight.SemiBold,
            color = TextMain,
        )
        Spacer(Modifier.height(8.dp))
        HomeStatusLine(desktopName = desktopName, connection = connection)

        Spacer(Modifier.weight(0.8f))

        // Center：拍题主入口（圆形 Primary Action，视觉中心偏上）
        TakePhotoAction(
            onTakePhoto = onTakePhoto,
        )

        Spacer(Modifier.weight(1.1f))

        // Bottom：次级操作
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            HomeSecondaryButton(
                text = "相册",
                icon = Icons.Outlined.PhotoLibrary,
                onClick = {
                    galleryLauncher.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    )
                },
                modifier = Modifier.weight(1f),
            )
            HomeSecondaryButton(
                text = "设备",
                icon = Icons.Outlined.Devices,
                onClick = { showSettings = true },
                modifier = Modifier.weight(1f),
            )
        }

        Spacer(Modifier.height(24.dp))
    }

    if (showSettings) {
        DeviceSettingsSheet(
            desktopName = desktopName,
            connection = connection,
            feedbackEnabled = feedbackEnabled,
            onFeedbackEnabledChange = onFeedbackEnabledChange,
            onReconnect = onReconnect,
            onUnpair = onUnpair,
            onDismiss = { showSettings = false },
        )
    }
}

/** 圆形蓝色拍题按钮 + 下方标题与副文案，按压 0.97 缩放。 */
@Composable
private fun TakePhotoAction(onTakePhoto: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.97f else 1f,
        label = "takePhotoScale",
    )

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.graphicsLayer {
            scaleX = scale
            scaleY = scale
        },
    ) {
        Box(
            modifier = Modifier
                .size(112.dp)
                .background(Primary, CircleShape)
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                ) { onTakePhoto() },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Outlined.PhotoCamera,
                contentDescription = "拍题",
                tint = Color.White,
                modifier = Modifier.size(44.dp),
            )
        }
        Spacer(Modifier.height(16.dp))
        Text(
            "拍题",
            fontSize = 22.sp,
            fontWeight = FontWeight.SemiBold,
            color = TextMain,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "拍照后直接发送到电脑",
            fontSize = 13.sp,
            color = TextSub,
        )
    }
}

@Composable
private fun HomeStatusLine(desktopName: String, connection: ConnectionState) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        when (connection) {
            ConnectionState.Connecting -> {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .background(Transparent, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    androidx.compose.material3.CircularProgressIndicator(
                        modifier = Modifier.size(12.dp),
                        strokeWidth = 2.dp,
                        color = Color(0xFFF59E0B),
                    )
                }
                Spacer(Modifier.size(6.dp))
                Text("正在连接…", fontSize = 13.sp, color = TextSub)
            }
            ConnectionState.Online -> {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .background(Green, CircleShape),
                )
                Spacer(Modifier.size(6.dp))
                Text("$desktopName · 已连接", fontSize = 13.sp, color = TextSub)
            }
            ConnectionState.Offline -> {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .background(TextFaint, CircleShape),
                )
                Spacer(Modifier.size(6.dp))
                Text("$desktopName · 电脑离线", fontSize = 13.sp, color = TextSub)
            }
            ConnectionState.Revoked -> {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .background(Danger, CircleShape),
                )
                Spacer(Modifier.size(6.dp))
                Text("$desktopName · 已撤销", fontSize = 13.sp, color = TextSub)
            }
        }
    }
}

@Composable
private fun HomeSecondaryButton(
    text: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .height(54.dp)
            .background(Surface, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = TextSub, modifier = Modifier.size(20.dp))
            Spacer(Modifier.size(8.dp))
            Text(text, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = TextMain)
        }
    }
}

/**
 * 设备管理：深色 ModalBottomSheet，替换旧白色 AlertDialog。
 * 解除配对为危险视觉层级（红字），但不做满屏红色大按钮。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceSettingsSheet(
    desktopName: String,
    connection: ConnectionState,
    deviceId: String = "",
    feedbackEnabled: Boolean = false,
    onFeedbackEnabledChange: (Boolean) -> Unit = {},
    onReconnect: () -> Unit,
    onUnpair: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = Surface,
        scrimColor = Color(0x99000000),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .navigationBarsPadding(),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "设备",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = TextMain,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onDismiss) {
                    Icon(
                        Icons.Outlined.Close,
                        contentDescription = "关闭",
                        tint = TextSub,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            Text(desktopName, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = TextMain)
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(
                            when (connection) {
                                ConnectionState.Online -> Green
                                ConnectionState.Connecting -> Color(0xFFF59E0B)
                                ConnectionState.Offline, ConnectionState.Revoked -> TextFaint
                            },
                            CircleShape,
                        )
                )
                Spacer(Modifier.size(6.dp))
                Text(
                    when (connection) {
                        ConnectionState.Online -> "已连接"
                        ConnectionState.Connecting -> "正在连接…"
                        ConnectionState.Offline -> "电脑离线"
                        ConnectionState.Revoked -> "已被桌面端撤销"
                    },
                    fontSize = 14.sp,
                    color = TextSub,
                )
            }

            if (deviceId.isNotEmpty()) {
                Spacer(Modifier.height(14.dp))
                Text(
                    "设备 ID  ${deviceId.takeLast(8)}",
                    fontSize = 13.sp,
                    color = TextFaint,
                )
            }

            Spacer(Modifier.height(24.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "保存扫描纠错样本",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        color = TextMain,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "将手动调整的页面边缘和原始图片保存在已配对电脑上，用于以后改进页面识别。数据不会上传云端。",
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                        color = TextFaint,
                    )
                }
                Spacer(Modifier.width(12.dp))
                Switch(
                    checked = feedbackEnabled,
                    onCheckedChange = onFeedbackEnabledChange,
                    colors = SwitchDefaults.colors(
                        checkedTrackColor = Primary,
                        checkedThumbColor = Color.White,
                    ),
                )
            }

            Spacer(Modifier.height(24.dp))

            Button(
                onClick = onReconnect,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Primary,
                    contentColor = Color.White,
                ),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth().height(50.dp),
            ) {
                Text("重新检查连接", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            }

            Spacer(Modifier.height(10.dp))

            TextButton(
                onClick = onUnpair,
                modifier = Modifier.fillMaxWidth().height(44.dp),
            ) {
                Text("解除配对", fontSize = 15.sp, color = Danger)
            }

            Spacer(Modifier.height(18.dp))
        }
    }
}