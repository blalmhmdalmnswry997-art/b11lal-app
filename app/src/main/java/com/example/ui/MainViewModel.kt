package com.example.ui

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.ToneGenerator
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.MainActivity
import com.example.crypto.CryptoManager
import com.example.data.CipherMeshRepository
import com.example.mesh.CallState
import com.example.mesh.CallType
import com.example.mesh.ChatMessage
import com.example.mesh.PeerNode
import com.example.nearby.NearbyConnectionsManager
import com.example.webrtc.WebRtcP2PManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

enum class AppScreen {
    RADAR,
    CHATS,
    CONVERSATION,
    VIDEO_CALL,
    DIAGNOSTICS
}

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val appContext = application.applicationContext

    val nearbyManager = NearbyConnectionsManager(appContext)
    val webRtcManager = WebRtcP2PManager(appContext, nearbyManager)
    val repository = CipherMeshRepository(appContext, nearbyManager, webRtcManager)

    private val nullPeer: PeerNode? = null
    private val nullChatMessage: ChatMessage? = null
    private val emptyMsgList = listOfNotNull(nullChatMessage)

    private var ringToneJob: Job? = null
    private var activeToneGen: ToneGenerator? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var knownMessageIds = setOf("sys-welcome")
    private var isMessageTrackerInitialized = false

    private val channelId = "ciphermesh_alerts_channel"

    private val _currentScreen = MutableStateFlow(AppScreen.RADAR)
    val currentScreen = _currentScreen.asStateFlow()

    private val _peerToVerify = MutableStateFlow(nullPeer)
    val peerToVerify = _peerToVerify.asStateFlow()

    private val _isGraphView = MutableStateFlow(false)
    val isGraphView = _isGraphView.asStateFlow()

    val localNodeName = repository.localNodeNameFlow
    val isAdvertising = nearbyManager.isAdvertising
    val isDiscovering = nearbyManager.isDiscovering
    val discoveredPeers = nearbyManager.discoveredPeers
    val connectedPeers = nearbyManager.connectedPeers
    val packetsForwarded = nearbyManager.packetsForwardedCount
    val packetsReceived = nearbyManager.packetsReceivedCount

    val isGlobalBridgeConnected = nearbyManager.isGlobalBridgeConnected
    val globalFrequencyCode = nearbyManager.globalFrequencyCode

    val conversations = repository.conversations
    val selectedPeerId = repository.selectedPeerId
    val verifiedPeers = repository.verifiedPeers
    val isRecordingVoiceNote = repository.isRecordingVoiceNote
    val recordingDurationSec = repository.recordingDurationSec
    val playingMessageId = repository.playingMessageId

    val isSelfDestructEnabled = repository.isSelfDestructEnabled
    val isAutoReplyEnabled = repository.isAutoReplyEnabled
    val autoReplyText = repository.autoReplyText
    val appPinCode = repository.appPinCode
    val isAppLocked = repository.isAppLocked
    val translatedMessages = repository.translatedMessages
    val pinnedMessages = repository.pinnedMessages
    val stegoSecrets = repository.stegoSecrets
    val revealedStegoIds = repository.revealedStegoIds

    val isWalkieTalkieEnabled = webRtcManager.isWalkieTalkieEnabled
    val isPttTransmitting = webRtcManager.isPttTransmitting
    val isPttReceiving = webRtcManager.isPttReceiving

    val callState = webRtcManager.callState
    val callType = webRtcManager.callType
    val activePeerName = webRtcManager.activePeerName
    val remoteVideoBitmap = webRtcManager.remoteVideoBitmap
    val localVideoBitmap = webRtcManager.localVideoBitmap
    val callFps = webRtcManager.fps
    val callBitrateKbps = webRtcManager.bitrateKbps
    val callRttMs = webRtcManager.rttMs
    val isMicMuted = webRtcManager.isMicMuted
    val isVideoMuted = webRtcManager.isVideoMuted
    val isSpeakerOn = webRtcManager.isSpeakerOn

    val activeMessages = combine(
        conversations,
        selectedPeerId
    ) { convs, selectedId ->
        if (selectedId != null) {
            convs[selectedId] ?: emptyMsgList
        } else {
            convs["*"] ?: emptyMsgList
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMsgList)

    init {
        createNotificationChannel()
        acquireHighPerfWifiLock()

        viewModelScope.launch {
            callState.collect { state ->
                when (state) {
                    CallState.INCOMING_RINGING -> {
                        _currentScreen.value = AppScreen.VIDEO_CALL
                        startSafeRingingLoop(isIncoming = true)
                        showSystemNotification(
                            title = "مكالمة واردة",
                            body = "${activePeerName.value} يتصل بك الآن"
                        )
                    }
                    CallState.OUTGOING_RINGING -> {
                        _currentScreen.value = AppScreen.VIDEO_CALL
                        startSafeRingingLoop(isIncoming = false)
                    }
                    CallState.CONNECTED -> {
                        stopAllCallTones()
                        _currentScreen.value = AppScreen.VIDEO_CALL
                    }
                    CallState.ENDED -> {
                        stopAllCallTones()
                        if (_currentScreen.value == AppScreen.VIDEO_CALL) {
                            _currentScreen.value = AppScreen.CONVERSATION
                        }
                    }
                    CallState.IDLE -> {
                        stopAllCallTones()
                        if (_currentScreen.value == AppScreen.VIDEO_CALL) {
                            _currentScreen.value = AppScreen.CONVERSATION
                        }
                    }
                }
            }
        }

        viewModelScope.launch {
            conversations.collect { convMap ->
                val allMsgs = convMap.values.flatten()
                val currentIds = allMsgs.map { it.id }.toSet()

                if (!isMessageTrackerInitialized) {
                    knownMessageIds = currentIds
                    isMessageTrackerInitialized = true
                } else {
                    val newIncomingList = allMsgs.filter { msg ->
                        !knownMessageIds.contains(msg.id) && !msg.isOutgoing && msg.senderId != "system"
                    }
                    knownMessageIds = currentIds
                    if (newIncomingList.isNotEmpty() && callState.value == CallState.IDLE) {
                        val latest = newIncomingList.last()
                        val isSos = latest.text.startsWith("🚨")
                        playShortMessageBeep(isSos)
                        showSystemNotification(
                            title = if (isSos) "🚨 نداء طوارئ من ${latest.senderName}" else "رسالة من ${latest.senderName}",
                            body = latest.text
                        )
                    }
                }
            }
        }
    }

    /**
     * Controlled non-leaking ToneGenerator loop that strictly terminates on hangup/answer
     */
    private fun startSafeRingingLoop(isIncoming: Boolean) {
        stopAllCallTones()
        ringToneJob = viewModelScope.launch {
            var toneGen: ToneGenerator? = null
            try {
                toneGen = ToneGenerator(AudioManager.STREAM_MUSIC, 80)
                activeToneGen = toneGen
                var cycles = 0
                while (isActive && cycles in 0..12 && (callState.value == CallState.INCOMING_RINGING || callState.value == CallState.OUTGOING_RINGING)) {
                    val toneType = if (isIncoming) ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD else ToneGenerator.TONE_SUP_RINGTONE
                    toneGen.startTone(toneType, 600)
                    delay(2000)
                    cycles += 1
                }
            } catch (t: Throwable) {
            } finally {
                try { toneGen?.stopTone() } catch (t: Throwable) {}
                try { toneGen?.release() } catch (t: Throwable) {}
                if (activeToneGen === toneGen) activeToneGen = null
            }
        }
    }

    fun stopAllCallTones() {
        ringToneJob?.cancel()
        ringToneJob = null
        val gen = activeToneGen
        activeToneGen = null
        try { gen?.stopTone() } catch (t: Throwable) {}
        try { gen?.release() } catch (t: Throwable) {}
    }

    private fun playShortMessageBeep(isSos: Boolean = false) {
        viewModelScope.launch {
            var gen: ToneGenerator? = null
            try {
                gen = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 75)
                gen.startTone(if (isSos) ToneGenerator.TONE_CDMA_EMERGENCY_RINGBACK else ToneGenerator.TONE_PROP_BEEP, 220)
                delay(280)
            } catch (t: Throwable) {
            } finally {
                try { gen?.stopTone() } catch (t: Throwable) {}
                try { gen?.release() } catch (t: Throwable) {}
            }
        }
    }

    fun updateGlobalFrequency(newFreq: String) {
        nearbyManager.setGlobalFrequency(newFreq)
    }

    private fun createNotificationChannel() {
        try {
            if (Build.VERSION.SDK_INT in Build.VERSION_CODES.O..Int.MAX_VALUE) {
                val nm = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                val channel = NotificationChannel(
                    channelId,
                    "تنبيهات ومكالمات الشبكة",
                    NotificationManager.IMPORTANCE_HIGH
                )
                nm?.createNotificationChannel(channel)
            }
        } catch (t: Throwable) {}
    }

    private fun showSystemNotification(title: String, body: String) {
        try {
            val nm = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            val intent = Intent(appContext, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pendingFlags = if (Build.VERSION.SDK_INT in Build.VERSION_CODES.M..Int.MAX_VALUE) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            val pendingIntent = PendingIntent.getActivity(appContext, 0, intent, pendingFlags)

            val notification = NotificationCompat.Builder(appContext, channelId)
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setContentTitle(title)
                .setContentText(body)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .build()

            nm.notify((System.currentTimeMillis() % 10000).toInt(), notification)
        } catch (t: Throwable) {}
    }

    private fun acquireHighPerfWifiLock() {
        try {
            val wm = appContext.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            if (wm != null && wifiLock == null) {
                @Suppress("DEPRECATION")
                val lock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "CipherMesh:HighPerfWifiLock")
                lock.setReferenceCounted(false)
                lock.acquire()
                wifiLock = lock
            }
        } catch (t: Throwable) {}
    }

    private fun releaseHighPerfWifiLock() {
        try {
            if (wifiLock?.isHeld == true) wifiLock?.release()
        } catch (t: Throwable) {}
        wifiLock = null
    }

    fun sendStegoMessageInActiveChat(coverText: String, secretText: String) {
        val recipientId = selectedPeerId.value ?: "*"
        repository.sendStegoHiddenMessage(recipientId, coverText, secretText)
    }

    fun toggleRevealStegoMessage(messageId: String) {
        repository.toggleRevealStegoMessage(messageId)
    }

    fun sendDigitalVoucherInActiveChat(amount: String, purpose: String) {
        val recipientId = selectedPeerId.value ?: "*"
        repository.sendSignedDigitalVoucher(recipientId, amount, purpose)
    }

    fun sendLiveTelemetryInActiveChat() {
        val recipientId = selectedPeerId.value ?: "*"
        repository.sendLiveTelemetryBeacon(recipientId)
    }

    fun getActivePeerDistanceText(): String {
        return repository.estimatePeerDistanceText(selectedPeerId.value)
    }

    fun editMessageInActiveChat(messageId: String, newText: String) {
        val peerKey = selectedPeerId.value ?: "*"
        repository.editMessage(peerKey, messageId, newText)
    }

    fun deleteMessageInActiveChat(messageId: String) {
        val peerKey = selectedPeerId.value ?: "*"
        repository.deleteMessageForEveryone(peerKey, messageId)
    }

    fun translateMessageToArabic(messageId: String, rawText: String): String {
        return repository.translateMessageOffline(messageId, rawText)
    }

    fun pinMessageInActiveChat(messageText: String?) {
        val peerKey = selectedPeerId.value ?: "*"
        repository.pinMessageInChat(peerKey, messageText)
    }

    fun togglePttTransmission() {
        if (isPttTransmitting.value) {
            webRtcManager.stopPttTransmission()
        } else {
            webRtcManager.startPttTransmission()
        }
    }

    fun toggleWalkieTalkieMode() {
        webRtcManager.toggleWalkieTalkieMode()
    }

    fun toggleSelfDestructMode() {
        repository.toggleSelfDestructMode()
    }

    fun saveAutoReplySettings(enabled: Boolean, replyText: String) {
        repository.configureAutoReply(enabled, replyText)
    }

    fun updateAppPin(newPin: String) {
        repository.updatePinCode(newPin)
    }

    fun lockApp() {
        repository.lockAppNow()
    }

    fun tryUnlockApp(enteredPin: String): Boolean {
        return repository.verifyPinOrDuress(enteredPin)
    }

    fun updateLocalNodeName(newName: String) {
        repository.updateNodeName(newName)
    }

    fun sendSosBroadcast() {
        repository.sendSosAlert()
        repository.selectPeer("*")
        _currentScreen.value = AppScreen.CONVERSATION
    }

    fun clearAllChats() {
        repository.clearAllConversations()
    }

    fun navigateTo(screen: AppScreen) {
        if (screen != AppScreen.VIDEO_CALL) {
            stopAllCallTones()
        }
        _currentScreen.value = screen
    }

    fun toggleGraphView() {
        _isGraphView.value = !_isGraphView.value
    }

    fun startMesh() {
        nearbyManager.startMesh()
    }

    fun stopMesh() {
        nearbyManager.stopMesh()
    }

    fun connectToPeer(endpointId: String) {
        nearbyManager.requestConnection(endpointId)
    }

    fun disconnectPeer(endpointId: String) {
        nearbyManager.disconnect(endpointId)
    }

    fun openConversation(peerId: String) {
        repository.selectPeer(peerId)
        _currentScreen.value = AppScreen.CONVERSATION
    }

    fun sendTextMessage(text: String) {
        if (text.isBlank()) return
        val recipientId = selectedPeerId.value ?: "*"
        repository.sendTextMessage(recipientId, text.trim())
    }

    fun startVoiceRecording() {
        repository.startVoiceNoteRecording()
    }

    fun stopAndSendVoiceRecording() {
        val recipientId = selectedPeerId.value ?: "*"
        repository.stopAndSendVoiceNote(recipientId)
    }

    fun cancelVoiceRecording() {
        repository.cancelVoiceNote()
    }

    fun playVoiceNote(messageId: String, filePath: String) {
        repository.playVoiceNote(messageId, filePath)
    }

    fun sendMediaAttachment(uri: Uri, mimeType: String) {
        val recipientId = selectedPeerId.value ?: "*"
        repository.sendMediaUri(recipientId, uri, mimeType)
    }

    fun startCall(peerNodeId: String, peerName: String, type: CallType) {
        webRtcManager.initiateCall(peerNodeId, peerName, type)
    }

    fun acceptCall() {
        stopAllCallTones()
        webRtcManager.acceptIncomingCall()
    }

    fun endCall() {
        stopAllCallTones()
        webRtcManager.rejectOrEndCall()
    }

    fun openVerificationDialog(peer: PeerNode) {
        _peerToVerify.value = peer
    }

    fun closeVerificationDialog() {
        _peerToVerify.value = null
    }

    fun markPeerVerified(peerNodeId: String) {
        repository.markPeerVerified(peerNodeId)
        _peerToVerify.value = null
    }

    fun getLocalFingerprint(): String {
        return CryptoManager.localFingerprint
    }

    override fun onCleared() {
        stopAllCallTones()
        releaseHighPerfWifiLock()
        super.onCleared()
    }
}
