package com.example.ui.screens

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.platform.testTag
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
    val isAdvertising by viewModel.isAdvertising.collectAsState()
    val isDiscovering by viewModel.isDiscovering.collectAsState()
    val discoveredPeers by viewModel.discoveredPeers.collectAsState()
    val connectedPeers by viewModel.connectedPeers.collectAsState()
    val packetsForwarded by viewModel.packetsForwarded.collectAsState()
    val packetsReceived by viewModel.packetsReceived.collectAsState()
    val verifiedPeers by viewModel.verifiedPeers.collectAsState()
    val isGraphView by viewModel.isGraphView.collectAsState()
    val peerToVerify by viewModel.peerToVerify.collectAsState()

    var showDiagnostics by remember { mutableStateOf(false) }

    val allPeers = remember(discoveredPeers, connectedPeers) {
        val map = discoveredPeers.toMutableMap()
        map.putAll(connectedPeers)
        map.values.toList()
    }

    val multiHopCount = allPeers.count { !it.isDirect }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF070D18))
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(if (isAdvertising || isDiscovering) EmeraldSecure else RedAlert)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "شبكة اتصال موزعة تلقائية 100% بدون إنترنت",
                        style = MaterialTheme.typography.labelSmall,
                        color = EmeraldSecure,
                        fontWeight = FontWeight.Bold
                    )
                }
                Text(
                    text = "${viewModel.repository.localNodeName} [${viewModel.repository.localNodeId.take(4)}]",
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White,
                    fontWeight = FontWeight.Bold
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = { viewModel.toggleGraphView() },
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(Color(0xFF0F1A2E))
                        .testTag("toggle_graph_button")
                ) {
                    Icon(
                        imageVector = if (isGraphView) Icons.Default.Radar else Icons.Default.Hub,
                        contentDescription = "تبديل عرض الشبكة",
                        tint = CyanPrimary
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                IconButton(
                    onClick = { showDiagnostics = true },
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(Color(0xFF0F1A2E))
                        .testTag("diagnostics_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Security,
                        contentDescription = "حالة الشبكة",
                        tint = AmberMesh
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

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
                label = "عبر القفزات",
                value = "$multiHopCount",
                color = AmberMesh,
                modifier = Modifier.weight(1f)
            )
            StatusChip(
                label = "الحزم الموجهة",
                value = "$packetsForwarded",
                color = EmeraldSecure,
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(250.dp)
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
                    .padding(12.dp)
            ) {
                Text(
                    text = if (isGraphView) "مخطط الشبكة متعددة القفزات" else "رادار الاكتشاف التلقائي عبر الشبكات",
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    color = Color(0xFF536B8D),
                    modifier = Modifier.align(Alignment.TopStart)
                )

                Text(
                    text = "تشفير AES-256 نشط",
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    color = EmeraldSecure,
                    modifier = Modifier.align(Alignment.TopEnd)
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

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

        Spacer(modifier = Modifier.height(8.dp))

        if (allPeers.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color(0xFF0B1424))
                    .padding(20.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Default.Radar,
                        contentDescription = null,
                        tint = Color(0xFF324867),
                        modifier = Modifier.size(48.dp)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "جاري المسح والربط التلقائي عبر الشبكات...",
                        color = Color(0xFF8FA4C4),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "يتصل التطبيق تلقائياً بأي جهاز عبر البلوتوث أو الواي فاي المحلي أو الشبكات المرتبطة دون الحاجة لإدخال أي إعدادات.",
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
                    PeerNodeCard(
                        peer = it,
                        isVerified = verifiedPeers.contains(it.nodeId),
                        onConnectClick = { viewModel.connectToPeer(it.endpointId) },
                        onDisconnectClick = { viewModel.disconnectPeer(it.endpointId) },
                        onChatClick = { viewModel.openConversation(it.nodeId) },
                        onVerifyClick = { viewModel.openVerificationDialog(it) }
                    )
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
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF1B2F4E))
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .background(AmberMesh.copy(alpha = 0.15f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Hub,
                                    contentDescription = null,
                                    tint = AmberMesh,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = "محرك الشبكة والتوجيه التلقائي",
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

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(EmeraldSecure.copy(alpha = 0.12f))
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.WifiOff,
                            contentDescription = null,
                            tint = EmeraldSecure,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "اكتشاف تلقائي متعدد القفزات (حتى 12 قفزة) عبر الشبكات والأسواق بدون إنترنت",
                            fontSize = 12.sp,
                            color = EmeraldSecure,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0xFF070D18))
                            .border(1.dp, Color(0xFF162540), RoundedCornerShape(12.dp))
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        DiagnosticRow("الجهاز المحلي", "${viewModel.repository.localNodeName} (${viewModel.repository.localNodeId})")
                        DiagnosticRow("عنوان الشبكة المحلي", viewModel.repository.nearbyManager.getPrimaryLocalIp())
                        DiagnosticRow("آلية الربط", "مسح تلقائي للشبكات + توجيه متعدد القفزات")
                        DiagnosticRow("خوارزمية التشفير", "AES-256-GCM / X25519")
                        DiagnosticRow("الحزم الموجهة", "$packetsForwarded حزمة معاد توجيهها")
                        DiagnosticRow("الحزم المستلمة", "$packetsReceived حزمة تمت معالجتها")
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Text(
                        text = "جدول المسارات النشطة",
                        fontSize = 11.sp,
                        color = CyanPrimary,
                        fontWeight = FontWeight.Bold
                    )

                    Spacer(modifier = Modifier.height(6.dp))

                    if (connectedPeers.isEmpty()) {
                        Text(
                            text = "لا توجد عقد متصلة حالياً",
                            fontSize = 12.sp,
                            color = Color.Gray
                        )
                    } else {
                        connectedPeers.values.forEach {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 3.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Color(0xFF0F1A2E))
                                    .padding(horizontal = 10.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(if (it.isDirect) EmeraldSecure else AmberMesh)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "${it.nodeName} • القفزات: ${it.hops}",
                                    fontSize = 12.sp,
                                    color = CyanPrimary,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    peerToVerify?.let {
        SecurityVerificationDialog(
            peer = it,
            isVerified = verifiedPeers.contains(it.nodeId),
            onVerifyClick = { viewModel.markPeerVerified(it.nodeId) },
            onDismiss = { viewModel.closeVerificationDialog() }
        )
    }
}

@Composable
private fun DiagnosticRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            fontSize = 11.sp,
            color = Color(0xFF8FA4C4)
        )
        Text(
            text = value,
            fontSize = 11.sp,
            color = Color.White,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.SemiBold
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
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("peer_card_${peer.endpointId}"),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF0F1A2E)),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (peer.isConnected) CyanPrimary.copy(alpha = 0.4f) else Color(0xFF1B2F4E)
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
                        .background(if (peer.isConnected) CyanPrimary.copy(alpha = 0.15f) else Color(0xFF162540)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (peer.isDirect) Icons.Default.Link else Icons.Default.Hub,
                        contentDescription = null,
                        tint = if (peer.isDirect) CyanPrimary else AmberMesh,
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
                            text = if (peer.isDirect) "اتصال مباشر تلقائي (قفزة 1)" else "متصل عبر الشبكة (${peer.hops} قفزات)",
                            fontSize = 11.sp,
                            color = if (peer.isDirect) EmeraldSecure else AmberMesh
                        )
                        Text(
                            text = "•",
                            fontSize = 11.sp,
                            color = Color.DarkGray
                        )
                        Text(
                            text = "${peer.signalStrengthDbm} dBm",
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            color = Color(0xFF536B8D)
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
