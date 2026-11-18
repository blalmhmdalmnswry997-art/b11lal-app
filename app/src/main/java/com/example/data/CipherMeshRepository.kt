package com.example.data

import android.app.NotificationManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
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
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale
import java.util.UUID
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

class CipherMeshRepository(
    private val context: Context,
    val nearbyManager: NearbyConnectionsManager,
    val webRtcManager: WebRtcP2PManager
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val prefs = context.getSharedPreferences("ciphermesh_prefs", Context.MODE_PRIVATE)
    private val dbLock = Any()

    private val sep = Char(124)
    private val nl = Char(10)

    private val chatsDbFile = File(context.filesDir, "ciphermesh_chats_db.json")
    private val outboxDbFile = File(context.filesDir, "ciphermesh_outbox_db.json")

    // Unified deterministic AES-256 key so all devices decrypt broadcast & offline messages cleanly
    private val universalMeshKey: SecretKey by lazy {
        val raw = MessageDigest.getInstance("SHA-256")
            .digest("CipherMesh_Universal_AES256_Key_2026".toByteArray(Charsets.UTF_8))
        SecretKeySpec(raw, "AES")
    }

    private val nullString: String? = null

    val localNodeId: String
    var localNodeName: String
        private set

    private val _localNodeNameFlow = MutableStateFlow("")
    val localNodeNameFlow = _localNodeNameFlow.asStateFlow()

    private val _isSelfDestructEnabled = MutableStateFlow(false)
    val isSelfDestructEnabled = _isSelfDestructEnabled.asStateFlow()

    private val _isAutoReplyEnabled = MutableStateFlow(false)
    val isAutoReplyEnabled = _isAutoReplyEnabled.asStateFlow()

    private val _autoReplyText = MutableStateFlow("أنا بعيد عن الهاتف حالياً، وصلتني رسالتك وسأرد عليك قريباً.")
    val autoReplyText = _autoReplyText.asStateFlow()

    private val _appPinCode = MutableStateFlow("")
    val appPinCode = _appPinCode.asStateFlow()

    private val _isAppLocked = MutableStateFlow(false)
    val isAppLocked = _isAppLocked.asStateFlow()

    private val _translatedMessages = MutableStateFlow(mapOf("init" to "").minus("init"))
    val translatedMessages = _translatedMessages.asStateFlow()

    private val _pinnedMessages = MutableStateFlow(mapOf("init" to "").minus("init"))
    val pinnedMessages = _pinnedMessages.asStateFlow()

    private val _stegoSecrets = MutableStateFlow(mapOf("init" to "").minus("init"))
    val stegoSecrets = _stegoSecrets.asStateFlow()

    private val _revealedStegoIds = MutableStateFlow(setOf("init").minus("init"))
    val revealedStegoIds = _revealedStegoIds.asStateFlow()

    private val autoReplyCooldownMap = Collections.synchronizedMap(mutableMapOf("init" to 0L).apply { clear() })
    private val sosRateLimitMap = Collections.synchronizedMap(mutableMapOf("init" to 0L).apply { clear() })

    private val welcomeMessage = ChatMessage(
        id = "sys-welcome",
        senderId = "system",
        senderName = "نظام الشبكة",
        recipientId = "*",
        text = "مرحباً بك في CipherMesh. الدردشة المشفرة والمكالمات الصوتية والمرئية جاهزة.",
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

    private val offlinePhraseDictionary = mapOf(
        "how are you" to "كيف حالك؟",
        "how are you doing" to "كيف أمورك؟",
        "where are you" to "أين أنت؟",
        "i am on my way" to "أنا في طريقي إليك",
        "on my way" to "في الطريق",
        "call me" to "اتصل بي",
        "can you call me" to "هل يمكنك الاتصال بي؟",
        "i need help" to "أحتاج إلى مساعدة",
        "help me" to "ساعدني",
        "are you there" to "هل أنت موجود؟",
        "wait a minute" to "انتظر دقيقة",
        "wait for me" to "انتظرني",
        "thank you" to "شكراً لك",
        "thanks a lot" to "شكراً جزيلاً",
        "good morning" to "صباح الخير",
        "good evening" to "مساء الخير",
        "good night" to "تصبح على خير",
        "see you soon" to "أراك قريباً",
        "see you later" to "أراك لاحقاً",
        "no internet" to "لا يوجد إنترنت",
        "everything is ok" to "كل شيء على ما يرام",
        "i am busy" to "أنا مشغول حالياً",
        "i am ready" to "أنا جاهز"
    )

    private val offlineWordDictionary = mapOf(
        "hello" to "مرحباً", "hi" to "أهلاً", "hey" to "يا هلا", "welcome" to "أهلاً وسهلاً",
        "yes" to "نعم", "no" to "لا", "ok" to "حسناً", "okay" to "تمام", "sure" to "بالتأكيد",
        "please" to "من فضلك", "thanks" to "شكراً", "thank" to "شكر", "sorry" to "عذراً",
        "i" to "أنا", "you" to "أنت", "he" to "هو", "she" to "هي", "we" to "نحن", "they" to "هم",
        "what" to "ماذا", "where" to "أين", "when" to "متى", "why" to "لماذا", "how" to "كيف",
        "good" to "جيد", "bad" to "سيئ", "great" to "رائع", "fine" to "بخير", "safe" to "آمن",
        "danger" to "خطر", "emergency" to "طوارئ", "urgent" to "عاجل", "important" to "مهم",
        "now" to "الآن", "later" to "لاحقاً", "today" to "اليوم", "tomorrow" to "غداً",
        "here" to "هنا", "there" to "هناك", "near" to "قريب", "far" to "بعيد",
        "home" to "المنزل", "work" to "العمل", "road" to "الطريق", "location" to "الموقع",
        "go" to "اذهب", "come" to "تعال", "wait" to "انتظر", "stop" to "توقف",
        "call" to "اتصال", "video" to "فيديو", "voice" to "صوت", "message" to "رسالة",
        "image" to "صورة", "file" to "ملف", "phone" to "هاتف", "battery" to "البطارية",
        "network" to "الشبكة", "connected" to "متصل", "offline" to "بدون إنترنت",
        "need" to "أحتاج", "want" to "أريد", "know" to "أعرف", "help" to "مساعدة",
        "friend" to "صديق", "brother" to "أخ", "team" to "الفريق", "water" to "ماء", "food" to "طعام"
    )

    init {
        var storedId = prefs.getString("local_node_id", null)
        if (storedId == null) {
            storedId = "mesh-" + UUID.randomUUID().toString().take(8)
            prefs.edit().putString("local_node_id", storedId).apply()
        }
        localNodeId = storedId

        var storedName = prefs.getString("local_node_name", null)
        if (storedName == null) {
            storedName = "مستخدم-" + localNodeId.takeLast(4).uppercase()
            prefs.edit().putString("local_node_name", storedName).apply()
        }
        localNodeName = storedName
        _localNodeNameFlow.value = storedName

        _isSelfDestructEnabled.value = prefs.getBoolean("self_destruct_enabled", false)
        _isAutoReplyEnabled.value = prefs.getBoolean("auto_reply_enabled", false)
        prefs.getString("auto_reply_text", null)?.takeIf { it.isNotBlank() }?.let { _autoReplyText.value = it }

        val savedPin = prefs.getString("app_pin_code", "") ?: ""
        _appPinCode.value = savedPin
        _isAppLocked.value = savedPin.isNotEmpty()

        prefs.getStringSet("verified_peers", null)?.let { _verifiedPeers.value = it.toSet() }
        loadConversationsFromDisk()
        loadOutboxFromDisk()

        nearbyManager.initialize(localNodeId, localNodeName)

        scope.launch {
            nearbyManager.incomingPackets.collect { packet ->
                handleIncomingPacket(packet)
            }
        }
        scope.launch {
            nearbyManager.onFileReceived.collect { (h, f) ->
                handleCompletedFileReceived(h, f)
            }
        }
        // Whenever a peer joins or reconnects, immediately sync pending messages to them
        scope.launch {
            var lastPeerCount = 0
            nearbyManager.connectedPeers.collect { peers ->
                if (peers.isNotEmpty()) {
                    delay(350)
                    flushPendingOutbox()
                    if (peers.size != lastPeerCount) {
                        lastPeerCount = peers.size
                        syncRecentMessagesToJoinedPeers()
                    }
                }
            }
        }
        scope.launch {
            while (isActive) {
                delay(3500)
                if (nearbyManager.connectedPeers.value.isNotEmpty() && pendingOutbox.isNotEmpty()) {
                    flushPendingOutbox()
                }
            }
        }
        scope.launch {
            while (isActive) {
                delay(5000)
                if (_isSelfDestructEnabled.value) purgeExpiredMessagesAndMedia()
            }
        }
    }

    private fun encryptWithUniversalKey(plainText: String): String {
        return CryptoManager.encryptText(plainText, universalMeshKey)
    }

    private fun decryptSmart(cipherText: String, senderNodeId: String): String {
        // 1. Try universal mesh key first
        try {
            val dec = CryptoManager.decryptText(cipherText, universalMeshKey)
            if (dec != cipherText && !looksLikeRawBase64Cipher(dec)) return dec
        } catch (e: Exception) {}

        // 2. Try pairwise session key
        try {
            val pairKey = nearbyManager.getSessionKey(senderNodeId)
            val dec2 = CryptoManager.decryptText(cipherText, pairKey)
            if (dec2 != cipherText && !looksLikeRawBase64Cipher(dec2)) return dec2
        } catch (e: Exception) {}

        return if (looksLikeRawBase64Cipher(cipherText)) "" else cipherText
    }

    private fun looksLikeRawBase64Cipher(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.length !in 28..600) return false
        if (trimmed.contains(' ')) return false
        val hasArabic = trimmed.any { it.code in 1536..1791 }
        if (hasArabic) return false
        val base64Chars = trimmed.count { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '+' || it == '/' || it == '=' }
        return base64Chars == trimmed.length
    }

    private fun syncRecentMessagesToJoinedPeers() {
        scope.launch {
            try {
                val allConv = _conversations.value
                for ((peerKey, msgList) in allConv) {
                    val recentOutgoing = msgList.filter { it.isOutgoing && it.id != "sys-welcome" }.takeLast(5)
                    for (msg in recentOutgoing) {
                        val enc = encryptWithUniversalKey(msg.text)
                        val syncPkt = MeshPacket(
                            packetId = msg.id,
                            sourceNodeId = localNodeId,
                            sourceNodeName = localNodeName,
                            destinationNodeId = peerKey,
                            ttl = 12,
                            hopCount = 0,
                            packetType = PacketType.TEXT_MESSAGE,
                            payloadCiphertext = enc,
                            timestamp = msg.timestamp
                        )
                        nearbyManager.sendMeshPacket(syncPkt)
                        delay(80)
                    }
                }
            } catch (e: Exception) {}
        }
    }

    fun selectPeer(peerId: String?) {
        _selectedPeerId.value = peerId
        if (peerId != null && peerId != "*") sendReadReceiptsForPeer(peerId)
    }

    fun markPeerVerified(peerId: String) {
        _verifiedPeers.value = _verifiedPeers.value + peerId
        prefs.edit().putStringSet("verified_peers", _verifiedPeers.value).apply()
    }

    fun clearAllConversations() {
        synchronized(dbLock) {
            _conversations.value = mapOf("*" to listOf(welcomeMessage))
            _translatedMessages.value = mapOf("init" to "").minus("init")
            _pinnedMessages.value = mapOf("init" to "").minus("init")
            _stegoSecrets.value = mapOf("init" to "").minus("init")
            _revealedStegoIds.value = setOf("init").minus("init")
            pendingOutbox.clear()
            try { chatsDbFile.delete() } catch (e: Exception) {}
            try { outboxDbFile.delete() } catch (e: Exception) {}
            try { File(context.filesDir, "received_media").deleteRecursively() } catch (e: Exception) {}
            try { File(context.filesDir, "sent_media").deleteRecursively() } catch (e: Exception) {}
            try { File(context.filesDir, "voice_notes").deleteRecursively() } catch (e: Exception) {}
        }
    }

    fun sendTextMessage(recipientId: String, text: String) {
        val clean = text.trim()
        if (clean.isEmpty()) return
        val encrypted = encryptWithUniversalKey(clean)
        val packet = MeshPacket(
            sourceNodeId = localNodeId,
            sourceNodeName = localNodeName,
            destinationNodeId = recipientId,
            ttl = 16,
            hopCount = 0,
            packetType = PacketType.TEXT_MESSAGE,
            payloadCiphertext = encrypted
        )

        val hasPeers = nearbyManager.connectedPeers.value.isNotEmpty()
        val msg = ChatMessage(
            id = packet.packetId,
            senderId = localNodeId,
            senderName = localNodeName,
            recipientId = recipientId,
            text = clean,
            timestamp = System.currentTimeMillis(),
            isOutgoing = true,
            status = if (hasPeers) MessageStatus.SENT else MessageStatus.SENDING,
            hopCount = 0,
            isEncrypted = true
        )
        val key = if (recipientId == "*") "*" else recipientId
        val list = _conversations.value[key] ?: emptyMsgList
        _conversations.value = _conversations.value + (key to (list + msg))
        saveConversationsToDisk()

        pendingOutbox[packet.packetId] = Pair(clean, packet)
        saveOutboxToDisk()
        nearbyManager.sendMeshPacket(packet)
    }

    fun sendStegoHiddenMessage(recipientId: String, coverText: String, secretText: String) {
        val cleanCover = coverText.trim().ifEmpty { "السلام عليكم، كيف حالك اليوم؟" }
        val cleanSecret = secretText.trim()
        if (cleanSecret.isEmpty()) {
            sendTextMessage(recipientId, cleanCover)
            return
        }

        val innerEncryptedSecret = encryptWithUniversalKey(cleanSecret)
        val rawWireText = listOf("__STEGO_V2__", cleanCover, innerEncryptedSecret).joinToString(sep.toString())
        val outerEncrypted = encryptWithUniversalKey(rawWireText)

        val packet = MeshPacket(
            sourceNodeId = localNodeId,
            sourceNodeName = localNodeName,
            destinationNodeId = recipientId,
            ttl = 16,
            hopCount = 0,
            packetType = PacketType.TEXT_MESSAGE,
            payloadCiphertext = outerEncrypted
        )

        val hasPeers = nearbyManager.connectedPeers.value.isNotEmpty()
        val msg = ChatMessage(
            id = packet.packetId,
            senderId = localNodeId,
            senderName = localNodeName,
            recipientId = recipientId,
            text = cleanCover,
            timestamp = System.currentTimeMillis(),
            isOutgoing = true,
            status = if (hasPeers) MessageStatus.SENT else MessageStatus.SENDING,
            hopCount = 0,
            isEncrypted = true
        )

        _stegoSecrets.value = _stegoSecrets.value + (packet.packetId to cleanSecret)
        val key = if (recipientId == "*") "*" else recipientId
        val list = _conversations.value[key] ?: emptyMsgList
        _conversations.value = _conversations.value + (key to (list + msg))
        saveConversationsToDisk()

        pendingOutbox[packet.packetId] = Pair(rawWireText, packet)
        saveOutboxToDisk()
        nearbyManager.sendMeshPacket(packet)
    }

    fun toggleRevealStegoMessage(messageId: String) {
        val cur = _revealedStegoIds.value
        _revealedStegoIds.value = if (cur.contains(messageId)) cur - messageId else cur + messageId
    }

    fun sendSignedDigitalVoucher(recipientId: String, amountText: String, purposeText: String) {
        val cleanAmount = amountText.trim().ifEmpty { "100" }
        val cleanPurpose = purposeText.trim().ifEmpty { "دفعة مستحقة" }
        val timestampStr = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())
        val issuerFp = CryptoManager.localFingerprint.replace(":", "").take(10).uppercase()

        val voucherBody = StringBuilder()
            .append("💸 [سند رقمي موثق]").append(nl)
            .append("• القيمة: ").append(cleanAmount).append(nl)
            .append("• البيان: ").append(cleanPurpose).append(nl)
            .append("• المُصدِر: ").append(localNodeName).append(nl)
            .append("• التاريخ: ").append(timestampStr).append(nl)
            .append("• البصمة: SIG-").append(issuerFp)
            .toString()

        sendTextMessage(recipientId, voucherBody)
    }

    fun sendLiveTelemetryBeacon(recipientId: String) {
        val batPct = getDeviceBatteryPercentage()
        val distStr = estimatePeerDistanceText(recipientId)
        val timeStr = SimpleDateFormat("HH:mm", Locale.US).format(Date())

        val beaconText = StringBuilder()
            .append("🔋 [حالة البطارية والشبكة - ").append(timeStr).append("]").append(nl)
            .append("• شحن البطارية: ").append(batPct).append("%").append(nl)
            .append("• حالة الاتصال: ").append(distStr)
            .toString()

        sendTextMessage(recipientId, beaconText)
    }

    fun getDeviceBatteryPercentage(): Int {
        return try {
            val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
            val lvl = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
            if (lvl in 1..100) lvl else {
                val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
                val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
                if (level in 0..Int.MAX_VALUE && scale in 1..Int.MAX_VALUE) (level * 100) / scale else 85
            }
        } catch (e: Exception) { 85 }
    }

    fun estimatePeerDistanceText(peerId: String?): String {
        if (peerId == null || peerId == "*") return "البث العام • متصل"
        val peer = nearbyManager.connectedPeers.value.values.find {
            it.nodeId == peerId || it.endpointId == peerId
        } ?: return "حفظ تلقائي للرسائل 📦"

        if (peer.endpointId.startsWith("global-")) {
            return "متصل عبر الإنترنت 🌍"
        }

        val rssi = peer.signalStrengthDbm
        val proximityClass = when {
            rssi in -58..0 -> "متصل قريب 🟢"
            rssi in -75..-59 -> "متصل متوسط 🔵"
            else -> "متصل بعيد 🟠"
        }
        return "$proximityClass (${peer.hops} قفزة)"
    }

    fun editMessage(peerKey: String, messageId: String, newText: String) {
        val cleanText = newText.trim()
        if (cleanText.isEmpty()) return
        val editedDisplay = if (cleanText.endsWith("(معدلة ✏️)")) cleanText else "$cleanText (معدلة ✏️)"

        _conversations.value = _conversations.value.mapValues { (k, list) ->
            if (k == peerKey) list.map { if (it.id == messageId) it.copy(text = editedDisplay) else it } else list
        }
        saveConversationsToDisk()

        val cmdPlain = listOf("__CMD_EDIT__", messageId, cleanText).joinToString(sep.toString())
        val encrypted = encryptWithUniversalKey(cmdPlain)
        val packet = MeshPacket(
            sourceNodeId = localNodeId,
            sourceNodeName = localNodeName,
            destinationNodeId = peerKey,
            ttl = 12,
            hopCount = 0,
            packetType = PacketType.TEXT_MESSAGE,
            payloadCiphertext = encrypted
        )
        nearbyManager.sendMeshPacket(packet)
    }

    fun deleteMessageForEveryone(peerKey: String, messageId: String) {
        deleteMessageMediaFiles(peerKey, messageId)
        _conversations.value = _conversations.value.mapValues { (k, list) ->
            if (k == peerKey) list.filterNot { it.id == messageId } else list
        }
        saveConversationsToDisk()

        val cmdPlain = listOf("__CMD_DELETE__", messageId).joinToString(sep.toString())
        val encrypted = encryptWithUniversalKey(cmdPlain)
        val packet = MeshPacket(
            sourceNodeId = localNodeId,
            sourceNodeName = localNodeName,
            destinationNodeId = peerKey,
            ttl = 12,
            hopCount = 0,
            packetType = PacketType.TEXT_MESSAGE,
            payloadCiphertext = encrypted
        )
        nearbyManager.sendMeshPacket(packet)
    }

    private fun deleteMessageMediaFiles(peerKey: String, messageId: String) {
        try {
            val path = _conversations.value[peerKey]?.find { it.id == messageId }?.mediaUri
            if (!path.isNullOrBlank()) File(path).takeIf { it.exists() }?.delete()
        } catch (e: Exception) {}
    }

    fun translateMessageOffline(messageId: String, rawText: String): String {
        val cleanSource = rawText.replace("(معدلة ✏️)", "").trim()
        if (looksLikeRawBase64Cipher(cleanSource)) return ""

        val lower = cleanSource.lowercase(Locale.ROOT)
            .replace("?", "")
            .replace("!", "")
            .replace(".", "")
            .replace(",", "")
            .trim()

        offlinePhraseDictionary[lower]?.let {
            _translatedMessages.value = _translatedMessages.value + (messageId to it)
            return it
        }

        var workingText = lower
        for ((eng, ar) in offlinePhraseDictionary) {
            if (workingText.contains(eng)) workingText = workingText.replace(eng, ar)
        }

        val words = workingText.split(' ').filter { it.isNotEmpty() }
        val translatedWords = mutableListOf("init").apply { clear() }
        var anyTranslated = false

        for (w in words) {
            val mapped = offlineWordDictionary[w]
            if (mapped != null) {
                anyTranslated = true
                if (mapped.isNotEmpty()) translatedWords.add(mapped)
            } else {
                translatedWords.add(w)
            }
        }

        val finalResult = if (anyTranslated || workingText != lower) {
            translatedWords.joinToString(" ")
        } else {
            "النص واضح بالعربية"
        }
        _translatedMessages.value = _translatedMessages.value + (messageId to finalResult)
        return finalResult
    }

    fun pinMessageInChat(peerKey: String, messageText: String?) {
        _pinnedMessages.value = if (messageText.isNullOrBlank()) _pinnedMessages.value - peerKey else _pinnedMessages.value + (peerKey to messageText)
    }

    private fun purgeExpiredMessagesAndMedia() {
        val now = System.currentTimeMillis()
        var changed = false
        val updated = _conversations.value.mapValues { (peerKey, list) ->
            val filtered = list.filter { msg ->
                val keep = (msg.id == "sys-welcome") || ((now - msg.timestamp) in 0L until 60000L)
                if (!keep) {
                    changed = true
                    msg.mediaUri?.let { try { File(it).delete() } catch (e: Exception) {} }
                }
                keep
            }
            if (peerKey == "*" && filtered.isEmpty()) listOf(welcomeMessage) else filtered
        }
        if (changed) {
            _conversations.value = updated
            saveConversationsToDisk()
            try { (context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager)?.cancelAll() } catch (e: Exception) {}
        }
    }

    fun toggleSelfDestructMode() {
        val next = !_isSelfDestructEnabled.value
        _isSelfDestructEnabled.value = next
        prefs.edit().putBoolean("self_destruct_enabled", next).apply()
    }

    fun configureAutoReply(enabled: Boolean, messageText: String) {
        val cleanText = messageText.trim().ifEmpty { "أنا بعيد عن الهاتف حالياً، وصلتني رسالتك وسأرد عليك قريباً." }
        _isAutoReplyEnabled.value = enabled
        _autoReplyText.value = cleanText
        prefs.edit().putBoolean("auto_reply_enabled", enabled).putString("auto_reply_text", cleanText).apply()
    }

    fun updatePinCode(newPin: String) {
        val cleanPin = newPin.trim()
        _appPinCode.value = cleanPin
        prefs.edit().putString("app_pin_code", cleanPin).apply()
        if (cleanPin.isEmpty()) _isAppLocked.value = false
    }

    fun lockAppNow() {
        if (_appPinCode.value.isNotEmpty()) _isAppLocked.value = true
    }

    fun verifyPinOrDuress(enteredPin: String): Boolean {
        val clean = enteredPin.trim()
        val currentPin = _appPinCode.value
        val reversedPin = if (currentPin.length in 3..Int.MAX_VALUE) currentPin.reversed() else ""

        if (clean == "9911" || (reversedPin.isNotEmpty() && reversedPin != currentPin && clean == reversedPin)) {
            clearAllConversations()
            _isAppLocked.value = false
            return true
        }
        if (clean == currentPin || currentPin.isEmpty()) {
            _isAppLocked.value = false
            return true
        }
        return false
    }

    fun updateNodeName(newName: String) {
        val cleanName = newName.trim()
        if (cleanName.isEmpty()) return
        localNodeName = cleanName
        _localNodeNameFlow.value = cleanName
        prefs.edit().putString("local_node_name", cleanName).apply()
        nearbyManager.initialize(localNodeId, cleanName)
        if (nearbyManager.isAdvertising.value || nearbyManager.isDiscovering.value) {
            nearbyManager.stopMesh()
            nearbyManager.startMesh()
        }
    }

    fun sendSosAlert() {
        val now = System.currentTimeMillis()
        val lastSos = sosRateLimitMap[localNodeId] ?: 0L
        if ((now - lastSos) in 0L until 8000L) return
        sosRateLimitMap[localNodeId] = now

        val bat = getDeviceBatteryPercentage()
        val timeStr = SimpleDateFormat("HH:mm", Locale.US).format(Date(now))
        val sosMessage = "🚨 [نداء طوارئ عاجل] من $localNodeName (البطارية: $bat% • الوقت: $timeStr): أحتاج إلى تواصل ومساعدة فورية!"
        sendTextMessage("*", sosMessage)
    }

    fun startVoiceNoteRecording() {
        try {
            val dir = File(context.filesDir, "voice_notes").apply { mkdirs() }
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
            FileInputStream(File(filePath)).use { fis ->
                mediaPlayer = MediaPlayer().apply {
                    setDataSource(fis.fd)
                    prepare()
                    start()
                    setOnCompletionListener {
                        _playingMessageId.value = null
                        it.release()
                    }
                }
            }
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
                val mediaDir = File(context.filesDir, "sent_media").apply { mkdirs() }
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
                        FileOutputStream(permFile).use { out -> input.copyTo(out) }
                        input.close()
                    }
                }
                if (permFile.exists() && permFile.length() in 1L..Long.MAX_VALUE) {
                    sendMediaFile(recipientId, permFile, MediaType.IMAGE, permFile.name, mimeType, 0)
                }
            } catch (e: Exception) {}
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

    private fun saveConversationsToDisk() {
        scope.launch {
            synchronized(dbLock) {
                try {
                    val root = JSONObject()
                    val currentMap = _conversations.value
                    val currentStego = _stegoSecrets.value
                    for ((peerKey, msgList) in currentMap) {
                        val arr = JSONArray()
                        for (msg in msgList) {
                            if (looksLikeRawBase64Cipher(msg.text)) continue
                            val obj = JSONObject()
                            obj.put("id", msg.id)
                            obj.put("senderId", msg.senderId)
                            obj.put("senderName", msg.senderName)
                            obj.put("recipientId", msg.recipientId)
                            obj.put("text", msg.text)
                            val plainStego = currentStego[msg.id] ?: ""
                            val encStego = if (plainStego.isNotEmpty()) encryptWithUniversalKey(plainStego) else ""
                            obj.put("stegoSecretEnc", encStego)
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
                            for (hop in msg.relayPath) relayArr.put(hop)
                            obj.put("relayPath", relayArr)
                            arr.put(obj)
                        }
                        root.put(peerKey, arr)
                    }
                    chatsDbFile.writeText(root.toString(), Charsets.UTF_8)
                } catch (e: Exception) {}
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
                var loadedStego = mapOf("init" to "").minus("init")
                val keys = root.keys()
                val defaultMediaType = welcomeMessage.mediaType

                while (keys.hasNext()) {
                    val peerKey = keys.next()
                    val arr = root.optJSONArray(peerKey) ?: continue
                    var msgList = emptyMsgList

                    for (i in 0 until arr.length()) {
                        val obj = arr.optJSONObject(i) ?: continue
                        val rawTxt = obj.optString("text", "")
                        if (looksLikeRawBase64Cipher(rawTxt)) continue

                        val msgId = obj.optString("id", UUID.randomUUID().toString())
                        val encStego = obj.optString("stegoSecretEnc", "")
                        if (encStego.isNotEmpty()) {
                            val decStego = decryptSmart(encStego, localNodeId)
                            if (decStego.isNotEmpty()) loadedStego = loadedStego + (msgId to decStego)
                        }

                        val mediaTypeStr = obj.optString("mediaType", "")
                        val mediaType = try { if (mediaTypeStr.isNotEmpty()) MediaType.valueOf(mediaTypeStr) else defaultMediaType } catch (e: Exception) { defaultMediaType }
                        val statusStr = obj.optString("status", MessageStatus.SENT.name)
                        val status = try { MessageStatus.valueOf(statusStr) } catch (e: Exception) { MessageStatus.SENT }

                        val relayArr = obj.optJSONArray("relayPath")
                        val relays = mutableListOf("init").apply { clear() }
                        if (relayArr != null) {
                            for (j in 0 until relayArr.length()) {
                                val hop = relayArr.optString(j, "")
                                if (hop.isNotEmpty()) relays.add(hop)
                            }
                        }

                        val msg = ChatMessage(
                            id = msgId,
                            senderId = obj.optString("senderId", ""),
                            senderName = obj.optString("senderName", ""),
                            recipientId = obj.optString("recipientId", peerKey),
                            text = rawTxt,
                            mediaType = mediaType,
                            mediaUri = obj.optString("mediaUri", "").ifEmpty { null },
                            fileName = obj.optString("fileName", "").ifEmpty { null },
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
                    if (msgList.isNotEmpty()) loadedMap = loadedMap + (peerKey to msgList)
                }

                _stegoSecrets.value = loadedStego
                if (loadedMap.isNotEmpty()) {
                    if (!loadedMap.containsKey("*")) loadedMap = loadedMap + ("*" to listOf(welcomeMessage))
                    _conversations.value = loadedMap
                }
            } catch (e: Exception) {}
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
                } catch (e: Exception) {}
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
                        MeshPacket.fromByteArray(Base64.decode(b64, Base64.NO_WRAP))?.let {
                            pendingOutbox[msgId] = Pair(plainText, it)
                        }
                    }
                }
            } catch (e: Exception) {}
        }
    }

    private fun flushPendingOutbox() {
        val snapshot = synchronized(pendingOutbox) { pendingOutbox.toMap() }
        if (snapshot.isEmpty()) return

        var statusUpdated = false
        for ((msgId, pair) in snapshot) {
            val plainText = pair.first
            val originalPacket = pair.second
            val reEncrypted = if (plainText.isNotEmpty()) encryptWithUniversalKey(plainText) else originalPacket.payloadCiphertext

            val updatedPacket = originalPacket.copy(
                sourceNodeName = localNodeName,
                ttl = 16,
                payloadCiphertext = reEncrypted
            )
            nearbyManager.sendMeshPacket(updatedPacket)

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
        if (statusUpdated) saveConversationsToDisk()
    }

    private fun sendReadReceiptsForPeer(peerId: String) {
        val msgs = _conversations.value[peerId] ?: return
        for (msg in msgs) {
            if (!msg.isOutgoing) {
                val readPacket = MeshPacket(
                    sourceNodeId = localNodeId,
                    sourceNodeName = localNodeName,
                    destinationNodeId = peerId,
                    ttl = 12,
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
                val rawDecrypted = decryptSmart(packet.payloadCiphertext, packet.sourceNodeId)
                if (rawDecrypted.isBlank()) return

                val convKey = if (packet.isBroadcast) "*" else packet.sourceNodeId
                val editPrefix = "__CMD_EDIT__" + sep
                val delPrefix = "__CMD_DELETE__" + sep
                val stegoV2Prefix = "__STEGO_V2__" + sep
                val stegoV1Prefix = "__STEGO__" + sep

                if (rawDecrypted.startsWith(editPrefix)) {
                    val parts = rawDecrypted.split(sep, limit = 3)
                    if (parts.size == 3) {
                        val targetId = parts[1]
                        val newBody = "${parts[2]} (معدلة ✏️)"
                        _conversations.value = _conversations.value.mapValues { (k, list) ->
                            if (k == convKey) list.map { m -> if (m.id == targetId) m.copy(text = newBody) else m } else list
                        }
                        saveConversationsToDisk()
                    }
                    return
                }

                if (rawDecrypted.startsWith(delPrefix)) {
                    val parts = rawDecrypted.split(sep, limit = 2)
                    if (parts.size == 2) {
                        val targetId = parts[1]
                        deleteMessageMediaFiles(convKey, targetId)
                        _conversations.value = _conversations.value.mapValues { (k, list) ->
                            if (k == convKey) list.filterNot { m -> m.id == targetId } else list
                        }
                        saveConversationsToDisk()
                    }
                    return
                }

                var visibleText = rawDecrypted
                if (rawDecrypted.startsWith(stegoV2Prefix)) {
                    val parts = rawDecrypted.split(sep, limit = 3)
                    if (parts.size == 3) {
                        visibleText = parts[1]
                        val innerDec = decryptSmart(parts[2], packet.sourceNodeId)
                        if (innerDec.isNotEmpty()) {
                            _stegoSecrets.value = _stegoSecrets.value + (packet.packetId to innerDec)
                        }
                    }
                } else if (rawDecrypted.startsWith(stegoV1Prefix)) {
                    val parts = rawDecrypted.split(sep, limit = 3)
                    if (parts.size == 3) {
                        visibleText = parts[1]
                        _stegoSecrets.value = _stegoSecrets.value + (packet.packetId to parts[2])
                    }
                }

                val msg = ChatMessage(
                    id = packet.packetId,
                    senderId = packet.sourceNodeId,
                    senderName = packet.sourceNodeName,
                    recipientId = packet.destinationNodeId,
                    text = visibleText,
                    timestamp = packet.timestamp,
                    isOutgoing = false,
                    status = MessageStatus.DELIVERED,
                    hopCount = packet.hopCount,
                    relayPath = packet.relayedBy,
                    isEncrypted = true
                )

                val list = _conversations.value[convKey] ?: emptyMsgList
                if (list.none { it.id == msg.id }) {
                    _conversations.value = _conversations.value + (convKey to (list + msg))
                    saveConversationsToDisk()

                    if (_isAutoReplyEnabled.value && !packet.isBroadcast && !visibleText.startsWith("🤖") && !visibleText.startsWith("🚨")) {
                        val now = System.currentTimeMillis()
                        val lastSent = autoReplyCooldownMap[packet.sourceNodeId] ?: 0L
                        if ((now - lastSent) in 30000L..Long.MAX_VALUE) {
                            autoReplyCooldownMap[packet.sourceNodeId] = now
                            scope.launch {
                                delay(1200)
                                sendTextMessage(packet.sourceNodeId, "🤖 [رد آلي]: ${_autoReplyText.value}")
                            }
                        }
                    }
                }
                if (_selectedPeerId.value == convKey && !packet.isBroadcast) {
                    val readPacket = MeshPacket(
                        sourceNodeId = localNodeId,
                        sourceNodeName = localNodeName,
                        destinationNodeId = packet.sourceNodeId,
                        ttl = 12,
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
                    msgs.map { if (it.id == ackId && it.status != MessageStatus.READ) it.copy(status = MessageStatus.DELIVERED) else it }
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
            PacketType.WEBRTC_SIGNAL -> webRtcManager.handleIncomingSignalPacket(packet)
            else -> {}
        }
    }

    private fun handleCompletedFileReceived(header: MediaHeader, tempFile: File) {
        try {
            val safeDir = File(context.filesDir, "received_media").apply { mkdirs() }
            val destFile = File(safeDir, "${header.messageId}_${header.fileName}")
            tempFile.copyTo(destFile, overwrite = true)

            if (!_isSelfDestructEnabled.value) {
                if (header.mimeType.contains("video") || header.fileName.endsWith(".mp4", ignoreCase = true)) {
                    saveVideoToGallery(destFile, header.fileName)
                } else if (header.mediaType == MediaType.IMAGE || header.mimeType.contains("image")) {
                    saveImageToGallery(destFile, header.fileName)
                }
            }

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
        } catch (e: Exception) {}
    }

    private fun saveImageToGallery(file: File, fileName: String) {
        try {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                if (Build.VERSION.SDK_INT in Build.VERSION_CODES.Q..Int.MAX_VALUE) {
                    put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/CipherMesh")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            if (uri != null) {
                resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
                if (Build.VERSION.SDK_INT in Build.VERSION_CODES.Q..Int.MAX_VALUE) {
                    values.clear()
                    values.put(MediaStore.Images.Media.IS_PENDING, 0)
                    resolver.update(uri, values, null, null)
                }
            }
        } catch (e: Exception) {}
    }

    private fun saveVideoToGallery(file: File, fileName: String) {
        try {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                if (Build.VERSION.SDK_INT in Build.VERSION_CODES.Q..Int.MAX_VALUE) {
                    put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/CipherMesh")
                    put(MediaStore.Video.Media.IS_PENDING, 1)
                }
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            if (uri != null) {
                resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
                if (Build.VERSION.SDK_INT in Build.VERSION_CODES.Q..Int.MAX_VALUE) {
                    values.clear()
                    values.put(MediaStore.Video.Media.IS_PENDING, 0)
                    resolver.update(uri, values, null, null)
                }
            }
        } catch (e: Exception) {}
    }
}
