package com.example.ui.screens

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
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
import androidx.compose.foundation.layout.width
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
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
fun ChatConversationScreen(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier
) {
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

    BackHandler {
        viewModel.navigateTo(AppScreen.CHATS)
    }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    val mediaPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri != null) {
            val mimeType = context.contentResolver.getType(uri) ?: "image/jpeg"
            viewModel.sendMediaAttachment(uri, mimeType)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF070D18))
    ) {
        // Conversation Top Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF0F1A2E))
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = { viewModel.navigateTo(AppScreen.CHATS) },
                    modifier = Modifier.testTag("back_button")
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = Color.White
                    )
                }

                Spacer(modifier = Modifier.width(4.dp))

                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(CircleShape)
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

                Spacer(modifier = Modifier.width(10.dp))

                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = peerDisplayName,
                            style = MaterialTheme.typography.titleMedium,
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
                    Text(
                        text = if (isBroadcast) "Multi-Hop Mesh Flooding" else if (currentPeer != null) "Direct Link • E2EE AES-256" else "Relayed via Mesh",
                        fontSize = 11.sp,
                        color = if (isBroadcast) AmberMesh else EmeraldSecure
                    )
                }
            }

            if (!isBroadcast && selectedPeerId != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = {
                            viewModel.startCall(selectedPeerId!!, peerDisplayName, CallType.VOICE)
                        },
                        modifier = Modifier.testTag("voice_call_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Call,
                            contentDescription = "Voice Call",
                            tint = CyanPrimary
                        )
                    }

                    IconButton(
                        onClick = {
                            viewModel.startCall(selectedPeerId!!, peerDisplayName, CallType.VIDEO)
                        },
                        modifier = Modifier.testTag("video_call_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Videocam,
                            contentDescription = "Video Call",
                            tint = EmeraldSecure
                        )
                    }

                    currentPeer?.let { peer ->
                        IconButton(
                            onClick = { viewModel.openVerificationDialog(peer) },
                            modifier = Modifier.testTag("verify_key_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Security,
                                contentDescription = "Verify Key",
                                tint = if (isVerified) EmeraldSecure else AmberMesh
                            )
                        }
                    }
                }
            }
        }

        // Messages List anchored to bottom
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = 12.dp, vertical = 8.dp),
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

        // Bottom Bar: Fixed directly at the bottom above the keyboard
        if (isRecordingVoice) {
            VoiceNoteRecordingBar(
                durationSec = recordingDuration,
                onCancel = { viewModel.cancelVoiceRecording() },
                onSend = { viewModel.stopAndSendVoiceRecording() }
            )
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF0F1A2E))
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = {
                        mediaPickerLauncher.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)
                        )
                    },
                    modifier = Modifier.testTag("attach_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.AttachFile,
                        contentDescription = "Attach Media",
                        tint = CyanPrimary
                    )
                }

                OutlinedTextField(
                    value = inputText,
                    onValueChange = { inputText = it },
                    placeholder = {
                        Text(
                            text = "Encrypted
