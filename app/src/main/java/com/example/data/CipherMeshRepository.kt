package com.example.data

import android.content.Context
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.util.Log
import com.example.crypto.CryptoManager
import com.example.mesh.CallType
import com.example.mesh.ChatMessage
import com.example.mesh.MediaHeader
import com.example.mesh.MediaType
import com.example.mesh.MessageStatus
import com.example.mesh.MeshPacket
import com.example.mesh.PacketType
import com.example.mesh.PeerNode
import com.example.nearby.NearbyConnectionsManager
import com.example.webrtc.WebRtcP2PManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.UUID

class CipherMeshRepository(
    private val context: Context,
    val nearbyManager: NearbyConnectionsManager,
    val webRtcManager: WebRtcP2PManager
) {
    companion object {
        private const val TAG = "CipherMeshRepo"
        private const val PREFS_NAME = "ciphermesh_prefs"
        private const val KEY_NODE_ID = "local_node_id"
        private const val KEY_NODE_NAME = "local_node_name"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    val localNodeId: String
    val localNodeName: String

    private val _conversations = MutableStateFlow>>(emptyMap())
    val conversations: StateFlow>> = _conversations.asStateFlow()

    private val _selectedPeerId = MutableStateFlow<String?>(null)
    val selectedPeerId: StateFlow<String?> = _selectedPeerId.asStateFlow()

    private val _verifiedPeers = MutableStateFlow<Set<String>>(emptySet())
    val verifiedPeers: StateFlow<Set<String>> = _verifiedPeers.asStateFlow()

    private var mediaRecorder: MediaRecorder? = null
    private var currentVoiceNoteFile: File? = null
    private val _isRecordingVoiceNote = MutableStateFlow(false)
    val isRecordingVoiceNote: StateFlow<Boolean> = _isRecordingVoiceNote.asStateFlow()

    private val _recordingDurationSec = MutableStateFlow(0)
    val recordingDurationSec: StateFlow<Int> = _recordingDurationSec.asStateFlow()

    private var mediaPlayer: MediaPlayer? = null
    private val _playingMessageId = MutableStateFlow<String?>(null)
    val playingMessageId: StateFlow<String?> = _playingMessageId.asStateFlow()

    init {
        var storedId = prefs.getString(KEY_NODE_ID, null)
        if (storedId == null) {
            storedId = "mesh-" + UUID.randomUUID().toString().take(8)
            prefs.edit().putString(KEY_NODE_ID, storedId).apply()
        }
        localNodeId = storedId

        var storedName = prefs.getString(KEY_NODE_NAME, null)
        if (storedName == null) {
            storedName = "Ghost-" + localNodeId.takeLast(4).uppercase()
            prefs.edit().putString(KEY_NODE_NAME, storedName).apply()
        }
        localNodeName = storedName

        nearbyManager.initialize(localNodeId, localNodeName)
        seedInitialChannel()

        scope.launch {
            nearbyManager.incomingPackets.collect { packet ->
                handleIncomingPacket(packet)
            }
        }

        scope.launch {
            nearbyManager.onFileReceived.collect { (header, tempFile) ->
                handleCompletedFileReceived(header, tempFile)
            }
        }
    }

    private fun seedInitialChannel() {
        val welcomeMessage = ChatMessage(
            id = "sys-welcome",
            senderId = "system",
            senderName = "Mesh Core",
            recipientId = "*",
            text = "Welcome to CipherMesh. P2P mesh network active with streaming media support.",
            timestamp = System.currentTimeMillis(),
            isOutgoing = false,
            status = MessageStatus.DELIVERED,
            hopCount = 0
        )
        _conversations.value = mapOf("*" to listOf(welcomeMessage))
    }

    fun selectPeer(peerId: String?) {
        _selectedPeerId.value = peerId
    }

    fun markPeerVerified(peerId: String) {
        _verifiedPeers.value = _verifiedPeers.value + peerId
    }

    private fun handleIncomingPacket(packet: MeshPacket) {
        when (packet.packetType) {
            PacketType.TEXT_MESSAGE -> handleIncomingTextMessage(packet)
            PacketType.DELIVERY_ACK -> handleDeliveryAck(packet)
            PacketType.READ_RECEIPT -> handleReadReceipt(packet)
            PacketType.WEBRTC_SIGNAL -> webRtcManager.handleIncomingSignalPacket(packet)
            else -> {}
        }
    }

    private fun handleIncomingTextMessage(packet: MeshPacket) {
        try {
            val sessionKey = nearbyManager.getSessionKey(packet.sourceNodeId)
            val decryptedText = try {
                CryptoManager.decryptText(packet.payloadCiphertext, sessionKey)
            } catch (e: Exception) {
                packet.payloadCiphertext
            }

            val chatMessage = ChatMessage(
                id = packet.packetId,
                senderId = packet.sourceNodeId,
                senderName = packet.sourceNodeName,
                recipientId = packet.destinationNodeId,
                text = decryptedText,
                timestamp = packet.timestamp,
                isOutgoing = false,
                status = MessageStatus.DELIVERED,
                hopCount = packet.hopCount,
                relayPath = packet.relayedBy,
                isEncrypted = true
            )

            val conversationKey = if (packet.isBroadcast) "*" else packet.sourceNodeId
            val currentList = _conversations.value[conversationKey] ?: emptyList()
            if (currentList.none { it.id == chatMessage.id }) {
                _conversations.value = _conversations.value + (conversationKey to (currentList + chatMessage))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling incoming text: ${e.message}")
        }
    }

    private fun handleDeliveryAck(packet: MeshPacket) {
        val acknowledgedMessageId = packet.payloadCiphertext
        _conversations.value = _conversations.value.mapValues { (_, messages) ->
            messages.map { msg ->
                if (msg.id == acknowledgedMessageId && msg.status != MessageStatus.READ) {
                    msg.copy(status = MessageStatus.DELIVERED, hopCount = maxOf(msg.hopCount, packet.hopCount), relayPath = packet.relayedBy)
                } else msg
            }
        }
    }

    private fun handleReadReceipt(packet: MeshPacket) {
        val readMessageId = packet.payloadCiphertext
        _conversations.value = _conversations.value.mapValues { (_, messages) ->
            messages.map { msg -> if (msg.id == readMessageId) msg.copy(status = MessageStatus.READ) else msg }
        }
    }

    private fun handleCompletedFileReceived(header: MediaHeader, tempFile: File) {
        try {
            val safeDir = File(context.filesDir, "received_media")
            safeDir.mkdirs()
            val destFile = File(safeDir, "${header.messageId}_${header.fileName}")
            tempFile.copyTo(destFile, overwrite = true)

            val chatMessage = ChatMessage(
                id = header.messageId,
                senderId = "Peer",
                senderName = header.fileName,
                recipientId = localNodeId,
                text = if (header.mediaType == MediaType.AUDIO_NOTE) "Voice Note (${header.durationSeconds}s)" else header.fileName,
                mediaType = header.mediaType,
                mediaUri = destFile.absolutePath,
                fileName = header.fileName,
                fileSizeBytes = header.fileSizeBytes,
                timestamp = System.currentTimeMillis(),
                isOutgoing = false,
                status = MessageStatus.DELIVERED,
                isEncrypted = true
            )

            val conversationKey = _selectedPeerId.value ?: "*"
            val currentList = _conversations.value[conversationKey] ?: emptyList()
            _conversations.value = _conversations.value + (conversationKey to (currentList + chatMessage))
        } catch (e: Exception) {
            Log.e(TAG, "Error processing received file: ${e.message}")
        }
    }

    fun sendTextMessage(recipientId: String, text: String) {
        val sessionKey = nearbyManager.getSessionKey(recipientId)
        val encryptedText = CryptoManager.encryptText(text, sessionKey)

        val packet = MeshPacket(
            sourceNodeId = localNodeId,
            sourceNodeName = localNodeName,
            destinationNodeId = recipientId,
            ttl = 5,
            hopCount = 0,
            packetType = PacketType.TEXT_MESSAGE,
            payloadCiphertext = encryptedText
        )

        val message = ChatMessage(
            id = packet.packetId,
            senderId = localNodeId,
            senderName = localNodeName,
            recipientId = recipientId,
            text = text,
            timestamp = System.currentTimeMillis(),
            isOutgoing = true,
            status = MessageStatus.SENT,
            hopCount = 0,
            isEncrypted = true
        )

        val conversationKey = if (recipientId == "*") "*" else recipientId
        val currentList = _conversations.value[conversationKey] ?: emptyList()
        _conversations.value = _conversations.value + (conversationKey to (currentList + message))

        nearbyManager.sendMeshPacket(packet)
    }

    fun startVoiceNoteRecording() {
        try {
            val audioDir = File(context.cacheDir, "voice_notes")
            audioDir.mkdirs()
            val audioFile = File(audioDir, "vn_${System.currentTimeMillis()}.m4a")
            currentVoiceNoteFile = audioFile

            mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioSamplingRate(44100)
                setAudioEncodingBitRate(64000)
                setOutputFile(audioFile.absolutePath)
                prepare()
                start()
            }

            _isRecordingVoiceNote.value = true
            _recordingDurationSec.value = 0

            scope.launch {
                while (_isRecordingVoiceNote.value) {
                    kotlinx.coroutines.delay(1000)
                    _recordingDurationSec.value += 1
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error starting voice recording: ${e.message}")
            _isRecordingVoiceNote.value = false
        }
    }

    fun stopAndSendVoiceNote(recipientId: String) {
        if (!_isRecordingVoiceNote.value) return
        val duration = _recordingDurationSec.value
        _isRecordingVoiceNote.value = false

        try {
            mediaRecorder?.stop()
            mediaRecorder?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping recorder: ${e.message}")
        }
        mediaRecorder = null

        val file = currentVoiceNoteFile
        if (file != null && file.exists() && file.length() > 0) {
            sendMediaFile(
                recipientId = recipientId,
                file = file,
                mediaType = MediaType.AUDIO_NOTE,
                fileName = "VoiceNote_${System.currentTimeMillis()}.m4a",
                mimeType = "audio/m4a",
                durationSeconds = duration
            )
        }
    }

    fun cancelVoiceNote() {
        _isRecordingVoiceNote.value = false
        try {
            mediaRecorder?.stop()
            mediaRecorder?.release()
        } catch (e: Exception) {}
        mediaRecorder = null
        currentVoiceNoteFile?.delete()
        currentVoiceNoteFile = null
    }

    fun playVoiceNote(messageId: String, filePath: String) {
        if (_playingMessageId.value == messageId) {
            stopVoiceNote()
            return
        }
        stopVoiceNote()
        try {
            mediaPlayer = MediaPlayer().apply {
                setDataSource(filePath)
                prepare()
                start()
                setOnCompletionListener {
                    _playingMessageId.value = null
                    it.release()
                }
            }
            _playingMessageId.value = messageId
        } catch (e: Exception) {
            Log.e(TAG, "Playback error: ${e.message}")
            _playingMessageId.value = null
        }
    }

    fun stopVoiceNote() {
        try {
            mediaPlayer?.stop()
            mediaPlayer?.release()
        } catch (e: Exception) {}
        mediaPlayer = null
        _playingMessageId.value = null
    }

    fun sendMediaUri(recipientId: String, uri: Uri, mimeType: String) {
        scope.launch {
            try {
                val input: InputStream? = context.contentResolver.openInputStream(uri)
                if (input != null) {
                    val ext = if (mimeType.contains("video")) ".mp4" else if (mimeType.contains("png")) ".png" else ".jpg"
                    val mediaTypeEnum = if (mimeType.contains("video")) MediaType.VIDEO else MediaType.IMAGE
                    val tempFile = File(context.cacheDir, "media_${System.currentTimeMillis()}$ext")
                    val out = FileOutputStream(tempFile)
                    input.copyTo(out)
                    input.close()
                    out.close()

                    sendMediaFile(
                        recipientId = recipientId,
                        file = tempFile,
                        mediaType = mediaTypeEnum,
                        fileName = tempFile.name,
                        mimeType = mimeType,
                        durationSeconds = 0
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error reading media URI: ${e.message}")
            }
        }
    }

    private fun sendMediaFile(
        recipientId: String,
        file: File,
        mediaType: MediaType,
        fileName: String,
        mimeType: String,
        durationSeconds: Int
    ) {
        val messageId = UUID.randomUUID().toString()
        val header = MediaHeader(
            payloadId = 0L,
            messageId = messageId,
            mediaType = mediaType,
            fileName = fileName,
            fileSizeBytes = file.length(),
            mimeType = mimeType,
            durationSeconds = durationSeconds
        )

        val chatMessage = ChatMessage(
            id = messageId,
            senderId = localNodeId,
            senderName = localNodeName,
            recipientId = recipientId,
            text = if (mediaType == MediaType.AUDIO_NOTE) "Voice Note (${durationSeconds}s)" else fileName,
            mediaType = mediaType,
            mediaUri = file.absolutePath,
            fileName = fileName,
            fileSizeBytes = file.length(),
            timestamp = System.currentTimeMillis(),
            isOutgoing = true,
            status = MessageStatus.SENT,
            isEncrypted = true
        )

        val conversationKey = if (recipientId == "*") "*" else recipientId
        val currentList = _conversations.value[conversationKey] ?: emptyList()
        _conversations.value = _conversations.value + (conversationKey to (currentList + chatMessage))

        val peer = nearbyManager.connectedPeers.value.values.find {
            it.nodeId == recipientId || it.endpointId == recipientId
        }
        val targetEndpoint = peer?.endpointId ?: nearbyManager.connectedPeers.value.keys.firstOrNull()

        if (targetEndpoint != null) {
            nearbyManager.sendFile(targetEndpoint, file, header)
        }
    }
}
