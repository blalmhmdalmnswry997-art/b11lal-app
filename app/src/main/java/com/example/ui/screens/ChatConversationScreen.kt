package com.example.ui.screens

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.mesh.CallType
import com.example.mesh.ChatMessage
import com.example.mesh.MediaType
import com.example.mesh.MessageStatus
import com.example.ui.AppScreen
import com.example.ui.MainViewModel
import com.example.ui.components.SecurityVerificationDialog
import com.example.ui.theme.AmberMesh
import com.example.ui.theme.CyanPrimary
import com.example.ui.theme.EmeraldSecure
import com.example.ui.theme.RedAlert
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ChatConversationScreen(viewModel: MainViewModel, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val selectedPeerId by viewModel.selectedPeerId.collectAsState()
    val connectedPeers by viewModel.connectedPeers.collectAsState()
    val messages by viewModel.activeMessages.collectAsState()
    val isRecordingVoice by viewModel.isRecordingVoiceNote.collectAsState()
    val recordingDuration by viewModel.recordingDurationSec.collectAsState()
    val playingMessageId by viewModel.playingMessageId.collectAsState()
    val verifiedPeers by viewModel.verifiedPeers.collectAsState()
    val peerToVerify by viewModel.peerToVerify.collectAsState()

    var inputText by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    val currentPeer = connectedPeers.values.find { it.nodeId == selectedPeerId || it.endpointId == selectedPeerId }
    val peerDisplayName = currentPeer?.nodeName ?: if (selectedPeerId == "*") "Global Mesh Broadcast" else "Peer-${selectedPeerId?.take(4)}"
    val isBroadcast = selectedPeerId == "*" || selectedPeerId == null
    val isVerified = selectedPeerId != null && verifiedPeers.contains(selectedPeerId)

    BackHandler { viewModel.navigateTo(AppScreen.CHATS) }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }

    val mediaPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri != null) {
            val mimeType = context.contentResolver.getType(uri) ?: "image/jpeg"
            viewModel.sendMediaAttachment(uri, mimeType)
        }
    }

    Column(modifier = modifier.fillMaxSize().background(Color(0xFF070D18))) {
        Row(
            modifier = Modifier.fillMaxWidth().background(Color(0xFF0F1A2E)).padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { viewModel.navigateTo(AppScreen.CHATS) }, modifier = Modifier.testTag("back_button")) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
                }
                Spacer(Modifier.width(4.dp))
                Box(
                    modifier = Modifier.size(38.dp).clip(CircleShape)
                        .background(if (isBroadcast) AmberMesh.copy(alpha = 0.2f) else CyanPrimary.copy(alpha = 0.2f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (isBroadcast) Icons.Default.Hub else Icons.Default.Lock,
                        contentDescription = null,
                        tint = if (isBroadcast) AmberMesh else CyanPrimary,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(Modifier.width(10.dp))
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(peerDisplayName, style = MaterialTheme.typography.titleMedium, color = Color.White, fontWeight = FontWeight.Bold)
                        if (isVerified) {
                            Spacer(Modifier.width(4.dp))
                            Icon(Icons.Default.CheckCircle, contentDescription = "Verified", tint = EmeraldSecure, modifier = Modifier.size(14.dp))
                        }
                    }
                    Text(
                        text = if (isBroadcast) "Multi-Hop Mesh Flooding" else if (currentPeer != null) "Direct Link • E2EE AES-256" else "Relayed via Mesh",
                        fontSize = 11.sp,
                        color = if (isBroadcast) AmberMesh else EmeraldSecure
                    )
                }
            }

            if (!isBroadcast && selectedPeerId != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { viewModel.startCall(selectedPeerId!!, peerDisplayName, CallType.VOICE) }, modifier = Modifier.testTag("voice_call_button")) {
                        Icon(Icons.Default.Call, contentDescription = "Voice Call", tint = CyanPrimary)
                    }
                    IconButton(onClick = { viewModel.startCall(selectedPeerId!!, peerDisplayName, CallType.VIDEO) }, modifier = Modifier.testTag("video_call_button")) {
                        Icon(Icons.Default.Videocam, contentDescription = "Video Call", tint = EmeraldSecure)
                    }
                    currentPeer?.let { peer ->
                        IconButton(onClick = { viewModel.openVerificationDialog(peer) }, modifier = Modifier.testTag("verify_key_button")) {
                            Icon(Icons.Default.Security, contentDescription = "Verify Key", tint = if (isVerified) EmeraldSecure else AmberMesh)
                        }
                    }
                }
            }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxWidth().weight(1f).padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.Bottom)
        ) {
            items(messages) { message ->
                MessageBubble(
                    message = message,
                    isPlaying = playingMessageId == message.id,
                    onPlayVoiceNote = { viewModel.playVoiceNote(message.id, message.mediaUri ?: "") }
                )
            }
        }

        if (isRecordingVoice) {
            VoiceNoteRecordingBar(
                durationSec = recordingDuration,
                onCancel = { viewModel.cancelVoiceRecording() },
                onSend = { viewModel.stopAndSendVoiceRecording() }
            )
        } else {
            Row(
                modifier = Modifier.fillMaxWidth().background(Color(0xFF0F1A2E)).padding(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = { mediaPickerLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)) },
                    modifier = Modifier.testTag("attach_button")
                ) {
                    Icon(Icons.Default.AttachFile, contentDescription = "Attach Media", tint = CyanPrimary)
                }

                OutlinedTextField(
                    value = inputText,
                    onValueChange = { inputText = it },
                    placeholder = { Text("Encrypted message...", color = Color(0xFF536B8D), fontSize = 13.sp) },
                    modifier = Modifier.weight(1f).testTag("message_input_field"),
                    shape = RoundedCornerShape(24.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = Color(0xFF070D18),
                        unfocusedContainerColor = Color(0xFF070D18),
                        focusedBorderColor = CyanPrimary,
                        unfocusedBorderColor = Color(0xFF1B2F4E),
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White
                    ),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = {
                        if (inputText.isNotBlank()) {
                            viewModel.sendTextMessage(inputText)
                            inputText = ""
                        }
                    }),
                    maxLines = 4
                )

                Spacer(Modifier.width(6.dp))

                if (inputText.isNotBlank()) {
                    IconButton(
                        onClick = {
                            viewModel.sendTextMessage(inputText)
                            inputText = ""
                        },
                        modifier = Modifier.size(44.dp).clip(CircleShape).background(CyanPrimary).testTag("send_button")
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send", tint = Color(0xFF001F29), modifier = Modifier.size(20.dp))
                    }
                } else {
                    IconButton(
                        onClick = { viewModel.startVoiceRecording() },
                        modifier = Modifier.size(44.dp).clip(CircleShape).background(EmeraldSecure).testTag("voice_record_button")
                    ) {
                        Icon(Icons.Default.Mic, contentDescription = "Record Voice Note", tint = Color(0xFF00220F), modifier = Modifier.size(22.dp))
                    }
                }
            }
        }
    }

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
private fun MessageBubble(message: ChatMessage, isPlaying: Boolean, onPlayVoiceNote: () -> Unit) {
    val alignment = if (message.isOutgoing) Alignment.End else Alignment.Start
    val bubbleColor = if (message.isOutgoing) Color(0xFF003644) else Color(0xFF132038)
    val borderColor = if (message.isOutgoing) CyanPrimary.copy(alpha = 0.5f) else Color(0xFF1F355A)

    Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = alignment) {
        if (message.hopCount > 0 || message.relayPath.isNotEmpty()) {
            val hopText = if (message.relayPath.isNotEmpty()) "Relayed via: ${message.relayPath.joinToString(" -> ")}" else "Mesh Relayed (${message.hopCount} hops)"
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)) {
                Icon(Icons.Default.Hub, contentDescription = null, tint = AmberMesh, modifier = Modifier.size(10.dp))
                Spacer(Modifier.width(4.dp))
                Text(hopText, fontSize = 9.sp, fontFamily = FontFamily.Monospace, color = AmberMesh, fontWeight = FontWeight.Medium)
            }
        }

        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = if (message.isOutgoing) 16.dp else 4.dp, bottomEnd = if (message.isOutgoing) 4.dp else 16.dp))
                .background(bubbleColor)
                .border(1.dp, borderColor, RoundedCornerShape(16.dp))
                .padding(12.dp)
        ) {
            Column {
                if (!message.isOutgoing) {
                    Text(message.senderName, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = CyanPrimary)
                    Spacer(Modifier.height(2.dp))
                }

                when (message.mediaType) {
                    MediaType.IMAGE -> {
                        val file = message.mediaUri?.let { File(it) }
                        if (file != null && file.exists()) {
                            AsyncImage(
                                model = ImageRequest.Builder(LocalContext.current).data(file).crossfade(true).build(),
                                contentDescription = "Received Photo",
                                modifier = Modifier.size(200.dp).clip(RoundedCornerShape(10.dp)),
                                contentScale = ContentScale.Crop
                            )
                        } else {
                            Text("📷 [Image: ${message.fileName}]", color = Color.White, fontSize = 13.sp)
                        }
                    }
                    MediaType.AUDIO_NOTE -> VoiceNoteBubble(isPlaying = isPlaying, text = message.text, onPlayClick = onPlayVoiceNote)
                    else -> Text(message.text, style = MaterialTheme.typography.bodyMedium, color = Color(0xFFE6F1FF))
                }

                Spacer(Modifier.height(4.dp))

                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.End, modifier = Modifier.align(Alignment.End)) {
                    val timeStr = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(message.timestamp))
                    Text(timeStr, fontSize = 10.sp, color = Color(0xFF8FA4C4), fontFamily = FontFamily.Monospace)
                    if (message.isOutgoing) {
                        Spacer(Modifier.width(4.dp))
                        val (icon, tint) = when (message.status) {
                            MessageStatus.SENDING -> Icons.Default.Check to Color.Gray
                            MessageStatus.SENT -> Icons.Default.Check to Color(0xFF8FA4C4)
                            MessageStatus.RELAYED -> Icons.Default.Hub to AmberMesh
                            MessageStatus.DELIVERED -> Icons.Default.DoneAll to Color.LightGray
                            MessageStatus.READ -> Icons.Default.DoneAll to CyanPrimary
                            MessageStatus.FAILED -> Icons.Default.Close to RedAlert
                        }
                        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(13.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun VoiceNoteBubble(isPlaying: Boolean, text: String, onPlayClick: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
        IconButton(onClick = onPlayClick, modifier = Modifier.size(36.dp).clip(CircleShape).background(EmeraldSecure)) {
            Icon(if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, contentDescription = null, tint = Color(0xFF00220F), modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(10.dp))
        Column {
            Text(text, fontSize = 12.sp, color = Color.White, fontWeight = FontWeight.SemiBold)
            Text(if (isPlaying) "Playing voice note..." else "Tap to play", fontSize = 10.sp, color = EmeraldSecure)
        }
    }
}

@Composable
private fun VoiceNoteRecordingBar(durationSec: Int, onCancel: () -> Unit, onSend: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().background(Color(0xFF0F1A2E)).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(12.dp).clip(CircleShape).background(RedAlert))
            Spacer(Modifier.width(10.dp))
            Text("Recording 0:${String.format("%02d", durationSec)}", color = RedAlert, fontSize = 14.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            IconButton(onClick = onCancel, modifier = Modifier.size(38.dp).clip(CircleShape).background(Color(0xFF162540))) {
                Icon(Icons.Default.Close, contentDescription = "Cancel", tint = Color.LightGray, modifier = Modifier.size(18.dp))
            }
            IconButton(onClick = onSend, modifier = Modifier.size(38.dp).clip(CircleShape).background(EmeraldSecure)) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send", tint = Color(0xFF00220F), modifier = Modifier.size(18.dp))
            }
        }
    }
}
