package com.example.nearby

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.wifi.WifiManager
import android.os.ParcelFileDescriptor
import android.util.Log
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.shareIn
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
import javax.crypto.SecretKey

class NearbyConnectionsManager(private val context: Context) {

    companion object {
        private const val TAG = "NearbyManager"
        private const val SERVICE_ID = "com.example.ciphermesh.p2p"
        val STRATEGY: Strategy = Strategy.P2P_CLUSTER
        private const val MAX_SEEN_PACKETS = 5000
        private const val LAN_UDP_PORT = 45454
        private const val LAN_TCP_PORT = 45455
    }

    private val connectionsClient: ConnectionsClient = Nearby.getConnectionsClient(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    var localNodeId: String = UUID.randomUUID().toString()
        private set
    var localNodeName: String = "Node-${localNodeId.take(4).uppercase()}"
        private set

    private val dummyPeer = PeerNode(endpointId = "", nodeId = "", nodeName = "")
    private val emptyPeers = mapOf("init" to dummyPeer).minus("init")

    private val _isAdvertising = MutableStateFlow(false)
    val isAdvertising = _isAdvertising.asStateFlow()

    private val _isDiscovering = MutableStateFlow(false)
    val isDiscovering = _isDiscovering.asStateFlow()

    private val _discoveredPeers = MutableStateFlow(emptyPeers)
    val discoveredPeers = _discoveredPeers.asStateFlow()

    private val _connectedPeers = MutableStateFlow(emptyPeers)
    val connectedPeers = _connectedPeers.asStateFlow()

    private val dummyPacket = MeshPacket(
        sourceNodeId = "",
        sourceNodeName = "",
        destinationNodeId = "",
        packetType = PacketType.TEXT_MESSAGE,
        payloadCiphertext = ""
    )
    private val _incomingPackets = MutableStateFlow(dummyPacket)
    val incomingPackets = _incomingPackets
        .filter { it.sourceNodeId.isNotEmpty() }
        .shareIn(scope, SharingStarted.Eagerly, replay = 0)

    private val _incomingVideoFrames = MutableStateFlow(ByteArray(0))
    val incomingVideoFrames = _incomingVideoFrames
        .filter { it.isNotEmpty() }
        .shareIn(scope, SharingStarted.Eagerly, replay = 0)

    private val _incomingAudioPackets = MutableStateFlow(ByteArray(0))
    val incomingAudioPackets = _incomingAudioPackets
        .filter { it.isNotEmpty() }
        .shareIn(scope, SharingStarted.Eagerly, replay = 0)

    private val defaultSecretKey by lazy {
        CryptoManager.deriveSharedSecretFromBase64(CryptoManager.localPublicKeyBase64)
    }
    private val peerSessionKeys by lazy {
        Collections.synchronizedMap(mutableMapOf("init" to defaultSecretKey).apply { clear() })
    }

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

    private val _onFileReceived = MutableStateFlow(Pair(dummyHeader, dummyFile))
    val onFileReceived = _onFileReceived
        .filter { it.first.messageId.isNotEmpty() }
        .shareIn(scope, SharingStarted.Eagerly, replay = 0)

    private val seenPacketIds = Collections.synchronizedSet(mutableSetOf("init").apply { clear() })

    // Wi-Fi LAN Sockets & State
    private val dummyOut = DataOutputStream(ByteArrayOutputStream())
    private val lanOutputs = Collections.synchronizedMap(mutableMapOf("init" to dummyOut).apply { clear() })
    private val connectingLanIps = Collections.synchronizedSet(mutableSetOf("init").apply { clear() })
    private var isLanRunning = false
    private var udpSocket: DatagramSocket? = null
    private var tcpServer: ServerSocket? = null
    private var multicastLock: WifiManager.MulticastLock? = null

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

    // -------------------------------------------------------------------------
    // Nearby Connections Callbacks
    // -------------------------------------------------------------------------

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
                            _onFileReceived.value = Pair(header, extractedFile)
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
            Log.e(TAG, "Extract file err: ${e.message}")
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

    // -------------------------------------------------------------------------
    // Wi-Fi Local Network (LAN) Engine (Buildings, Markets, Malls without Internet)
    // -------------------------------------------------------------------------

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

        // 1. TCP Server for incoming Wi-Fi peer connections
        scope.launch {
            try {
                tcpServer = ServerSocket(LAN_TCP_PORT)
                while (isLanRunning) {
                    val client = tcpServer?.accept() ?: break
                    val ip = client.inetAddress.hostAddress ?: continue
                    handleLanSocket(client, "lan-$ip", "lan-$ip", "WiFi-$ip")
                }
            } catch (e: Exception) {
                Log.e(TAG, "LAN TCP Server err: ${e.message}")
            }
        }

        // 2. UDP Listener for peer beacons on the same Wi-Fi router
        scope.launch {
            try {
                udpSocket = DatagramSocket(null).apply {
                    reuseAddress = true
                    broadcast = true
                    bind(InetSocketAddress(LAN_UDP_PORT))
                }
                val buf = ByteArray(512)
                while (isLanRunning) {
                    val pkt = DatagramPacket(buf, buf.size)
                    udpSocket?.receive(pkt) ?: break
                    val msg = String(pkt.data, 0, pkt.length, Charsets.UTF_8)
                    val senderIp = pkt.address.hostAddress ?: continue
                    if (msg.startsWith("CMESH|")) {
                        val parts = msg.split("|")
                        if (parts.size in 3..10) {
                            val peerNodeId = parts[1]
                            val peerName = parts[2]
                            if (peerNodeId != localNodeId) {
                                onLanPeerDiscovered(senderIp, peerNodeId, peerName)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "LAN UDP Listener err: ${e.message}")
            }
        }

        // 3. UDP Beacon Broadcaster + Subnet Unicast Sweep (Bypasses router broadcast isolation)
        scope.launch {
            var tick = 0
            while (isLanRunning) {
                try {
                    val beaconBytes = "CMESH|$localNodeId|$localNodeName|$LAN_TCP_PORT".toByteArray(Charsets.UTF_8)
                    val sender = udpSocket ?: DatagramSocket().apply { broadcast = true }

                    // Send to global broadcast
                    try {
                        sender.send(DatagramPacket(beaconBytes, beaconBytes.size, InetAddress.getByName("255.255.255.255"), LAN_UDP_PORT))
                    } catch (e: Exception) {}

                    // Send to local Wi-Fi interface broadcast and subnet unicast sweep
                    val localIps = getLocalIPv4Addresses()
                    for (ip in localIps) {
                        val parts = ip.split(".")
                        if (parts.size == 4) {
                            val prefix = "${parts[0]}.${parts[1]}.${parts[2]}."
                            try {
                                sender.send(DatagramPacket(beaconBytes, beaconBytes.size, InetAddress.getByName("${prefix}255"), LAN_UDP_PORT))
                            } catch (e: Exception) {}

                            // Sweep subnet every 3rd cycle to bypass public router broadcast filters
                            if (tick % 3 == 0) {
                                for (host in 1..254) {
                                    val targetIp = "$prefix$host"
                                    if (targetIp != ip) {
                                        try {
                                            sender.send(DatagramPacket(beaconBytes, beaconBytes.size, InetAddress.getByName(targetIp), LAN_UDP_PORT))
                                        } catch (e: Exception) {}
                                    }
                                }
                            }
                        }
                    }
                    tick += 1
                } catch (e: Exception) {}
                delay(2500)
            }
        }
    }

    private fun stopLanEngine() {
        isLanRunning = false
        try { multicastLock?.let { if (it.isHeld) it.release() } } catch (e: Exception) {}
        try { udpSocket?.close() } catch (e: Exception) {}
        try { tcpServer?.close() } catch (e: Exception) {}
        synchronized(lanOutputs) {
            lanOutputs.values.forEach { try { it.close() } catch (e: Exception) {} }
            lanOutputs.clear()
        }
        connectingLanIps.clear()
    }

    private fun getLocalIPv4Addresses(): List<String> {
        val result = mutableListOf("init").apply { clear() }
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return result
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
        } catch (e: Exception) {}
        return result
    }

    private fun onLanPeerDiscovered(ip: String, peerNodeId: String, peerName: String) {
        val endpointId = "lan-$ip"
        val alreadyConnected = _connectedPeers.value.values.any { it.endpointId == endpointId || it.nodeId == peerNodeId }
        if (alreadyConnected || !connectingLanIps.add(ip)) return

        scope.launch {
            try {
                val socket = Socket()
                socket.connect(InetSocketAddress(ip, LAN_TCP_PORT), 3000)
                handleLanSocket(socket, endpointId, peerNodeId, "$peerName (Wi-Fi)")
            } catch (e: Exception) {
                // Peer may initiate connection to us instead
            } finally {
                connectingLanIps.remove(ip)
            }
        }
    }

    private fun handleLanSocket(socket: Socket, endpointId: String, initialNodeId: String, initialName: String) {
        scope.launch {
            try {
                socket.tcpNoDelay = true
                val dis = DataInputStream(BufferedInputStream(socket.getInputStream()))
                val dos = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))
                lanOutputs[endpointId] = dos

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
                sendKeyExchange(endpointId)

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
                            _onFileReceived.value = Pair(header, tempFile)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.d(TAG, "LAN peer disconnected $endpointId: ${e.message}")
            } finally {
                lanOutputs.remove(endpointId)
                _connectedPeers.value = _connectedPeers.value - endpointId
                try { socket.close() } catch (e: Exception) {}
            }
        }
    }

