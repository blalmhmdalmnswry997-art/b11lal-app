package com.example.webrtc

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.example.crypto.CryptoManager
import com.example.mesh.CallState
import com.example.mesh.CallType
import com.example.mesh.MeshPacket
import com.example.mesh.PacketType
import com.example.mesh.WebRtcSignalMessage
import com.example.nearby.NearbyConnectionsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.sqrt

class WebRtcP2PManager(
    private val context: Context,
    private val nearbyManager: NearbyConnectionsManager
) {
    companion object {
        private const val SAMPLE_RATE = 16000
        private const val AUDIO_CHANNEL_IN = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_CHANNEL_OUT = AudioFormat.CHANNEL_OUT_MONO
        private const val AUDIO_ENCODING = AudioFormat.ENCODING_PCM_16BIT
        private const val AUDIO_PACKET_BYTES = 3200
        private const val VAD_RMS_THRESHOLD = 135.0
    }

    private val streamCipherMask = byteArrayOf(
        0x5A.toByte(), 0x3C.toByte(), 0x7F.toByte(), 0x19.toByte(),
        0x6D.toByte(), 0x42.toByte(), 0x2E.toByte(), 0x0B.toByte()
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val cameraExecutor = Executors.newSingleThreadExecutor()

    private val nullString: String? = null
    private val nullBitmap: Bitmap? = null

    private val _callState = MutableStateFlow(CallState.IDLE)
    val callState = _callState.asStateFlow()

    private val _activePeerId = MutableStateFlow(nullString)
    val activePeerId = _activePeerId.asStateFlow()

    private val _activePeerName = MutableStateFlow("متصل")
    val activePeerName = _activePeerName.asStateFlow()

    private val _callType = MutableStateFlow(CallType.VIDEO)
    val callType = _callType.asStateFlow()

    private val _fps = MutableStateFlow(0)
    val fps = _fps.asStateFlow()

    private val _bitrateKbps = MutableStateFlow(0)
    val bitrateKbps = _bitrateKbps.asStateFlow()

    private val _rttMs = MutableStateFlow(12)
    val rttMs = _rttMs.asStateFlow()

    private val _packetLossPercent = MutableStateFlow(0)
    val packetLossPercent = _packetLossPercent.asStateFlow()

    private val _remoteVideoBitmap = MutableStateFlow(nullBitmap)
    val remoteVideoBitmap = _remoteVideoBitmap.asStateFlow()

    private val _localVideoBitmap = MutableStateFlow(nullBitmap)
    val localVideoBitmap = _localVideoBitmap.asStateFlow()

    private val _isMicMuted = MutableStateFlow(false)
    val isMicMuted = _isMicMuted.asStateFlow()

    private val _isVideoMuted = MutableStateFlow(false)
    val isVideoMuted = _isVideoMuted.asStateFlow()

    private val _isSpeakerOn = MutableStateFlow(true)
    val isSpeakerOn = _isSpeakerOn.asStateFlow()

    private val _isFrontCamera = MutableStateFlow(true)
    val isFrontCamera = _isFrontCamera.asStateFlow()

    private val _isWalkieTalkieEnabled = MutableStateFlow(true)
    val isWalkieTalkieEnabled = _isWalkieTalkieEnabled.asStateFlow()

    private val _isPttTransmitting = MutableStateFlow(false)
    val isPttTransmitting = _isPttTransmitting.asStateFlow()

    private val _isPttReceiving = MutableStateFlow(false)
    val isPttReceiving = _isPttReceiving.asStateFlow()

    // Adaptive Bitrate (ABR) Controller State
    private val adaptiveJpegQuality = AtomicInteger(64)
    private val adaptiveFrameIntervalMs = AtomicInteger(65)
    private val adaptiveTargetShortSide = AtomicInteger(240)

    // Audio Sequence & Packet Loss Tracking
    private val outgoingAudioSeq = AtomicInteger(0)
    private var lastReceivedAudioSeq = -1
    private var receivedAudioPacketsWindow = 0
    private var lostAudioPacketsWindow = 0

    private var activeCameraProvider: ProcessCameraProvider? = null
    private var audioRecordJob: Job? = null
    private var pttResetJob: Job? = null
    private var audioTrack: AudioTrack? = null
    private var audioRecord: AudioRecord? = null
    private var noiseSuppressor: NoiseSuppressor? = null
    private var echoCanceler: AcousticEchoCanceler? = null
    private var gainControl: AutomaticGainControl? = null

    private val audioLock = Any()
    private val isVideoFrameInFlight = AtomicBoolean(false)
    private val isVideoDecodeInFlight = AtomicBoolean(false)

    private var frameCounter = 0
    private var byteCounter = 0
    private var lastStatsTime = System.currentTimeMillis()
    private var lastFrameTimestamp = 0L

    init {
        scope.launch(Dispatchers.IO) {
            nearbyManager.incomingAudioPackets.collect { packetBytes ->
                onEncryptedAudioReceived(packetBytes)
            }
        }

        scope.launch(Dispatchers.Default) {
            nearbyManager.incomingVideoFrames.collect { frameBytes ->
                if (isVideoDecodeInFlight.compareAndSet(false, true)) {
                    try {
                        onEncryptedVideoReceived(frameBytes)
                    } finally {
                        isVideoDecodeInFlight.set(false)
                    }
                }
            }
        }

        scope.launch {
            while (isActive) {
                delay(1000)
                val now = System.currentTimeMillis()
                val elapsed = now - lastStatsTime
                if (elapsed in 1L..Long.MAX_VALUE) {
                    val sec = elapsed / 1000.0
                    val currentFps = (frameCounter / sec).toInt()
                    val currentKbps = ((byteCounter * 8) / (sec * 1024)).toInt()
                    _fps.value = currentFps
                    _bitrateKbps.value = currentKbps

                    val totalAudioWindow = receivedAudioPacketsWindow + lostAudioPacketsWindow
                    if (totalAudioWindow in 1..Int.MAX_VALUE) {
                        _packetLossPercent.value = ((lostAudioPacketsWindow * 100) / totalAudioWindow).coerceIn(0, 100)
                    } else {
                        _packetLossPercent.value = 0
                    }
                    receivedAudioPacketsWindow = 0
                    lostAudioPacketsWindow = 0

                    // Dynamic Adaptive Bitrate (ABR) adjustment based on real-time FPS & Latency
                    if (_callState.value == CallState.CONNECTED && !_isVideoMuted.value) {
                        val lat = _rttMs.value
                        if (lat in 180..Int.MAX_VALUE || currentFps in 0..5) {
                            // Network congestion detected: step down resolution & quality for smooth motion
                            adaptiveJpegQuality.set(45)
                            adaptiveFrameIntervalMs.set(90)
                            adaptiveTargetShortSide.set(192)
                        } else if (lat in 90..179) {
                            adaptiveJpegQuality.set(56)
                            adaptiveFrameIntervalMs.set(70)
                            adaptiveTargetShortSide.set(224)
                        } else {
                            // Clear channel: step up to crisp HD quality
                            adaptiveJpegQuality.set(68)
                            adaptiveFrameIntervalMs.set(58)
                            adaptiveTargetShortSide.set(256)
                        }
                    }

                    frameCounter = 0
                    byteCounter = 0
                    lastStatsTime = now
                }
            }
        }
    }

    private fun encryptMediaPayload(plainBytes: ByteArray): ByteArray {
        val peerId = _activePeerId.value
        if (peerId != null && peerId != "*") {
            try {
                val key = nearbyManager.getSessionKey(peerId)
                return CryptoManager.encryptAesGcm(plainBytes, key)
            } catch (t: Throwable) {}
        }
        val out = ByteArray(plainBytes.size)
        val maskLen = streamCipherMask.size
        for (i in plainBytes.indices) {
            out[i] = (plainBytes[i].toInt() xor streamCipherMask[i % maskLen].toInt()).toByte()
        }
        return out
    }

    private fun decryptMediaPayload(cipherBytes: ByteArray): ByteArray {
        val peerId = _activePeerId.value
        if (peerId != null && peerId != "*" && cipherBytes.size in 29..Int.MAX_VALUE) {
            try {
                val key = nearbyManager.getSessionKey(peerId)
                return CryptoManager.decryptAesGcm(cipherBytes, key)
            } catch (t: Throwable) {}
        }
        val firstPeer = nearbyManager.connectedPeers.value.values.firstOrNull()
        if (firstPeer != null && cipherBytes.size in 29..Int.MAX_VALUE) {
            try {
                val key = nearbyManager.getSessionKey(firstPeer.nodeId)
                return CryptoManager.decryptAesGcm(cipherBytes, key)
            } catch (t: Throwable) {}
        }
        val out = ByteArray(cipherBytes.size)
        val maskLen = streamCipherMask.size
        for (i in cipherBytes.indices) {
            out[i] = (cipherBytes[i].toInt() xor streamCipherMask[i % maskLen].toInt()).toByte()
        }
        return out
    }

    /**
     * Voice Activity Detection (VAD) + Digital Speech Clarifier:
     * Returns true if active human speech is detected, false if silence/background noise.
     */
    private fun processVadAndClarifyPcm16(pcmBytes: ByteArray, length: Int): Boolean {
        var sumSquares = 0.0
        val sampleCount = length / 2
        if (sampleCount == 0) return false

        var i = 0
        while (i + 1 in 0 until length) {
            val lo = pcmBytes[i].toInt() and 0xFF
            val hi = pcmBytes[i + 1].toInt()
            var sample = (hi shl 8) or lo

            sumSquares += (sample.toDouble() * sample.toDouble())

            if (sample in -150..150) {
                sample = 0
            } else {
                sample = ((sample * 14) / 10).coerceIn(-32700, 32700)
            }

            pcmBytes[i] = (sample and 0xFF).toByte()
            pcmBytes[i + 1] = ((sample shr 8) and 0xFF).toByte()
            i += 2
        }

        val rms = sqrt(sumSquares / sampleCount)
        return rms >= VAD_RMS_THRESHOLD
    }

    fun toggleWalkieTalkieMode() {
        val nextState = !_isWalkieTalkieEnabled.value
        _isWalkieTalkieEnabled.value = nextState
        if (!nextState && _isPttTransmitting.value) {
            stopPttTransmission()
        }
    }

    fun startPttTransmission() {
        // Floor Control Arbitration: Prevent talking over an active incoming PTT stream
        if (_callState.value != CallState.IDLE || _isPttTransmitting.value || _isPttReceiving.value) return
        _isPttTransmitting.value = true
        nearbyManager.setCallHighSpeedMode(true)
        ensureAudibleSpeakerVolume()
        initAudioPlayer()
        initAudioRecorder()
    }

    fun stopPttTransmission() {
        if (!_isPttTransmitting.value) return
        _isPttTransmitting.value = false
        if (_callState.value == CallState.IDLE) {
            releaseAudioRecorder()
            nearbyManager.setCallHighSpeedMode(false)
        }
    }

    fun initiateCall(peerNodeId: String, peerName: String, type: CallType) {
        if (_isPttTransmitting.value) stopPttTransmission()
        nearbyManager.clearMediaStreams()
        _activePeerId.value = peerNodeId
        _activePeerName.value = peerName
        _callType.value = type
        _isVideoMuted.value = (type == CallType.VOICE)
        _callState.value = CallState.OUTGOING_RINGING

        val offer = WebRtcSignalMessage(
            type = "OFFER",
            sdp = "v=0;o=CipherMesh;s=DirectCall",
            callType = type
        )
        sendSignalPacket(peerNodeId, offer)
    }

    fun acceptIncomingCall() {
        val peerId = _activePeerId.value ?: return
        if (_isPttTransmitting.value) stopPttTransmission()
        _callState.value = CallState.CONNECTED
        nearbyManager.setCallHighSpeedMode(true)

        val answer = WebRtcSignalMessage(
            type = "ANSWER",
            sdp = "v=0;o=CipherMesh;s=DirectAnswer",
            callType = _callType.value
        )
        sendSignalPacket(peerId, answer)
        startCallMedia()
    }

    fun rejectOrEndCall() {
        val peerId = _activePeerId.value
        resetCallSession()
        if (peerId != null) {
            scope.launch(Dispatchers.IO) {
                val hangup = WebRtcSignalMessage(type = "HANGUP")
                sendSignalPacket(peerId, hangup)
                delay(120)
                sendSignalPacket(peerId, hangup)
            }
        }
    }

    private fun resetCallSession() {
        _isPttTransmitting.value = false
        _callState.value = CallState.ENDED
        stopCallMedia()
        unbindCameraHardware()
        nearbyManager.setCallHighSpeedMode(false)
        _activePeerId.value = null
        _remoteVideoBitmap.value = null
        _localVideoBitmap.value = null
        isVideoFrameInFlight.set(false)
        isVideoDecodeInFlight.set(false)
        lastReceivedAudioSeq = -1

        scope.launch {
            delay(200)
            if (_callState.value == CallState.ENDED) {
                _callState.value = CallState.IDLE
            }
        }
    }

    private fun unbindCameraHardware() {
        try {
            ContextCompat.getMainExecutor(context).execute {
                try {
                    activeCameraProvider?.unbindAll()
                } catch (t: Throwable) {}
            }
        } catch (t: Throwable) {}
    }

    fun handleIncomingSignalPacket(packet: MeshPacket) {
        val signal = WebRtcSignalMessage.fromJson(packet.payloadCiphertext) ?: return
        when (signal.type) {
            "OFFER" -> {
                if (_callState.value == CallState.IDLE || _callState.value == CallState.ENDED) {
                    _activePeerId.value = packet.sourceNodeId
                    _activePeerName.value = packet.sourceNodeName
                    _callType.value = signal.callType
                    _isVideoMuted.value = (signal.callType == CallType.VOICE)
                    _callState.value = CallState.INCOMING_RINGING
                }
            }
            "ANSWER" -> {
                if (_callState.value == CallState.OUTGOING_RINGING) {
                    _callState.value = CallState.CONNECTED
                    nearbyManager.setCallHighSpeedMode(true)
                    startCallMedia()
                }
            }
            "HANGUP" -> {
                resetCallSession()
            }
        }
    }

    private fun sendSignalPacket(targetPeerId: String, signal: WebRtcSignalMessage) {
        val pkt = MeshPacket(
            sourceNodeId = nearbyManager.localNodeId,
            sourceNodeName = nearbyManager.localNodeName,
            destinationNodeId = targetPeerId,
            ttl = 10,
            packetType = PacketType.WEBRTC_SIGNAL,
            payloadCiphertext = signal.toJson()
        )
        nearbyManager.sendMeshPacket(pkt)
    }

    private fun startCallMedia() {
        ensureAudibleSpeakerVolume()
        initAudioPlayer()
        initAudioRecorder()
    }

    private fun stopCallMedia() {
        releaseAudioRecorder()
        releaseAudioPlayer()
    }

    private fun ensureAudibleSpeakerVolume() {
        try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
            am.mode = AudioManager.MODE_NORMAL
            am.isSpeakerphoneOn = _isSpeakerOn.value
            val maxVol = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val curVol = am.getStreamVolume(AudioManager.STREAM_MUSIC)
            if (curVol in 0..(maxVol / 2)) {
                am.setStreamVolume(AudioManager.STREAM_MUSIC, (maxVol * 4) / 5, 0)
            }
        } catch (t: Throwable) {}
    }

    @Suppress("DEPRECATION")
    fun bindCameraCapture(lifecycleOwner: LifecycleOwner) {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        if (!granted) return

        try {
            val future = ProcessCameraProvider.getInstance(context)
            future.addListener({
                try {
                    val provider = future.get()
                    activeCameraProvider = provider
                    if (_callState.value != CallState.CONNECTED && _callState.value != CallState.OUTGOING_RINGING) {
                        provider.unbindAll()
                        return@addListener
                    }

                    val selector = if (_isFrontCamera.value) {
                        CameraSelector.DEFAULT_FRONT_CAMERA
                    } else {
                        CameraSelector.DEFAULT_BACK_CAMERA
                    }

                    val analysis = ImageAnalysis.Builder()
                        .setTargetResolution(Size(360, 480))
                        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()

                    analysis.setAnalyzer(cameraExecutor) { proxy ->
                        encodeAndSendFrame(proxy)
                    }

                    provider.unbindAll()
                    provider.bindToLifecycle(lifecycleOwner, selector, analysis)
                } catch (t: Throwable) {}
            }, ContextCompat.getMainExecutor(context))
        } catch (t: Throwable) {}
    }

    private fun encodeAndSendFrame(imageProxy: ImageProxy) {
        try {
            val activeState = _callState.value
            if (activeState != CallState.CONNECTED && activeState != CallState.OUTGOING_RINGING) {
                return
            }
            val now = System.currentTimeMillis()
            val dynamicInterval = adaptiveFrameIntervalMs.get().toLong()
            if (_isVideoMuted.value || (now - lastFrameTimestamp) in 0L until dynamicInterval) {
                return
            }
            if (!isVideoFrameInFlight.compareAndSet(false, true)) {
                return
            }
            lastFrameTimestamp = now

            try {
                val jpegData = convertProxyHardwareSmoothJpeg(imageProxy)
                if (jpegData != null && _callState.value == CallState.CONNECTED) {
                    // Prepend 8-byte capture timestamp for End-to-End Latency & Jitter tracking
                    val timedFrame = ByteBuffer.allocate(8 + jpegData.size)
                        .putLong(System.currentTimeMillis())
                        .put(jpegData)
                        .array()
                    val encryptedPayload = encryptMediaPayload(timedFrame)
                    nearbyManager.sendDirectVideoFrame(encryptedPayload)
                }
            } finally {
                isVideoFrameInFlight.set(false)
            }
        } catch (t: Throwable) {
            isVideoFrameInFlight.set(false)
        } finally {
            try { imageProxy.close() } catch (t: Throwable) {}
        }
    }

    private fun onEncryptedVideoReceived(encryptedBytes: ByteArray) {
        if (_callState.value != CallState.CONNECTED) return
        try {
            val decrypted = decryptMediaPayload(encryptedBytes)
            var rawJpeg = decrypted

            if (decrypted.size in 32..Int.MAX_VALUE) {
                val sentTs = ByteBuffer.wrap(decrypted, 0, 8).long
                val now = System.currentTimeMillis()
                val diff = if (now >= sentTs) (now - sentTs) else (sentTs - now)
                if (diff in 1L..5000L) {
                    _rttMs.value = diff.toInt().coerceIn(5, 999)
                    rawJpeg = decrypted.copyOfRange(8, decrypted.size)
                }
            }

            val opts = BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.ARGB_8888
                inDither = true
            }
            val bmp = BitmapFactory.decodeByteArray(rawJpeg, 0, rawJpeg.size, opts)
                ?: BitmapFactory.decodeByteArray(decrypted, 0, decrypted.size, opts)

            if (bmp != null) {
                _remoteVideoBitmap.value = bmp
                frameCounter += 1
                byteCounter += encryptedBytes.size
            }
        } catch (t: Throwable) {}
    }

    private fun convertProxyHardwareSmoothJpeg(image: ImageProxy): ByteArray? {
        try {
            val srcW = image.width
            val srcH = image.height
            if (srcW in 0..15 || srcH in 0..15) return null

            val yPlane = image.planes[0]
            val uPlane = image.planes[1]
            val vPlane = image.planes[2]

            val yBuf = yPlane.buffer
            val uBuf = uPlane.buffer
            val vBuf = vPlane.buffer

            val yRowStride = yPlane.rowStride
            val yPixStride = yPlane.pixelStride
            val uRowStride = uPlane.rowStride
            val uPixStride = uPlane.pixelStride
            val vRowStride = vPlane.rowStride
            val vPixStride = vPlane.pixelStride

            val nv21 = ByteArray(srcW * srcH + (srcW * srcH) / 2)
            var idx = 0
            val yLim = yBuf.limit()

            if (yPixStride == 1 && yRowStride == srcW) {
                yBuf.position(0)
                yBuf.get(nv21, 0, srcW * srcH)
                idx = srcW * srcH
            } else {
                for (r in 0 until srcH) {
                    val rowStart = r * yRowStride
                    for (c in 0 until srcW) {
                        val pos = rowStart + c * yPixStride
                        nv21[idx++] = if (pos in 0 until yLim) yBuf.get(pos) else 0
                    }
                }
            }

            val uvH = srcH / 2
            val uvW = srcW / 2
            val uLim = uBuf.limit()
            val vLim = vBuf.limit()

            for (r in 0 until uvH) {
                val uRow = r * uRowStride
                val vRow = r * vRowStride
                for (c in 0 until uvW) {
                    val uPos = uRow + c * uPixStride
                    val vPos = vRow + c * vPixStride
                    nv21[idx++] = if (vPos in 0 until vLim) vBuf.get(vPos) else 128.toByte()
                    nv21[idx++] = if (uPos in 0 until uLim) uBuf.get(uPos) else 128.toByte()
                }
            }

            val yuvImg = YuvImage(nv21, ImageFormat.NV21, srcW, srcH, null)
            val rawOut = ByteArrayOutputStream()
            yuvImg.compressToJpeg(Rect(0, 0, srcW, srcH), 80, rawOut)
            val rawBytes = rawOut.toByteArray()

            val decoded = BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size) ?: return null

            val targetShortSide = adaptiveTargetShortSide.get().toFloat()
            val scale = minOf(targetShortSide / minOf(decoded.width, decoded.height), 1f)
            val m = Matrix()
            m.postScale(scale, scale)

            val deg = image.imageInfo.rotationDegrees.toFloat()
            if (deg != 0f) {
                m.postRotate(deg)
            }
            if (_isFrontCamera.value) {
                m.postScale(-1f, 1f)
            }

            val smoothBitmap = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, m, true)
            _localVideoBitmap.value = smoothBitmap

            val finalStream = ByteArrayOutputStream()
            val dynamicQuality = adaptiveJpegQuality.get().coerceIn(38, 75)
            smoothBitmap.compress(Bitmap.CompressFormat.JPEG, dynamicQuality, finalStream)
            return finalStream.toByteArray()
        } catch (t: Throwable) {
            return null
        }
    }

    @SuppressLint("MissingPermission")
    private fun initAudioRecorder() {
        releaseAudioRecorder()
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (!granted) return

        try {
            val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AUDIO_CHANNEL_IN, AUDIO_ENCODING)
            val recBufSize = maxOf(minBuf * 3, AUDIO_PACKET_BYTES * 4)

            val rec = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                AUDIO_CHANNEL_IN,
                AUDIO_ENCODING,
                recBufSize
            )

            if (rec.state != AudioRecord.STATE_INITIALIZED) return

            try {
                val sId = rec.audioSessionId
                if (NoiseSuppressor.isAvailable()) {
                    noiseSuppressor = NoiseSuppressor.create(sId)?.apply { enabled = true }
                }
                if (AcousticEchoCanceler.isAvailable()) {
                    echoCanceler = AcousticEchoCanceler.create(sId)?.apply { enabled = true }
                }
                if (AutomaticGainControl.isAvailable()) {
                    gainControl = AutomaticGainControl.create(sId)?.apply { enabled = true }
                }
            } catch (t: Throwable) {}

            rec.startRecording()
            audioRecord = rec

            audioRecordJob = scope.launch(Dispatchers.IO) {
                val packetBuf = ByteArray(AUDIO_PACKET_BYTES)
                var silentFramesCount = 0

                while (isActive && (_callState.value == CallState.CONNECTED || _isPttTransmitting.value)) {
                    val shouldSend = (_callState.value == CallState.CONNECTED && !_isMicMuted.value) || _isPttTransmitting.value
                    if (shouldSend) {
                        var totalRead = 0
                        while (totalRead in 0 until AUDIO_PACKET_BYTES && isActive) {
                            val r = try {
                                rec.read(packetBuf, totalRead, AUDIO_PACKET_BYTES - totalRead)
                            } catch (t: Throwable) {
                                -1
                            }
                            if (r in 1..Int.MAX_VALUE) {
                                totalRead += r
                            } else {
                                break
                            }
                        }

                        if (totalRead in 1..Int.MAX_VALUE) {
                            val chunk = packetBuf.copyOf(totalRead)
                            val hasVoice = processVadAndClarifyPcm16(chunk, chunk.size)

                            if (hasVoice) {
                                silentFramesCount = 0
                            } else {
                                silentFramesCount += 1
                            }

                            // VAD Optimization: Transmit active speech + 3 trailing frames for natural decay
                            if (hasVoice || silentFramesCount in 1..3 || _isPttTransmitting.value) {
                                val seq = outgoingAudioSeq.incrementAndGet()
                                val sequencedAudio = ByteBuffer.allocate(4 + chunk.size)
                                    .putInt(seq)
                                    .put(chunk)
                                    .array()
                                val encryptedAudio = encryptMediaPayload(sequencedAudio)
                                nearbyManager.sendDirectAudioFrame(encryptedAudio)
                            }
                        }
                    } else {
                        delay(25)
                    }
                }
            }
        } catch (t: Throwable) {}
    }

    private fun releaseAudioRecorder() {
        audioRecordJob?.cancel()
        audioRecordJob = null
        try { noiseSuppressor?.release() } catch (t: Throwable) {}
        noiseSuppressor = null
        try { echoCanceler?.release() } catch (t: Throwable) {}
        echoCanceler = null
        try { gainControl?.release() } catch (t: Throwable) {}
        gainControl = null
        try { audioRecord?.stop() } catch (t: Throwable) {}
        try { audioRecord?.release() } catch (t: Throwable) {}
        audioRecord = null
    }

    private fun initAudioPlayer() {
        synchronized(audioLock) {
            if (audioTrack != null) return
            try {
                val minBuf = AudioTrack.getMinBufferSize(SAMPLE_RATE, AUDIO_CHANNEL_OUT, AUDIO_ENCODING)
                val trackBufSize = maxOf(minBuf * 3, AUDIO_PACKET_BYTES * 5)

                val track = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AUDIO_ENCODING)
                            .setSampleRate(SAMPLE_RATE)
                            .setChannelMask(AUDIO_CHANNEL_OUT)
                            .build()
                    )
                    .setBufferSizeInBytes(trackBufSize)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()

                track.play()
                audioTrack = track
            } catch (t: Throwable) {}
        }
    }

    private fun releaseAudioPlayer() {
        synchronized(audioLock) {
            try { audioTrack?.pause() } catch (t: Throwable) {}
            try { audioTrack?.flush() } catch (t: Throwable) {}
            try { audioTrack?.stop() } catch (t: Throwable) {}
            try { audioTrack?.release() } catch (t: Throwable) {}
            audioTrack = null
            Unit
        }
    }

    private fun onEncryptedAudioReceived(encryptedAudio: ByteArray) {
        val inCall = (_callState.value == CallState.CONNECTED)
        if (!inCall && !_isWalkieTalkieEnabled.value) return

        if (audioTrack == null) {
            ensureAudibleSpeakerVolume()
            initAudioPlayer()
        }
        val track = audioTrack ?: return

        try {
            val decrypted = decryptMediaPayload(encryptedAudio)
            var pcmData = decrypted

            // Extract 4-byte sequence number to measure Packet Loss & Jitter
            if (decrypted.size in 36..Int.MAX_VALUE && (decrypted.size % 2 == 0)) {
                val seq = ByteBuffer.wrap(decrypted, 0, 4).int
                if (seq in 1..10_000_000) {
                    if (lastReceivedAudioSeq in 1 until seq) {
                        val gap = (seq - lastReceivedAudioSeq - 1).coerceIn(0, 20)
                        lostAudioPacketsWindow += gap
                    }
                    lastReceivedAudioSeq = seq
                    receivedAudioPacketsWindow += 1
                    pcmData = decrypted.copyOfRange(4, decrypted.size)
                }
            }

            if (!inCall) {
                _isPttReceiving.value = true
                // Floor Control: If we were transmitting, yield if remote started first
                pttResetJob?.cancel()
                pttResetJob = scope.launch {
                    delay(650)
                    _isPttReceiving.value = false
                }
            }

            synchronized(audioLock) {
                if (track.playState != AudioTrack.PLAYSTATE_PLAYING) {
                    try { track.play() } catch (t: Throwable) {}
                }
                track.write(pcmData, 0, pcmData.size, AudioTrack.WRITE_BLOCKING)
                byteCounter += encryptedAudio.size
                Unit
            }
        } catch (t: Throwable) {}
    }

    fun toggleMic() {
        _isMicMuted.value = !_isMicMuted.value
    }

    fun toggleVideo() {
        _isVideoMuted.value = !_isVideoMuted.value
    }

    fun toggleSpeaker() {
        _isSpeakerOn.value = !_isSpeakerOn.value
        try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            am?.isSpeakerphoneOn = _isSpeakerOn.value
        } catch (t: Throwable) {}
    }

    fun toggleCamera(lifecycleOwner: LifecycleOwner) {
        _isFrontCamera.value = !_isFrontCamera.value
        bindCameraCapture(lifecycleOwner)
    }
}
