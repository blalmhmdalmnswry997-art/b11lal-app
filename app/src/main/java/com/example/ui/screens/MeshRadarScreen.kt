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
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.mesh.PeerNode
import com.example.ui.AppScreen
import com.example.ui.MainViewModel
import com.example.ui.components.MeshDiagnosticsDialog
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

    // Combine discovered and connected peers for radar plotting
    val allPeers = remember(discoveredPeers, connectedPeers) {
        val map = discoveredPeers.toMutableMap()
        connectedPeers.forEach { (k, v) -> map[k] = v }
        map.values.toList()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF070D18))
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        // Top Tactical Status Bar
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
                        text = "100% OFFLINE P2P MESH",
                        style = MaterialTheme.typography.labelSmall,
                        color = EmeraldSecure,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
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
                        contentDescription = "Toggle Topology View",
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
                        contentDescription = "Diagnostics",
                        tint = AmberMesh
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Mesh Status Metrics Chips
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            StatusChip(
                label = "DIRECT PEERS",
                value = "${connectedPeers.size}",
                color = CyanPrimary,
                modifier = Modifier.weight(1f)
            )
            StatusChip(
                label = "DISCOVERED",
                value = "${discoveredPeers.size}",
                color = AmberMesh,
                modifier = Modifier.weight(1f)
            )
            StatusChip(
                label = "FORWARDED",
                value = "$packetsForwarded",
                color = EmeraldSecure,
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Center Visual Area: Animated Tactical Radar or Topology Mesh Graph
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(260.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(Color(0xFF0B1424))
                .border(1.dp, Color(0xFF1B2F4E), RoundedCornerShape(20.dp)),
            contentAlignment = Alignment.Center
        ) {
            if (isGraphView) {
                TopologyGraphCanvas(
                    localNodeName = viewModel.repository.localNodeName,
                    peers = allPeers
                )
            } else {
                TacticalRadarCanvas(peers = allPeers)
            }

            // Radar overlay HUD
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(12.dp)
            ) {
                Text(
                    text = if (isGraphView) "TOPOLOGY GRAPH: P2P_CLUSTER" else "BLE & WI-FI DIRECT RADAR",
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    color = Color(0xFF536B8D),
                    modifier = Modifier.align(Alignment.TopStart)
                )

                Text(
                    text = "AES-256-GCM ACTIVE",
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    color = EmeraldSecure,
                    modifier = Modifier.align(Alignment.TopEnd)
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Section Title: Discovered & Connected Nodes
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "NEIGHBORHOOD NODES (${allPeers.size})",
                style = MaterialTheme.typography.labelSmall,
                color = Color(0xFF8FA4C4),
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if (isAdvertising && isDiscovering) "Auto-Mesh Active" else "Mesh Paused",
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
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    modifier = Modifier.height(28.dp)
                ) {
                    Text(
                        text = if (isAdvertising) "Stop" else "Start Scan",
                        fontSize = 11.sp,
                        color = if (isAdvertising) Color.White else Color(0xFF001F29),
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Peer Node Cards List
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
                        text = "Scanning for nearby devices...",
                        color = Color(0xFF8FA4C4),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Make sure other phones have CipherMesh open. No Wi-Fi router or cellular needed.",
                        color = Color(0xFF536B8D),
                        fontSize = 11.sp,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
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
                items(allPeers) { peer ->
                    PeerNodeCard(
                        peer = peer,
                        isVerified = verifiedPeers.contains(peer.nodeId),
                        onConnectClick = { viewModel.connectToPeer(peer.endpointId) },
                        onDisconnectClick = { viewModel.disconnectPeer(peer.endpointId) },
                        onChatClick = { viewModel.openConversation(peer.nodeId) },
                        onVerifyClick = { viewModel.openVerificationDialog(peer) }
                    )
                }
            }
        }
    }

    // Diagnostics Dialog
    if (showDiagnostics) {
        MeshDiagnosticsDialog(
            localNodeId = viewModel.repository.localNodeId,
            localNodeName = viewModel.repository.localNodeName,
            connectedPeers = connectedPeers,
            packetsForwarded = packetsForwarded,
            packetsReceived = packetsReceived,
            onDismiss = { showDiagnostics = false }
        )
    }

    // Key Verification Dialog
    peerToVerify?.let { peer ->
        SecurityVerificationDialog(
            peer = peer,
            isVerified = verifiedPeers.contains(peer.nodeId),
            onVerifyClick = { viewModel.markPeerVerified(peer.nodeId) },
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
                        imageVector = if (peer.isConnected) Icons.Default.Link else Icons.Default.Wifi,
                        contentDescription = null,
                        tint = if (peer.isConnected) CyanPrimary else Color.Gray,
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
                                contentDescription = "Verified",
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
                            text = if (peer.isConnected) "Connected (1-hop direct)" else "Discovered",
                            fontSize = 11.sp,
                            color = if (peer.isConnected) EmeraldSecure else Color(0xFF8FA4C4)
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
                            contentDescription = "Verify Key",
                            tint = if (isVerified) EmeraldSecure else AmberMesh,
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    Button(
                        onClick = onChatClick,
                        colors = ButtonDefaults.buttonColors(containerColor = CyanPrimary),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 4.dp),
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
                            text = "Chat",
                            fontSize = 12.sp,
                            color = Color(0xFF001F29),
                            fontWeight = FontWeight.Bold
                        )
                    }
                } else {
                    OutlinedButton(
                        onClick = onConnectClick,
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        modifier = Modifier.height(34.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, CyanPrimary)
                    ) {
                        Text(
                            text = "Connect",
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

/**
 * Animated Tactical Sonar Radar Canvas.
 */
@Composable
private fun TacticalRadarCanvas(peers: List<PeerNode>) {
    val transition = rememberInfiniteTransition(label = "radarSweep")
    val angle by transition.animateFloat(
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

        // Draw concentric range circles
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

        // Draw crosshair axes
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

        // Draw sweep line with glowing gradient
        val rad = Math.toRadians(angle.toDouble())
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

        // Center dot: Local Node
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

        // Plot Peer Blips around center
        peers.forEachIndexed { index, peer ->
            val peerAngle = (index * (360f / maxOf(peers.size, 1)) + 45f) % 360f
            val peerRad = Math.toRadians(peerAngle.toDouble())
            val dist = radius * (0.45f + ((index % 3) * 0.2f))
            val peerPos = Offset(
                (center.x + dist * cos(peerRad)).toFloat(),
                (center.y + dist * sin(peerRad)).toFloat()
            )

            val blipColor = if (peer.isConnected) CyanPrimary else AmberMesh
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

/**
 * Animated Topology Graph Canvas showing mesh links and packet pulses.
 */
@Composable
private fun TopologyGraphCanvas(
    localNodeName: String,
    peers: List<PeerNode>
) {
    val transition = rememberInfiniteTransition(label = "pulse")
    val pulseProgress by transition.animateFloat(
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

        // Draw local hub center
        drawCircle(
            color = EmeraldSecure,
            radius = 8.dp.toPx(),
            center = center
        )

        peers.forEachIndexed { index, peer ->
            val angle = (index * (360f / maxOf(peers.size, 1))) % 360f
            val rad = Math.toRadians(angle.toDouble())
            val peerPos = Offset(
                (center.x + radius * cos(rad)).toFloat(),
                (center.y + radius * sin(rad)).toFloat()
            )

            // Draw link line between local node and peer
            val linkColor = if (peer.isConnected) CyanPrimary.copy(alpha = 0.5f) else Color(0xFF1B2F4E)
            drawLine(
                color = linkColor,
                start = center,
                end = peerPos,
                strokeWidth = 2f
            )

            // Draw animated packet pulse dot along link line
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

            // Draw peer node circle
            val peerColor = if (peer.isConnected) CyanPrimary else AmberMesh
            drawCircle(
                color = peerColor,
                radius = 7.dp.toPx(),
                center = peerPos
            )
        }
    }
}
