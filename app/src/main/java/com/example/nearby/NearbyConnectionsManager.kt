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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.SecretKey

class NearbyConnectionsManager(private val context: Context) {

    companion object {
        private const val TAG = "NearbyManager"
        private const val SERVICE_ID = "com.example.ciphermesh.p2p"
        val STRATEGY: Strategy = Strategy.P2P_CLUSTER
        private const val MAX_SEEN_PACKETS = 8000
        private const val LAN_UDP_PORT = 45454
        private const val LAN_TCP_PORT = 45455
        private const val DEFAULT_MESH_TTL = 12
    }

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

    private val _onFileReceived = MutableStateFlow(Pair(dummyHeader, dummyFile))
    val onFileReceived = _onFileReceived
        .filter { it.first.messageId.isNotEmpty() }
        .shareIn(scope, SharingStarted.Eagerly, replay = 0)

    private val seenPacketIds = Collections.synchronizedSet(mutableSetOf("init").apply { clear() })

    private val dummyOut = DataOutputStream(ByteArrayOutputStream())
    private val lanOutputs = Collections.synchronizedMap(mutableMapOf("init" to dummyOut).apply { clear() })
    private val knownLanPeerIps = Collections.synchronizedSet(mutableSetOf("init").apply { clear() })
    private val connectingLanIps = Collections.synchronizedSet(mutableSetOf("init").apply { clear() })

    private val isVideoSendBusy = AtomicBoolean(false)
    private val lastVideoSendTime = AtomicLong(0L)
    private val isAudioSendBusy = AtomicBoolean(false)
    private val lastAudioSendTime = AtomicLong(0L)

