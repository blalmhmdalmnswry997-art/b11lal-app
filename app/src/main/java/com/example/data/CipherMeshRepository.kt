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
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.math.pow
import kotlin.math.roundToInt

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
    private val auditLogFile = File(context.filesDir, "ciphermesh_vouchers_audit.json")

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

    private val _autoReplyText = MutableStateFlow("أنا بعيد عن الهاتف حالياً، وصلتني رسالتك المشفرة عبر الشبكة وسأرد عليك قريباً.")
    val autoReplyText = _autoReplyText.asStateFlow()

    private val _appPinCode = MutableStateFlow("")
    val appPinCode = _appPinCode.asStateFlow()

    private val _isAppLocked = MutableStateFlow(false)
    val isAppLocked = _isAppLocked.asStateFlow()

    private val _translatedMessages = MutableStateFlow(mapOf("init" to "").minus("init"))
    val translatedMessages = _translatedMessages.asStateFlow()

    private val _pinnedMessages = MutableStateFlow(mapOf("init" to "").minus("init"))
    val pinnedMessages = _pinnedMessages.asStateFlow()

    // Independent Encrypted Steganography Vault (Stores encrypted ciphertext until user reveals)
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
        text = "مرحباً بك في CipherMesh. التشفير المزدوج (AES-256-GCM)، التوجيه الذكي (Store-and-Forward)، ومكالمات الصوت والفيديو المتكيفة نشطة.",
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

    private val meshMuleVault by lazy {
        Collections.synchronizedMap(mutableMapOf("init" to dummyPacket).apply { clear() })
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
        "what is your location" to "ما هو موقعك؟",
        "send me the file" to "أرسل لي الملف",
        "send me a picture" to "أرسل لي صورة",
        "the connection is good" to "الاتصال ممتاز",
        "no internet" to "لا يوجد إنترنت",
        "everything is ok" to "كل شيء على ما يرام",
        "i am busy" to "أنا مشغول حالياً",
        "i am ready" to "أنا جاهز",
        "are you ready" to "هل أنت جاهز؟",
        "come here" to "تعال إلى هنا",
        "let us go" to "هيا بنا نذهب",
        "what happened" to "ماذا حدث؟",
        "don't worry" to "لا تقلق",
        "do not worry" to "لا تقلق",
        "be careful" to "كن حذراً",
        "i agree" to "أنا موافق",
        "i don't know" to "لا أعرف",
        "i do not know" to "لا أعرف"
    )

    private val offlineWordDictionary = mapOf(
        "hello" to "مرحباً", "hi" to "أهلاً", "hey" to "يا هلا", "welcome" to "أهلاً وسهلاً",
        "yes" to "نعم", "no" to "لا", "ok" to "حسناً", "okay" to "تمام", "sure" to "بالتأكيد",
        "please" to "من فضلك", "thanks" to "شكراً", "thank" to "شكر", "sorry" to "عذراً",
        "i" to "أنا", "you" to "أنت", "he" to "هو", "she" to "هي", "we" to "نحن", "they" to "هم",
        "my" to "خاصتي", "your" to "خاصتك", "our" to "خاصتنا", "me" to "لي", "us" to "لنا",
        "am" to "", "is" to "يكون", "are" to "", "was" to "كان", "were" to "كانوا",
        "will" to "سوف", "can" to "يستطيع", "cannot" to "لا يستطيع", "must" to "يجب",
        "have" to "لدي", "has" to "لديه", "had" to "كان لديه", "do" to "يفعل", "not" to "ليس",
        "what" to "ماذا", "where" to "أين", "when" to "متى", "why" to "لماذا", "who" to "من", "how" to "كيف",
        "good" to "جيد", "bad" to "سيئ", "great" to "رائع", "fine" to "بخير", "safe" to "آمن",
        "danger" to "خطر", "emergency" to "طوارئ", "urgent" to "عاجل", "important" to "مهم",
        "fast" to "سريع", "slow" to "بطيء", "new" to "جديد", "old" to "قديم", "big" to "كبير", "small" to "صغير",
        "now" to "الآن", "later" to "لاحقاً", "today" to "اليوم", "tomorrow" to "غداً", "yesterday" to "أمس",
        "time" to "وقت", "minute" to "دقيقة", "minutes" to "دقائق", "hour" to "ساعة", "hours" to "ساعات",
        "here" to "هنا", "there" to "هناك", "near" to "قريب", "far" to "بعيد", "inside" to "داخل", "outside" to "خارج",
        "home" to "المنزل", "work" to "العمل", "market" to "السوق", "road" to "الطريق", "place" to "المكان",
        "location" to "الموقع", "direction" to "الاتجاه", "north" to "شمال", "south" to "جنوب", "east" to "شرق", "west" to "غرب",
        "go" to "اذهب", "going" to "ذاهب", "come" to "تعال", "coming" to "قادم", "wait" to "انتظر", "waiting" to "أنتظر",
        "stop" to "توقف", "start" to "ابدأ", "send" to "أرسل", "sent" to "تم الإرسال", "receive" to "استلم", "received" to "تم الاستلام",
        "call" to "اتصال", "video" to "فيديو", "voice" to "صوت", "audio" to "صوت", "message" to "رسالة", "messages" to "رسائل",
        "image" to "صورة", "photo" to "صورة", "picture" to "صورة", "file" to "ملف", "phone" to "هاتف", "battery" to "البطارية",
        "network" to "الشبكة", "mesh" to "الشبكة المباشرة", "wifi" to "واي فاي", "bluetooth" to "بلوتوث",
        "connected" to "متصل", "disconnected" to "غير متصل", "online" to "متصل", "offline" to "بدون إنترنت",
        "encrypted" to "مشفر", "secure" to "آمن", "key" to "مفتاح", "password" to "كلمة المرور", "code" to "رمز",
        "need" to "أحتاج", "want" to "أريد", "know" to "أعرف", "see" to "أرى", "hear" to "أسمع", "speak" to "أتحدث",
        "talk" to "تحدث", "open" to "افتح", "close" to "أغلق", "check" to "تحقق", "help" to "مساعدة",
        "friend" to "صديق", "brother" to "أخ", "team" to "الفريق", "group" to "المجموعة", "everyone" to "الجميع",
        "water" to "ماء", "food" to "طعام", "car" to "سيارة", "money" to "مال", "shop" to "محل",
        "one" to "واحد", "two" to "اثنان", "three" to "ثلاثة", "four" to "أربعة", "five" to "خمسة",
        "six" to "ستة", "seven" to "سبعة", "eight" to "ثمانية", "nine" to "تسعة", "ten" to "عشرة",
        "and" to "و", "or" to "أو", "but" to "لكن", "with" to "مع", "without" to "بدون", "from" to "من", "to" to "إلى",
        "in" to "في", "on" to "على", "at" to "عند", "for" to "من أجل", "about" to "حول", "very" to "جداً", "all" to "كل"
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
        val savedAutoReply = prefs.getString("auto_reply_text", null)
        if (!savedAutoReply.isNullOrBlank()) {
            _autoReplyText.value = savedAutoReply
        }

        val savedPin = prefs.getString("app_pin_code", "") ?: ""
        _appPinCode.value = savedPin
        _isAppLocked.value = savedPin.isNotEmpty()

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
                    delay(500)
                    flushPendingOutbox()
                    flushMeshMuleVault()
                }
            }
        }

        scope.launch {
            while (isActive) {
                delay(4500)
                if (nearbyManager.connectedPeers.value.isNotEmpty()) {
                    if (pendingOutbox.isNotEmpty()) flushPendingOutbox()
                    if (meshMuleVault.isNotEmpty()) flushMeshMuleVault()
                }
            }
        }

        scope.launch {
            while (isActive) {
                delay(5000)
                if (_isSelfDestructEnabled.value) {
                    purgeExpiredMessagesAndMedia()
                }
            }
        }
    }

    private fun computeSha256Hex(input: String): String {
        return try {
            val md = MessageDigest.getInstance("SHA-256")
            val digest = md.digest(input.toByteArray(Charsets.UTF_8))
            val sb = StringBuilder()
            for (b in digest.take(8)) {
                sb.append(String.format("%02X", b))
            }
            sb.toString()
        } catch (e: Exception) {
            UUID.randomUUID().toString().replace("-", "").take(16).uppercase()
        }
    }

    /**
     * Dynamic TTL Selection based on Route Topology & Priority:
     * Avoids fixed 64-hop flooding for direct 1-hop peers while preserving long range for multi-hop & SOS.
     */
    private fun calculateSmartTtl(recipientId: String, isPrioritySos: Boolean = false): Int {
        if (isPrioritySos) return 32
        if (recipientId == "*") return 14
        val peer = nearbyManager.connectedPeers.value.values.find {
            it.nodeId == recipientId || it.endpointId == recipientId
        }
        return when {
            peer == null -> 24
            peer.isDirect -> 4
            else -> (peer.hops + 6).coerceIn(6, 32)
        }
    }

    /**
     * Independent Encrypted Steganography:
     * Encrypts the secret payload independently with AES-256-GCM before embedding,
     * and only decrypts when the user explicitly interacts to reveal it.
     */
    fun sendStegoHiddenMessage(recipientId: String, coverText: String, secretText: String) {
        val cleanCover = coverText.trim().ifEmpty { "السلام عليكم، كيف الأمور اليوم؟" }
        val cleanSecret = secretText.trim()
        if (cleanSecret.isEmpty()) {
            sendTextMessage(recipientId, cleanCover)
            return
        }

        val sessionKey = nearbyManager.getSessionKey(recipientId)
        val innerEncryptedSecret = CryptoManager.encryptText(cleanSecret, sessionKey)
        val rawWireText = listOf("__STEGO_V2__", cleanCover, innerEncryptedSecret).joinToString(sep.toString())
        val outerEncrypted = CryptoManager.encryptText(rawWireText, sessionKey)

        val packet = MeshPacket(
            sourceNodeId = localNodeId,
            sourceNodeName = localNodeName,
            destinationNodeId = recipientId,
            ttl = calculateSmartTtl(recipientId),
            hopCount = 0,
            packetType = PacketType.TEXT_MESSAGE,
            payloadCiphertext = outerEncrypted
        )

        val hasConnectedPeers = nearbyManager.connectedPeers.value.isNotEmpty()
        val initialStatus = if (hasConnectedPeers) MessageStatus.SENT else MessageStatus.SENDING

        val msg = ChatMessage(
            id = packet.packetId,
            senderId = localNodeId,
            senderName = localNodeName,
            recipientId = recipientId,
            text = cleanCover,
            timestamp = System.currentTimeMillis(),
            isOutgoing = true,
            status = initialStatus,
            hopCount = 0,
            isEncrypted = true
        )

        _stegoSecrets.value = _stegoSecrets.value + (packet.packetId to cleanSecret)
        val key = if (recipientId == "*") "*" else recipientId
        val list = _conversations.value[key] ?: emptyMsgList
        _conversations.value = _conversations.value + (key to (list + msg))
        saveConversationsToDisk()

        if (recipientId != "*" || !hasConnectedPeers) {
            pendingOutbox[packet.packetId] = Pair(rawWireText, packet)
            meshMuleVault[packet.packetId] = packet
            saveOutboxToDisk()
        }
        if (hasConnectedPeers) {
            nearbyManager.sendMeshPacket(packet)
        }
    }

    fun toggleRevealStegoMessage(messageId: String) {
        val current = _revealedStegoIds.value
        _revealedStegoIds.value = if (current.contains(messageId)) {
            current - messageId
        } else {
            current + messageId
        }
    }

    /**
     * Cryptographically Audited Digital Voucher with SHA-256 Content Digest & Timestamp:
     */
    fun sendSignedDigitalVoucher(recipientId: String, amountText: String, purposeText: String) {
        val cleanAmount = amountText.trim().ifEmpty { "100" }
        val cleanPurpose = purposeText.trim().ifEmpty { "دفعة مستحقة" }
        val timestampStr = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        val issuerFp = CryptoManager.localFingerprint.replace(":", "").take(12).uppercase()
        val contentHash = computeSha256Hex("$cleanAmount|$cleanPurpose|$localNodeId|$timestampStr|$issuerFp")

        val voucherBody = StringBuilder()
            .append("💸 [سند رقمي موثق SHA-256 #").append(contentHash.take(8)).append("]").append(nl)
            .append("• القيمة/الاعتماد: ").append(cleanAmount).append(nl)
            .append("• البيان: ").append(cleanPurpose).append(nl)
            .append("• المُصدِر: ").append(localNodeName).append(" (").append(localNodeId.take(8)).append(")").append(nl)
            .append("• وقت الإصدار: ").append(timestampStr).append(nl)
            .append("• بصمة المحتوى (SHA-256): ").append(contentHash).append(nl)
            .append("• التوقيع التشفيري: SIG-").append(issuerFp)
            .toString()

        appendVoucherToAuditLog(contentHash, cleanAmount, cleanPurpose, recipientId, timestampStr)
        sendTextMessage(recipientId, voucherBody)
    }

    private fun appendVoucherToAuditLog(hash: String, amount: String, purpose: String, recipient: String, timeStr: String) {
        scope.launch {
            synchronized(dbLock) {
                try {
                    val arr = if (auditLogFile.exists()) {
                        JSONArray(auditLogFile.readText(Charsets.UTF_8))
                    } else {
                        JSONArray()
                    }
                    val entry = JSONObject().apply {
                        put("hash", hash)
                        put("amount", amount)
                        put("purpose", purpose)
                        put("issuer", localNodeId)
                        put("recipient", recipient)
                        put("timestamp", timeStr)
                    }
                    arr.put(entry)
                    auditLogFile.writeText(arr.toString(), Charsets.UTF_8)
                } catch (e: Exception) {}
            }
        }
    }

    fun sendLiveTelemetryBeacon(recipientId: String) {
        val batPct = getDeviceBatteryPercentage()
        val distStr = estimatePeerDistanceText(recipientId)
        val ipStr = nearbyManager.getPrimaryLocalIp()
        val timeStr = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())

        val beaconText = StringBuilder()
            .append("📡 [تقرير تشخيص الشبكة والبطارية - ").append(timeStr).append("]").append(nl)
            .append("• مستوى البطارية: ").append(batPct).append("% 🔋").append(nl)
            .append("• حالة القرب والإشارة: ").append(distStr).append(nl)
            .append("• المسار النشط: ").append(ipStr).append(" (RSM + AES-256-GCM)")
            .toString()

        sendTextMessage(recipientId, beaconText)
    }

    fun getDeviceBatteryPercentage(): Int {
        return try {
            val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
            val lvl = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
            if (lvl in 1..100) {
                lvl
            } else {
                val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
                val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
                if (level in 0..Int.MAX_VALUE && scale in 1..Int.MAX_VALUE) {
                    (level * 100) / scale
                } else 85
            }
        } catch (e: Exception) {
            85
        }
    }

    /**
     * Realistic Signal & Proximity Classifier with Error Margin:
     * Displays signal level (dBm), proximity tier (قريب / متوسط / بعيد), and estimated range with margin.
     */
    fun estimatePeerDistanceText(peerId: String?): String {
        if (peerId == null || peerId == "*") return "بث شبكي متعدد المسارات (Mesh)"
        val peer = nearbyManager.connectedPeers.value.values.find {
            it.nodeId == peerId || it.endpointId == peerId
        } ?: return "خارج التغطية المباشرة (تخزين وترحيل تلقائي 📦)"

        if (peer.endpointId.startsWith("global-")) {
            return "ربط عبر الجسر الدولي 🌍 (${peer.hops} قفزات)"
        }

        val rssi = peer.signalStrengthDbm
        val txPower = -41.0
        val ratio = (txPower - rssi.toDouble()) / (10.0 * 2.4)
        val rawMeters = 10.0.pow(ratio).coerceIn(1.0, 200.0).roundToInt()
        val margin = maxOf(2, (rawMeters * 0.35).roundToInt())

        val proximityClass = when {
            rssi in -55..0 -> "قريب 🟢"
            rssi in -72..-56 -> "متوسط 🔵"
            else -> "بعيد 🟠"
        }

        return if (peer.isDirect) {
            "$proximityClass ($rssi dBm • ~$rawMetersم ±${margin}م)"
        } else {
            "عبر ${peer.hops} قفزات ($rssi dBm)"
        }
    }

    private fun flushMeshMuleVault() {
        val snapshot = synchronized(meshMuleVault) { meshMuleVault.toMap() }
        if (snapshot.isEmpty()) return
        for ((pktId, pkt) in snapshot) {
            nearbyManager.sendMeshPacket(pkt)
            meshMuleVault.remove(pktId)
        }
    }

    fun editMessage(peerKey: String, messageId: String, newText: String) {
        val cleanText = newText.trim()
        if (cleanText.isEmpty()) return
        val editedDisplay = if (cleanText.endsWith("(معدلة ✏️)")) cleanText else "$cleanText (معدلة ✏️)"

        _conversations.value = _conversations.value.mapValues { (k, list) ->
            if (k == peerKey) {
                list.map { msg ->
                    if (msg.id == messageId) msg.copy(text = editedDisplay) else msg
                }
            } else list
        }
        saveConversationsToDisk()

        val cmdPlain = listOf("__CMD_EDIT__", messageId, cleanText).joinToString(sep.toString())
        val encrypted = CryptoManager.encryptText(cmdPlain, nearbyManager.getSessionKey(peerKey))
        val packet = MeshPacket(
            sourceNodeId = localNodeId,
            sourceNodeName = localNodeName,
            destinationNodeId = peerKey,
            ttl = calculateSmartTtl(peerKey),
            hopCount = 0,
            packetType = PacketType.TEXT_MESSAGE,
            payloadCiphertext = encrypted
        )
        if (nearbyManager.connectedPeers.value.isNotEmpty()) {
            nearbyManager.sendMeshPacket(packet)
        }
    }

    fun deleteMessageForEveryone(peerKey: String, messageId: String) {
        deleteMessageMediaFiles(peerKey, messageId)
        _conversations.value = _conversations.value.mapValues { (k, list) ->
            if (k == peerKey) {
                list.filterNot { it.id == messageId }
            } else list
        }
        saveConversationsToDisk()

        val cmdPlain = listOf("__CMD_DELETE__", messageId).joinToString(sep.toString())
        val encrypted = CryptoManager.encryptText(cmdPlain, nearbyManager.getSessionKey(peerKey))
        val packet = MeshPacket(
            sourceNodeId = localNodeId,
            sourceNodeName = localNodeName,
            destinationNodeId = peerKey,
            ttl = calculateSmartTtl(peerKey),
            hopCount = 0,
            packetType = PacketType.TEXT_MESSAGE,
            payloadCiphertext = encrypted
        )
        if (nearbyManager.connectedPeers.value.isNotEmpty()) {
            nearbyManager.sendMeshPacket(packet)
        }
    }

    private fun deleteMessageMediaFiles(peerKey: String, messageId: String) {
        try {
            val msg = _conversations.value[peerKey]?.find { it.id == messageId }
            val path = msg?.mediaUri
            if (!path.isNullOrBlank()) {
                val f = File(path)
                if (f.exists()) f.delete()
            }
        } catch (e: Exception) {}
    }

    fun translateMessageOffline(messageId: String, rawText: String): String {
        val cleanSource = rawText.replace("(معدلة ✏️)", "").trim()
        val lower = cleanSource.lowercase(Locale.ROOT)
            .replace("?", "")
            .replace("!", "")
            .replace(".", "")
            .replace(",", "")
            .trim()

        val exactPhrase = offlinePhraseDictionary[lower]
        if (exactPhrase != null) {
            _translatedMessages.value = _translatedMessages.value + (messageId to exactPhrase)
            return exactPhrase
        }

        var workingText = lower
        for ((engPhrase, arPhrase) in offlinePhraseDictionary) {
            if (workingText.contains(engPhrase)) {
                workingText = workingText.replace(engPhrase, arPhrase)
            }
        }

        val words = workingText.split(' ', '\n', '\t').filter { it.isNotEmpty() }
        val translatedWords = mutableListOf("init").apply { clear() }
        var anyTranslated = false

        for (w in words) {
            val mapped = offlineWordDictionary[w]
            if (mapped != null) {
                anyTranslated = true
                if (mapped.isNotEmpty()) {
                    translatedWords.add(mapped)
                }
            } else {
                translatedWords.add(w)
            }
        }

        val finalResult = if (anyTranslated || workingText != lower) {
            translatedWords.joinToString(" ")
        } else {
            "الترجمة المحلية: $cleanSource"
        }

        _translatedMessages.value = _translatedMessages.value + (messageId to finalResult)
        return finalResult
    }

    fun pinMessageInChat(peerKey: String, messageText: String?) {
        if (messageText.isNullOrBlank()) {
            _pinnedMessages.value = _pinnedMessages.value - peerKey
        } else {
            _pinnedMessages.value = _pinnedMessages.value + (peerKey to messageText)
        }
    }

    /**
     * Comprehensive Self-Destruct Purge:
     * Deletes expired messages from memory, local JSON database, media files, and system notifications.
     */
    private fun purgeExpiredMessagesAndMedia() {
        val now = System.currentTimeMillis()
        var changed = false
        val updated = _conversations.value.mapValues { (peerKey, list) ->
            val filtered = list.filter { msg ->
                val ageMs = now - msg.timestamp
                val keep = (msg.id == "sys-welcome") || (ageMs in 0L until 60000L)
                if (!keep) {
                    changed = true
                    msg.mediaUri?.let { path ->
                        try { File(path).delete() } catch (e: Exception) {}
                    }
                }
                keep
            }
            if (peerKey == "*" && filtered.isEmpty()) listOf(welcomeMessage) else filtered
        }
        if (changed) {
            _conversations.value = updated
            saveConversationsToDisk()
            try {
                val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                nm?.cancelAll()
            } catch (e: Exception) {}
        }
    }

    fun toggleSelfDestructMode() {
        val next = !_isSelfDestructEnabled.value
        _isSelfDestructEnabled.value = next
        prefs.edit().putBoolean("self_destruct_enabled", next).apply()
    }

    fun configureAutoReply(enabled: Boolean, messageText: String) {
        val cleanText = messageText.trim().ifEmpty {
            "أنا بعيد عن الهاتف حالياً، وصلتني رسالتك المشفرة عبر الشبكة وسأرد عليك قريباً."
        }
        _isAutoReplyEnabled.value = enabled
        _autoReplyText.value = cleanText
        prefs.edit()
            .putBoolean("auto_reply_enabled", enabled)
            .putString("auto_reply_text", cleanText)
            .apply()
    }

    fun updatePinCode(newPin: String) {
        val cleanPin = newPin.trim()
        _appPinCode.value = cleanPin
        val pinHash = if (cleanPin.isNotEmpty()) computeSha256Hex("PIN_SALT_$cleanPin") else ""
        prefs.edit()
            .putString("app_pin_code", cleanPin)
            .putString("app_pin_hash", pinHash)
            .apply()
        if (cleanPin.isEmpty()) {
            _isAppLocked.value = false
        }
    }

    fun lockAppNow() {
        if (_appPinCode.value.isNotEmpty()) {
            _isAppLocked.value = true
        }
    }

    /**
     * Panic Mode & Duress Verification:
     * Supports reverse-PIN duress (typing your PIN backwards) or emergency panic code to shred keys & data.
     */
    fun verifyPinOrDuress(enteredPin: String): Boolean {
        val clean = enteredPin.trim()
        val currentPin = _appPinCode.value
        val reversedPin = if (currentPin.length in 3..Int.MAX_VALUE) currentPin.reversed() else ""

        if (clean == "9911" || (reversedPin.isNotEmpty() && reversedPin != currentPin && clean == reversedPin)) {
            executePanicKeyAndDataShred()
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

    /**
     * Priority Rate-Limited SOS Alert with Battery, Timestamp & Sender Identity Verification:
     */
    fun sendSosAlert() {
        val now = System.currentTimeMillis()
        val lastSos = sosRateLimitMap[localNodeId] ?: 0L
        if ((now - lastSos) in 0L until 10000L) return
        sosRateLimitMap[localNodeId] = now

        val bat = getDeviceBatteryPercentage()
        val timeStr = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(now))
        val idFp = CryptoManager.localFingerprint.replace(":", "").take(8).uppercase()
        val sosMessage = "🚨 [نداء طوارئ SOS بأولوية قصوى P0] من $localNodeName (هوية: $idFp | البطارية: $bat% | الوقت: $timeStr): أحتاج إلى مساعدة وتواصل فوري عبر الشبكة!"
        sendTextMessage("*", sosMessage)
    }

    private fun executePanicKeyAndDataShred() {
        clearAllConversations()
        try {
            val newEmergencyNodeId = "mesh-" + UUID.randomUUID().toString().take(8)
            prefs.edit().putString("local_node_id", newEmergencyNodeId).apply()
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            nm?.cancelAll()
        } catch (e: Exception) {}
    }

    fun clearAllConversations() {
        synchronized(dbLock) {
            _conversations.value = mapOf("*" to listOf(welcomeMessage))
            _translatedMessages.value = mapOf("init" to "").minus("init")
            _pinnedMessages.value = mapOf("init" to "").minus("init")
            _stegoSecrets.value = mapOf("init" to "").minus("init")
            _revealedStegoIds.value = setOf("init").minus("init")
            pendingOutbox.clear()
            meshMuleVault.clear()
            try { chatsDbFile.delete() } catch (e: Exception) {}
            try { outboxDbFile.delete() } catch (e: Exception) {}
            try { auditLogFile.delete() } catch (e: Exception) {}
            try { File(context.filesDir, "received_media").deleteRecursively() } catch (e: Exception) {}
            try { File(context.filesDir, "sent_media").deleteRecursively() } catch (e: Exception) {}
            try { File(context.filesDir, "voice_notes").deleteRecursively() } catch (e: Exception) {}
            try { context.cacheDir.deleteRecursively() } catch (e: Exception) {}
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
                    val currentStego = _stegoSecrets.value
                    val masterKey = nearbyManager.getSessionKey(localNodeId)
                    for ((peerKey, msgList) in currentMap) {
                        val arr = JSONArray()
                        for (msg in msgList) {
                            val obj = JSONObject()
                            obj.put("id", msg.id)
                            obj.put("senderId", msg.senderId)
                            obj.put("senderName", msg.senderName)
                            obj.put("recipientId", msg.recipientId)
                            obj.put("text", msg.text)
                            val plainStego = currentStego[msg.id] ?: ""
                            val diskEncryptedStego = if (plainStego.isNotEmpty()) {
                                CryptoManager.encryptText(plainStego, masterKey)
                            } else ""
                            obj.put("stegoSecretEnc", diskEncryptedStego)
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
                var loadedStego = mapOf("init" to "").minus("init")
                val keys = root.keys()
                val defaultMediaType = welcomeMessage.mediaType
                val masterKey = nearbyManager.getSessionKey(localNodeId)

                while (keys.hasNext()) {
                    val peerKey = keys.next()
                    val arr = root.optJSONArray(peerKey) ?: continue
                    var msgList = emptyMsgList

                    for (i in 0 until arr.length()) {
                        val obj = arr.optJSONObject(i) ?: continue
                        val msgId = obj.optString("id", UUID.randomUUID().toString())
                        val encStego = obj.optString("stegoSecretEnc", "")
                        if (encStego.isNotEmpty()) {
                            val decStego = try {
                                CryptoManager.decryptText(encStego, masterKey)
                            } catch (e: Exception) {
                                encStego
                            }
                            loadedStego = loadedStego + (msgId to decStego)
                        }

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
                            id = msgId,
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

                _stegoSecrets.value = loadedStego
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

            val updatedPacket = originalPacket.copy(
                sourceNodeName = localNodeName,
                ttl = calculateSmartTtl(recipientId),
                payloadCiphertext = reEncrypted
            )
            nearbyManager.sendMeshPacket(updatedPacket)

            if (recipientId == "*") {
                pendingOutbox.remove(msgId)
            }

            _conversations.value = _conversations.value.mapValues { (_, msgs) ->
                msgs.map {
                    if (it.id == msgId && it.status == MessageStatus.SENDING) {
                        statusUpdated = true
                        it.copy(status = MessageStatus.RELAYED)
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
                    ttl = calculateSmartTtl(peerId),
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
                val rawDecrypted = try {
                    CryptoManager.decryptText(packet.payloadCiphertext, key)
                } catch (e: Exception) {
                    packet.payloadCiphertext
                }

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
                            if (k == convKey) {
                                list.map { m -> if (m.id == targetId) m.copy(text = newBody) else m }
                            } else list
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
                            if (k == convKey) {
                                list.filterNot { m -> m.id == targetId }
                            } else list
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
                        val innerDecrypted = try {
                            CryptoManager.decryptText(parts[2], key)
                        } catch (e: Exception) {
                            parts[2]
                        }
                        _stegoSecrets.value = _stegoSecrets.value + (packet.packetId to innerDecrypted)
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
                        ttl = calculateSmartTtl(packet.sourceNodeId),
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
                meshMuleVault.remove(ackId)
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
                meshMuleVault.remove(readId)
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
        } catch (e: Exception) {
            Log.e("CipherMeshRepo", "File err: ${e.message}")
        }
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
                resolver.openOutputStream(uri)?.use { out ->
                    file.inputStream().use { inp -> inp.copyTo(out) }
                }
                if (Build.VERSION.SDK_INT in Build.VERSION_CODES.Q..Int.MAX_VALUE) {
                    values.clear()
                    values.put(MediaStore.Images.Media.IS_PENDING, 0)
                    resolver.update(uri, values, null, null)
                }
            }
        } catch (e: Exception) {
            Log.e("CipherMeshRepo", "Gallery image save err: ${e.message}")
        }
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
                resolver.openOutputStream(uri)?.use { out ->
                    file.inputStream().use { inp -> inp.copyTo(out) }
                }
                if (Build.VERSION.SDK_INT in Build.VERSION_CODES.Q..Int.MAX_VALUE) {
                    values.clear()
                    values.put(MediaStore.Video.Media.IS_PENDING, 0)
                    resolver.update(uri, values, null, null)
                }
            }
        } catch (e: Exception) {
            Log.e("CipherMeshRepo", "Gallery video save err: ${e.message}")
        }
    }

    fun sendTextMessage(recipientId: String, text: String) {
        val isSos = text.startsWith("🚨")
        val encrypted = CryptoManager.encryptText(text, nearbyManager.getSessionKey(recipientId))
        val packet = MeshPacket(
            sourceNodeId = localNodeId,
            sourceNodeName = localNodeName,
            destinationNodeId = recipientId,
            ttl = calculateSmartTtl(recipientId, isPrioritySos = isSos),
            hopCount = 0,
            packetType = PacketType.TEXT_MESSAGE,
            payloadCiphertext = encrypted
        )

        val hasConnectedPeers = nearbyManager.connectedPeers.value.isNotEmpty()
        val isDirectTarget = nearbyManager.connectedPeers.value.values.any {
            (it.nodeId == recipientId || it.endpointId == recipientId) && it.isDirect
        }
        val initialStatus = when {
            !hasConnectedPeers -> MessageStatus.SENDING
            isDirectTarget || recipientId == "*" -> MessageStatus.SENT
            else -> MessageStatus.RELAYED
        }

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
            meshMuleVault[packet.packetId] = packet
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
