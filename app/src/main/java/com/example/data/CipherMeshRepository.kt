package com.example.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.util.Base64
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
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.util.Collections
import java.util.UUID

class CipherMeshRepository(
    private val context: Context,
    val nearbyManager: NearbyConnectionsManager,
    val webRtcManager: WebRtcP2PManager
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val prefs = context.getSharedPreferences("ciphermesh_prefs", Context.MODE_PRIVATE)
    private val dbLock = Any()

    private val chatsDbFile = File(context.filesDir, "ciphermesh_chats_db.json")
    private val outboxDbFile = File(context.filesDir, "ciphermesh_outbox_db.json")

    private val nullString: String? = null

    val localNodeId: String
    val localNodeName: String

    private val welcomeMessage = ChatMessage(
        id = "sys-welcome",
        senderId = "system",
        senderName = "نظام الشبكة",
        recipientId = "*",
        text = "مرحباً بك في CipherMesh. المحادثات الدائمة والمزامنة الذكية بدون إنترنت نشطة.",
        timestamp = System.currentTimeMillis(),
        isOutgoing = false,
        status = MessageStatus.DELIVERED,
        hopCount = 0
    )

    private val emptyMsgList = listOf(welcomeMessage).minus(welcomeMessage)

    private val _conversations = MutableStateFlow(mapOf("*" to listOf(welcomeMessage)))
    val conversations = _conversations.asStateFlow()

    private val _selectedPeerId = MutableStateFlow(nullString)
    val selectedPeerId = _selectedPeerId.asStateFlow()

    private val _verifiedPeers = MutableStateFlow(setOf("init").minus("init"))
    val verifiedPeers = _verifiedPeers.asStateFlow()

    private val dummyPacket by lazy {
        MeshPacket(
            sourceNodeId = "",
            sourceNodeName = "",
            destinationNodeId = "",
            packetType = PacketType.TEXT_MESSAGE,
            payloadCiphertext = ""
        )
    }

    private val pendingOutbox by lazy {
        Collections.synchronizedMap(mutableMapOf("init" to Pair("", dummyPacket)).apply { clear() })
    }

    private var mediaRecorder: MediaRecorder? = null
    private var currentVoiceNoteFile: File? = null
    private val _isRecordingVoiceNote = MutableStateFlow(false)
    val isRecordingVoiceNote = _isRecordingVoiceNote.asStateFlow()

    private val _recordingDurationSec = MutableStateFlow(0)
    val recordingDurationSec = _recordingDurationSec.asStateFlow()

    private var mediaPlayer: MediaPlayer? = null
    private val _playingMessageId = MutableStateFlow(nullString)
    val playingMessageId = _playingMessageId.asStateFlow()

    init {
        var storedId = prefs.getString("local_node_id", null)
        if (storedId == null) {
            storedId = "mesh-" + UUID.randomUUID().toString().take(8)
            prefs.edit().putString("local_node_id", storedId).apply()
        }
        localNodeId = storedId

        var storedName = prefs.getString("local_node_name", null)
        if (storedName == null) {
            storedName = "Ghost-" + localNodeId.takeLast(4).uppercase()
            prefs.edit().putString("local_node_name", storedName).apply()
        }
        localNodeName = storedName

        loadVerifiedPeersFromPrefs()
        loadConversationsFromDisk()
        loadOutboxFromDisk()

        nearbyManager.initialize(localNodeId, localNodeName)

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

        scope.launch {
            nearbyManager.connectedPeers.collect { peers ->
                if (peers.isNotEmpty()) {
                    delay(800)
                    flushPendingOutbox()
                }
            }
        }

        scope.launch {
            while (isActive) {
                delay(6000)
                if (nearbyManager.connectedPeers.value.isNotEmpty() && pendingOutbox.isNotEmpty()) {
                    flushPendingOutbox()
                }
            }
        }
    }

    fun selectPeer(peerId: String?) {
        _selectedPeerId.value = peerId
        if (peerId != null && peerId != "*") {
            sendReadReceiptsForPeer(peerId)
        }
    }

    fun markPeerVerified(peerId: String) {
        _verifiedPeers.value = _verifiedPeers.value + peerId
        prefs.edit().putStringSet("verified_peers", _verifiedPeers.value).apply()
    }

    private fun loadVerifiedPeersFromPrefs() {
        val saved = prefs.getStringSet("verified_peers", null)
        if (saved != null) {
            _verifiedPeers.value = saved.toSet()
        }
    }

    private fun saveConversationsToDisk() {
        scope.launch {
            synchronized(dbLock) {
                try {
                    val root = JSONObject()
                    val currentMap = _conversations.value
                    for ((peerKey, msgList) in currentMap) {
                        val arr = JSONArray()
                        for (msg in msgList) {
                            val obj = JSONObject()
                            obj.put("id", msg.id)
                            obj.put("senderId", msg.senderId)
                            obj.put("senderName", msg.senderName)
                            obj.put("recipientId", msg.recipientId)
                            obj.put("text", msg.text)
                            obj.put("mediaType", msg.mediaType?.name ?: "")
                            obj.put("mediaUri", msg.mediaUri ?: "")
                            obj.put("fileName", msg.fileName ?: "")
                            obj.put("fileSizeBytes", msg.fileSizeBytes)
                            obj.put("timestamp", msg.timestamp)
                            obj.put("isOutgoing", msg.isOutgoing)
                            obj.put("status", msg.status.name)
                            obj.put("hopCount", msg.hopCount)
                            obj.put("isEncrypted", msg.isEncrypted)

                            val relayArr = JSONArray()
                            for (hop in msg.relayPath) {
                                relayArr.put(hop)
                            }
                            obj.put("relayPath", relayArr)
                            arr.put(obj)
                        }
                        root.put(peerKey, arr)
                    }
                    chatsDbFile.writeText(root.toString(), Charsets.UTF_8)
                } catch (e: Exception) {
                    Log.e("CipherMeshRepo", "Save DB err: ${e.message}")
                }
            }
        }
    }

    private fun loadConversationsFromDisk() {
        synchronized(dbLock) {
            try {
                if (!chatsDbFile.exists()) return
                val content = chatsDbFile.readText(Charsets.UTF_8)
                if (content.isBlank()) return

                val root = JSONObject(content)
                var loadedMap = mapOf("*" to listOf(welcomeMessage)).minus("*")
                val keys = root.keys()
                val defaultMediaType = welcomeMessage.mediaType

                while (keys.hasNext()) {
                    val peerKey = keys.next()
                    val arr = root.optJSONArray(peerKey) ?: continue
                    var msgList = emptyMsgList

                    for (i in 0 until arr.length()) {
                        val obj = arr.optJSONObject(i) ?: continue
                        val mediaTypeStr = obj.optString("mediaType", "")
                        val mediaType = try {
                            if (mediaTypeStr.isNotEmpty()) MediaType.valueOf(mediaTypeStr) else defaultMediaType
                        } catch (e: Exception) {
                            defaultMediaType
                        }

                        val statusStr = obj.optString("status", MessageStatus.SENT.name)
                        val status = try {
                            MessageStatus.valueOf(statusStr)
                        } catch (e: Exception) {
                            MessageStatus.SENT
                        }

                        val relayArr = obj.optJSONArray("relayPath")
                        val relays = mutableListOf("init").apply { clear() }
                        if (relayArr != null) {
                            for (j in 0 until relayArr.length()) {
                                val hop = relayArr.optString(j, "")
                                if (hop.isNotEmpty()) relays.add(hop)
                            }
                        }

                        val rawUri = obj.optString("mediaUri", "")
                        val rawFile = obj.optString("fileName", "")

                        val msg = ChatMessage(
                            id = obj.optString("id", UUID.randomUUID().toString()),
                            senderId = obj.optString("senderId", ""),
                            senderName = obj.optString("senderName", ""),
                            recipientId = obj.optString("recipientId", peerKey),
                            text = obj.optString("text", ""),
                            mediaType = mediaType,
                            mediaUri = rawUri.ifEmpty { null },
                            fileName = rawFile.ifEmpty { null },
                            fileSizeBytes = obj.optLong("fileSizeBytes", 0L),
                            timestamp = obj.optLong("timestamp", System.currentTimeMillis()),
                            isOutgoing = obj.optBoolean("isOutgoing", false),
                            status = status,
                            hopCount = obj.optInt("hopCount", 0),
                            relayPath = relays,
                            isEncrypted = obj.optBoolean("isEncrypted", true)
                        )
                        msgList = msgList + msg
                    }

                    if (msgList.isNotEmpty()) {
                        loadedMap = loadedMap + (peerKey to msgList)
                    }
                }

                if (loadedMap.isNotEmpty()) {
                    if (!loadedMap.containsKey("*")) {
                        loadedMap = loadedMap + ("*" to listOf(welcomeMessage))
                    }
                    _conversations.value = loadedMap
                }
            } catch (e: Exception) {
                Log.e("CipherMeshRepo", "Load DB err: ${e.message}")
            }
        }
    }

    private fun saveOutboxToDisk() {
        scope.launch {
            synchronized(dbLock) {
                try {
                    val arr = JSONArray()
                    val snapshot = synchronized(pendingOutbox) { pendingOutbox.toMap() }
                    for ((msgId, pair) in snapshot) {
                        val obj = JSONObject()
                        obj.put("msgId", msgId)
                        obj.put("plainText", pair.first)
                        obj.put("packetB64", Base64.encodeToString(pair.second.toByteArray(), Base64.NO_WRAP))
                        arr.put(obj)
                    }
                    outboxDbFile.writeText(arr.toString(), Charsets.UTF_8)
                } catch (e: Exception) {
                    Log.e("CipherMeshRepo", "Save outbox err: ${e.message}")
                }
            }
        }
    }

    private fun loadOutboxFromDisk() {
        synchronized(dbLock) {
            try {
                if (!outboxDbFile.exists()) return
                val content = outboxDbFile.readText(Charsets.UTF_8)
                if (content.isBlank()) return
                val arr = JSONArray(content)
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val msgId = obj.optString("msgId", "")
                    val plainText = obj.optString("plainText", "")
                    val b64 = obj.optString("packetB64", "")
                    if (msgId.isNotEmpty() && b64.isNotEmpty()) {
                        val bytes = Base64.decode(b64, Base64.NO_WRAP)
                        val pkt = MeshPacket.fromByteArray(bytes)
                        if (pkt != null) {
                            pendingOutbox[msgId] = Pair(plainText, pkt)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("CipherMeshRepo", "Load outbox err: ${e.message}")
            }
        }
    }

    private fun flushPendingOutbox() {
        val snapshot = synchronized(pendingOutbox) { pendingOutbox.toMap() }
        if (snapshot.isEmpty()) return

        var statusUpdated = false
        for ((msgId, pair) in snapshot) {
            val plainText = pair.first
            val originalPacket = pair.second
            val recipientId = originalPacket.destinationNodeId

            val reEncrypted = if (plainText.isNotEmpty()) {
                CryptoManager.encryptText(plainText, nearbyManager.getSessionKey(recipientId))
            } else {
                originalPacket.payloadCiphertext
            }

            val updatedPacket = originalPacket.copy(payloadCiphertext = reEncrypted)
            nearbyManager.sendMeshPacket(updatedPacket)

            if (recipientId == "*") {
                pendingOutbox.remove(msgId)
            }

            _conversations.value = _conversations.value.mapValues { (_, msgs) ->
                msgs.map {
                    if (it.id == msgId && it.status == MessageStatus.SENDING) {
                        statusUpdated = true
                        it.copy(status = MessageStatus.SENT)
                    } else it
                }
            }
        }

        saveOutboxToDisk()
        if (statusUpdated) {
            saveConversationsToDisk()
        }
    }

    private fun sendReadReceiptsForPeer(peerId: String) {
        val msgs = _conversations.value[peerId] ?: return
        for (msg in msgs) {
            if (!msg.isOutgoing) {
                val readPacket = MeshPacket(
                    sourceNodeId = localNodeId,
                    sourceNodeName = localNodeName,
                    destinationNodeId = peerId,
                    ttl = 5,
                    hopCount = 0,
                    packetType = PacketType.READ_RECEIPT,
                    payloadCiphertext = msg.id
                )
                nearbyManager.sendMeshPacket(readPacket)
            }
        }
    }

    private fun handleIncomingPacket(packet: MeshPacket) {
        when (packet.packetType) {
            PacketType.TEXT_MESSAGE -> {
                val key = nearbyManager.getSessionKey(packet.sourceNodeId)
                val txt = try {
                    CryptoManager.decryptText(packet.payloadCiphertext, key)
                } catch (e: Exception) {
                    packet.payloadCiphertext
                }
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
                val list = _conversations.value[convKey] ?: emptyMsgList
                if (list.none { it.id == msg.id }) {
                    _conversations.value = _conversations.value + (convKey to (list + msg))
                    saveConversationsToDisk()
                }
                if (_selectedPeerId.value == convKey && !packet.isBroadcast) {
                    val readPacket = MeshPacket(
                        sourceNodeId = localNodeId,
                        sourceNodeName = localNodeName,
                        destinationNodeId = packet.sourceNodeId,
                        ttl = 5,
                        hopCount = 0,
                        packetType = PacketType.READ_RECEIPT,
                        payloadCiphertext = packet.packetId
                    )
                    nearbyManager.sendMeshPacket(readPacket)
                }
            }
            PacketType.DELIVERY_ACK -> {
                val ackId = packet.payloadCiphertext
                pendingOutbox.remove(ackId)
                saveOutboxToDisk()
                _conversations.value = _conversations.value.mapValues { (_, msgs) ->
                    msgs.map {
                        if (it.id == ackId && it.status != MessageStatus.READ) {
                            it.copy(status = MessageStatus.DELIVERED)
                        } else it
                    }
                }
                saveConversationsToDisk()
            }
            PacketType.READ_RECEIPT -> {
                val readId = packet.payloadCiphertext
                pendingOutbox.remove(readId)
                saveOutboxToDisk()
                _conversations.value = _conversations.value.mapValues { (_, msgs) ->
                    msgs.map { if (it.id == readId) it.copy(status = MessageStatus.READ) else it }
                }
                saveConversationsToDisk()
            }
            PacketType.WEBRTC_SIGNAL -> {
                webRtcManager.handleIncomingSignalPacket(packet)
            }
            else -> {}
        }
    }

    private fun handleCompletedFileReceived(header: MediaHeader, tempFile: File) {
        try {
            val safeDir = File(context.filesDir, "received_media")
            safeDir.mkdirs()
            val destFile = File(safeDir, "${header.messageId}_${header.fileName}")
            tempFile.copyTo(destFile, overwrite = true)

            val firstPeer = nearbyManager.connectedPeers.value.values.firstOrNull()
            val peerId = _selectedPeerId.value ?: firstPeer?.nodeId ?: "*"
            val msg = ChatMessage(
                id = header.messageId,
                senderId = peerId,
                senderName = firstPeer?.nodeName ?: "متصل",
                recipientId = localNodeId,
                text = if (header.mediaType == MediaType.AUDIO_NOTE) "رسالة صوتية (${header.durationSeconds} ثانية)" else header.fileName,
                mediaType = header.mediaType,
                mediaUri = destFile.absolutePath,
                fileName = header.fileName,
                fileSizeBytes = destFile.length(),
                timestamp = System.currentTimeMillis(),
                isOutgoing = false,
                status = MessageStatus.DELIVERED,
                isEncrypted = true
            )
            val list = _conversations.value[peerId] ?: emptyMsgList
            if (list.none { it.id == msg.id }) {
                _conversations.value = _conversations.value + (peerId to (list + msg))
                saveConversationsToDisk()
            }
        } catch (e: Exception) {
            Log.e("CipherMeshRepo", "File err: ${e.message}")
        }
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

        val hasConnectedPeers = nearbyManager.connectedPeers.value.isNotEmpty()
        val initialStatus = if (hasConnectedPeers) MessageStatus.SENT else MessageStatus.SENDING

        val msg = ChatMessage(
            id = packet.packetId,
            senderId = localNodeId,
            senderName = localNodeName,
            recipientId = recipientId,
            text = text,
            timestamp = System.currentTimeMillis(),
            isOutgoing = true,
            status = initialStatus,
            hopCount = 0,
            isEncrypted = true
        )
        val key = if (recipientId == "*") "*" else recipientId
        val list = _conversations.value[key] ?: emptyMsgList
        _conversations.value = _conversations.value + (key to (list + msg))
        saveConversationsToDisk()

        if (recipientId != "*" || !hasConnectedPeers) {
            pendingOutbox[packet.packetId] = Pair(text, packet)
            saveOutboxToDisk()
        }

        if (hasConnectedPeers) {
            nearbyManager.sendMeshPacket(packet)
        }
    }

    fun startVoiceNoteRecording() {
        try {
            val dir = File(context.filesDir, "voice_notes")
            dir.mkdirs()
            val file = File(dir, "vn_${System.currentTimeMillis()}.m4a")
            currentVoiceNoteFile = file
            mediaRecorder = if (Build.VERSION.SDK_INT in Build.VERSION_CODES.S..Int.MAX_VALUE) {
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
        } catch (e: Exception) {
            _isRecordingVoiceNote.value = false
        }
    }

    fun stopAndSendVoiceNote(recipientId: String) {
        if (!_isRecordingVoiceNote.value) return
        val duration = maxOf(1, _recordingDurationSec.value)
        _isRecordingVoiceNote.value = false
        try { mediaRecorder?.stop() } catch (e: Exception) {}
        try { mediaRecorder?.release() } catch (e: Exception) {}
        mediaRecorder = null
        val file = currentVoiceNoteFile
        if (file != null && file.exists() && file.length() in 1L..Long.MAX_VALUE) {
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
        if (_playingMessageId.value == messageId) {
            stopVoiceNote()
            return
        }
        stopVoiceNote()
        try {
            val fis = FileInputStream(File(filePath))
            mediaPlayer = MediaPlayer().apply {
                setDataSource(fis.fd)
                prepare()
                start()
                setOnCompletionListener {
                    _playingMessageId.value = null
                    it.release()
                }
            }
            fis.close()
            _playingMessageId.value = messageId
        } catch (e: Exception) {
            _playingMessageId.value = null
        }
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
                val ext = if (isVideo) ".mp4" else ".jpg"
                val mediaDir = File(context.filesDir, "sent_media")
                mediaDir.mkdirs()
                val permFile = File(mediaDir, "media_${System.currentTimeMillis()}$ext")
                if (!isVideo) {
                    val bmp = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
                    if (bmp != null) {
                        val r = minOf(1024f / bmp.width, 1024f / bmp.height, 1f)
                        val scaled = Bitmap.createScaledBitmap(bmp, (bmp.width * r).toInt(), (bmp.height * r).toInt(), true)
                        FileOutputStream(permFile).use { scaled.compress(Bitmap.CompressFormat.JPEG, 75, it) }
                    }
                }
                if (!permFile.exists() || permFile.length() == 0L) {
                    val input: InputStream? = context.contentResolver.openInputStream(uri)
                    if (input != null) {
                        val out = FileOutputStream(permFile)
                        input.copyTo(out)
                        input.close()
                        out.close()
                    }
                }
                if (permFile.exists() && permFile.length() in 1L..Long.MAX_VALUE) {
                    sendMediaFile(recipientId, permFile, MediaType.IMAGE, permFile.name, mimeType, 0)
                }
            } catch (e: Exception) {
                Log.e("CipherMeshRepo", "URI err: ${e.message}")
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
        val header = MediaHeader(0L, messageId, mediaType, fileName, file.length(), mimeType, durationSeconds)
        val msg = ChatMessage(
            id = messageId,
            senderId = localNodeId,
            senderName = localNodeName,
            recipientId = recipientId,
            text = if (mediaType == MediaType.AUDIO_NOTE) "رسالة صوتية (${durationSeconds} ثانية)" else fileName,
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
        val list = _conversations.value[key] ?: emptyMsgList
        _conversations.value = _conversations.value + (key to (list + msg))
        saveConversationsToDisk()

        val peer = nearbyManager.connectedPeers.value.values.find { it.nodeId == recipientId || it.endpointId == recipientId }
        val targetEndpoint = peer?.endpointId ?: nearbyManager.connectedPeers.value.keys.firstOrNull()
        if (targetEndpoint != null) {
            nearbyManager.sendFile(targetEndpoint, file, header)
        }
    }
}