    private var isLanRunning = false
    private var udpSocket: DatagramSocket? = null
    private var tcpServer: ServerSocket? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    init {
        val saved = prefs.getStringSet("saved_gateways", null)
        if (saved != null) {
            _gatewayIps.value = saved.toList().sorted()
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
        return ips.firstOrNull() ?: "غير متصل"
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
            } catch (e: Exception) {
                Log.e(TAG, "LAN TCP Server err: ${e.message}")
            }
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
                val localIpsCache = getLocalIPv4Addresses().toSet()

                while (isLanRunning) {
                    val pkt = DatagramPacket(buf, buf.size)
                    udpSocket?.receive(pkt) ?: break
                    val len = pkt.length
                    if (len in 0..3) continue

                    val senderIp = pkt.address.hostAddress ?: continue
                    if (localIpsCache.contains(senderIp) || getLocalIPv4Addresses().contains(senderIp)) {
                        continue
                    }

                    val b0 = pkt.data[0]
                    val b1 = pkt.data[1]

                    // Fast UDP Video Frame ('V', 'F')
                    if (b0 == 0x56.toByte() && b1 == 0x46.toByte()) {
                        knownLanPeerIps.add(senderIp)
                        _incomingVideoFrames.value = pkt.data.copyOfRange(2, len)
                        continue
                    }

                    // Fast UDP Audio Frame ('A', 'F')
                    if (b0 == 0x41.toByte() && b1 == 0x46.toByte()) {
                        knownLanPeerIps.add(senderIp)
                        _incomingAudioPackets.value = pkt.data.copyOfRange(2, len)
                        continue
                    }

                    // Fast UDP Mesh Chat/Signal Packet ('M', 'P')
                    if (b0 == 0x4D.toByte() && b1 == 0x50.toByte()) {
                        knownLanPeerIps.add(senderIp)
                        val meshBytes = pkt.data.copyOfRange(2, len)
                        handleIncomingBytes("lan-$senderIp", meshBytes)
                        continue
                    }

                    val msg = String(pkt.data, 0, len, Charsets.UTF_8)
                    if (msg.startsWith("CMESH|")) {
                        val parts = msg.split("|")
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
            } catch (e: Exception) {
                Log.e(TAG, "LAN UDP Listener err: ${e.message}")
            }
        }

        scope.launch {
            var tick = 0
            while (isLanRunning) {
                try {
                    val beaconBytes = "CMESH|$localNodeId|$localNodeName\vert{}$LAN_TCP_PORT".toByteArray(Charsets.UTF_8)
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

                            val commonPrefixes = listOf(prefix, "192.168.43.", "192.168.49.", "192.168.1.", "192.168.0.", "10.0.0.").distinct()
                            for (pfx in commonPrefixes) {
                                try {
                                    sender.send(DatagramPacket(beaconBytes, beaconBytes.size, InetAddress.getByName("${pfx}255"), LAN_UDP_PORT))
                                } catch (e: Exception) {}
                                if (tick % 4 == 0) {
                                    for (host in 1..254) {
                                        val targetIp = "$pfx$host"
                                        if (targetIp != ip) {
                                            try {
                                                sender.send(DatagramPacket(beaconBytes, beaconBytes.size, InetAddress.getByName(targetIp), LAN_UDP_PORT))
                                            } catch (e: Exception) {}
                                        }
                                    }
                                }
                            }
                        }
                    }

                    if (tick % 3 == 0 && _connectedPeers.value.isNotEmpty()) {
                        broadcastMultiHopPresence()
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
        val localIps = getLocalIPv4Addresses()
        if (localIps.contains(ip)) return

        knownLanPeerIps.add(ip)
        val endpointId = "lan-$ip"
        val alreadyConnected = lanOutputs.containsKey(endpointId)
        if (alreadyConnected || !connectingLanIps.add(ip)) return

        scope.launch {
            try {
                val socket = Socket()
                socket.tcpNoDelay = true
                socket.keepAlive = true
                socket.connect(InetSocketAddress(ip, LAN_TCP_PORT), 2500)
                handleLanSocket(socket, endpointId, peerNodeId, "$peerName (Wi-Fi)")
            } catch (e: Exception) {
            } finally {
                connectingLanIps.remove(ip)
            }
        }
    }

    private fun handleLanSocket(socket: Socket, endpointId: String, initialNodeId: String, initialName: String) {
        scope.launch {
            try {
                socket.tcpNoDelay = true
                socket.keepAlive = true
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

                delay(300)
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
                            _onFileReceived.value = Pair(header, tempFile)
                        }
                    }
                }
            } catch (e: Exception) {
            } finally {
                lanOutputs.remove(endpointId)
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
        if (endpointId.startsWith("lan-")) return
        connectionsClient.requestConnection(localNodeName, endpointId, connectionLifecycleCallback)
    }

    fun disconnect(endpointId: String) {
        if (endpointId.startsWith("lan-")) {
            val ip = endpointId.removePrefix("lan-")
            knownLanPeerIps.remove(ip)
            lanOutputs.remove(endpointId)?.let { try { it.close() } catch (e: Exception) {} }
        } else {
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
            ttl = 6,
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
            ttl = 6,
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
                    sourceNodeName = peer.nodeName.replace(" (Wi-Fi)", "").replace(" (تلقائي)", ""),
                    destinationNodeId = "*",
                    ttl = 5,
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
        if (bytes.size in 4..Int.MAX_VALUE && bytes[0] == 0x56.toByte() && bytes[1] == 0x46.toByte()) {
            _incomingVideoFrames.value = bytes.copyOfRange(2, bytes.size)
            return
        }
        if (bytes.size in 4..Int.MAX_VALUE && bytes[0] == 0x41.toByte() && bytes[1] == 0x46.toByte()) {
            _incomingAudioPackets.value = bytes.copyOfRange(2, bytes.size)
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

    private fun handleKeyExchangePacket(incomingEndpointId: String, packet: MeshPacket) {
        try {
            val peerPubKeyBase64 = packet.payloadCiphertext
            val sharedKey = CryptoManager.deriveSharedSecretFromBase64(peerPubKeyBase64)
            peerSessionKeys[packet.sourceNodeId] = sharedKey

            val hopsCount = packet.hopCount + 1
            val isDirectPeer = (hopsCount == 1)

            if (isDirectPeer) {
                peerSessionKeys[incomingEndpointId] = sharedKey
                val existing = _connectedPeers.value[incomingEndpointId]
                val cleanName = packet.sourceNodeName.replace(" (Wi-Fi)", "").replace(" (تلقائي)", "")
                val displayName = if (incomingEndpointId.startsWith("lan-")) {
                    "$cleanName (Wi-Fi)"
                } else {
                    cleanName
                }
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
                    val cleanName = packet.sourceNodeName
