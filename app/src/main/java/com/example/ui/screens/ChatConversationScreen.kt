package com.example.ui.screens

import android.net.Uri
import android.speech.tts.TextToSpeech
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
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

private val nullStrVal: String? = null
private val newlineChar = Char(10)

@Composable
fun ChatConversationScreen(viewModel: MainViewModel, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current

    val nullTts: TextToSpeech? = null
    var ttsEngine by remember { mutableStateOf(nullTts) }
    var voiceSpeedLabel by remember { mutableStateOf("1x") }

    DisposableEffect(context) {
        var instance: TextToSpeech? = null
        instance = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                try {
                    instance?.language = Locale("ar")
                } catch (e: Exception) {}
            }
        }
        ttsEngine = instance
        onDispose {
            try {
                instance?.stop()
                instance?.shutdown()
            } catch (e: Exception) {}
        }
    }

    val selectedPeerId by viewModel.selectedPeerId.collectAsState()
    val connectedPeers by viewModel.connectedPeers.collectAsState()
    val messages by viewModel.activeMessages.collectAsState()
    val isRecordingVoice by viewModel.isRecordingVoiceNote.collectAsState()
    val recordingDuration by viewModel.recordingDurationSec.collectAsState()
    val playingMessageId by viewModel.playingMessageId.collectAsState()
    val verifiedPeers by viewModel.verifiedPeers.collectAsState()
    val peerToVerify by viewModel.peerToVerify.collectAsState()

    val translatedMessages by viewModel.translatedMessages.collectAsState()
    val pinnedMessages by viewModel.pinnedMessages.collectAsState()
    val stegoSecrets by viewModel.stegoSecrets.collectAsState()
    val revealedStegoIds by viewModel.revealedStegoIds.collectAsState()
    val isSelfDestructEnabled by viewModel.isSelfDestructEnabled.collectAsState()

    var inputText by remember { mutableStateOf("") }
    var selectedMessageId by remember { mutableStateOf(nullStrVal) }
    var editingMessageId by remember { mutableStateOf(nullStrVal) }
    var editingTextValue by remember { mutableStateOf("") }
    var replyQuoteText by remember { mutableStateOf(nullStrVal) }
    var replyQuoteSender by remember { mutableStateOf(nullStrVal) }

    var showStegoDialog by remember { mutableStateOf(false) }
    var stegoCoverInput by remember { mutableStateOf("السلام عليكم، كيف حالك اليوم؟") }
    var stegoSecretInput by remember { mutableStateOf("") }

    var showFilterStarredOnly by remember { mutableStateOf(false) }
    var starredMessageIds by remember { mutableStateOf(setOf("init").minus("init")) }
    var messageReactions by remember { mutableStateOf(mapOf("init" to "").minus("init")) }

    var isSearchActive by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var fullScreenImagePath by remember { mutableStateOf(nullStrVal) }

    val listState = rememberLazyListState()

    val currentPeerKey = selectedPeerId ?: "*"
    val currentPinnedText = pinnedMessages[currentPeerKey]

    val currentPeer = connectedPeers.values.find { it.nodeId == selectedPeerId || it.endpointId == selectedPeerId }
    val peerDisplayName = currentPeer?.nodeName ?: if (selectedPeerId == "*") "البث العام للشبكة" else "متصل-${selectedPeerId?.take(4)}"
    val isBroadcast = selectedPeerId == "*" || selectedPeerId == null
    val isVerified = selectedPeerId != null && verifiedPeers.contains(selectedPeerId)

    val liveDistanceText = remember(connectedPeers, selectedPeerId) {
        viewModel.getActivePeerDistanceText()
    }

    val displayedMessages = remember(messages, searchQuery, isSearchActive, showFilterStarredOnly, starredMessageIds) {
        val base = if (showFilterStarredOnly) {
            messages.filter { starredMessageIds.contains(it.id) }
        } else {
            messages
        }
        if (!isSearchActive || searchQuery.isBlank()) {
            base
        } else {
            base.filter { it.text.contains(searchQuery.trim(), ignoreCase = true) }
        }
    }

    val quickReplies = remember {
        listOf(
            "👋 السلام عليكم",
            "👍 تم الاستلام",
            "📞 اتصل بي فيديو HD",
            "📍 أين موقعك؟",
            "✅ أنا في الطريق",
            "⏳ انتظر لحظة"
        )
    }

    BackHandler { viewModel.navigateTo(AppScreen.CHATS) }

    LaunchedEffect(displayedMessages.size) {
        if (displayedMessages.isNotEmpty()) {
            listState.animateScrollToItem(displayedMessages.size - 1)
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

    Column(modifier = modifier.fillMaxSize().background(Color(0xFF070D18))) {
        // Top Chat Header Bar
        Row(
            modifier = Modifier.fillMaxWidth().background(Color(0xFF0F1A2E)).padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                IconButton(onClick = { viewModel.navigateTo(AppScreen.CHATS) }, modifier = Modifier.testTag("back_button")) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "رجوع", tint = Color.White)
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
                        Text(
                            text = peerDisplayName,
                            style = MaterialTheme.typography.titleMedium,
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (isVerified) {
                            Spacer(Modifier.width(4.dp))
                            Icon(Icons.Default.CheckCircle, contentDescription = "موثوق", tint = EmeraldSecure, modifier = Modifier.size(14.dp))
                        }
                    }
                    Text(
                        text = if (inputText.isNotEmpty()) "✍️ جاري الكتابة الآن..." else "✨ صوت وفيديو HD • $liveDistanceText",
                        fontSize = 11.sp,
                        color = if (inputText.isNotEmpty()) CyanPrimary else if (isSelfDestructEnabled || isBroadcast) AmberMesh else EmeraldSecure,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = { showFilterStarredOnly = !showFilterStarredOnly }
                ) {
                    Icon(
                        imageVector = Icons.Default.Star,
                        contentDescription = "الرسائل المميزة بنجمة",
                        tint = if (showFilterStarredOnly) AmberMesh else Color.LightGray
                    )
                }

                IconButton(
                    onClick = {
                        isSearchActive = !isSearchActive
                        if (!isSearchActive) searchQuery = ""
                    }
                ) {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = "بحث في المحادثة",
                        tint = if (isSearchActive) AmberMesh else Color.LightGray
                    )
                }

                if (!isBroadcast && selectedPeerId != null) {
                    IconButton(onClick = { viewModel.startCall(selectedPeerId!!, peerDisplayName, CallType.VOICE) }, modifier = Modifier.testTag("voice_call_button")) {
                        Icon(Icons.Default.Call, contentDescription = "مكالمة صوتية HD", tint = CyanPrimary)
                    }
                    IconButton(onClick = { viewModel.startCall(selectedPeerId!!, peerDisplayName, CallType.VIDEO) }, modifier = Modifier.testTag("video_call_button")) {
                        Icon(Icons.Default.Videocam, contentDescription = "مكالمة فيديو HD", tint = EmeraldSecure)
                    }
                    currentPeer?.let { peer ->
                        IconButton(onClick = { viewModel.openVerificationDialog(peer) }, modifier = Modifier.testTag("verify_key_button")) {
                            Icon(Icons.Default.Security, contentDescription = "توثيق الأمان", tint = if (isVerified) EmeraldSecure else AmberMesh)
                        }
                    }
                }
            }
        }

        if (isSearchActive) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF0B1424))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("ابحث عن أي رسالة أو كلمة...", fontSize = 12.sp, color = Color.Gray) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp)
                )
                Spacer(Modifier.width(8.dp))
                IconButton(onClick = {
                    isSearchActive = false
                    searchQuery = ""
                }) {
                    Icon(Icons.Default.Close, contentDescription = "إغلاق البحث", tint = Color.LightGray)
                }
            }
        }

        if (!currentPinnedText.isNullOrBlank()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF132238))
                    .border(0.5.dp, AmberMesh.copy(alpha = 0.5f))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.Star, contentDescription = null, tint = AmberMesh, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text("رسالة مثبتة في أعلى المحادثة 📌", fontSize = 10.sp, color = AmberMesh, fontWeight = FontWeight.Bold)
                        Text(
                            text = currentPinnedText,
                            fontSize = 12.sp,
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                IconButton(
                    onClick = { viewModel.pinMessageInActiveChat(null) },
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(Icons.Default.Close, contentDescription = "إلغاء التثبيت", tint = Color.Gray, modifier = Modifier.size(16.dp))
                }
            }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxWidth().weight(1f).padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.Bottom)
        ) {
            items(displayedMessages) { message ->
                val isSelected = (selectedMessageId == message.id)
                val translatedText = translatedMessages[message.id]
                val secretStegoText = stegoSecrets[message.id]
                val isStegoRevealed = revealedStegoIds.contains(message.id)
                val isStarred = starredMessageIds.contains(message.id)
                val reactionEmoji = messageReactions[message.id]

                MessageBubble(
                    message = message,
                    isPlaying = playingMessageId == message.id,
                    isSelected = isSelected,
                    isStarred = isStarred,
                    reactionEmoji = reactionEmoji,
                    voiceSpeedLabel = voiceSpeedLabel,
                    translatedText = translatedText,
                    secretStegoText = secretStegoText,
                    isStegoRevealed = isStegoRevealed,
                    onBubbleClick = {
                        selectedMessageId = if (selectedMessageId == message.id) null else message.id
                    },
                    onReactionSelected = { emoji ->
                        messageReactions = messageReactions + (message.id to emoji)
                        val baseClean = message.text
                            .replace(" [❤️]", "")
                            .replace(" [😂]", "")
                            .replace(" [🔥]", "")
                            .replace(" [👍]", "")
                            .replace(" [👏]", "")
                            .replace("(معدلة ✏️)", "")
                            .trim()
                        viewModel.editMessageInActiveChat(message.id, "$baseClean [$emoji]")
                        selectedMessageId = null
                    },
                    onToggleStar = {
                        starredMessageIds = if (isStarred) {
                            starredMessageIds - message.id
                        } else {
                            starredMessageIds + message.id
                        }
                        selectedMessageId = null
                    },
                    onToggleVoiceSpeed = {
                        val nextSpeed = when (voiceSpeedLabel) {
                            "1x" -> "1.5x"
                            "1.5x" -> "2x"
                            else -> "1x"
                        }
                        voiceSpeedLabel = nextSpeed
                        val rate = when (nextSpeed) {
                            "1.5x" -> 1.5f
                            "2x" -> 2.0f
                            else -> 1.0f
                        }
                        try { ttsEngine?.setSpeechRate(rate) } catch (e: Exception) {}
                    },
                    onToggleStegoReveal = {
                        viewModel.toggleRevealStegoMessage(message.id)
                    },
                    onPlayVoiceNote = { viewModel.playVoiceNote(message.id, message.mediaUri ?: "") },
                    onImageClick = { path -> fullScreenImagePath = path },
                    onSpeakClick = {
                        try {
                            val textToRead = translatedText ?: message.text
                            val hasArabic = textToRead.any { it.code in 1536..1791 }
                            ttsEngine?.language = if (hasArabic) Locale("ar") else Locale.US
                            ttsEngine?.speak(textToRead, TextToSpeech.QUEUE_FLUSH, null, message.id)
                        } catch (e: Exception) {}
                        selectedMessageId = null
                    },
                    onTranslateClick = {
                        viewModel.translateMessageToArabic(message.id, message.text)
                    },
                    onEditClick = {
                        editingMessageId = message.id
                        editingTextValue = message.text.replace("(معدلة ✏️)", "").trim()
                        selectedMessageId = null
                    },
                    onReplyClick = {
                        replyQuoteSender = if (message.isOutgoing) "أنت" else message.senderName
                        replyQuoteText = message.text
                        selectedMessageId = null
                    },
                    onPinClick = {
                        viewModel.pinMessageInActiveChat(message.text)
                        selectedMessageId = null
                    },
                    onCopyClick = {
                        clipboardManager.setText(AnnotatedString(message.text))
                        selectedMessageId = null
                    },
                    onDeleteClick = {
                        viewModel.deleteMessageInActiveChat(message.id)
                        selectedMessageId = null
                    }
                )
            }
        }

        // Attractive Chat Tools & Quick Replies Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF0B1424))
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .background(AmberMesh.copy(alpha = 0.18f))
                    .border(1.dp, AmberMesh, RoundedCornerShape(14.dp))
                    .clickable {
                        stegoSecretInput = ""
                        showStegoDialog = true
                    }
                    .padding(horizontal = 10.dp, vertical = 5.dp)
            ) {
                Text(
                    text = "🔒 رسالة سرية بكلمة مرور",
                    fontSize = 11.sp,
                    color = AmberMesh,
                    fontWeight = FontWeight.Bold
                )
            }

            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .background(EmeraldSecure.copy(alpha = 0.18f))
                    .border(1.dp, EmeraldSecure, RoundedCornerShape(14.dp))
                    .clickable { viewModel.sendLiveTelemetryInActiveChat() }
                    .padding(horizontal = 10.dp, vertical = 5.dp)
            ) {
                Text(
                    text = "🔋 مشاركة حالة البطارية والشبكة",
                    fontSize = 11.sp,
                    color = EmeraldSecure,
                    fontWeight = FontWeight.Bold
                )
            }

            for (quickText in quickReplies) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color(0xFF132238))
                        .border(1.dp, CyanPrimary.copy(alpha = 0.35f), RoundedCornerShape(14.dp))
                        .clickable { viewModel.sendTextMessage(quickText) }
                        .padding(horizontal = 10.dp, vertical = 5.dp)
                ) {
                    Text(
                        text = quickText,
                        fontSize = 11.sp,
                        color = Color(0xFFE6F1FF),
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }

        if (!replyQuoteText.isNullOrBlank()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF132238))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "الرد على ${replyQuoteSender ?: "رسالة"} ↩️",
                        fontSize = 10.sp,
                        color = CyanPrimary,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = replyQuoteText ?: "",
                        fontSize = 11.sp,
                        color = Color.LightGray,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                IconButton(
                    onClick = {
                        replyQuoteText = null
                        replyQuoteSender = null
                    },
                    modifier = Modifier.size(26.dp)
                ) {
                    Icon(Icons.Default.Close, contentDescription = "إلغاء الرد", tint = Color.Gray, modifier = Modifier.size(16.dp))
                }
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
                    Icon(Icons.Default.AttachFile, contentDescription = "إرفاق صورة أو فيديو", tint = CyanPrimary)
                }

                OutlinedTextField(
                    value = inputText,
                    onValueChange = { inputText = it },
                    placeholder = { Text("اكتب رسالة (أو اضغط على أي رسالة للتفاعل ❤️ والترجمة)...", color = Color(0xFF536B8D), fontSize = 11.sp) },
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
                            val finalMsg = if (!replyQuoteText.isNullOrBlank()) {
                                "↩️ [${replyQuoteText?.take(35)}]" + newlineChar + inputText
                            } else {
                                inputText
                            }
                            viewModel.sendTextMessage(finalMsg)
                            inputText = ""
                            replyQuoteText = null
                            replyQuoteSender = null
                        }
                    }),
                    maxLines = 4
                )

                Spacer(Modifier.width(6.dp))

                if (inputText.isNotBlank()) {
                    IconButton(
                        onClick = {
                            val finalMsg = if (!replyQuoteText.isNullOrBlank()) {
                                "↩️ [${replyQuoteText?.take(35)}]" + newlineChar + inputText
                            } else {
                                inputText
                            }
                            viewModel.sendTextMessage(finalMsg)
                            inputText = ""
                            replyQuoteText = null
                            replyQuoteSender = null
                        },
                        modifier = Modifier.size(44.dp).clip(CircleShape).background(CyanPrimary).testTag("send_button")
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "إرسال", tint = Color(0xFF001F29), modifier = Modifier.size(20.dp))
                    }
                } else {
                    IconButton(
                        onClick = { viewModel.startVoiceRecording() },
                        modifier = Modifier.size(44.dp).clip(CircleShape).background(EmeraldSecure).testTag("voice_record_button")
                    ) {
                        Icon(Icons.Default.Mic, contentDescription = "تسجيل صوتي نقي", tint = Color(0xFF00220F), modifier = Modifier.size(22.dp))
                    }
                }
            }
        }
    }

    if (showStegoDialog) {
        Dialog(onDismissRequest = { showStegoDialog = false }) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF0B1424)),
                border = androidx.compose.foundation.BorderStroke(1.dp, AmberMesh)
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text(
                        text = "🔒 إرسال رسالة خاصة مخفية",
                        style = MaterialTheme.typography.titleMedium,
                        color = AmberMesh,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "تظهر الرسالة العادية على الشاشة، وعند الضغط عليها يظهر النص السري المخفي بداخلها:",
                        fontSize = 11.sp,
                        color = Color(0xFF8FA4C4)
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = stegoCoverInput,
                        onValueChange = { stegoCoverInput = it },
                        label = { Text("الرسالة الظاهرية العادية", color = CyanPrimary, fontSize = 11.sp) },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = stegoSecretInput,
                        onValueChange = { stegoSecretInput = it },
                        label = { Text("النص السري المخفي بداخلها 🔒", color = AmberMesh, fontSize = 11.sp) },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(16.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        OutlinedButton(onClick = { showStegoDialog = false }) {
                            Text("إلغاء", color = Color.LightGray)
                        }
                        Spacer(Modifier.width(8.dp))
                        Button(
                            onClick = {
                                if (stegoSecretInput.isNotBlank()) {
                                    viewModel.sendStegoMessageInActiveChat(stegoCoverInput, stegoSecretInput)
                                    showStegoDialog = false
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = AmberMesh)
                        ) {
                            Text("إرسال الآن 🔒", color = Color(0xFF001F29), fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }

    val activeEditId = editingMessageId
    if (activeEditId != null) {
        Dialog(onDismissRequest = { editingMessageId = null }) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF0B1424)),
                border = androidx.compose.foundation.BorderStroke(1.dp, CyanPrimary)
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Edit, contentDescription = null, tint = CyanPrimary, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "تعديل الرسالة المرسلة",
                            style = MaterialTheme.typography.titleMedium,
                            color = Color.White,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "سيتم تحديث نص الرسالة فوراً لديك ولدى الطرف المتصل:",
                        fontSize = 12.sp,
                        color = Color(0xFF8FA4C4)
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = editingTextValue,
                        onValueChange = { editingTextValue = it },
                        label = { Text("النص الجديد", color = CyanPrimary) },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(16.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        OutlinedButton(
                            onClick = { editingMessageId = null },
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text("إلغاء", color = Color.LightGray)
                        }
                        Spacer(Modifier.width(8.dp))
                        Button(
                            onClick = {
                                if (editingTextValue.isNotBlank()) {
                                    viewModel.editMessageInActiveChat(activeEditId, editingTextValue)
                                    editingMessageId = null
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = EmeraldSecure),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text("حفظ التعديل ✏️", color = Color(0xFF00220F), fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }

    val previewPath = fullScreenImagePath
    if (previewPath != null) {
        Dialog(onDismissRequest = { fullScreenImagePath = null }) {
            Card(
                modifier = Modifier.fillMaxWidth().padding(8.dp),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF070D18)),
                border = androidx.compose.foundation.BorderStroke(1.dp, CyanPrimary)
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("معاينة الصورة بوضوح كامل HD", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        IconButton(onClick = { fullScreenImagePath = null }) {
                            Icon(Icons.Default.Close, contentDescription = "إغلاق", tint = Color.White)
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    AsyncImage(
                        model = ImageRequest.Builder(LocalContext.current).data(File(previewPath)).crossfade(true).build(),
                        contentDescription = "معاينة الصورة",
                        modifier = Modifier.fillMaxWidth().height(360.dp).clip(RoundedCornerShape(12.dp)),
                        contentScale = ContentScale.Fit
                    )
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
private fun MessageBubble(
    message: ChatMessage,
    isPlaying: Boolean,
    isSelected: Boolean,
    isStarred: Boolean,
    reactionEmoji: String?,
    voiceSpeedLabel: String,
    translatedText: String?,
    secretStegoText: String?,
    isStegoRevealed: Boolean,
    onBubbleClick: () -> Unit,
    onReactionSelected: (String) -> Unit,
    onToggleStar: () -> Unit,
    onToggleVoiceSpeed: () -> Unit,
    onToggleStegoReveal: () -> Unit,
    onPlayVoiceNote: () -> Unit,
    onImageClick: (String) -> Unit,
    onSpeakClick: () -> Unit,
    onTranslateClick: () -> Unit,
    onEditClick: () -> Unit,
    onReplyClick: () -> Unit,
    onPinClick: () -> Unit,
    onCopyClick: () -> Unit,
    onDeleteClick: () -> Unit
) {
    val alignment = if (message.isOutgoing) Alignment.End else Alignment.Start
    val bubbleColor = if (message.isOutgoing) Color(0xFF003644) else Color(0xFF132038)
    val borderColor = if (isSelected) AmberMesh else if (message.isOutgoing) CyanPrimary.copy(alpha = 0.5f) else Color(0xFF1F355A)

    val hasEnglishChars = remember(message.text) {
        message.text.any { it in 'a'..'z' || it in 'A'..'Z' }
    }

    val reactionOptions = remember { listOf("❤️", "👍", "🔥", "😂", "👏") }

    Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = alignment) {
        if (message.hopCount in 1..Int.MAX_VALUE || message.relayPath.isNotEmpty()) {
            val hopText = if (message.relayPath.isNotEmpty()) "مرسل عبر: ${message.relayPath.joinToString(" <- ")}" else "تم توجيهه (${message.hopCount} قفزات)"
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)) {
                Icon(Icons.Default.Hub, contentDescription = null, tint = AmberMesh, modifier = Modifier.size(10.dp))
                Spacer(Modifier.width(4.dp))
                Text(hopText, fontSize = 9.sp, fontFamily = FontFamily.Monospace, color = AmberMesh, fontWeight = FontWeight.Medium)
            }
        }

        // Instant Emoji Reaction Bar when bubble is tapped
        if (isSelected) {
            Row(
                modifier = Modifier
                    .padding(bottom = 4.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color(0xFF0F1A2E))
                    .border(1.dp, CyanPrimary.copy(alpha = 0.5f), RoundedCornerShape(20.dp))
                    .padding(horizontal = 10.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                for (emoji in reactionOptions) {
                    Text(
                        text = emoji,
                        fontSize = 18.sp,
                        modifier = Modifier.clickable { onReactionSelected(emoji) }
                    )
                }
            }
        }

        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = if (message.isOutgoing) 16.dp else 4.dp, bottomEnd = if (message.isOutgoing) 4.dp else 16.dp))
                .background(bubbleColor)
                .border(if (isSelected) 1.5.dp else 1.dp, borderColor, RoundedCornerShape(16.dp))
                .clickable { onBubbleClick() }
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
                                contentDescription = "صورة",
                                modifier = Modifier
                                    .size(210.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .clickable { onImageClick(file.absolutePath) },
                                contentScale = ContentScale.Crop
                            )
                        } else {
                            Text("📷 [صورة: ${message.fileName}]", color = Color.White, fontSize = 13.sp)
                        }
                    }
                    MediaType.AUDIO_NOTE -> VoiceNoteBubble(
                        isPlaying = isPlaying,
                        text = message.text,
                        speedLabel = voiceSpeedLabel,
                        onPlayClick = onPlayVoiceNote,
                        onSpeedClick = onToggleVoiceSpeed
                    )
                    else -> Text(message.text, style = MaterialTheme.typography.bodyMedium, color = Color(0xFFE6F1FF))
                }

                if (!secretStegoText.isNullOrBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color(0xFF1A1405))
                            .border(1.dp, AmberMesh.copy(alpha = 0.7f), RoundedCornerShape(10.dp))
                            .clickable { onToggleStegoReveal() }
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Column {
                            Text(
                                text = if (isStegoRevealed) "🔒 النص السري المخفي (اضغط للإخفاء):" else "🔒 تحتوي على نص سري مخفي (اضغط للكشف)",
                                fontSize = 10.sp,
                                color = AmberMesh,
                                fontWeight = FontWeight.Bold
                            )
                            if (isStegoRevealed) {
                                Spacer(Modifier.height(3.dp))
                                Text(
                                    text = secretStegoText,
                                    fontSize = 13.sp,
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }

                if (!translatedText.isNullOrBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color(0xFF071322))
                            .border(1.dp, EmeraldSecure.copy(alpha = 0.6f), RoundedCornerShape(10.dp))
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Column {
                            Text(
                                text = "🌐 الترجمة الفورية للعربية:",
                                fontSize = 10.sp,
                                color = EmeraldSecure,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = translatedText,
                                fontSize = 13.sp,
                                color = Color.White,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }

                if (hasEnglishChars && translatedText.isNullOrBlank() && !isSelected) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "🌐 اضغط لترجمة الرسالة للعربية",
                        fontSize = 10.sp,
                        color = CyanPrimary,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.clickable { onTranslateClick() }
                    )
                }

                if (isSelected) {
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color(0xFF070D18))
                            .border(1.dp, Color(0xFF1F355A), RoundedCornerShape(10.dp))
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 6.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        ActionChipButton(
                            label = "نطق 🔊",
                            color = CyanPrimary,
                            onClick = onSpeakClick
                        )
                        ActionChipButton(
                            label = if (isStarred) "إلغاء النجمة ⭐" else "تمييز بنجمة ⭐",
                            color = AmberMesh,
                            onClick = onToggleStar
                        )
                        ActionChipButton(
                            label = "ترجمة 🌐",
                            color = EmeraldSecure,
                            onClick = onTranslateClick
                        )
                        if (message.isOutgoing && message.mediaType == null) {
                            ActionChipButton(
                                label = "تعديل ✏️",
                                color = CyanPrimary,
                                onClick = onEditClick
                            )
                        }
                        ActionChipButton(
                            label = "رد ↩️",
                            color = Color.White,
                            onClick = onReplyClick
                        )
                        ActionChipButton(
                            label = "تثبيت 📌",
                            color = AmberMesh,
                            onClick = onPinClick
                        )
                        ActionChipButton(
                            label = "نسخ 📋",
                            color = Color.LightGray,
                            onClick = onCopyClick
                        )
                        if (message.id != "sys-welcome") {
                            ActionChipButton(
                                label = "حذف 🗑️",
                                color = RedAlert,
                                onClick = onDeleteClick
                            )
                        }
                    }
                }

                Spacer(Modifier.height(4.dp))

                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.End, modifier = Modifier.align(Alignment.End)) {
                    if (!reactionEmoji.isNullOrBlank()) {
                        Text(reactionEmoji, fontSize = 13.sp)
                        Spacer(Modifier.width(4.dp))
                    }
                    if (isStarred) {
                        Text("⭐", fontSize = 11.sp)
                        Spacer(Modifier.width(4.dp))
                    }
                    val timeStr = SimpleDateFormat("hh:mm a", Locale("ar")).format(Date(message.timestamp))
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
private fun ActionChipButton(
    label: String,
    color: Color,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(color.copy(alpha = 0.15f))
            .clickable { onClick() }
            .padding(horizontal = 7.dp, vertical = 4.dp)
    ) {
        Text(
            text = label,
            fontSize = 10.sp,
            color = color,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun VoiceNoteBubble(
    isPlaying: Boolean,
    text: String,
    speedLabel: String,
    onPlayClick: () -> Unit,
    onSpeedClick: () -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
        IconButton(onClick = onPlayClick, modifier = Modifier.size(36.dp).clip(CircleShape).background(EmeraldSecure)) {
            Icon(if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, contentDescription = null, tint = Color(0xFF00220F), modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(10.dp))
        Column {
            Text(text, fontSize = 12.sp, color = Color.White, fontWeight = FontWeight.SemiBold)
            Text(if (isPlaying) "جاري التشغيل بنقاء HD..." else "اضغط للاستماع", fontSize = 10.sp, color = EmeraldSecure)
        }
        Spacer(Modifier.width(10.dp))
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(CyanPrimary.copy(alpha = 0.2f))
                .border(1.dp, CyanPrimary.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                .clickable { onSpeedClick() }
                .padding(horizontal = 8.dp, vertical = 4.dp)
        ) {
            Text(speedLabel, fontSize = 11.sp, color = CyanPrimary, fontWeight = FontWeight.ExtraBold)
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
            Text("تسجيل صوتي نقي HD • 0:${String.format("%02d", durationSec)}", color = RedAlert, fontSize = 13.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            IconButton(onClick = onCancel, modifier = Modifier.size(38.dp).clip(CircleShape).background(Color(0xFF162540))) {
                Icon(Icons.Default.Close, contentDescription = "إلغاء", tint = Color.LightGray, modifier = Modifier.size(18.dp))
            }
            IconButton(onClick = onSend, modifier = Modifier.size(38.dp).clip(CircleShape).background(EmeraldSecure)) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "إرسال", tint = Color(0xFF00220F), modifier = Modifier.size(18.dp))
            }
        }
    }
}
