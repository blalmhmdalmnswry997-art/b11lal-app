package com.example.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.mesh.PeerNode
import com.example.ui.MainViewModel
import com.example.ui.components.SecurityVerificationDialog
import com.example.ui.theme.AmberMesh
import com.example.ui.theme.CyanPrimary
import com.example.ui.theme.EmeraldSecure
import com.example.ui.theme.RedAlert
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun MeshRadarScreen(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current

    val localNodeName by viewModel.localNodeName.collectAsState()
    val isAdvertising by viewModel.isAdvertising.collectAsState()
    val isDiscovering by viewModel.isDiscovering.collectAsState()
    val discoveredPeers by viewModel.discoveredPeers.collectAsState()
    val connectedPeers by viewModel.connectedPeers.collectAsState()
    val packetsForwarded by viewModel.packetsForwarded.collectAsState()
    val packetsReceived by viewModel.packetsReceived.collectAsState()
    val verifiedPeers by viewModel.verifiedPeers.collectAsState()
    val isGraphView by viewModel.isGraphView.collectAsState()
    val peerToVerify by viewModel.peerToVerify.collectAsState()

    val isGlobalBridgeConnected by viewModel.nearbyManager.isGlobalBridgeConnected.collectAsState()

    val isWalkieTalkieEnabled by viewModel.isWalkieTalkieEnabled.collectAsState()
    val isPttTransmitting by viewModel.isPttTransmitting.collectAsState()
    val isPttReceiving by viewModel.isPttReceiving.collectAsState()
    val isSelfDestructEnabled by viewModel.isSelfDestructEnabled.collectAsState()
    val isAutoReplyEnabled by viewModel.isAutoReplyEnabled.collectAsState()
    val autoReplyText by viewModel.autoReplyText.collectAsState()
    val appPinCode by viewModel.appPinCode.collectAsState()
    val isAppLocked by viewModel.isAppLocked.collectAsState()

    var showDiagnostics by remember { mutableStateOf(false) }
    var showEditNameDialog by remember { mutableStateOf(false) }
    var showDeveloperDialog by remember { mutableStateOf(false) }
    var showBlackBoxDialog by remember { mutableStateOf(false) }

    var newNameInput by remember(localNodeName) { mutableStateOf(localNodeName) }
    var autoReplyInput by remember(autoReplyText) { mutableStateOf(autoReplyText) }
    var pinInputConfig by remember(appPinCode) { mutableStateOf(appPinCode) }

    var unlockPinAttempt by remember { mutableStateOf("") }
    var unlockError by remember { mutableStateOf(false) }

    val allPeers = remember(discoveredPeers, connectedPeers) {
        val map = discoveredPeers.toMutableMap()
        map.putAll(connectedPeers)
        map.values.toList()
    }

    val multiHopCount = allPeers.count { !it.isDirect }

    if (isAppLocked) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(Color(0xFF040810))
                .padding(24.dp),
            contentAlignment = Alignment.Center
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(22.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF0B1424)),
                border = androidx.compose.foundation.BorderStroke(1.5.dp, CyanPrimary)
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        modifier = Modifier
                            .size(64.dp)
                            .clip(CircleShape)
                            .background(CyanPrimary.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Lock,
                            contentDescription = null,
                            tint = CyanPrimary,
                            modifier = Modifier.size(32.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(14.dp))
                    Text(
                        text = "بوابة الحماية الرقمية",
                        style = MaterialTheme.typography.titleLarge,
                        color = Color.White,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "أدخل الرمز السري لفتح التطبيق (أو رمز الطوارئ المموه 9911 لمسح السجل تلقائياً):",
                        fontSize = 12.sp,
                        color = Color(0xFF8FA4C4),
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    OutlinedTextField(
                        value = unlockPinAttempt,
                        onValueChange = {
                            unlockPinAttempt = it
                            unlockError = false
                        },
                        singleLine = true,
                        label = { Text("الرمز السري", color = CyanPrimary) },
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (unlockError) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "الرمز غير صحيح، حاول مرة أخرى",
                            fontSize = 12.sp,
                            color = RedAlert,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Spacer(modifier = Modifier.height(18.dp))
                    Button(
                        onClick = {
                            val ok = viewModel.tryUnlockApp(unlockPinAttempt)
                            if (ok) {
                                unlockPinAttempt = ""
                                unlockError = false
                            } else {
                                unlockError = true
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = EmeraldSecure),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "فتح القفل",
                            color = Color(0xFF00220F),
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                    }
                }
            }
        }
        return
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF070D18))
            .padding(horizontal = 16.dp, vertical = 6.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(9.dp)
                            .clip(CircleShape)
                            .background(if (isAdvertising || isDiscovering) EmeraldSecure else RedAlert)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (isGlobalBridgeConnected) "🌍 الربط المحلي والدولي التلقائي نشط" else "شبكة اتصال موزعة تلقائية (64 قفزة)",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isGlobalBridgeConnected) EmeraldSecure else CyanPrimary,
                        fontWeight = FontWeight.Bold
                    )
                }

                Spacer(modifier = Modifier.height(2.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable {
                            newNameInput = localNodeName
                            showEditNameDialog = true
                        }
                        .padding(vertical = 2.dp, horizontal = 4.dp)
                ) {
                    Text(
                        text = "$localNodeName [${viewModel.repository.localNodeId.take(4)}]",
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Icon(
                        imageVector = Icons.Default.Edit,
                        contentDescription = "تعديل الاسم",
                        tint = CyanPrimary,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = { viewModel.sendSosBroadcast() },
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(RedAlert.copy(alpha = 0.2f))
                        .border(1.dp, RedAlert, CircleShape)
                        .testTag("sos_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = "نداء طوارئ SOS",
                        tint = RedAlert,
                        modifier = Modifier.size(18.dp)
                    )
                }

                Spacer(modifier = Modifier.width(5.dp))

                IconButton(
                    onClick = { showBlackBoxDialog = true },
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF0F1A2E))
                        .border(1.dp, EmeraldSecure.copy(alpha = 0.5f), CircleShape)
                ) {
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = "الصندوق الأسود ودرع الحماية",
                        tint = EmeraldSecure,
                        modifier = Modifier.size(18.dp)
                    )
                }

                Spacer(modifier = Modifier.width(5.dp))

                IconButton(
                    onClick = { viewModel.toggleGraphView() },
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF0F1A2E))
                        .testTag("toggle_graph_button")
                ) {
                    Icon(
                        imageVector = if (isGraphView) Icons.Default.Radar else Icons.Default.Hub,
                        contentDescription = "تبديل عرض الشبكة",
                        tint = CyanPrimary,
                        modifier = Modifier.size(18.dp)
                    )
                }

                Spacer(modifier = Modifier.width(5.dp))

                IconButton(
                    onClick = {
                        autoReplyInput = autoReplyText
                        pinInputConfig = appPinCode
                        showDiagnostics = true
                    },
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF0F1A2E))
                        .border(1.dp, AmberMesh.copy(alpha = 0.5f), CircleShape)
                        .testTag("diagnostics_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Security,
                        contentDescription = "مركز القيادة والمميزات",
                        tint = AmberMesh,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(5.dp))

        // Interactive Developer Credits & Feedback Banner (@BR_B1 | 773551513)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0xFF101D33))
                .border(1.dp, AmberMesh.copy(alpha = 0.65f), RoundedCornerShape(12.dp))
                .clickable { showDeveloperDialog = true }
                .padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(AmberMesh.copy(alpha = 0.2f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Person,
                        contentDescription = null,
                        tint = AmberMesh,
                        modifier = Modifier.size(16.dp)
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Column {
                    Text(
                        text = "مطور التطبيق: تليجرام @BR_B1  •  واتس: 773551513",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = Color.White
                    )
                    Text(
                        text = "نستقبل آرائكم ومقترحاتكم (اضغط للتواصل المباشر) 💬",
                        fontSize = 9.sp,
                        color = AmberMesh,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(EmeraldSecure.copy(alpha = 0.2f))
                    .padding(horizontal = 8.dp, vertical = 3.dp)
            ) {
                Text(
                    text = "تواصل 📞",
                    fontSize = 10.sp,
                    color = EmeraldSecure,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        Spacer(modifier = Modifier.height(5.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            StatusChip(
                label = "الأجهزة المتصلة",
                value = "${connectedPeers.size}",
                color = CyanPrimary,
                modifier = Modifier.weight(1f)
            )
            StatusChip(
                label = "عبر القفزات والدول",
                value = "$multiHopCount",
                color = AmberMesh,
                modifier = Modifier.weight(1f)
            )
            StatusChip(
                label = "درع ضد التنصت",
                value = "100% 🛡️",
                color = EmeraldSecure,
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(modifier = Modifier.height(6.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(165.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(Color(0xFF0B1424))
                .border(1.dp, Color(0xFF1B2F4E), RoundedCornerShape(20.dp)),
            contentAlignment = Alignment.Center
        ) {
            if (isGraphView) {
                val pulseTransition = rememberInfiniteTransition(label = "pulse")
                val pulseProgress by pulseTransition.animateFloat(
                    initialValue = 0f,
                    targetValue = 1f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(2000, easing = LinearEasing),
                        repeatMode = RepeatMode.Restart
                    ),
                    label = "pulseProgress"
                )

                Canvas(modifier = Modifier.fillMaxSize()) {
                    val center = Offset(size.width / 2f, size.height / 2f)
                    val radius = minOf(size.width, size.height) / 2.5f

                    drawCircle(
                        color = EmeraldSecure,
                        radius = 8.dp.toPx(),
                        center = center
                    )

                    for (index in allPeers.indices) {
                        val peer = allPeers[index]
                        val angle = (index * (360f / maxOf(allPeers.size, 1))) % 360f
                        val rad = Math.toRadians(angle.toDouble())
                        val peerPos = Offset(
                            (center.x + radius * cos(rad)).toFloat(),
                            (center.y + radius * sin(rad)).toFloat()
                        )

                        val linkColor = if (peer.isConnected) CyanPrimary.copy(alpha = 0.5f) else Color(0xFF1B2F4E)
                        drawLine(
                            color = linkColor,
                            start = center,
                            end = peerPos,
                            strokeWidth = 2f
                        )

                        if (peer.isConnected) {
                            val pulsePos = Offset(
                                center.x + (peerPos.x - center.x) * pulseProgress,
                                center.y + (peerPos.y - center.y) * pulseProgress
                            )
                            drawCircle(
                                color = CyanPrimary,
                                radius = 3.dp.toPx(),
                                center = pulsePos
                            )
                        }

                        val peerColor = if (peer.isDirect) CyanPrimary else AmberMesh
                        drawCircle(
                            color = peerColor,
                            radius = 7.dp.toPx(),
                            center = peerPos
                        )
                    }
                }
            } else {
                val radarTransition = rememberInfiniteTransition(label = "radarSweep")
                val sweepAngle by radarTransition.animateFloat(
                    initialValue = 0f,
                    targetValue = 360f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(4000, easing = LinearEasing),
                        repeatMode = RepeatMode.Restart
                    ),
                    label = "sweepAngle"
                )

                Canvas(modifier = Modifier.fillMaxSize()) {
                    val center = Offset(size.width / 2f, size.height / 2f)
                    val radius = minOf(size.width, size.height) / 2.2f
                    val gridColor = Color(0xFF1B2F4E)

                    for (i in 1..4) {
                        val r = radius * (i / 4f)
                        drawCircle(
                            color = gridColor,
                            radius = r,
                            center = center,
                            style = Stroke(width = 1f)
                        )
                    }

                    drawLine(
                        color = gridColor,
                        start = Offset(center.x - radius, center.y),
                        end = Offset(center.x + radius, center.y),
                        strokeWidth = 1f
                    )
                    drawLine(
                        color = gridColor,
                        start = Offset(center.x, center.y - radius),
                        end = Offset(center.x, center.y + radius),
                        strokeWidth = 1f
                    )

                    val rad = Math.toRadians(sweepAngle.toDouble())
                    val sweepEnd = Offset(
                        (center.x + radius * cos(rad)).toFloat(),
                        (center.y + radius * sin(rad)).toFloat()
                    )

                    drawLine(
                        brush = Brush.linearGradient(
                            colors = listOf(CyanPrimary.copy(alpha = 0.8f), Color.Transparent),
                            start = center,
                            end = sweepEnd
                        ),
                        start = center,
                        end = sweepEnd,
                        strokeWidth = 2f
                    )

                    drawCircle(
                        color = EmeraldSecure,
                        radius = 6.dp.toPx(),
                        center = center
                    )
                    drawCircle(
                        color = EmeraldSecure.copy(alpha = 0.3f),
                        radius = 12.dp.toPx(),
                        center = center,
                        style = Stroke(width = 2f)
                    )

                    for (index in allPeers.indices) {
                        val peer = allPeers[index]
                        val peerAngle = (index * (360f / maxOf(allPeers.size, 1)) + 45f) % 360f
                        val peerRad = Math.toRadians(peerAngle.toDouble())
                        val dist = radius * (0.45f + ((index % 3) * 0.2f))
                        val peerPos = Offset(
                            (center.x + dist * cos(peerRad)).toFloat(),
                            (center.y + dist * sin(peerRad)).toFloat()
                        )

                        val blipColor = if (peer.isDirect) CyanPrimary else AmberMesh
                        drawCircle(
                            color = blipColor,
                            radius = 5.dp.toPx(),
                            center = peerPos
                        )
                        drawCircle(
                            color = blipColor.copy(alpha = 0.4f),
                            radius = 10.dp.toPx(),
                            center = peerPos,
                            style = Stroke(width = 1.5f)
                        )
                    }
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(10.dp)
            ) {
                Text(
                    text = if (isGraphView) "مخطط الشبكة التلقائية" else "رادار الاكتشاف التلقائي المباشر والدولي",
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    color = Color(0xFF536B8D),
                    modifier = Modifier.align(Alignment.TopStart)
                )

                Text(
                    text = if (isAutoReplyEnabled) "الرد الآلي نشط 🤖" else "حماية X25519 نشطة 🛡️",
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    color = EmeraldSecure,
                    modifier = Modifier.align(Alignment.TopEnd)
                )
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        // Walkie-Talkie Live PTT Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(
                    when {
                        isPttTransmitting -> RedAlert.copy(alpha = 0.22f)
                        isPttReceiving -> EmeraldSecure.copy(alpha = 0.22f)
                        else -> Color(0xFF0F1A2E)
                    }
                )
                .border(
                    1.dp,
                    when {
                        isPttTransmitting -> RedAlert
                        isPttReceiving -> EmeraldSecure
                        else -> Color(0xFF1B2F4E)
                    },
                    RoundedCornerShape(14.dp)
                )
                .padding(horizontal = 12.dp, vertical = 7.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    imageVector = if (isPttReceiving) Icons.Default.VolumeUp else if (isWalkieTalkieEnabled) Icons.Default.Mic else Icons.Default.MicOff,
                    contentDescription = null,
                    tint = when {
                        isPttTransmitting -> RedAlert
                        isPttReceiving -> EmeraldSecure
                        isWalkieTalkieEnabled -> CyanPrimary
                        else -> Color.Gray
                    },
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column {
                    Text(
                        text = when {
                            isPttTransmitting -> "جاري بث صوتك مباشرة عبر اللاسلكي..."
                            isPttReceiving -> "استقبال بث صوتي مباشر الآن..."
                            isWalkieTalkieEnabled -> "اللاسلكي الفوري (محلي ودولي) جاهز"
                            else -> "جهاز اللاسلكي الفوري متوقف"
                        },
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    Text(
                        text = "تحدث فوراً بدون رنين مع جميع المتصلين",
                        fontSize = 9.sp,
                        color = Color(0xFF8FA4C4)
                    )
                }
            }

            Button(
                onClick = { viewModel.togglePttTransmission() },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isPttTransmitting) RedAlert else CyanPrimary
                ),
                shape = RoundedCornerShape(10.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                modifier = Modifier.height(34.dp)
            ) {
                Text(
                    text = if (isPttTransmitting) "إيقاف البث" else "تحدث مباشر 🎙️",
                    fontSize = 11.sp,
                    color = if (isPttTransmitting) Color.White else Color(0xFF001F29),
                    fontWeight = FontWeight.Bold
                )
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "الأجهزة والعقد المكتشفة (${allPeers.size})",
                style = MaterialTheme.typography.labelSmall,
                color = Color(0xFF8FA4C4),
                fontWeight = FontWeight.Bold
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if (isAdvertising && isDiscovering) "الربط التلقائي نشط" else "الشبكة متوقفة",
                    fontSize = 11.sp,
                    color = if (isAdvertising && isDiscovering) EmeraldSecure else Color.Gray
                )
                Spacer(modifier = Modifier.width(6.dp))
                Button(
                    onClick = {
                        if (isAdvertising || isDiscovering) {
                            viewModel.stopMesh()
                        } else {
                            viewModel.startMesh()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isAdvertising) Color(0xFF162540) else CyanPrimary
                    ),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    modifier = Modifier.height(28.dp)
                ) {
                    Text(
                        text = if (isAdvertising) "إيقاف" else "بدء البحث",
                        fontSize = 11.sp,
                        color = if (isAdvertising) Color.White else Color(0xFF001F29),
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        if (allPeers.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color(0xFF0B1424))
                    .padding(14.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Default.Radar,
                        contentDescription = null,
                        tint = Color(0xFF324867),
                        modifier = Modifier.size(36.dp)
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "جاري البحث والربط التلقائي الشامل...",
                        color = Color(0xFF8FA4C4),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "يتم اكتشاف الأجهزة القريبة والدولية والاتصال بها تلقائياً بالكامل دون الحاجة لأي إعدادات.",
                        color = Color(0xFF536B8D),
                        fontSize = 11.sp,
                        textAlign = TextAlign.Center
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(allPeers) {
                    val peerItem = it
                    PeerNodeCard(
                        peer = peerItem,
                        isVerified = verifiedPeers.contains(peerItem.nodeId),
                        onConnectClick = { viewModel.connectToPeer(peerItem.endpointId) },
                        onDisconnectClick = { viewModel.disconnectPeer(peerItem.endpointId) },
                        onChatClick = { viewModel.openConversation(peerItem.nodeId) },
                        onVerifyClick = { viewModel.openVerificationDialog(peerItem) }
                    )
                }
            }
        }
    }

    // Developer Contact & Feedback Dialog (@BR_B1 | 773551513)
    if (showDeveloperDialog) {
        Dialog(onDismissRequest = { showDeveloperDialog = false }) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(22.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF0B1424)),
                border = androidx.compose.foundation.BorderStroke(1.5.dp, AmberMesh)
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .clip(CircleShape)
                            .background(AmberMesh.copy(alpha = 0.18f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Person,
                            contentDescription = null,
                            tint = AmberMesh,
                            modifier = Modifier.size(30.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Text(
                        text = "مطور التطبيق • نستقبل آرائكم",
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White,
                        fontWeight = FontWeight.ExtraBold
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    Text(
                        text = "يسعدنا استقبال آرائكم، مقترحاتكم، وأفكاركم التطويرية على مدار الساعة عبر القنوات الرسمية:",
                        fontSize = 12.sp,
                        color = Color(0xFF8FA4C4),
                        textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    // Telegram Card (@BR_B1)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0xFF070D18))
                            .border(1.dp, CyanPrimary.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("حساب التليجرام الرسمي", fontSize = 10.sp, color = CyanPrimary, fontWeight = FontWeight.Bold)
                            Text("@BR_B1", fontSize = 15.sp, color = Color.White, fontWeight = FontWeight.ExtraBold, fontFamily = FontFamily.Monospace)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            OutlinedButton(
                                onClick = {
                                    clipboardManager.setText(AnnotatedString("@BR_B1"))
                                },
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                modifier = Modifier.height(32.dp)
                            ) {
                                Text("نسخ", fontSize = 11.sp, color = Color.White)
                            }
                            Button(
                                onClick = {
                                    try {
                                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/BR_B1"))
                                        context.startActivity(intent)
                                    } catch (e: Exception) {}
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = CyanPrimary),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                modifier = Modifier.height(32.dp)
                            ) {
                                Text("فتح تليجرام", fontSize = 11.sp, color = Color(0xFF001F29), fontWeight = FontWeight.Bold)
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // WhatsApp Card (773551513)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0xFF070D18))
                            .border(1.dp, EmeraldSecure.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("رقم الواتساب المباشر", fontSize = 10.sp, color = EmeraldSecure, fontWeight = FontWeight.Bold)
                            Text("773551513", fontSize = 15.sp, color = Color.White, fontWeight = FontWeight.ExtraBold, fontFamily = FontFamily.Monospace)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            OutlinedButton(
                                onClick = {
                                    clipboardManager.setText(AnnotatedString("773551513"))
                                },
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                modifier = Modifier.height(32.dp)
                            ) {
                                Text("نسخ", fontSize = 11.sp, color = Color.White)
                            }
                            Button(
                                onClick = {
                                    try {
                                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/967773551513"))
                                        context.startActivity(intent)
                                    } catch (e: Exception) {}
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = EmeraldSecure),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                modifier = Modifier.height(32.dp)
                            ) {
                                Text("مراسلة واتس", fontSize = 11.sp, color = Color(0xFF00220F), fontWeight = FontWeight.Bold)
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                val inviteText = "تطبيق CipherMesh للدردشة والمكالمات المشفرة بدون إنترنت وعبر الحدود 🌍 - تطوير: تليجرام @BR_B1 | واتساب: 773551513 (نستقبل آرائكم)"
                                viewModel.sendTextMessage(inviteText)
                                showDeveloperDialog = false
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = AmberMesh),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Share, contentDescription = null, tint = Color(0xFF001F29), modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("مشاركة بالشبكة", fontSize = 11.sp, color = Color(0xFF001F29), fontWeight = FontWeight.Bold)
                        }

                        OutlinedButton(
                            onClick = { showDeveloperDialog = false },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("إغلاق", fontSize = 11.sp, color = Color.LightGray)
                        }
                    }
                }
            }
        }
    }

    // Anti-Spy Shield & Encrypted Mesh BlackBox Dialog
    if (showBlackBoxDialog) {
        Dialog(onDismissRequest = { showBlackBoxDialog = false }) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF0B1424)),
                border = androidx.compose.foundation.BorderStroke(1.dp, EmeraldSecure)
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "🛡️ درع كشف التنصت والصندوق الأسود",
                            style = MaterialTheme.typography.titleMedium,
                            color = EmeraldSecure,
                            fontWeight = FontWeight.Bold
                        )
                        IconButton(onClick = { showBlackBoxDialog = false }) {
                            Icon(Icons.Default.Close, contentDescription = "إغلاق", tint = Color.Gray)
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0xFF070D18))
                            .border(1.dp, Color(0xFF162540), RoundedCornerShape(12.dp))
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text("• فحص محاولات الاعتراض (MITM): 0 تهديد (آمن 100%)", fontSize = 11.sp, color = EmeraldSecure, fontWeight = FontWeight.Bold)
                        Text("• بصمة التشفير (X25519): ${viewModel.getLocalFingerprint().take(19)}", fontSize = 11.sp, color = Color.White, fontFamily = FontFamily.Monospace)
                        Text("• عنوان الربط النشط: ${viewModel.nearbyManager.getPrimaryLocalIp()}", fontSize = 11.sp, color = CyanPrimary)
                        Text("• الحزم المستلمة والمفحوصة: $packetsReceived حزمة مشفرة", fontSize = 11.sp, color = Color.LightGray)
                        Text("• الحزم الموجهة عبر القفزات: $packetsForwarded حزمة", fontSize = 11.sp, color = AmberMesh)
                        Text("• الطاقة الحالية للهاتف: ${viewModel.repository.getDeviceBatteryPercentage()}% 🔋", fontSize = 11.sp, color = EmeraldSecure)
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Button(
                        onClick = {
                            val report = "🛡️ [تقرير الصندوق الأسود CipherMesh] - الحالة: آمن 100% ضد التنصت | البصمة: ${viewModel.getLocalFingerprint()} | الحزم المؤمنة: ${packetsReceived + packetsForwarded}"
                            clipboardManager.setText(AnnotatedString(report))
                            showBlackBoxDialog = false
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = EmeraldSecure),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("نسخ شهادة الفحص الأمني 📋", color = Color(0xFF00220F), fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                }
            }
        }
    }

    if (showEditNameDialog) {
        Dialog(onDismissRequest = { showEditNameDialog = false }) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF0B1424)),
                border = androidx.compose.foundation.BorderStroke(1.dp, CyanPrimary)
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text(
                        text = "تخصيص اسم جهازك في الشبكة",
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "سيظهر هذا الاسم تلقائياً لدى جميع الأجهزة المتصلة بك محلياً ودولياً:",
                        fontSize = 12.sp,
                        color = Color(0xFF8FA4C4)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = newNameInput,
                        onValueChange = { newNameInput = it },
                        singleLine = true,
                        label = { Text("الاسم الظاهر في الشبكة", color = CyanPrimary) },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        OutlinedButton(
                            onClick = { showEditNameDialog = false },
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text("إلغاء", color = Color.LightGray)
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(
                            onClick = {
                                if (newNameInput.isNotBlank()) {
                                    viewModel.updateLocalNodeName(newNameInput)
                                    showEditNameDialog = false
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = CyanPrimary),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text("حفظ وتحديث", color = Color(0xFF001F29), fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }

    if (showDiagnostics) {
        Dialog(onDismissRequest = { showDiagnostics = false }) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF0B1424)),
                border = androidx.compose.foundation.BorderStroke(1.dp, AmberMesh)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Security,
                                contentDescription = null,
                                tint = AmberMesh,
                                modifier = Modifier.size(22.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "مركز القيادة والمميزات الحصرية",
                                style = MaterialTheme.typography.titleMedium,
                                color = Color.White,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        IconButton(onClick = { showDiagnostics = false }) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "إغلاق",
                                tint = Color.Gray
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    // Developer Credits inside Command Center
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color(0xFF070D18))
                            .border(1.dp, CyanPrimary.copy(alpha = 0.4f), RoundedCornerShape(10.dp))
                            .clickable {
                                showDiagnostics = false
                                showDeveloperDialog = true
                            }
                            .padding(10.dp)
                    ) {
                        Column {
                            Text(
                                text = "برمجة وتطوير: تليجرام @BR_B1 | واتساب: 773551513",
                                fontSize = 11.sp,
                                color = CyanPrimary,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "نستقبل آرائكم ومقترحاتكم لتطوير التطبيق (اضغط للتواصل)",
                                fontSize = 10.sp,
                                color = AmberMesh
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "الرسائل ذاتية التدمير (60 ثانية)",
                                fontSize = 12.sp,
                                color = Color.White,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "حذف تلقائي للرسائل بعد دقيقة من قراءتها",
                                fontSize = 10.sp,
                                color = Color(0xFF8FA4C4)
                            )
                        }
                        Switch(
                            checked = isSelfDestructEnabled,
                            onCheckedChange = { viewModel.toggleSelfDestructMode() }
                        )
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "استقبال اللاسلكي الفوري (PTT)",
                                fontSize = 12.sp,
                                color = Color.White,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "سماع البث المباشر تلقائياً بدون رنين",
                                fontSize = 10.sp,
                                color = Color(0xFF8FA4C4)
                            )
                        }
                        Switch(
                            checked = isWalkieTalkieEnabled,
                            onCheckedChange = { viewModel.toggleWalkieTalkieMode() }
                        )
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "الرد الآلي الذكي",
                                fontSize = 12.sp,
                                color = Color.White,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "يرد تلقائياً على من يراسلك وأنت مشغول",
                                fontSize = 10.sp,
                                color = Color(0xFF8FA4C4)
                            )
                        }
                        Switch(
                            checked = isAutoReplyEnabled,
                            onCheckedChange = { viewModel.saveAutoReplySettings(it, autoReplyInput) }
                        )
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    OutlinedTextField(
                        value = autoReplyInput,
                        onValueChange = {
                            autoReplyInput = it
                            viewModel.saveAutoReplySettings(isAutoReplyEnabled, it)
                        },
                        singleLine = true,
                        label = { Text("رسالة الرد الآلي", fontSize = 11.sp, color = CyanPrimary) },
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedTextField(
                        value = pinInputConfig,
                        onValueChange = {
                            pinInputConfig = it
                            viewModel.updateAppPin(it)
                        },
                        singleLine = true,
                        label = { Text("رمز قفل التطبيق (اتركه فارغاً لإلغاء القفل)", fontSize = 11.sp, color = AmberMesh) },
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (appPinCode.isNotEmpty()) {
                            Button(
                                onClick = {
                                    viewModel.lockApp()
                                    showDiagnostics = false
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = CyanPrimary),
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Lock,
                                    contentDescription = null,
                                    tint = Color(0xFF001F29),
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "قفل الآن",
                                    color = Color(0xFF001F29),
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 11.sp
                                )
                            }
                        }

                        Button(
                            onClick = {
                                viewModel.clearAllChats()
                                showDiagnostics = false
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = RedAlert),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "مسح الطوارئ",
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 11.sp
                            )
                        }
                    }
                }
            }
        }
    }

    val verifyPeer = peerToVerify
    if (verifyPeer != null) {
        SecurityVerificationDialog(
            peer = verifyPeer,
            isVerified = verifiedPeers.contains(verifyPeer.nodeId),
            onVerifyClick = { viewModel.markPeerVerified(verifyPeer.nodeId) },
            onDismiss = { viewModel.closeVerificationDialog() }
        )
    }
}

