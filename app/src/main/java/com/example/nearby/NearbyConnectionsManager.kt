package com.example.nearby

import android.content.Context
import android.net.wifi.WifiManager
import android.os.ParcelFileDescriptor
import com.example.crypto.CryptoManager
import com.example.mesh.MediaHeader
import com.example.mesh.MediaType
import com.example.mesh.MeshPacket
import com.example.mesh.PacketType
import com.example.mesh.PeerNode
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionsClient
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.SecretKey

class NearbyConnectionsManager(private val context: Context) {

    companion object {
        private const val SERVICE_ID = "com.example.ciphermesh.p2p"
        val STRATEGY: Strategy = Strategy.P2P_CLUSTER
        private const val MAX_SEEN_PACKETS = 10000
        private const val LAN_UDP_PORT = 45454
        private const val LAN_TCP_PORT = 45455
        private const val GLOBAL_RELAY_PORT = 1883
        private const val DEFAULT_MESH_TTL = 64
        private const val AUTO_GLOBAL_TOPIC = "ciphermesh/v2/global_auto_mesh"
    }

    private val sepChar = Char(124)
    private val cmeshPrefix = "CMESH" + sepChar
    private val videoAckBytes = byteArrayOf(0x56.toByte(), 0x41.toByte())

    private val globalRelayHosts = listOf(
        "broker.emqx.io",
        "broker.hivemq.com",
        "test.mosquitto.org"
    )

