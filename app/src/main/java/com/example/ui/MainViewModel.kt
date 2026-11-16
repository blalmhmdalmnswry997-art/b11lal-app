package com.example.ui

import android.app.Application
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.media.ToneGenerator
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
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

    private var activeRingtone: Ringtone? = null
    private var ringbackJob: Job? = null
    private var knownMessageIds = setOf("sys-welcome")
    private var isMessageTrackerInitialized = false

    private val _currentScreen = MutableStateFlow(AppScreen.RADAR)
    val currentScreen = _currentScreen.asStateFlow()

    private val _peerToVerify = MutableStateFlow(nullPeer)
    val peerToVerify = _peerToVerify.asStateFlow()

    private val _isGraphView = MutableStateFlow(false)
    val isGraphView = _isGraphView.asStateFlow()

    val isAdvertising = nearbyManager.isAdvertising
    val isDiscovering = nearbyManager.isDiscovering
    val discoveredPeers = nearbyManager.discoveredPeers
    val connectedPeers = nearbyManager.connectedPeers
    val packetsForwarded = nearbyManager.packetsForwardedCount
    val packetsReceived = nearbyManager.packetsReceivedCount

    val conversations = repository.conversations
    val selectedPeerId = repository.selectedPeerId
    val verifiedPeers = repository.verifiedPeers
    val isRecordingVoiceNote = repository.isRecordingVoiceNote
    val recordingDurationSec = repository.recordingDurationSec
    val playingMessageId = repository.playingMessageId

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
        viewModelScope.launch {
            callState.collect { state ->
                when (state) {
                    CallState.INCOMING_RINGING -> {
                        _currentScreen.value = AppScreen.VIDEO_CALL
                        startIncomingRingtone()
                    }
                    CallState.OUTGOING_RINGING -> {
                        _currentScreen.value = AppScreen.VIDEO_CALL
                        startOutgoingRingback()
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
                    val hasNewIncoming = allMsgs.any { msg ->
                        !knownMessageIds.contains(msg.id) && !msg.isOutgoing && msg.senderId != "system"
                    }
                    knownMessageIds = currentIds
                    if (hasNewIncoming && callState.value == CallState.IDLE) {
                        playIncomingMessageAlert()
                    }
                }
            }
        }
    }

    private fun startIncomingRingtone() {
        stopAllCallTones()
        try {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            val ringtone = RingtoneManager.getRingtone(appContext, uri)
            activeRingtone = ringtone
            ringtone?.play()
        } catch (t: Throwable) {}
    }

    private fun startOutgoingRingback() {
        stopAllCallTones()
        ringbackJob = viewModelScope.launch {
            var toneGen: ToneGenerator? = null
            try {
                toneGen = ToneGenerator(AudioManager.STREAM_VOICE_CALL, 70)
                while (isActive && callState.value == CallState.OUTGOING_RINGING) {
                    toneGen.startTone(ToneGenerator.TONE_SUP_RINGTONE, 900)
                    delay(2500)
                }
            } catch (t: Throwable) {
            } finally {
                try { toneGen?.release() } catch (t: Throwable) {}
            }
        }
    }

    private fun stopAllCallTones() {
        ringbackJob?.cancel()
        ringbackJob = null
        try {
            activeRingtone?.stop()
        } catch (t: Throwable) {}
        activeRingtone = null
    }

    private fun playIncomingMessageAlert() {
        try {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            val notif = RingtoneManager.getRingtone(appContext, uri)
            notif?.play()
        } catch (t: Throwable) {}
    }

    fun navigateTo(screen: AppScreen) {
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
        webRtcManager.acceptIncomingCall()
    }

    fun endCall() {
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
        super.onCleared()
    }
}