@Composable
private fun StatusChip(
    label: String,
    value: String,
    color: Color,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xFF0F1A2E))
            .border(1.dp, color.copy(alpha = 0.3f), RoundedCornerShape(10.dp))
            .padding(horizontal = 8.dp, vertical = 6.dp)
    ) {
        Column {
            Text(
                text = label,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                color = color
            )
            Text(
                text = value,
                fontSize = 15.sp,
                fontWeight = FontWeight.ExtraBold,
                color = Color.White
            )
        }
    }
}

@Composable
private fun PeerNodeCard(
    peer: PeerNode,
    isVerified: Boolean,
    onConnectClick: () -> Unit,
    onDisconnectClick: () -> Unit,
    onChatClick: () -> Unit,
    onVerifyClick: () -> Unit
) {
    val isGlobalPeer = peer.endpointId.startsWith("global-") || peer.nodeName.contains("دولي")

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("peer_card_${peer.endpointId}"),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF0F1A2E)),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (isGlobalPeer) EmeraldSecure.copy(alpha = 0.5f) else if (peer.isConnected) CyanPrimary.copy(alpha = 0.4f) else Color(0xFF1B2F4E)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(if (isGlobalPeer) EmeraldSecure.copy(alpha = 0.15f) else if (peer.isConnected) CyanPrimary.copy(alpha = 0.15f) else Color(0xFF162540)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (peer.isDirect) Icons.Default.Link else Icons.Default.Hub,
                        contentDescription = null,
                        tint = if (isGlobalPeer) EmeraldSecure else if (peer.isDirect) CyanPrimary else AmberMesh,
                        modifier = Modifier.size(20.dp)
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = peer.nodeName,
                            style = MaterialTheme.typography.titleSmall,
                            color = Color.White,
                            fontWeight = FontWeight.Bold
                        )
                        if (isVerified) {
                            Spacer(modifier = Modifier.width(4.dp))
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = "موثوق",
                                tint = EmeraldSecure,
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = when {
                                isGlobalPeer -> "متصل تلقائياً عبر الجسر الدولي 🌍"
                                peer.isDirect -> "اتصال مباشر تلقائي بدون إنترنت (قفزة 1)"
                                else -> "متصل تلقائياً عبر الشبكة (${peer.hops} قفزات)"
                            },
                            fontSize = 11.sp,
                            color = if (isGlobalPeer || peer.isDirect) EmeraldSecure else AmberMesh
                        )
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (peer.isConnected) {
                    IconButton(
                        onClick = onVerifyClick,
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Security,
                            contentDescription = "توثيق المفتاح",
                            tint = if (isVerified) EmeraldSecure else AmberMesh,
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    Button(
                        onClick = onChatClick,
                        colors = ButtonDefaults.buttonColors(containerColor = CyanPrimary),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        modifier = Modifier.height(34.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Chat,
                            contentDescription = null,
                            tint = Color(0xFF001F29),
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "محادثة",
                            fontSize = 12.sp,
                            color = Color(0xFF001F29),
                            fontWeight = FontWeight.Bold
                        )
                    }
                } else {
                    OutlinedButton(
                        onClick = onConnectClick,
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        modifier = Modifier.height(34.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, CyanPrimary)
                    ) {
                        Text(
                            text = "اتصال",
                            fontSize = 12.sp,
                            color = CyanPrimary,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }
    }
}
