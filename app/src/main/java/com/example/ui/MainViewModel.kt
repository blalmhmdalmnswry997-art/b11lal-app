package com.example.ui

import android.app.Application
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class AppScreen {
    RADAR,
    CHATS,
    CONVERSATION,
    VIDEO_CALL,
    DIAGNOSTICS
}

class MainViewModel(application: Application) : AndroidViewModel(application) {

    val nearbyManager = NearbyConnectionsManager(application.applicationContext)
    val webRtcManager = WebRtcP2PManager(application.applicationContext, nearbyManager)
    val repository = CipherMeshRepository(application.applicationContext, nearbyManager, webRtcManager)

    // Current Screen
    private val _currentScreen = MutableStateFlow(AppScreen.RADAR)
    val currentScreen: StateFlow<AppScreen> = _currentScreen.asStateFlow()

    // Safety Verification Dialog
    private val _peerToVerify = MutableStateFlow<PeerNode?>(null)
    val peerToVerify: StateFlow<PeerNode?> = _peerToVerify.asStateFlow()

    // Mesh Radar view mode (Radar vs Interactive Graph)
    private val _isGraphView = MutableStateFlow(false)
    val isGraphView: StateFlow<Boolean> = _isGraphView.asStateFlow()

    // State flows from managers & repository
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

    // WebRTC call states
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

    // Active conversation messages
    val activeMessages: StateFlow<List<ChatMessage>> = combine(
        conversations,
        selectedPeerId
    ) { convs, selectedId ->
        if (selectedId != null) {
            convs[selectedId] ?: emptyList()
        } else {
            convs["*"] ?: emptyList()
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        // Automatically open video call screen when incoming or outgoing call activates
        viewModelScope.launch {
            callState.collect { state ->
                if (state == CallState.INCOMING_RINGING || state == CallState.OUTGOING_RINGING || state == CallState.CONNECTED) {
                    _currentScreen.value = AppScreen.VIDEO_CALL
                } else if (state == CallState.ENDED && _currentScreen.value == AppScreen.VIDEO_CALL) {
                    _currentScreen.value = AppScreen.CONVERSATION
                }
            }
        }
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
}
