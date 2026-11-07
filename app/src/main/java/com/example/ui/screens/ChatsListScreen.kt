package com.example.ui.screens

import androidx.compose.foundation.background
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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.mesh.ChatMessage
import com.example.mesh.MediaType
import com.example.mesh.MessageStatus
import com.example.ui.AppScreen
import com.example.ui.MainViewModel
import com.example.ui.theme.AmberMesh
import com.example.ui.theme.CyanPrimary
import com.example.ui.theme.EmeraldSecure
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ChatsListScreen(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier
) {
    val conversations by viewModel.conversations.collectAsState()
    val connectedPeers by viewModel.connectedPeers.collectAsState()
    val verifiedPeers by viewModel.verifiedPeers.collectAsState()

    // Build chat list entries: always include Broadcast Mesh Room ("*"), plus any active conversations or connected peers
    val chatEntries = rememberChatEntries(conversations, connectedPeers.values.toList())

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF070D18))
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Encrypted Mesh Chats",
                        style = MaterialTheme.typography.titleLarge,
                        color = Color.White,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Zero-Internet • E2EE AES-256-GCM",
                        fontSize = 11.sp,
                        color = EmeraldSecure,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF0F1A2E))
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = "${connectedPeers.size} active nodes",
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = CyanPrimary
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(chatEntries) { entry ->
                    ChatListItemCard(
                        entry = entry,
                        isVerified = verifiedPeers.contains(entry.peerId),
                        onClick = { viewModel.openConversation(entry.peerId) }
                    )
                }
            }
        }

        // Floating Action Button to open Radar / Scanner
        FloatingActionButton(
            onClick = { viewModel.navigateTo(AppScreen.RADAR) },
            containerColor = CyanPrimary,
            contentColor = Color(0xFF001F29),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(20.dp)
                .testTag("radar_fab")
        ) {
            Icon(
                imageVector = Icons.Default.Radar,
                contentDescription = "Open Radar"
            )
        }
    }
}

data class ChatItemEntry(
    val peerId: String,
    val title: String,
    val subtitle: String,
    val lastMessage: ChatMessage?,
    val isBroadcast: Boolean,
    val isDirect: Boolean
)

@Composable
private fun rememberChatEntries(
    conversations: Map<String, List<ChatMessage>>,
    connectedPeers: List<com.example.mesh.PeerNode>
): List<ChatItemEntry> {
    val list = mutableListOf<ChatItemEntry>()

    // Broadcast room
    val broadcastMsgs = conversations["*"] ?: emptyList()
    list.add(
        ChatItemEntry(
            peerId = "*",
            title = "Global Mesh Broadcast",
            subtitle = "Multi-hop ad-hoc cluster channel",
            lastMessage = broadcastMsgs.lastOrNull(),
            isBroadcast = true,
            isDirect = false
        )
    )

    // Add direct connected peers or conversations
    val peerIds = (conversations.keys.filter { it != "*" } + connectedPeers.map { it.nodeId }).distinct()
    peerIds.forEach { peerId ->
        val peer = connectedPeers.find { it.nodeId == peerId }
        val msgs = conversations[peerId] ?: emptyList()
        val title = peer?.nodeName ?: "Node-${peerId.take(4)}"
        list.add(
            ChatItemEntry(
                peerId = peerId,
                title = title,
                subtitle = if (peer != null) "Direct link • 1-hop" else "Multi-hop mesh peer",
                lastMessage = msgs.lastOrNull(),
                isBroadcast = false,
                isDirect = peer != null
            )
        )
    }

    return list
}

@Composable
private fun ChatListItemCard(
    entry: ChatItemEntry,
    isVerified: Boolean,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .testTag("chat_card_${entry.peerId}"),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF0F1A2E)),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF1B2F4E))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .clip(CircleShape)
                    .background(
                        if (entry.isBroadcast) AmberMesh.copy(alpha = 0.15f) else CyanPrimary.copy(alpha = 0.15f)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (entry.isBroadcast) Icons.Default.Hub else Icons.Default.Lock,
                    contentDescription = null,
                    tint = if (entry.isBroadcast) AmberMesh else CyanPrimary,
                    modifier = Modifier.size(22.dp)
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = entry.title,
                            style = MaterialTheme.typography.titleMedium,
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
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

                    entry.lastMessage?.let { msg ->
                        val timeStr = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(msg.timestamp))
                        Text(
                            text = timeStr,
                            fontSize = 11.sp,
                            color = Color(0xFF536B8D),
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val previewText = when {
                        entry.lastMessage == null -> entry.subtitle
                        entry.lastMessage.mediaType == MediaType.AUDIO_NOTE -> "🎙️ Voice Note"
                        entry.lastMessage.mediaType == MediaType.IMAGE -> "📷 Image attachment"
                        else -> entry.lastMessage.text
                    }

                    Text(
                        text = previewText,
                        fontSize = 12.sp,
                        color = Color(0xFF8FA4C4),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )

                    entry.lastMessage?.let { msg ->
                        if (msg.isOutgoing) {
                            Spacer(modifier = Modifier.width(6.dp))
                            Icon(
                                imageVector = if (msg.status == MessageStatus.DELIVERED || msg.status == MessageStatus.READ) Icons.Default.DoneAll else Icons.Default.Done,
                                contentDescription = null,
                                tint = if (msg.status == MessageStatus.READ) CyanPrimary else Color(0xFF536B8D),
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}
