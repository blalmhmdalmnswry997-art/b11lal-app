package com.example

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.ui.AppScreen
import com.example.ui.MainViewModel
import com.example.ui.screens.ChatConversationScreen
import com.example.ui.screens.ChatsListScreen
import com.example.ui.screens.MeshRadarScreen
import com.example.ui.screens.VideoCallScreen
import com.example.ui.theme.CipherMeshTheme
import com.example.ui.theme.CyanPrimary

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            CipherMeshTheme(darkTheme = true) {
                CipherMeshApp()
            }
        }
    }
}

@Composable
fun CipherMeshApp(viewModel: MainViewModel = viewModel()) {
    val context = LocalContext.current
    val currentScreen by viewModel.currentScreen.collectAsState()

    val requiredPermissions = remember {
        val list = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
        )

        if (Build.VERSION.SDK_INT in Build.VERSION_CODES.S..Int.MAX_VALUE) {
            list.add(Manifest.permission.BLUETOOTH_SCAN)
            list.add(Manifest.permission.BLUETOOTH_ADVERTISE)
            list.add(Manifest.permission.BLUETOOTH_CONNECT)
        }

        if (Build.VERSION.SDK_INT in Build.VERSION_CODES.TIRAMISU..Int.MAX_VALUE) {
            list.add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }

        list.toTypedArray()
    }

    var hasAllPermissions by remember {
        mutableStateOf(
            requiredPermissions.all {
                ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
            }
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        hasAllPermissions = results.values.all { it }
        viewModel.startMesh()
    }

    LaunchedEffect(Unit) {
        viewModel.startMesh()
        if (!hasAllPermissions) {
            permissionLauncher.launch(requiredPermissions)
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        containerColor = Color(0xFF070D18),
        bottomBar = {
            if (currentScreen == AppScreen.RADAR || currentScreen == AppScreen.CHATS) {
                NavigationBar(
                    containerColor = Color(0xFF0A1322),
                    modifier = Modifier.windowInsetsPadding(WindowInsets.navigationBars)
                ) {
                    NavigationBarItem(
                        selected = currentScreen == AppScreen.RADAR,
                        onClick = { viewModel.navigateTo(AppScreen.RADAR) },
                        icon = {
                            Icon(
                                imageVector = Icons.Default.Radar,
                                contentDescription = "رادار الشبكة",
                                modifier = Modifier.size(24.dp)
                            )
                        },
                        label = {
                            Text(
                                text = "رادار الشبكة",
                                fontSize = 12.sp,
                                fontWeight = if (currentScreen == AppScreen.RADAR) FontWeight.Bold else FontWeight.Normal
                            )
                        },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = Color(0xFF001F29),
                            selectedTextColor = CyanPrimary,
                            indicatorColor = CyanPrimary,
                            unselectedIconColor = Color(0xFF8FA4C4),
                            unselectedTextColor = Color(0xFF8FA4C4)
                        ),
                        modifier = Modifier.testTag("nav_radar")
                    )

                    NavigationBarItem(
                        selected = currentScreen == AppScreen.CHATS,
                        onClick = { viewModel.navigateTo(AppScreen.CHATS) },
                        icon = {
                            Icon(
                                imageVector = Icons.Default.Chat,
                                contentDescription = "المحادثات",
                                modifier = Modifier.size(24.dp)
                            )
                        },
                        label = {
                            Text(
                                text = "المحادثات",
                                fontSize = 12.sp,
                                fontWeight = if (currentScreen == AppScreen.CHATS) FontWeight.Bold else FontWeight.Normal
                            )
                        },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = Color(0xFF001F29),
                            selectedTextColor = CyanPrimary,
                            indicatorColor = CyanPrimary,
                            unselectedIconColor = Color(0xFF8FA4C4),
                            unselectedTextColor = Color(0xFF8FA4C4)
                        ),
                        modifier = Modifier.testTag("nav_chats")
                    )
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            Crossfade(targetState = currentScreen, label = "ScreenTransition") { screen ->
                when (screen) {
                    AppScreen.RADAR -> MeshRadarScreen(viewModel = viewModel)
                    AppScreen.CHATS -> ChatsListScreen(viewModel = viewModel)
                    AppScreen.CONVERSATION -> ChatConversationScreen(viewModel = viewModel)
                    AppScreen.VIDEO_CALL -> VideoCallScreen(viewModel = viewModel)
                    AppScreen.DIAGNOSTICS -> MeshRadarScreen(viewModel = viewModel)
                }
            }

            if (!hasAllPermissions) {
                PermissionRequestBanner(
                    onRequestPermissions = { permissionLauncher.launch(requiredPermissions) },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(16.dp)
                )
            }
        }
    }
}

@Composable
private fun PermissionRequestBanner(
    onRequestPermissions: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF0F1A2E)),
        border = androidx.compose.foundation.BorderStroke(1.dp, CyanPrimary)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Security,
                    contentDescription = null,
                    tint = CyanPrimary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "مطلوب تفعيل صلاحيات الشبكة المباشرة",
                    style = MaterialTheme.typography.titleSmall,
                    color = Color.White,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = "يحتاج تطبيق CipherMesh إلى صلاحيات البلوتوث والواي فاي القريب والكاميرا والمايكروفون لاكتشاف الأجهزة القريبة وإجراء المكالمات المشفرة بدون إنترنت.",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF8FA4C4),
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(12.dp))

            Button(
                onClick = onRequestPermissions,
                colors = ButtonDefaults.buttonColors(containerColor = CyanPrimary),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "منح الصلاحيات وتفعيل الشبكة",
                    color = Color(0xFF001F29),
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}
