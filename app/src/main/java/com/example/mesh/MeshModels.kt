package com.example.mesh

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

enum class PacketType {
    TEXT_MESSAGE,
    DELIVERY_ACK,
    READ_RECEIPT,
    KEY_EXCHANGE,
    MEDIA_HEADER,
    WEBRTC_SIGNAL,
    ROUTING_PING
}

enum class MessageStatus {
    SENDING,
    SENT,
    RELAYED,
    DELIVERED,
    READ,
    FAILED
}

enum class MediaType {
    TEXT,
    IMAGE,
    AUDIO_NOTE,
    FILE
}

enum class CallState {
    IDLE,
    OUTGOING_RINGING,
    INCOMING_RINGING,
    CONNECTED,
    ENDED
}

enum class CallType {
    VOICE,
    VIDEO
}

/**
 * Ad-hoc Mesh Network Packet.
 * Flooded or routed over Nearby Connections P2P Cluster.
 */
data class MeshPacket(
    val packetId: String = UUID.randomUUID().toString(),
    val sourceNodeId: String,
    val sourceNodeName: String,
    val destinationNodeId: String, // Specific nodeId or "*" for broadcast
    val ttl: Int = 5,
    val hopCount: Int = 0,
    val relayedBy: List<String> = emptyList(),
    val packetType: PacketType,
    val payloadCiphertext: String, // Base64 of AES-256-GCM encrypted payload
    val timestamp: Long = System.currentTimeMillis(),
    val extraMeta: String? = null
) {
    val isBroadcast: Boolean
        get() = destinationNodeId == "*" || destinationNodeId.isBlank()

    fun toByteArray(): ByteArray {
        val json = JSONObject().apply {
            put("pid", packetId)
            put("src", sourceNodeId)
            put("srcName", sourceNodeName)
            put("dst", destinationNodeId)
            put("ttl", ttl)
            put("hop", hopCount)
            val relaysArray = JSONArray()
            relayedBy.forEach { relaysArray.put(it) }
            put("relays", relaysArray)
            put("type", packetType.name)
            put("payload", payloadCiphertext)
            put("ts", timestamp)
            if (extraMeta != null) put("meta", extraMeta)
        }
        return json.toString().toByteArray(Charsets.UTF_8)
    }

    companion object {
        fun fromByteArray(bytes: ByteArray): MeshPacket? {
            return try {
                val json = JSONObject(String(bytes, Charsets.UTF_8))
                val relaysList = mutableListOf<String>()
                val relaysArray = json.optJSONArray("relays")
                if (relaysArray != null) {
                    for (i in 0 until relaysArray.length()) {
                        relaysList.add(relaysArray.getString(i))
                    }
                }
                MeshPacket(
                    packetId = json.getString("pid"),
                    sourceNodeId = json.getString("src"),
                    sourceNodeName = json.optString("srcName", "Peer"),
                    destinationNodeId = json.getString("dst"),
                    ttl = json.getInt("ttl"),
                    hopCount = json.optInt("hop", 0),
                    relayedBy = relaysList,
                    packetType = PacketType.valueOf(json.getString("type")),
                    payloadCiphertext = json.getString("payload"),
                    timestamp = json.optLong("ts", System.currentTimeMillis()),
                    extraMeta = if (json.has("meta")) json.getString("meta") else null
                )
            } catch (e: Exception) {
                null
            }
        }
    }
}

/**
 * Message Model representing a chat item in the local UI/storage.
 */
data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val senderId: String,
    val senderName: String,
    val recipientId: String,
    val text: String,
    val mediaType: MediaType = MediaType.TEXT,
    val mediaUri: String? = null,
    val fileName: String? = null,
    val fileSizeBytes: Long = 0L,
    val timestamp: Long = System.currentTimeMillis(),
    val isOutgoing: Boolean,
    val status: MessageStatus = MessageStatus.SENT,
    val hopCount: Int = 0,
    val relayPath: List<String> = emptyList(),
    val isEncrypted: Boolean = true
)

/**
 * Node in the P2P Mesh Network.
 */
data class PeerNode(
    val endpointId: String, // Nearby Connections endpoint identifier
    val nodeId: String, // Persistent UUID of the peer
    val nodeName: String,
    val isConnected: Boolean = false,
    val isDirect: Boolean = true,
    val hops: Int = 1,
    val publicKeyBase64: String? = null,
    val fingerprint: String? = null,
    val isVerified: Boolean = false,
    val lastSeen: Long = System.currentTimeMillis(),
    val signalStrengthDbm: Int = -55,
    val batteryPct: Int = 85,
    val packetsForwarded: Int = 0
)

/**
 * WebRTC / P2P Call Signaling message payload.
 */
data class WebRtcSignalMessage(
    val type: String, // "OFFER", "ANSWER", "CANDIDATE", "HANGUP", "HEARTBEAT"
    val sdp: String? = null,
    val candidate: String? = null,
    val sdpMid: String? = null,
    val sdpMLineIndex: Int = 0,
    val callType: CallType = CallType.VIDEO,
    val callId: String = UUID.randomUUID().toString()
) {
    fun toJson(): String {
        val json = JSONObject().apply {
            put("type", type)
            if (sdp != null) put("sdp", sdp)
            if (candidate != null) put("candidate", candidate)
            if (sdpMid != null) put("sdpMid", sdpMid)
            put("sdpMLineIndex", sdpMLineIndex)
            put("callType", callType.name)
            put("callId", callId)
        }
        return json.toString()
    }

    companion object {
        fun fromJson(jsonStr: String): WebRtcSignalMessage? {
            return try {
                val json = JSONObject(jsonStr)
                WebRtcSignalMessage(
                    type = json.getString("type"),
                    sdp = if (json.has("sdp")) json.getString("sdp") else null,
                    candidate = if (json.has("candidate")) json.getString("candidate") else null,
                    sdpMid = if (json.has("sdpMid")) json.getString("sdpMid") else null,
                    sdpMLineIndex = json.optInt("sdpMLineIndex", 0),
                    callType = CallType.valueOf(json.optString("callType", CallType.VIDEO.name)),
                    callId = json.optString("callId", "")
                )
            } catch (e: Exception) {
                null
            }
        }
    }
}

/**
 * High-Speed Media Header sent before sending large files or audio streams.
 */
data class MediaHeader(
    val payloadId: Long,
    val messageId: String,
    val mediaType: MediaType,
    val fileName: String,
    val fileSizeBytes: Long,
    val mimeType: String,
    val durationSeconds: Int = 0
) {
    fun toJson(): String {
        return JSONObject().apply {
            put("pid", payloadId)
            put("mid", messageId)
            put("type", mediaType.name)
            put("name", fileName)
            put("size", fileSizeBytes)
            put("mime", mimeType)
            put("dur", durationSeconds)
        }.toString()
    }

    companion object {
        fun fromJson(jsonStr: String): MediaHeader? {
            return try {
                val json = JSONObject(jsonStr)
                MediaHeader(
                    payloadId = json.getLong("pid"),
                    messageId = json.getString("mid"),
                    mediaType = MediaType.valueOf(json.getString("type")),
                    fileName = json.getString("name"),
                    fileSizeBytes = json.getLong("size"),
                    mimeType = json.getString("mime"),
                    durationSeconds = json.optInt("dur", 0)
                )
            } catch (e: Exception) {
                null
            }
        }
    }
}
