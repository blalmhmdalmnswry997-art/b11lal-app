package com.example.ui.screens

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.mesh.CallState
import com.example.mesh.CallType
import com.example.ui.MainViewModel
import com.example.ui.theme.AmberMesh
import com.example.ui.theme.CyanPrimary
import com.example.ui.theme.EmeraldSecure
import com.example.ui.theme.RedAlert

@Composable
fun VideoCallScreen(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier
) {
    val callState by viewModel.callState.collectAsState()
    val callType by viewModel.callType.collectAsState()
    val peerName by viewModel.activePeerName.collectAsState()
    val remoteBitmap by viewModel.remoteVideoBitmap.collectAsState()
    val localBitmap by viewModel.localVideoBitmap.collectAsState()
    val fps by viewModel.callFps.collectAsState()
    val bitrateKbps by viewModel.callBitrateKbps.collectAsState()
    val rttMs by viewModel.callRttMs.collectAsState()
    val isMicMuted by viewModel.isMicMuted.collectAsState()
    val isVideoMuted by viewModel.isVideoMuted.collectAsState()
    val isSpeakerOn by viewModel.isSpeakerOn.collectAsState()

    val lifecycleOwner = LocalLifecycleOwner.current

    // Bind CameraX on launch if call is active
    LaunchedEffect(callState) {
        if (callState == CallState.CONNECTED || callState == CallState.OUTGOING_RINGING) {
            viewModel.webRtcManager.bindCameraCapture(lifecycleOwner)
        }
    }

    BackHandler {
        viewModel.endCall()
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF040810))
    ) {
        // Main Video Stream: Remote Stream or Audio Call Graphic
        if (remoteBitmap != null && !isVideoMuted && callType == CallType.VIDEO) {
            Image(
                bitmap = remoteBitmap!!.asImageBitmap(),
                contentDescription = "Remote Video Stream",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        } else {
            // Placeholder / Audio Call Interface
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(130.dp)
                        .clip(CircleShape)
                        .background(CyanPrimary.copy(alpha = 0.15f))
                        .border(2.dp, CyanPrimary, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (callType == CallType.VIDEO) Icons.Default.Videocam else Icons.Default.Person,
                        contentDescription = null,
                        tint = CyanPrimary,
                        modifier = Modifier.size(64.dp)
                    )
                }

                Spacer(modifier = Modifier.height(20.dp))

                Text(
                    text = peerName,
                    style = MaterialTheme.typography.headlineMedium,
                    color = Color.White,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(6.dp))

                Text(
                    text = when (callState) {
                        CallState.OUTGOING_RINGING -> "Calling via Direct Wi-Fi Direct socket..."
                        CallState.INCOMING_RINGING -> "Incoming Offline Encrypted Call..."
                        CallState.CONNECTED -> "Direct P2P Encrypted Call Active"
                        else -> "Call Terminated"
                    },
                    fontSize = 13.sp,
                    color = if (callState == CallState.CONNECTED) EmeraldSecure else AmberMesh,
                    fontWeight = FontWeight.Medium
                )
            }
        }

        // Floating Local PIP Camera Preview
        if (localBitmap != null && callType == CallType.VIDEO && !isVideoMuted) {
            Card(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 40.dp, end = 16.dp)
                    .size(width = 110.dp, height = 150.dp),
                shape = RoundedCornerShape(14.dp),
                border = androidx.compose.foundation.BorderStroke(1.5.dp, CyanPrimary)
            ) {
                Image(
                    bitmap = localBitmap!!.asImageBitmap(),
                    contentDescription = "Local Camera Preview",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            }
        }

        // Top Telemetry HUD
        Column(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(top = 40.dp, start = 16.dp)
        ) {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xCC070D18))
                    .border(1.dp, Color(0xFF1F355A), RoundedCornerShape(8.dp))
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Lock,
                    contentDescription = null,
                    tint = EmeraldSecure,
                    modifier = Modifier.size(13.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "AES-256-GCM ZERO-INTERNET",
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    color = EmeraldSecure,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.height(6.dp))

            if (callState == CallState.CONNECTED) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xCC070D18))
                        .border(1.dp, Color(0xFF1F355A), RoundedCornerShape(8.dp))
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Column {
                        Text(
                            text = "FPS: $fps  •  BITRATE: ${bitrateKbps} kbps",
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            color = CyanPrimary
                        )
                        Text(
                            text = "RTT: ${rttMs}ms (Direct Hotspot/Wi-Fi Socket)",
                            fontSize = 9.sp,
                            fontFamily = FontFamily.Monospace,
                            color = Color(0xFF8FA4C4)
                        )
                    }
                }
            }
        }

        // Incoming Call Action Dialog
        if (callState == CallState.INCOMING_RINGING) {
            Card(
                modifier = Modifier
                    .align(Alignment.Center)
                    .fillMaxWidth()
                    .padding(32.dp),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF0F1A2E)),
                border = androidx.compose.foundation.BorderStroke(1.dp, CyanPrimary)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "Incoming Call",
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "$peerName is calling you offline",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color(0xFF8FA4C4)
                    )

                    Spacer(modifier = Modifier.height(24.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        IconButton(
                            onClick = { viewModel.endCall() },
                            modifier = Modifier
                                .size(56.dp)
                                .clip(CircleShape)
                                .background(RedAlert)
                                .testTag("decline_call_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.CallEnd,
                                contentDescription = "Decline",
                                tint = Color.White,
                                modifier = Modifier.size(28.dp)
                            )
                        }

                        IconButton(
                            onClick = { viewModel.acceptCall() },
                            modifier = Modifier
                                .size(56.dp)
                                .clip(CircleShape)
                                .background(EmeraldSecure)
                                .testTag("accept_call_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Phone,
                                contentDescription = "Accept",
                                tint = Color(0xFF00220F),
                                modifier = Modifier.size(28.dp)
                            )
                        }
                    }
                }
            }
        }

        // Floating Bottom Controls Bar
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(bottom = 36.dp, start = 20.dp, end = 20.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(32.dp))
                    .background(Color(0xDD0F1A2E))
                    .border(1.dp, Color(0xFF1F355A), RoundedCornerShape(32.dp))
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Mic Mute Toggle
                IconButton(
                    onClick = { viewModel.webRtcManager.toggleMic() },
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(if (isMicMuted) RedAlert else Color(0xFF162540))
                        .testTag("mute_mic_button")
                ) {
                    Icon(
                        imageVector = if (isMicMuted) Icons.Default.MicOff else Icons.Default.Mic,
                        contentDescription = "Toggle Mic",
                        tint = Color.White
                    )
                }

                // Video Toggle (if Video Call)
                if (callType == CallType.VIDEO) {
                    IconButton(
                        onClick = { viewModel.webRtcManager.toggleVideo() },
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(if (isVideoMuted) RedAlert else Color(0xFF162540))
                            .testTag("mute_video_button")
                    ) {
                        Icon(
                            imageVector = if (isVideoMuted) Icons.Default.VideocamOff else Icons.Default.Videocam,
                            contentDescription = "Toggle Video",
                            tint = Color.White
                        )
                    }

                    // Flip Camera
                    IconButton(
                        onClick = { viewModel.webRtcManager.toggleCamera(lifecycleOwner) },
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF162540))
                            .testTag("flip_camera_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Cameraswitch,
                            contentDescription = "Flip Camera",
                            tint = CyanPrimary
                        )
                    }
                }

                // Speaker Toggle
                IconButton(
                    onClick = { viewModel.webRtcManager.toggleSpeaker() },
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(if (isSpeakerOn) CyanPrimary else Color(0xFF162540))
                        .testTag("speaker_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.VolumeUp,
                        contentDescription = "Toggle Speaker",
                        tint = if (isSpeakerOn) Color(0xFF001F29) else Color.White
                    )
                }

                // End Call Button
                IconButton(
                    onClick = { viewModel.endCall() },
                    modifier = Modifier
                        .size(54.dp)
                        .clip(CircleShape)
                        .background(RedAlert)
                        .testTag("end_call_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.CallEnd,
                        contentDescription = "End Call",
                        tint = Color.White,
                        modifier = Modifier.size(26.dp)
                    )
                }
            }
        }
    }
}