    // -------------------------------------------------------------------------
    // Start / Stop Mesh
    // -------------------------------------------------------------------------

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
        if (endpointId.startsWith("lan-")) return
        connectionsClient.requestConnection(localNodeName, endpointId, connectionLifecycleCallback)
    }

    fun disconnect(endpointId: String) {
        if (endpointId.startsWith("lan-")) {
            lanOutputs.remove(endpointId)?.let { try { it.close() } catch (e: Exception) {} }
        } else {
            connectionsClient.disconnectFromEndpoint(endpointId)
        }
        _connectedPeers.value = _connectedPeers.value - endpointId
        peerSessionKeys.remove(endpointId)
    }

    // -------------------------------------------------------------------------
    // Key Exchange & Mesh Routing
    // -------------------------------------------------------------------------

    private fun sendKeyExchange(endpointId: String) {
        val packet = MeshPacket(
            sourceNodeId = localNodeId,
            sourceNodeName = localNodeName,
            destinationNodeId = endpointId,
            ttl = 1,
            packetType = PacketType.KEY_EXCHANGE,
            payloadCiphertext = CryptoManager.localPublicKeyBase64,
            extraMeta = CryptoManager.localFingerprint
        )
        sendDirectPayload(endpointId, packet.toByteArray())
    }

    fun getSessionKey(peerNodeIdOrEndpoint: String): SecretKey {
        return peerSessionKeys[peerNodeIdOrEndpoint] ?: defaultSecretKey
    }

    private fun handleIncomingBytes(incomingEndpointId: String, bytes: ByteArray) {
        if (bytes.size in 4..Int.MAX_VALUE && bytes[0] == 0x56.toByte() && bytes[1] == 0x46.toByte()) {
            _incomingVideoFrames.value = bytes.copyOfRange(2, bytes.size)
            return
        }
        if (bytes.size in 4..Int.MAX_VALUE && bytes[0] == 0x41.toByte() && bytes[1] == 0x46.toByte()) {
            _incomingAudioPackets.value = bytes.copyOfRange(2, bytes.size)
            return
        }

        val packet = MeshPacket.fromByteArray(bytes) ?: return
        _packetsReceivedCount.value += 1

        if (!markPacketSeen(packet.packetId)) return

        if (packet.packetType == PacketType.KEY_EXCHANGE) {
            handleKeyExchangePacket(incomingEndpointId, packet)
            return
        }

        if (packet.packetType == PacketType.MEDIA_HEADER) {
            val header = MediaHeader.fromJson(packet.extraMeta ?: packet.payloadCiphertext)
            if (header != null) {
                val readyFile = completedPayloadFiles.remove(header.payloadId)
                if (readyFile != null) {
                    _onFileReceived.value = Pair(header, readyFile)
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
            _incomingPackets.value = packet
            if (packet.packetType == PacketType.TEXT_MESSAGE && !packet.isBroadcast) {
                sendDeliveryAck(packet)
            }
        }

        val shouldForward = (!isTarget || packet.isBroadcast) && (packet.ttl in 2..Int.MAX_VALUE)
        if (shouldForward) {
            relayPacket(packet, incomingEndpointId)
        }
    }

    private fun handleKeyExchangePacket(endpointId: String, packet: MeshPacket) {
        try {
            val peerPubKeyBase64 = packet.payloadCiphertext
            val sharedKey = CryptoManager.deriveSharedSecretFromBase64(peerPubKeyBase64)
            peerSessionKeys[endpointId] = sharedKey
            peerSessionKeys[packet.sourceNodeId] = sharedKey

            val existing = _connectedPeers.value[endpointId]
            val displayName = if (endpointId.startsWith("lan-") && !packet.sourceNodeName.contains("Wi-Fi")) {
                "${packet.sourceNodeName} (Wi-Fi)"
            } else {
                packet.sourceNodeName
            }
            if (existing != null) {
                val updated = existing.copy(
                    nodeId = packet.sourceNodeId,
                    nodeName = displayName,
                    publicKeyBase64 = peerPubKeyBase64,
                    fingerprint = packet.extraMeta ?: CryptoManager.computeFingerprint(sharedKey.encoded)
                )
                _connectedPeers.value = _connectedPeers.value + (endpointId to updated)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Key exchange err: ${e.message}")
        }
    }

    private fun relayPacket(packet: MeshPacket, arrivedFromEndpoint: String) {
        val relayedPacket = packet.copy(
            ttl = packet.ttl - 1,
            hopCount = packet.hopCount + 1,
            relayedBy = packet.relayedBy + localNodeName
        )
        val packetBytes = relayedPacket.toByteArray()
        _connectedPeers.value.forEach { (endpointId, peer) ->
            if (endpointId != arrivedFromEndpoint && !packet.relayedBy.contains(peer.nodeName)) {
                sendDirectPayload(endpointId, packetBytes)
            }
        }
        _packetsForwardedCount.value += 1
    }

    private fun sendDeliveryAck(receivedPacket: MeshPacket) {
        val ackPacket = MeshPacket(
            sourceNodeId = localNodeId,
            sourceNodeName = localNodeName,
            destinationNodeId = receivedPacket.sourceNodeId,
            ttl = 5,
            packetType = PacketType.DELIVERY_ACK,
            payloadCiphertext = receivedPacket.packetId,
            extraMeta = "ACK"
        )
        sendMeshPacket(ackPacket)
    }

    // -------------------------------------------------------------------------
    // Public Sending APIs (Hybrid Wi-Fi LAN + Nearby)
    // -------------------------------------------------------------------------

    fun sendMeshPacket(packet: MeshPacket) {
        markPacketSeen(packet.packetId)
        val bytes = packet.toByteArray()
        val directPeer = _connectedPeers.value.values.find {
            it.nodeId == packet.destinationNodeId || it.endpointId == packet.destinationNodeId
        }
        if (directPeer != null && !packet.isBroadcast) {
            sendDirectPayload(directPeer.endpointId, bytes)
        } else {
            _connectedPeers.value.keys.forEach { endpointId ->
                sendDirectPayload(endpointId, bytes)
            }
        }
    }

    private fun sendDirectPayload(endpointId: String, bytes: ByteArray) {
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
                    Log.e(TAG, "LAN send err: ${e.message}")
                }
            }
            return
        }

        if (bytes.size in 1..32000) {
            try {
                connectionsClient.sendPayload(endpointId, Payload.fromBytes(bytes))
            } catch (e: Exception) {
                Log.e(TAG, "Nearby send err: ${e.message}")
            }
        }
    }

    fun sendDirectVideoFrame(frameBytes: ByteArray) {
        val safeFrame = if (frameBytes.size in 30000..Int.MAX_VALUE) {
            try {
                val bmp = BitmapFactory.decodeByteArray(frameBytes, 0, frameBytes.size)
                val out = ByteArrayOutputStream()
                bmp?.compress(Bitmap.CompressFormat.JPEG, 35, out)
                out.toByteArray().takeIf { it.isNotEmpty() } ?: frameBytes
            } catch (e: Exception) {
                frameBytes
            }
        } else {
            frameBytes
        }

        val header = byteArrayOf(0x56.toByte(), 0x46.toByte())
        val packet = ByteArray(header.size + safeFrame.size)
        System.arraycopy(header, 0, packet, 0, header.size)
        System.arraycopy(safeFrame, 0, packet, header.size, safeFrame.size)

        _connectedPeers.value.keys.forEach { endpointId ->
            sendDirectPayload(endpointId, packet)
        }
    }

    fun sendDirectAudioFrame(audioBytes: ByteArray) {
        val header = byteArrayOf(0x41.toByte(), 0x46.toByte())
        val packet = ByteArray(header.size + audioBytes.size)
        System.arraycopy(header, 0, packet, 0, header.size)
        System.arraycopy(audioBytes, 0, packet, header.size, audioBytes.size)

        _connectedPeers.value.keys.forEach { endpointId ->
            sendDirectPayload(endpointId, packet)
        }
    }

    fun sendFile(targetEndpointId: String, file: File, header: MediaHeader) {
        val lanOut = lanOutputs[targetEndpointId]
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
                    Log.e(TAG, "LAN file send err: ${e.message}")
                }
            }
            return
        }

        try {
            val filePayload = Payload.fromFile(file)
            val updatedHeader = header.copy(payloadId = filePayload.id)
            val headerPacket = MeshPacket(
                sourceNodeId = localNodeId,
                sourceNodeName = localNodeName,
                destinationNodeId = targetEndpointId,
                ttl = 1,
                packetType = PacketType.MEDIA_HEADER,
                payloadCiphertext = updatedHeader.toJson(),
                extraMeta = updatedHeader.toJson()
            )
            sendDirectPayload(targetEndpointId, headerPacket.toByteArray())
            connectionsClient.sendPayload(targetEndpointId, filePayload)
        } catch (e: Exception) {
            Log.e(TAG, "Nearby file send err: ${e.message}")
        }
    }
}