    private val connectionsClient: ConnectionsClient = Nearby.getConnectionsClient(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val prefs = context.getSharedPreferences("ciphermesh_network_prefs", Context.MODE_PRIVATE)

    var localNodeId: String = UUID.randomUUID().toString()
        private set
    var localNodeName: String = "Node-${localNodeId.take(4).uppercase()}"
        private set

    private val dummyPeer = PeerNode(endpointId = "", nodeId = "", nodeName = "")
    private val emptyPeers = mapOf("init" to dummyPeer).minus("init")
    private val emptyStringList = listOf("init").minus("init")

    private val _isAdvertising = MutableStateFlow(false)
    val isAdvertising = _isAdvertising.asStateFlow()

    private val _isDiscovering = MutableStateFlow(false)
    val isDiscovering = _isDiscovering.asStateFlow()

    private val _discoveredPeers = MutableStateFlow(emptyPeers)
    val discoveredPeers = _discoveredPeers.asStateFlow()

    private val _connectedPeers = MutableStateFlow(emptyPeers)
    val connectedPeers = _connectedPeers.asStateFlow()

    private val _gatewayIps = MutableStateFlow(emptyStringList)
    val gatewayIps = _gatewayIps.asStateFlow()

    // 100% Automatic Global Cross-Border Bridge State
    private val _isGlobalBridgeConnected = MutableStateFlow(false)
    val isGlobalBridgeConnected = _isGlobalBridgeConnected.asStateFlow()

    private val _globalFrequencyCode = MutableStateFlow("تلقائي-عالمي")
    val globalFrequencyCode = _globalFrequencyCode.asStateFlow()

    private val incomingPacketChannel = Channel(capacity = 256, onBufferOverflow = BufferOverflow.DROP_OLDEST) { _: MeshPacket -> }
    val incomingPackets = incomingPacketChannel
        .receiveAsFlow()
        .shareIn(scope, SharingStarted.Eagerly, replay = 0)

    private val incomingVideoChannel = Channel(capacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST) { _: ByteArray -> }
    val incomingVideoFrames = incomingVideoChannel
        .receiveAsFlow()
        .shareIn(scope, SharingStarted.Eagerly, replay = 0)

    private val incomingAudioChannel = Channel(capacity = 32, onBufferOverflow = BufferOverflow.DROP_OLDEST) { _: ByteArray -> }
    val incomingAudioPackets = incomingAudioChannel
        .receiveAsFlow()
        .shareIn(scope, SharingStarted.Eagerly, replay = 0)

    private val outgoingVideoChannel = Channel(capacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST) { _: ByteArray -> }
    private val outgoingAudioChannel = Channel(capacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST) { _: ByteArray -> }
    private val outgoingGlobalChannel = Channel(capacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST) { _: ByteArray -> }

    private val unackedVideoFrames = AtomicInteger(0)
    private val lastVideoSendTimestamp = AtomicLong(0L)
    private val isHighSpeedCallMode = AtomicBoolean(false)

    private val defaultSecretKey by lazy {
        CryptoManager.deriveSharedSecretFromBase64(CryptoManager.localPublicKeyBase64)
    }
    private val peerSessionKeys by lazy {
        Collections.synchronizedMap(mutableMapOf("init" to defaultSecretKey).apply { clear() })
    }

    private val nextHopRoutes = Collections.synchronizedMap(mutableMapOf("init" to "init").apply { clear() })

    private val _packetsForwardedCount = MutableStateFlow(0)
    val packetsForwardedCount = _packetsForwardedCount.asStateFlow()

    private val _packetsReceivedCount = MutableStateFlow(0)
    val packetsReceivedCount = _packetsReceivedCount.asStateFlow()

    private val dummyHeader = MediaHeader(0L, "", MediaType.IMAGE, "", 0L, "", 0)
    private val dummyFile = File("")
    private val pendingMediaHeaders = Collections.synchronizedMap(mutableMapOf(0L to dummyHeader).apply { clear() })
    private val completedPayloadFiles = Collections.synchronizedMap(mutableMapOf(0L to dummyFile).apply { clear() })

    private val dummyPayload by lazy { Payload.fromBytes(ByteArray(1)) }
    private val incomingFilePayloads by lazy {
        Collections.synchronizedMap(mutableMapOf(0L to dummyPayload).apply { clear() })
    }

    private val fileReceivedChannel = Channel(capacity = 32, onBufferOverflow = BufferOverflow.DROP_OLDEST) { _: Pair<MediaHeader, File> -> }
    val onFileReceived = fileReceivedChannel
        .receiveAsFlow()
        .shareIn(scope, SharingStarted.Eagerly, replay = 0)

    private val seenPacketIds = Collections.synchronizedSet(mutableSetOf("init").apply { clear() })
    private val dummyOut = DataOutputStream(ByteArrayOutputStream())
    private val lanOutputs = Collections.synchronizedMap(mutableMapOf("init" to dummyOut).apply { clear() })
    private val knownLanPeerIps = Collections.synchronizedSet(mutableSetOf("init").apply { clear() })
    private val connectingLanIps = Collections.synchronizedSet(mutableSetOf("init").apply { clear() })

    private var isLanRunning = false
    private var udpSocket: DatagramSocket? = null
    private var tcpServer: ServerSocket? = null
    private var globalRelaySocket: Socket? = null
    private var globalRelayOut: DataOutputStream? = null
    private val globalWriteLock = Any()
    private var multicastLock: WifiManager.MulticastLock? = null

    init {
        val saved = prefs.getStringSet("saved_gateways", null)
        if (saved != null) {
            _gatewayIps.value = saved.toList().sorted()
        }
        startMediaSenderWorkers()
        startGlobalBridgeWorker()
    }

    fun setGlobalFrequency(newFrequency: String) {
        // Automatic mode: kept for compatibility, always uses unified automatic global mesh
        _globalFrequencyCode.value = "تلقائي-عالمي"
    }

    fun setCallHighSpeedMode(enabled: Boolean) {
        isHighSpeedCallMode.set(enabled)
        clearMediaStreams()
        if (enabled) {
            try { connectionsClient.stopDiscovery() } catch (e: Exception) {}
            try { connectionsClient.stopAdvertising() } catch (e: Exception) {}
        } else {
            if (isLanRunning) {
                startAdvertising()
                startDiscovery()
            }
        }
    }

    fun clearMediaStreams() {
        unackedVideoFrames.set(0)
        while (outgoingVideoChannel.tryReceive().isSuccess) {}
        while (outgoingAudioChannel.tryReceive().isSuccess) {}
        while (incomingVideoChannel.tryReceive().isSuccess) {}
        while (incomingAudioChannel.tryReceive().isSuccess) {}
    }

    /**
     * Zero-Config Automatic Cross-Border Global Bridge:
     * Automatically links all users across countries without any manual settings.
     */
    private fun startGlobalBridgeWorker() {
        scope.launch(Dispatchers.IO) {
            for (payload in outgoingGlobalChannel) {
                sendGlobalPublishPacket(payload)
            }
        }

        scope.launch(Dispatchers.IO) {
            var hostIndex = 0
            while (isActive) {
                if (!isLanRunning) {
                    delay(1500)
                    continue
                }
                val host = globalRelayHosts[hostIndex % globalRelayHosts.size]
                hostIndex += 1
                var sock: Socket? = null
                try {
                    sock = Socket()
                    sock.tcpNoDelay = true
                    sock.keepAlive = true
                    sock.connect(InetSocketAddress(host, GLOBAL_RELAY_PORT), 4000)

                    val dis = DataInputStream(BufferedInputStream(sock.getInputStream()))
                    val dos = DataOutputStream(BufferedOutputStream(sock.getOutputStream()))

                    val clientId = "cm-" + localNodeId.takeLast(6) + "-" + (System.currentTimeMillis() % 999)
                    val connectPkt = buildMqttConnectPacket(clientId)
                    synchronized(globalWriteLock) {
                        dos.write(connectPkt)
                        dos.flush()
                    }

                    val ackType = dis.readByte().toInt() and 0xFF
                    val ackRem = readMqttRemainingLength(dis)
                    if (ackRem in 1..256) {
                        dis.skipBytes(ackRem)
                    }
                    if (ackType != 0x20) {
                        sock.close()
                        delay(2000)
                        continue
                    }

                    globalRelaySocket = sock
                    globalRelayOut = dos
                    _isGlobalBridgeConnected.value = true

                    val subPkt = buildMqttSubscribePacket(AUTO_GLOBAL_TOPIC)
                    synchronized(globalWriteLock) {
                        dos.write(subPkt)
                        dos.flush()
                    }

                    sendGlobalPresenceBeacon()
                    broadcastMultiHopPresence()

                    while (isLanRunning && !sock.isClosed && isActive) {
                        val headerByte = dis.readByte().toInt() and 0xFF
                        val remLen = readMqttRemainingLength(dis)
                        if (remLen in 0..20_000_000) {
                            val packetType = headerByte / 16
                            if (packetType == 3 && remLen in 4..20_000_000) {
                                val tHi = dis.readByte().toInt() and 0xFF
                                val tLo = dis.readByte().toInt() and 0xFF
                                val topicLen = tHi * 256 + tLo
                                val topicBytes = ByteArray(topicLen)
                                dis.readFully(topicBytes)

                                var headerConsumed = 2 + topicLen
                                val qos = (headerByte and 0x06) / 2
                                if (qos in 1..2) {
                                    dis.readByte()
                                    dis.readByte()
                                    headerConsumed += 2
                                }

                                val payloadLen = remLen - headerConsumed
                                if (payloadLen in 1..10_000_000) {
                                    val data = ByteArray(payloadLen)
                                    dis.readFully(data)
                                    handleIncomingGlobalBytes(data)
                                }
                            } else if (remLen in 1..20_000_000) {
                                val skipBuf = ByteArray(remLen)
                                dis.readFully(skipBuf)
                            }
                        } else {
                            break
                        }
                    }
                } catch (e: Exception) {
                } finally {
                    _isGlobalBridgeConnected.value = false
                    globalRelayOut = null
                    try { sock?.close() } catch (e: Exception) {}
                }
                delay(3000)
            }
        }

        scope.launch(Dispatchers.IO) {
            var pingCounter = 0
            while (isActive) {
                delay(7000)
                if (_isGlobalBridgeConnected.value && isLanRunning) {
                    try {
                        val dos = globalRelayOut
                        if (dos != null) {
                            synchronized(globalWriteLock) {
                                dos.writeByte(0xC0)
                                dos.writeByte(0x00)
                                dos.flush()
                            }
                            sendGlobalPresenceBeacon()
                            if (pingCounter % 2 == 0) {
                                broadcastMultiHopPresence()
                            }
                            pingCounter += 1
                        }
                    } catch (e: Exception) {
                        _isGlobalBridgeConnected.value = false
                        try { globalRelaySocket?.close() } catch (ex: Exception) {}
                    }
                }
            }
        }
    }

    private fun sendGlobalPresenceBeacon() {
        val beaconText = listOf("CMESH", localNodeId, localNodeName, "GLOBAL").joinToString(sepChar.toString())
        val beaconBytes = beaconText.toByteArray(Charsets.UTF_8)
        val framed = ByteArray(2 + beaconBytes.size)
        framed[0] = 0x47.toByte()
        framed[1] = 0x42.toByte()
        System.arraycopy(beaconBytes, 0, framed, 2, beaconBytes.size)
        outgoingGlobalChannel.trySend(framed)
    }

    private fun sendGlobalPublishPacket(payloadBytes: ByteArray) {
        val dos = globalRelayOut ?: return
        if (!_isGlobalBridgeConnected.value) return
        try {
            val topicBytes = AUTO_GLOBAL_TOPIC.toByteArray(Charsets.UTF_8)
            val remLen = 2 + topicBytes.size + payloadBytes.size
            val out = ByteArrayOutputStream(5 + remLen)
            out.write(0x30)
            writeMqttRemainingLength(out, remLen)
            out.write((topicBytes.size / 256) and 0xFF)
            out.write(topicBytes.size and 0xFF)
            out.write(topicBytes)
            out.write(payloadBytes)

            val pktBytes = out.toByteArray()
            synchronized(globalWriteLock) {
                dos.write(pktBytes)
                dos.flush()
            }
        } catch (e: Exception) {
            _isGlobalBridgeConnected.value = false
            try { globalRelaySocket?.close() } catch (ex: Exception) {}
        }
    }

    private fun handleIncomingGlobalBytes(data: ByteArray) {
        if (data.size in 0..2) return
        val b0 = data[0]
        val b1 = data[1]
        val body = data.copyOfRange(2, data.size)

        if (b0 == 0x47.toByte() && b1 == 0x42.toByte()) {
            val msg = String(body, Charsets.UTF_8)
            if (msg.startsWith(cmeshPrefix)) {
                val parts = msg.split(sepChar)
                if (parts.size in 3..10) {
                    val peerNodeId = parts[1]
                    val peerName = parts[2]
                    if (peerNodeId != localNodeId) {
                        val globalEpId = "global-$peerNodeId"
                        val alreadyDirect = _connectedPeers.value.values.any { it.nodeId == peerNodeId && !it.endpointId.startsWith("global-") }
                        if (!alreadyDirect && !_connectedPeers.value.containsKey(globalEpId)) {
                            val cleanName = peerName.replace(" (Wi-Fi)", "").replace(" (دولي 🌍)", "")
                            val peerNode = PeerNode(
                                endpointId = globalEpId,
                                nodeId = peerNodeId,
                                nodeName = "$cleanName (دولي 🌍)",
                                isConnected = true,
                                isDirect = false,
                                hops = 2,
                                signalStrengthDbm = -50
                            )
                            _connectedPeers.value = _connectedPeers.value + (globalEpId to peerNode)
                            broadcastMultiHopPresence()
                        }
                    }
                }
            }
            return
        }

        if (b0 == 0x56.toByte() && b1 == 0x46.toByte()) {
            incomingVideoChannel.trySend(body)
            return
        }

        if (b0 == 0x41.toByte() && b1 == 0x46.toByte()) {
            incomingAudioChannel.trySend(body)
            return
        }

        if (b0 == 0x4D.toByte() && b1 == 0x50.toByte()) {
            val pkt = MeshPacket.fromByteArray(body) ?: return
            if (pkt.sourceNodeId == localNodeId) return
            handleIncomingBytes("global-${pkt.sourceNodeId}", body)
        }
    }

    private fun buildMqttConnectPacket(clientId: String): ByteArray {
        val cidBytes = clientId.toByteArray(Charsets.UTF_8)
        val varHeader = byteArrayOf(
            0x00, 0x04,
            0x4D, 0x51, 0x54, 0x54,
            0x04,
            0x02,
            0x00, 0x3C
        )
        val remLen = varHeader.size + 2 + cidBytes.size
        val out = ByteArrayOutputStream(5 + remLen)
        out.write(0x10)
        writeMqttRemainingLength(out, remLen)
        out.write(varHeader)
        out.write((cidBytes.size / 256) and 0xFF)
        out.write(cidBytes.size and 0xFF)
        out.write(cidBytes)
        return out.toByteArray()
    }

    private fun buildMqttSubscribePacket(topic: String): ByteArray {
        val topicBytes = topic.toByteArray(Charsets.UTF_8)
        val remLen = 2 + 2 + topicBytes.size + 1
        val out = ByteArrayOutputStream(5 + remLen)
        out.write(0x82)
        writeMqttRemainingLength(out, remLen)
        out.write(0x00)
        out.write(0x01)
        out.write((topicBytes.size / 256) and 0xFF)
        out.write(topicBytes.size and 0xFF)
        out.write(topicBytes)
        out.write(0x00)
        return out.toByteArray()
    }

    private fun writeMqttRemainingLength(out: ByteArrayOutputStream, length: Int) {
        var x = length
        do {
            var enc = x % 128
            x /= 128
            if (x in 1..Int.MAX_VALUE) {
                enc = enc or 128
            }
            out.write(enc)
        } while (x in 1..Int.MAX_VALUE)
    }

    private fun readMqttRemainingLength(dis: DataInputStream): Int {
        var multiplier = 1
        var value = 0
        while (true) {
            val encodedByte = dis.readByte().toInt() and 0xFF
            value += (encodedByte and 127) * multiplier
            if ((encodedByte and 128) == 0) break
            multiplier *= 128
            if (multiplier in (128 * 128 * 128 + 1)..Int.MAX_VALUE) break
        }
        return value
    }

    private fun startMediaSenderWorkers() {
        scope.launch(Dispatchers.IO) {
            for (rawFrame in outgoingVideoChannel) {
                try {
                    val header = byteArrayOf(0x56.toByte(), 0x46.toByte())
                    val framedPacket = ByteArray(header.size + rawFrame.size)
                    System.arraycopy(header, 0, framedPacket, 0, header.size)
                    System.arraycopy(rawFrame, 0, framedPacket, header.size, rawFrame.size)

                    if (knownLanPeerIps.isNotEmpty()) {
                        sendUdpFastFrame(0x56.toByte(), 0x46.toByte(), rawFrame, includeBroadcast = false)
                    }

                    val activeLan = lanOutputs.entries.toList()
                    for ((epId, dos) in activeLan) {
                        try {
                            synchronized(dos) {
                                dos.writeByte(1)
                                dos.writeInt(framedPacket.size)
                                dos.write(framedPacket)
                                dos.flush()
                            }
                        } catch (e: Exception) {
                            lanOutputs.remove(epId, dos)
                            try { dos.close() } catch (ex: Exception) {}
                        }
                    }

                    if (_isGlobalBridgeConnected.value && _connectedPeers.value.keys.any { it.startsWith("global-") }) {
                        outgoingGlobalChannel.trySend(framedPacket)
                    }

                    val nearbyEndpoints = _connectedPeers.value.values
                        .map { it.endpointId }
                        .filter { !it.startsWith("lan-") && !it.startsWith("global-") }
                        .distinct()

                    if (nearbyEndpoints.isNotEmpty() && framedPacket.size in 1..32000) {
                        var waited = 0
                        while (unackedVideoFrames.get() in 2..Int.MAX_VALUE && waited in 0..32) {
                            delay(10)
                            waited += 1
                        }
                        if (unackedVideoFrames.get() in 2..Int.MAX_VALUE) {
                            unackedVideoFrames.set(1)
                        }

                        val latestFrame = outgoingVideoChannel.tryReceive().getOrNull() ?: rawFrame
                        val finalPacket = if (latestFrame !== rawFrame) {
                            ByteArray(header.size + latestFrame.size).also { pkt ->
                                System.arraycopy(header, 0, pkt, 0, header.size)
                                System.arraycopy(latestFrame, 0, pkt, header.size, latestFrame.size)
                            }
                        } else {
                            framedPacket
                        }

                        unackedVideoFrames.incrementAndGet()
                        lastVideoSendTimestamp.set(System.currentTimeMillis())

                        for (epId in nearbyEndpoints) {
                            try {
                                connectionsClient.sendPayload(epId, Payload.fromBytes(finalPacket))
                                    .addOnFailureListener {
                                        unackedVideoFrames.set(0)
                                    }
                            } catch (e: Exception) {
                                unackedVideoFrames.set(0)
                            }
                        }
                    }
                } catch (e: Exception) {
                    unackedVideoFrames.set(0)
                }
            }
        }

        scope.launch(Dispatchers.IO) {
            for (audioBytes in outgoingAudioChannel) {
                try {
                    val header = byteArrayOf(0x41.toByte(), 0x46.toByte())
                    val framedPacket = ByteArray(header.size + audioBytes.size)
                    System.arraycopy(header, 0, framedPacket, 0, header.size)
                    System.arraycopy(audioBytes, 0, framedPacket, header.size, audioBytes.size)

                    sendUdpFastFrame(0x41.toByte(), 0x46.toByte(), audioBytes, includeBroadcast = true)

                    val activeLan = lanOutputs.entries.toList()
                    for ((epId, dos) in activeLan) {
                        try {
                            synchronized(dos) {
                                dos.writeByte(1)
                                dos.writeInt(framedPacket.size)
                                dos.write(framedPacket)
                                dos.flush()
                            }
                        } catch (e: Exception) {
                            lanOutputs.remove(epId, dos)
                            try { dos.close() } catch (ex: Exception) {}
                        }
                    }

                    if (_isGlobalBridgeConnected.value && _connectedPeers.value.keys.any { it.startsWith("global-") }) {
                        outgoingGlobalChannel.trySend(framedPacket)
                    }

                    val nearbyEndpoints = _connectedPeers.value.values
                        .map { it.endpointId }
                        .filter { !it.startsWith("lan-") && !it.startsWith("global-") }
                        .distinct()

                    if (nearbyEndpoints.isNotEmpty() && framedPacket.size in 1..32000) {
                        for (epId in nearbyEndpoints) {
                            try {
                                connectionsClient.sendPayload(epId, Payload.fromBytes(framedPacket))
                            } catch (e: Exception) {}
                        }
                    }
                } catch (e: Exception) {}
            }
        }
    }

    private fun markPacketSeen(packetId: String): Boolean {
        synchronized(seenPacketIds) {
            if (!seenPacketIds.add(packetId)) return false
            if (seenPacketIds.size in MAX_SEEN_PACKETS..Int.MAX_VALUE) {
                val it = seenPacketIds.iterator()
                if (it.hasNext()) {
                    it.next()
                    it.remove()
                }
            }
            return true
        }
    }

    fun initialize(nodeId: String, nodeName: String) {
        this.localNodeId = nodeId
        this.localNodeName = nodeName
    }

    fun getPrimaryLocalIp(): String {
        val ips = getLocalIPv4Addresses()
        val baseIp = ips.firstOrNull() ?: "اتصال مباشر P2P"
        return if (_isGlobalBridgeConnected.value) "$baseIp + جسر دولي تلقائي 🌍" else baseIp
    }

    fun addGatewayIp(rawIp: String) {
        val cleaned = rawIp.trim()
        if (cleaned.isEmpty()) return
        val updated = (_gatewayIps.value + cleaned).distinct().sorted()
        _gatewayIps.value = updated
        prefs.edit().putStringSet("saved_gateways", updated.toSet()).apply()
        knownLanPeerIps.add(cleaned)
    }

    fun removeGatewayIp(ip: String) {
        val updated = _gatewayIps.value.filter { it != ip }
        _gatewayIps.value = updated
        prefs.edit().putStringSet("saved_gateways", updated.toSet()).apply()
        knownLanPeerIps.remove(ip)
        disconnect("lan-$ip")
    }

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            when (payload.type) {
                Payload.Type.BYTES -> {
                    val bytes = payload.asBytes() ?: return
                    handleIncomingBytes(endpointId, bytes)
                }
                Payload.Type.FILE -> {
                    incomingFilePayloads[payload.id] = payload
                }
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            if (update.status == PayloadTransferUpdate.Status.SUCCESS) {
                val payload = incomingFilePayloads.remove(update.payloadId) ?: return
                scope.launch {
                    val extractedFile = extractPayloadFile(update.payloadId, payload)
                    if (extractedFile != null && extractedFile.exists()) {
                        val header = pendingMediaHeaders.remove(update.payloadId)
                        if (header != null) {
                            fileReceivedChannel.trySend(Pair(header, extractedFile))
                        } else {
                            completedPayloadFiles[update.payloadId] = extractedFile
                        }
                    }
                }
            }
        }
    }

