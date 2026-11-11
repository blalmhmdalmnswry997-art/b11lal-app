package com.example.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.util.Log
import com.example.crypto.CryptoManager
import com.example.mesh.ChatMessage
import com.example.mesh.MediaHeader
import com.example.mesh.MediaType
import com.example.mesh.MessageStatus
import com.example.mesh.MeshPacket
import com.example.mesh.PacketType
import com.example.nearby.NearbyConnectionsManager
import com.example.webrtc.WebRtcP2PManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.UUID

class CipherMeshRepository(
    private val context: Context,
    val nearbyManager: NearbyConnectionsManager,
    val webRtcManager: WebRtcP2PManager
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val prefs = context.getSharedPreferences("ciphermesh_prefs", Context.MODE_PRIVATE)

    val localNodeId: String = prefs.getString("local_node_id", null) ?: ("mesh-" + UUID.randomUUID().toString().take(8)).also {
        prefs.edit().putString("local_node_id", it).apply()
    }
    val localNodeName: String = prefs.getString("local_node_name", null) ?: ("Ghost-" + localNodeId.takeLast(4).uppercase()).also {
        prefs.edit().putString("local_node_name", it).apply()
    }

    private val _conversations = MutableStateFlow>>(emptyMap())
    val conversations: StateFlow>> = _conversations.asStateFlow()

    private val _selectedPeerId = MutableStateFlow<String?>(null)
    val selectedPeerId: StateFlow<String?> = _selectedPeerId.asStateFlow()

    private val _verifiedPeers = MutableStateFlow<Set<String>>(emptySet())
    val verifiedPeers: StateFlow<Set<String>> = _verifiedPeers.asStateFlow()

    private var mediaRecorder: MediaRecorder? = null
    private var currentVoiceNoteFile: File? = null
    private val _isRecordingVoiceNote = MutableStateFlow<Boolean>(false)
    val isRecordingVoiceNote: StateFlow<Boolean> = _isRecordingVoiceNote.asStateFlow()

    private val _recordingDurationSec = MutableStateFlow<Int>(0)
    val recordingDurationSec: StateFlow<Int> = _recordingDurationSec.asStateFlow()

    private var mediaPlayer: MediaPlayer? = null
    private val _playingMessageId = MutableStateFlow<String?>(null)
    val playingMessageId: StateFlow<String?> = _playingMessageId.asStateFlow()

    init {
        nearbyManager.initialize(localNodeId, localNodeName)
        val welcome = ChatMessage(
            id = "sys-welcome",
            senderId = "system",
            senderName = "Mesh Core",
            recipientId = "*",
            text = "Welcome to CipherMesh. Zero-Internet P2P mesh network active.",
            timestamp = System.currentTimeMillis(),
            isOutgoing = false,
            status = MessageStatus.DELIVERED,
            hopCount = 0
        )
        _conversations.value = mapOf("*" to listOf(welcome))

        scope.launch { nearbyManager.incomingPackets.collect { handleIncomingPacket(it) } }
        scope.launch { nearbyManager.onFileReceived.collect { (h, f) -> handleCompletedFileReceived(h, f) } }
    }

    fun selectPeer(peerId: String?) { _selectedPeerId.value = peerId }
    fun markPeerVerified(peerId: String) { _verifiedPeers.value = _verifiedPeers.value + peerId }

    private fun handleIncomingPacket(packet: MeshPacket) {
        when (packet.packetType) {
            PacketType.TEXT_MESSAGE -> {
                val key = nearbyManager.getSessionKey(packet.sourceNodeId)
                val txt = try { CryptoManager.decryptText(packet.payloadCiphertext, key) } catch (e: Exception) { packet.payloadCiphertext }
                val msg = ChatMessage(
                    id = packet.packetId,
                    senderId = packet.sourceNodeId,
                    senderName = packet.sourceNodeName,
                    recipientId = packet.destinationNodeId,
                    text = txt,
                    timestamp = packet.timestamp,
                    isOutgoing = false,
                    status = MessageStatus.DELIVERED,
                    hopCount = packet.hopCount,
                    relayPath = packet.relayedBy,
                    isEncrypted = true
                )
                val convKey = if (packet.isBroadcast) "*" else packet.sourceNodeId
                val list = _conversations.value[convKey] ?: emptyList()
                if (list.none { it.id == msg.id }) _conversations.value = _conversations.value + (convKey to (list + msg))
            }
            PacketType.DELIVERY_ACK -> {
                val ackId = packet.payloadCiphertext
                _conversations.value = _conversations.value.mapValues { (_, msgs) ->
                    msgs.map { if (it.id == ackId && it.status != MessageStatus.READ) it.copy(status = MessageStatus.DELIVERED) else it }
                }
            }
            PacketType.READ_RECEIPT -> {
                val readId = packet.payloadCiphertext
                _conversations.value = _conversations.value.mapValues { (_, msgs) ->
                    msgs.map { if (it.id == readId) it.copy(status = MessageStatus.READ) else it }
                }
            }
            PacketType.WEBRTC_SIGNAL -> webRtcManager.handleIncomingSignalPacket(packet)
            else -> {}
        }
    }

    private fun handleCompletedFileReceived(header: MediaHeader, tempFile: File) {
        try {
            val safeDir = File(context.filesDir, "received_media").apply { mkdirs() }
            val destFile = File(safeDir, "${header.messageId}_${header.fileName}")
            tempFile.copyTo(destFile, overwrite = true)

            val firstPeer = nearbyManager.connectedPeers.value.values.firstOrNull()
            val peerId = _selectedPeerId.value?.takeIf { it != "*" } ?: firstPeer?.nodeId ?: "*"
            val msg = ChatMessage(
                id = header.messageId,
                senderId = peerId,
                senderName = firstPeer?.nodeName ?: "Peer",
                recipientId = localNodeId,
                text = if (header.mediaType == MediaType.AUDIO_NOTE) "Voice Note (${header.durationSeconds}s)" else header.fileName,
                mediaType = header.mediaType,
                mediaUri = destFile.absolutePath,
                fileName = header.fileName,
                fileSizeBytes = destFile.length(),
                timestamp = System.currentTimeMillis(),
                isOutgoing = false,
                status = MessageStatus.DELIVERED,
                isEncrypted = true
            )
            val list = _conversations.value[peerId] ?: emptyList()
            _conversations.value = _conversations.value + (peerId to (list + msg))
        } catch (e: Exception) { Log.e("CipherMeshRepo", "File err: ${e.message}") }
    }

    fun sendTextMessage(recipientId: String, text: String) {
        val encrypted = CryptoManager.encryptText(text, nearbyManager.getSessionKey(recipientId))
        val packet = MeshPacket(
            sourceNodeId = localNodeId,
            sourceNodeName = localNodeName,
            destinationNodeId = recipientId,
            ttl = 5,
            hopCount = 0,
            packetType = PacketType.TEXT_MESSAGE,
            payloadCiphertext = encrypted
        )
        val msg = ChatMessage(
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
        val key = if (recipientId == "*") "*" else recipientId
        val list = _conversations.value[key] ?: emptyList()
        _conversations.value = _conversations.value + (key to (list + msg))
        nearbyManager.sendMeshPacket(packet)
    }

    fun startVoiceNoteRecording() {
        try {
            val dir = File(context.cacheDir, "voice_notes").apply { mkdirs() }
            val file = File(dir, "vn_${System.currentTimeMillis()}.m4a")
            currentVoiceNoteFile = file
            mediaRecorder = (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(context) else @Suppress("DEPRECATION") MediaRecorder()).apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioSamplingRate(44100)
                setAudioEncodingBitRate(64000)
                setOutputFile(file.absolutePath)
                prepare()
                start()
            }
            _isRecordingVoiceNote.value = true
            _recordingDurationSec.value = 0
            scope.launch {
                while (_isRecordingVoiceNote.value) {
                    delay(1000)
                    _recordingDurationSec.value += 1
                }
            }
        } catch (e: Exception) { _isRecordingVoiceNote.value = false }
    }

    fun stopAndSendVoiceNote(recipientId: String) {
        if (!_isRecordingVoiceNote.value) return
        val duration = maxOf(1, _recordingDurationSec.value)
        _isRecordingVoiceNote.value = false
        try { mediaRecorder?.stop() } catch (e: Exception) {}
        try { mediaRecorder?.release() } catch (e: Exception) {}
        mediaRecorder = null
        val file = currentVoiceNoteFile
        if (file != null && file.exists() && file.length() > 0L) {
            sendMediaFile(recipientId, file, MediaType.AUDIO_NOTE, file.name, "audio/m4a", duration)
        }
    }

    fun cancelVoiceNote() {
        _isRecordingVoiceNote.value = false
        try { mediaRecorder?.stop() } catch (e: Exception) {}
        try { mediaRecorder?.release() } catch (e: Exception) {}
        mediaRecorder = null
        currentVoiceNoteFile?.delete()
        currentVoiceNoteFile = null
    }

    fun playVoiceNote(messageId: String, filePath: String) {
        if (_playingMessageId.value == messageId) { stopVoiceNote(); return }
        stopVoiceNote()
        try {
            FileInputStream(File(filePath)).use { fis ->
                mediaPlayer = MediaPlayer().apply {
                    setDataSource(fis.fd)
                    prepare()
                    start()
                    setOnCompletionListener { _playingMessageId.value = null; it.release() }
                }
            }
            _playingMessageId.value = messageId
        } catch (e: Exception) { _playingMessageId.value = null }
    }

    fun stopVoiceNote() {
        try { mediaPlayer?.stop() } catch (e: Exception) {}
        try { mediaPlayer?.release() } catch (e: Exception) {}
        mediaPlayer = null
        _playingMessageId.value = null
    }

    fun sendMediaUri(recipientId: String, uri: Uri, mimeType: String) {
        scope.launch {
            try {
                val isVideo = mimeType.contains("video")
                val tempFile = File(context.cacheDir, "media_${System.currentTimeMillis()}${if (isVideo) ".mp4" else ".jpg"}")
                if (!isVideo) {
                    val bmp = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
                    if (bmp != null) {
                        val r = minOf(1024f / bmp.width, 1024f / bmp.height, 1f)
                        val scaled = Bitmap.createScaledBitmap(bmp, (bmp.width * r).toInt(), (bmp.height * r).toInt(), true)
                        FileOutputStream(tempFile).use { scaled.compress(Bitmap.CompressFormat.JPEG, 75, it) }
                    }
                }
                if (!tempFile.exists() || tempFile.length() == 0L) {
                    context.contentResolver.openInputStream(uri)?.use { inp -> FileOutputStream(tempFile).use { out -> inp.copyTo(out) } }
                }
                if (tempFile.exists() && tempFile.length() > 0L) {
                    sendMediaFile(recipientId, tempFile, MediaType.IMAGE, tempFile.name, mimeType, 0)
                }
            } catch (e: Exception) { Log.e("CipherMeshRepo", "URI err: ${e.message}") }
        }
    }

    private fun sendMediaFile(recipientId: String, file: File, mediaType: MediaType, fileName: String, mimeType: String, durationSeconds: Int) {
        val messageId = UUID.randomUUID().toString()
        val header = MediaHeader(0L, messageId, mediaType, fileName, file.length(), mimeType, durationSeconds)
        val msg = ChatMessage(
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
        val key = if (recipientId == "*") "*" else recipientId
        val list = _conversations.value[key] ?: emptyList()
        _conversations.value = _conversations.value + (key to (list + msg))

        val peer = nearbyManager.connectedPeers.value.values.find { it.nodeId == recipientId || it.endpointId == recipientId }
        val targetEndpoint = peer?.endpointId ?: nearbyManager.connectedPeers.value.keys.firstOrNull()
        if (targetEndpoint != null) nearbyManager.sendFile(targetEndpoint, file, header)
    }
}