    private fun extractPayloadFile(payloadId: Long, payload: Payload): File? {
        try {
            val javaFile = try { payload.asFile()?.asJavaFile() } catch (e: Exception) { null }
            if (javaFile != null && javaFile.exists() && javaFile.canRead()) {
                return javaFile
            }
            val pfd = payload.asFile()?.asParcelFileDescriptor() ?: return null
            val tempFile = File(context.cacheDir, "nb_recv_${payloadId}_${System.currentTimeMillis()}.tmp")
            ParcelFileDescriptor.AutoCloseInputStream(pfd).use { inp ->
                FileOutputStream(tempFile).use { out ->
                    inp.copyTo(out)
                }
            }
            return tempFile
        } catch (e: Exception) {
            return null
        }
    }

    private val connectionLifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            connectionsClient.acceptConnection(endpointId, payloadCallback)
        }

        override fun onConnectionResult(endpointId: String, resolution: ConnectionResolution) {
            if (resolution.status.isSuccess) {
                val discovered = _discoveredPeers.value[endpointId]
                val peerName = discovered?.nodeName ?: "Peer-${endpointId.take(4)}"
                val peerNode = PeerNode(
                    endpointId = endpointId,
                    nodeId = discovered?.nodeId ?: endpointId,
                    nodeName = peerName,
                    isConnected = true,
                    isDirect = true,
                    hops = 1
                )
                _connectedPeers.value = _connectedPeers.value + (endpointId to peerNode)
                _discoveredPeers.value = _discoveredPeers.value - endpointId
                sendKeyExchange(endpointId)
            } else {
                _connectedPeers.value = _connectedPeers.value - endpointId
            }
        }

        override fun onDisconnected(endpointId: String) {
            _connectedPeers.value = _connectedPeers.value - endpointId
            peerSessionKeys.remove(endpointId)
        }
    }

    private val endpointDiscoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            val peerNode = PeerNode(
                endpointId = endpointId,
                nodeId = endpointId,
                nodeName = info.endpointName,
                isConnected = false,
                isDirect = true,
                hops = 1,
                signalStrengthDbm = -55
            )
            _discoveredPeers.value = _discoveredPeers.value + (endpointId to peerNode)
            requestConnection(endpointId)
        }

        override fun onEndpointLost(endpointId: String) {
            _discoveredPeers.value = _discoveredPeers.value - endpointId
        }
    }

    private fun startLanEngine() {
        if (isLanRunning) return
        isLanRunning = true
        _isAdvertising.value = true
        _isDiscovering.value = true

        try {
            val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            multicastLock = wm?.createMulticastLock("CipherMeshLanLock")?.apply {
                setReferenceCounted(true)
                acquire()
            }
        } catch (e: Exception) {}

        scope.launch {
            try {
                tcpServer = ServerSocket(LAN_TCP_PORT)
                while (isLanRunning) {
                    val client = tcpServer?.accept() ?: break
                    val ip = client.inetAddress.hostAddress ?: continue
                    knownLanPeerIps.add(ip)
                    handleLanSocket(client, "lan-$ip", "lan-$ip", "WiFi-$ip")
                }
            } catch (e: Exception) {}
        }

        scope.launch {
            try {
                udpSocket = DatagramSocket(null).apply {
                    reuseAddress = true
                    broadcast = true
                    receiveBufferSize = 262144
                    sendBufferSize = 262144
                    bind(InetSocketAddress(LAN_UDP_PORT))
                }
                val buf = ByteArray(65000)
                while (isLanRunning) {
                    val pkt = DatagramPacket(buf, buf.size)
                    udpSocket?.receive(pkt) ?: break
                    val len = pkt.length
                    if (len in 0..1) continue

                    val senderIp = pkt.address.hostAddress ?: continue
                    if (getLocalIPv4Addresses().contains(senderIp)) continue

                    val b0 = pkt.data[0]
                    val b1 = pkt.data[1]

                    if (b0 == 0x56.toByte() && b1 == 0x41.toByte()) {
                        unackedVideoFrames.set(0)
                        continue
                    }

                    if (b0 == 0x56.toByte() && b1 == 0x46.toByte() && len in 4..Int.MAX_VALUE) {
                        knownLanPeerIps.add(senderIp)
                        incomingVideoChannel.trySend(pkt.data.copyOfRange(2, len))
                        continue
                    }

                    if (b0 == 0x41.toByte() && b1 == 0x46.toByte() && len in 4..Int.MAX_VALUE) {
                        knownLanPeerIps.add(senderIp)
                        incomingAudioChannel.trySend(pkt.data.copyOfRange(2, len))
                        continue
                    }

                    if (b0 == 0x4D.toByte() && b1 == 0x50.toByte() && len in 4..Int.MAX_VALUE) {
                        knownLanPeerIps.add(senderIp)
                        val meshBytes = pkt.data.copyOfRange(2, len)
                        handleIncomingBytes("lan-$senderIp", meshBytes)
                        continue
                    }

                    val msg = String(pkt.data, 0, len, Charsets.UTF_8)
                    if (msg.startsWith(cmeshPrefix)) {
                        val parts = msg.split(sepChar)
                        if (parts.size in 3..10) {
                            val peerNodeId = parts[1]
                            val peerName = parts[2]
                            if (peerNodeId != localNodeId) {
                                knownLanPeerIps.add(senderIp)
                                onLanPeerDiscovered(senderIp, peerNodeId, peerName)
                            }
                        }
                    }
                }
            } catch (e: Exception) {}
        }

        scope.launch {
            var tick = 0
            while (isLanRunning && isActive) {
                try {
                    if (!isHighSpeedCallMode.get()) {
                        val beaconText = listOf("CMESH", localNodeId, localNodeName, LAN_TCP_PORT.toString()).joinToString(sepChar.toString())
                        val beaconBytes = beaconText.toByteArray(Charsets.UTF_8)
                        val sender = udpSocket ?: DatagramSocket().apply { broadcast = true }

                        try {
                            sender.send(DatagramPacket(beaconBytes, beaconBytes.size, InetAddress.getByName("255.255.255.255"), LAN_UDP_PORT))
                        } catch (e: Exception) {}

                        val localIps = getLocalIPv4Addresses()
                        for (ip in localIps) {
                            val parts = ip.split(".")
                            if (parts.size == 4) {
                                val prefix = "${parts[0]}.${parts[1]}.${parts[2]}."
                                try {
                                    sender.send(DatagramPacket(beaconBytes, beaconBytes.size, InetAddress.getByName("${prefix}255"), LAN_UDP_PORT))
                                } catch (e: Exception) {}
                            }
                        }

                        for (peerIp in knownLanPeerIps.toSet()) {
                            if (!lanOutputs.containsKey("lan-$peerIp")) {
                                onLanPeerDiscovered(peerIp, "lan-$peerIp", "WiFi-$peerIp")
                            }
                        }

                        if (tick % 3 == 0 && _connectedPeers.value.isNotEmpty()) {
                            broadcastMultiHopPresence()
                        }
                        tick += 1
                    }
                } catch (e: Exception) {}
                delay(3000)
            }
        }
    }

    private fun stopLanEngine() {
        isLanRunning = false
        _isGlobalBridgeConnected.value = false
        try { multicastLock?.let { if (it.isHeld) it.release() } } catch (e: Exception) {}
        try { udpSocket?.close() } catch (e: Exception) {}
        try { tcpServer?.close() } catch (e: Exception) {}
        try { globalRelaySocket?.close() } catch (e: Exception) {}
        synchronized(lanOutputs) {
            lanOutputs.values.forEach { try { it.close() } catch (e: Exception) {} }
            lanOutputs.clear()
        }
        connectingLanIps.clear()
    }

    private fun getLocalIPv4Addresses() = run {
        val result = mutableListOf("init").apply { clear() }
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            if (interfaces != null) {
                while (interfaces.hasMoreElements()) {
                    val nif = interfaces.nextElement()
                    if (!nif.isUp || nif.isLoopback) continue
                    val addrs = nif.inetAddresses
                    while (addrs.hasMoreElements()) {
                        val addr = addrs.nextElement()
                        if (addr is Inet4Address && !addr.isLoopbackAddress) {
                            addr.hostAddress?.let { result.add(it) }
                        }
                    }
                }
            }
        } catch (e: Exception) {}
        result
    }

    private fun onLanPeerDiscovered(ip: String, peerNodeId: String, peerName: String) {
        if (getLocalIPv4Addresses().contains(ip)) return
        knownLanPeerIps.add(ip)
        val endpointId = "lan-$ip"
        if (lanOutputs.containsKey(endpointId) || !connectingLanIps.add(ip)) return

        scope.launch {
            try {
                val socket = Socket()
                socket.tcpNoDelay = true
                socket.keepAlive = true
                socket.connect(InetSocketAddress(ip, LAN_TCP_PORT), 2000)
                handleLanSocket(socket, endpointId, peerNodeId, "$peerName (Wi-Fi)")
            } catch (e: Exception) {
            } finally {
                connectingLanIps.remove(ip)
            }
        }
    }

    private fun handleLanSocket(socket: Socket, endpointId: String, initialNodeId: String, initialName: String) {
        scope.launch {
            var dosRef: DataOutputStream? = null
            try {
                socket.tcpNoDelay = true
                socket.keepAlive = true
                val dis = DataInputStream(BufferedInputStream(socket.getInputStream()))
                val dos = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))
                dosRef = dos
                lanOutputs[endpointId] = dos

                if (!_connectedPeers.value.containsKey(endpointId)) {
                    val peerNode = PeerNode(
                        endpointId = endpointId,
                        nodeId = initialNodeId,
                        nodeName = initialName,
                        isConnected = true,
                        isDirect = true,
                        hops = 1,
                        signalStrengthDbm = -42
                    )
                    _connectedPeers.value = _connectedPeers.value + (endpointId to peerNode)
                }
                sendKeyExchange(endpointId)

                delay(250)
                announceKnownPeersToEndpoint(endpointId)

                while (isLanRunning && !socket.isClosed) {
                    val frameType = dis.readByte().toInt()
                    if (frameType == 1) {
                        val len = dis.readInt()
                        if (len in 1..20_000_000) {
                            val bytes = ByteArray(len)
                            dis.readFully(bytes)
                            handleIncomingBytes(endpointId, bytes)
                        }
                    } else if (frameType == 2) {
                        val headerLen = dis.readInt()
                        val headerBytes = ByteArray(headerLen)
                        dis.readFully(headerBytes)
                        val fileLen = dis.readLong()
                        val tempFile = File(context.cacheDir, "lan_recv_${System.currentTimeMillis()}.tmp")
                        FileOutputStream(tempFile).use { fos ->
                            val buf = ByteArray(16384)
                            var remaining = fileLen
                            while (remaining in 1L..Long.MAX_VALUE) {
                                val toRead = minOf(remaining, buf.size.toLong()).toInt()
                                val read = dis.read(buf, 0, toRead)
                                if (read == -1) break
                                fos.write(buf, 0, read)
                                remaining -= read
                            }
                        }
                        val header = MediaHeader.fromJson(String(headerBytes, Charsets.UTF_8))
                        if (header != null && tempFile.exists()) {
                            fileReceivedChannel.trySend(Pair(header, tempFile))
                        }
                    }
                }
            } catch (e: Exception) {
            } finally {
                if (dosRef != null) {
                    lanOutputs.remove(endpointId, dosRef)
                }
                try { socket.close() } catch (e: Exception) {}
            }
        }
    }

    fun startMesh() {
        startLanEngine()
        startAdvertising()
        startDiscovery()
    }

    fun stopMesh() {
        stopLanEngine()
        stopAdvertising()
        stopDiscovery()
        connectionsClient.stopAllEndpoints()
        _connectedPeers.value = emptyPeers
        _discoveredPeers.value = emptyPeers
    }

    fun startAdvertising() {
        val options = AdvertisingOptions.Builder()
            .setStrategy(STRATEGY)
            .setDisruptiveUpgrade(true)
            .build()
        connectionsClient.startAdvertising(localNodeName, SERVICE_ID, connectionLifecycleCallback, options)
            .addOnSuccessListener { _isAdvertising.value = true }
    }

    fun stopAdvertising() {
        connectionsClient.stopAdvertising()
        _isAdvertising.value = false
    }

    fun startDiscovery() {
        val options = DiscoveryOptions.Builder().setStrategy(STRATEGY).build()
        connectionsClient.startDiscovery(SERVICE_ID, endpointDiscoveryCallback, options)
            .addOnSuccessListener { _isDiscovering.value = true }
    }

    fun stopDiscovery() {
        connectionsClient.stopDiscovery()
        _isDiscovering.value = false
    }

    fun requestConnection(endpointId: String) {
        if (endpointId.startsWith("lan-") || endpointId.startsWith("global-")) return
        connectionsClient.requestConnection(localNodeName, endpointId, connectionLifecycleCallback)
    }

    fun disconnect(endpointId: String) {
        if (endpointId.startsWith("lan-")) {
            val ip = endpointId.removePrefix("lan-")
            knownLanPeerIps.remove(ip)
            lanOutputs.remove(endpointId)?.let { try { it.close() } catch (e: Exception) {} }
        } else if (!endpointId.startsWith("global-")) {
            connectionsClient.disconnectFromEndpoint(endpointId)
        }
        _connectedPeers.value = _connectedPeers.value - endpointId
        peerSessionKeys.remove(endpointId)
    }

    private fun sendKeyExchange(endpointId: String) {
        val packet = MeshPacket(
            sourceNodeId = localNodeId,
            sourceNodeName = localNodeName,
            destinationNodeId = "*",
            ttl = DEFAULT_MESH_TTL,
            hopCount = 0,
            packetType = PacketType.KEY_EXCHANGE,
            payloadCiphertext = CryptoManager.localPublicKeyBase64,
            extraMeta = CryptoManager.localFingerprint
        )
        markPacketSeen(packet.packetId)
        sendReliablePacketToEndpoint(endpointId, packet.toByteArray())
    }

    private fun broadcastMultiHopPresence() {
        val packet = MeshPacket(
            sourceNodeId = localNodeId,
            sourceNodeName = localNodeName,
            destinationNodeId = "*",
            ttl = DEFAULT_MESH_TTL,
            hopCount = 0,
            packetType = PacketType.KEY_EXCHANGE,
            payloadCiphertext = CryptoManager.localPublicKeyBase64,
            extraMeta = CryptoManager.localFingerprint
        )
        sendMeshPacket(packet)
    }

    private fun announceKnownPeersToEndpoint(targetEndpointId: String) {
        val snapshot = _connectedPeers.value.values.toList()
        for (peer in snapshot) {
            val pubKey = peer.publicKeyBase64 ?: ""
            val fp = peer.fingerprint ?: ""
            if (peer.endpointId != targetEndpointId && pubKey.isNotEmpty()) {
                val gossipPacket = MeshPacket(
                    sourceNodeId = peer.nodeId,
                    sourceNodeName = peer.nodeName.replace(" (Wi-Fi)", "").replace(" (تلقائي)", "").replace(" (دولي 🌍)", ""),
                    destinationNodeId = "*",
                    ttl = DEFAULT_MESH_TTL,
                    hopCount = peer.hops,
                    relayedBy = listOf(localNodeName),
                    packetType = PacketType.KEY_EXCHANGE,
                    payloadCiphertext = pubKey,
                    extraMeta = fp
                )
                sendReliablePacketToEndpoint(targetEndpointId, gossipPacket.toByteArray())
            }
        }
    }

    fun getSessionKey(peerNodeIdOrEndpoint: String): SecretKey {
        return peerSessionKeys[peerNodeIdOrEndpoint] ?: defaultSecretKey
    }

    private fun handleIncomingBytes(incomingEndpointId: String, bytes: ByteArray) {
        if (bytes.size == 2 && bytes[0] == 0x56.toByte() && bytes[1] == 0x41.toByte()) {
            val current = unackedVideoFrames.decrementAndGet()
            if (current in Int.MIN_VALUE..0) {
                unackedVideoFrames.set(0)
            }
            return
        }

        if (bytes.size in 4..Int.MAX_VALUE && bytes[0] == 0x56.toByte() && bytes[1] == 0x46.toByte()) {
            incomingVideoChannel.trySend(bytes.copyOfRange(2, bytes.size))
            if (!incomingEndpointId.startsWith("lan-") && !incomingEndpointId.startsWith("global-")) {
                try {
                    connectionsClient.sendPayload(incomingEndpointId, Payload.fromBytes(videoAckBytes))
                } catch (e: Exception) {}
            }
            return
        }

        if (bytes.size in 4..Int.MAX_VALUE && bytes[0] == 0x41.toByte() && bytes[1] == 0x46.toByte()) {
            incomingAudioChannel.trySend(bytes.copyOfRange(2, bytes.size))
            return
        }

        val packet = MeshPacket.fromByteArray(bytes) ?: return
        if (packet.sourceNodeId == localNodeId) return

        _packetsReceivedCount.value += 1
        if (!markPacketSeen(packet.packetId)) return

        nextHopRoutes[packet.sourceNodeId] = incomingEndpointId

        if (packet.packetType == PacketType.KEY_EXCHANGE) {
            handleKeyExchangePacket(incomingEndpointId, packet)
            if (packet.ttl in 2..Int.MAX_VALUE) {
                relayPacket(packet, incomingEndpointId)
            }
            return
        }

        if (packet.packetType == PacketType.MEDIA_HEADER) {
            val header = MediaHeader.fromJson(packet.extraMeta ?: packet.payloadCiphertext)
            if (header != null) {
                val readyFile = completedPayloadFiles.remove(header.payloadId)
                if (readyFile != null) {
                    fileReceivedChannel.trySend(Pair(header, readyFile))
                } else {
                    pendingMediaHeaders[header.payloadId] = header
                }
            }
            return
        }

        val isTarget = packet.destinationNodeId == localNodeId ||
                packet.destinationNodeId == incomingEndpointId ||
                packet.isBroadcast

        if (isTarget) {
            incomingPacketChannel.trySend(packet)
            if (packet.packetType == PacketType.TEXT_MESSAGE && !packet.isBroadcast) {
                sendDeliveryAck(packet)
            }
        }

        val shouldForward = (!isTarget || packet.isBroadcast) && (packet.ttl in 2..Int.MAX_VALUE)
        if (shouldForward) {
            relayPacket(packet, incomingEndpointId)
        }
    }

    private fun handleKeyExchangePacket(incomingEndpointId: String, packet: MeshPacket) {
        try {
            val peerPubKeyBase64 = packet.payloadCiphertext
            val sharedKey = CryptoManager.deriveSharedSecretFromBase64(peerPubKeyBase64)
            peerSessionKeys[packet.sourceNodeId] = sharedKey

            val isGlobal = incomingEndpointId.startsWith("global-")
            val hopsCount = if (isGlobal) maxOf(2, packet.hopCount + 1) else packet.hopCount + 1

            if (hopsCount == 1 && !isGlobal) {
                peerSessionKeys[incomingEndpointId] = sharedKey
                val existing = _connectedPeers.value[incomingEndpointId]
                val cleanName = packet.sourceNodeName.replace(" (Wi-Fi)", "").replace(" (تلقائي)", "").replace(" (دولي 🌍)", "")
                val displayName = if (incomingEndpointId.startsWith("lan-")) "$cleanName (Wi-Fi)" else cleanName
                val updated = PeerNode(
                    endpointId = incomingEndpointId,
                    nodeId = packet.sourceNodeId,
                    nodeName = displayName,
                    publicKeyBase64 = peerPubKeyBase64,
                    fingerprint = packet.extraMeta ?: CryptoManager.computeFingerprint(sharedKey.encoded),
                    isConnected = true,
                    isDirect = true,
                    hops = 1,
                    signalStrengthDbm = existing?.signalStrengthDbm ?: -45
                )
                _connectedPeers.value = _connectedPeers.value + (incomingEndpointId to updated)
            } else {
                val alreadyDirect = _connectedPeers.value.values.any { it.nodeId == packet.sourceNodeId && it.isDirect }
                if (!alreadyDirect) {
                    val cleanName = packet.sourceNodeName.replace(" (Wi-Fi)", "").replace(" (تلقائي)", "").replace(" (دولي 🌍)", "")
                    val label = if (isGlobal) "$cleanName (دولي 🌍)" else "$cleanName (عبر $hopsCount قفزات)"
                    val keyId = if (isGlobal) "global-${packet.sourceNodeId}" else packet.sourceNodeId
                    val multiHopPeer = PeerNode(
                        endpointId = incomingEndpointId,
                        nodeId = packet.sourceNodeId,
                        nodeName = label,
                        publicKeyBase64 = peerPubKeyBase64,
                        fingerprint = packet.extraMeta ?: CryptoManager.computeFingerprint(sharedKey.encoded),
                        isConnected = true,
                        isDirect = false,
                        hops = hopsCount,
                        signalStrengthDbm = if (isGlobal) -50 else -65
                    )
                    _connectedPeers.value = _connectedPeers.value + (keyId to multiHopPeer)
                }
            }
        } catch (e: Exception) {}
    }

    private fun relayPacket(packet: MeshPacket, arrivedFromEndpoint: String) {
        val relayedPacket = packet.copy(
            ttl = packet.ttl - 1,
            hopCount = packet.hopCount + 1,
            relayedBy = packet.relayedBy + localNodeName
        )
        val packetBytes = relayedPacket.toByteArray()
        val distinctEndpoints = _connectedPeers.value.values.map { it.endpointId }.distinct()
        for (endpointId in distinctEndpoints) {
            if (endpointId != arrivedFromEndpoint && !endpointId.startsWith("global-")) {
                sendReliablePacketToEndpoint(endpointId, packetBytes)
            }
        }

        if (!arrivedFromEndpoint.startsWith("global-") && _isGlobalBridgeConnected.value) {
            val framed = ByteArray(2 + packetBytes.size)
            framed[0] = 0x4D.toByte()
            framed[1] = 0x50.toByte()
            System.arraycopy(packetBytes, 0, framed, 2, packetBytes.size)
            outgoingGlobalChannel.trySend(framed)
        }

        _packetsForwardedCount.value += 1
    }

    private fun sendDeliveryAck(receivedPacket: MeshPacket) {
        val ackPacket = MeshPacket(
            sourceNodeId = localNodeId,
            sourceNodeName = localNodeName,
            destinationNodeId = receivedPacket.sourceNodeId,
            ttl = DEFAULT_MESH_TTL,
            packetType = PacketType.DELIVERY_ACK,
            payloadCiphertext = receivedPacket.packetId,
            extraMeta = "ACK"
        )
        sendMeshPacket(ackPacket)
    }

    fun sendMeshPacket(packet: MeshPacket) {
        markPacketSeen(packet.packetId)
        val elevatedPacket = if (packet.ttl in 1..15) packet.copy(ttl = DEFAULT_MESH_TTL) else packet
        val bytes = elevatedPacket.toByteArray()

        scope.launch {
            sendUdpFastFrame(0x4D.toByte(), 0x50.toByte(), bytes, includeBroadcast = true)
        }

        if (_isGlobalBridgeConnected.value) {
            val framed = ByteArray(2 + bytes.size)
            framed[0] = 0x4D.toByte()
            framed[1] = 0x50.toByte()
            System.arraycopy(bytes, 0, framed, 2, bytes.size)
            outgoingGlobalChannel.trySend(framed)
        }

        val activeEndpoints = (_connectedPeers.value.keys + _connectedPeers.value.values.map { it.endpointId } + lanOutputs.keys)
            .filter { !it.startsWith("global-") }
            .distinct()
        for (endpointId in activeEndpoints) {
            sendReliablePacketToEndpoint(endpointId, bytes)
        }
    }

    private fun sendReliablePacketToEndpoint(endpointId: String, bytes: ByteArray) {
        if (endpointId.startsWith("global-")) {
            if (_isGlobalBridgeConnected.value) {
                val framed = ByteArray(2 + bytes.size)
                framed[0] = 0x4D.toByte()
                framed[1] = 0x50.toByte()
                System.arraycopy(bytes, 0, framed, 2, bytes.size)
                outgoingGlobalChannel.trySend(framed)
            }
            return
        }

        val lanOut = lanOutputs[endpointId]
        if (lanOut != null) {
            scope.launch {
                try {
                    synchronized(lanOut) {
                        lanOut.writeByte(1)
                        lanOut.writeInt(bytes.size)
                        lanOut.write(bytes)
                        lanOut.flush()
                    }
                } catch (e: Exception) {
                    lanOutputs.remove(endpointId, lanOut)
                    try { lanOut.close() } catch (ex: Exception) {}
                }
            }
            return
        }

        if (!endpointId.startsWith("lan-") && bytes.size in 1..32000) {
            try {
                connectionsClient.sendPayload(endpointId, Payload.fromBytes(bytes))
            } catch (e: Exception) {}
        }
    }

    private fun sendUdpFastFrame(b0: Byte, b1: Byte, payloadBytes: ByteArray, includeBroadcast: Boolean) {
        if (payloadBytes.size !in 1..60000) return
        val sock = udpSocket ?: return
        try {
            val packetData = ByteArray(2 + payloadBytes.size)
            packetData[0] = b0
            packetData[1] = b1
            System.arraycopy(payloadBytes, 0, packetData, 2, payloadBytes.size)

            val targets = knownLanPeerIps.toSet()
            for (ip in targets) {
                try {
                    val dgram = DatagramPacket(packetData, packetData.size, InetAddress.getByName(ip), LAN_UDP_PORT)
                    sock.send(dgram)
                } catch (e: Exception) {}
            }

            if (includeBroadcast) {
                try {
                    val dgramAll = DatagramPacket(packetData, packetData.size, InetAddress.getByName("255.255.255.255"), LAN_UDP_PORT)
                    sock.send(dgramAll)
                } catch (e: Exception) {}
            }
        } catch (e: Exception) {}
    }

    fun sendDirectVideoFrame(frameBytes: ByteArray) {
        outgoingVideoChannel.trySend(frameBytes)
    }

    fun sendDirectAudioFrame(audioBytes: ByteArray) {
        outgoingAudioChannel.trySend(audioBytes)
    }

    fun sendFile(targetEndpointId: String, file: File, header: MediaHeader) {
        val resolvedEndpoint = nextHopRoutes[targetEndpointId] ?: targetEndpointId
        val lanOut = lanOutputs[resolvedEndpoint] ?: lanOutputs.values.firstOrNull()
        if (lanOut != null) {
            scope.launch {
                try {
                    val headerBytes = header.toJson().toByteArray(Charsets.UTF_8)
                    synchronized(lanOut) {
                        lanOut.writeByte(2)
                        lanOut.writeInt(headerBytes.size)
                        lanOut.write(headerBytes)
                        lanOut.writeLong(file.length())
                        FileInputStream(file).use { fis ->
                            val buf = ByteArray(16384)
                            while (true) {
                                val read = fis.read(buf)
                                if (read == -1) break
                                lanOut.write(buf, 0, read)
                            }
                        }
                        lanOut.flush()
                    }
                } catch (e: Exception) {
                    lanOutputs.remove(resolvedEndpoint, lanOut)
                }
            }
            return
        }

        if (!resolvedEndpoint.startsWith("global-")) {
            try {
                val filePayload = Payload.fromFile(file)
                val updatedHeader = header.copy(payloadId = filePayload.id)
                val headerPacket = MeshPacket(
                    sourceNodeId = localNodeId,
                    sourceNodeName = localNodeName,
                    destinationNodeId = resolvedEndpoint,
                    ttl = 1,
                    packetType = PacketType.MEDIA_HEADER,
                    payloadCiphertext = updatedHeader.toJson(),
                    extraMeta = updatedHeader.toJson()
                )
                sendReliablePacketToEndpoint(resolvedEndpoint, headerPacket.toByteArray())
                connectionsClient.sendPayload(resolvedEndpoint, filePayload)
            } catch (e: Exception) {}
        }
    }
}
