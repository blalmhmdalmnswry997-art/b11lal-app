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
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
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
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class WebRtcP2PManager(
    private val context: Context,
    private val nearbyManager: NearbyConnectionsManager
) {
    companion object {
        private const val SAMPLE_RATE = 16000
        private const val AUDIO_CHANNEL_IN = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_CHANNEL_OUT = AudioFormat.CHANNEL_OUT_MONO
        private const val AUDIO_ENCODING = AudioFormat.ENCODING_PCM_16BIT
        private const val AUDIO_PACKET_BYTES = 1280
        private const val VIDEO_INTERVAL_MS = 65L
        private const val VIDEO_JPEG_QUALITY = 36
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

    private val _rttMs = MutableStateFlow(8)
    val rttMs = _rttMs.asStateFlow()

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

    private var activeCameraProvider: ProcessCameraProvider? = null
    private var audioRecordJob: Job? = null
    private var pttResetJob: Job? = null
    private var audioTrack: AudioTrack? = null
    private var audioRecord: AudioRecord? = null

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
                    _fps.value = (frameCounter / sec).toInt()
                    _bitrateKbps.value = ((byteCounter * 8) / (sec * 1024)).toInt()
                    frameCounter = 0
                    byteCounter = 0
                    lastStatsTime = now
                }
            }
        }
    }

    private fun transformStreamBytes(input: ByteArray): ByteArray {
        val out = ByteArray(input.size)
        val maskLen = streamCipherMask.size
        for (i in input.indices) {
            out[i] = (input[i].toInt() xor streamCipherMask[i % maskLen].toInt()).toByte()
        }
        return out
    }

    fun toggleWalkieTalkieMode() {
        val nextState = !_isWalkieTalkieEnabled.value
        _isWalkieTalkieEnabled.value = nextState
        if (!nextState && _isPttTransmitting.value) {
            stopPttTransmission()
        }
    }

    fun startPttTransmission() {
        if (_callState.value != CallState.IDLE || _isPttTransmitting.value) return
        _isPttTransmitting.value = true
        ensureAudibleSpeakerVolume()
        initAudioPlayer()
        initAudioRecorder()
    }

    fun stopPttTransmission() {
        if (!_isPttTransmitting.value) return
        _isPttTransmitting.value = false
        if (_callState.value == CallState.IDLE) {
            releaseAudioRecorder()
            nearbyManager.clearMediaStreams()
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
        nearbyManager.clearMediaStreams()
        _callState.value = CallState.CONNECTED

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
                delay(100)
                sendSignalPacket(peerId, hangup)
            }
        }
    }

    private fun resetCallSession() {
        _isPttTransmitting.value = false
        _callState.value = CallState.ENDED
        stopCallMedia()
        unbindCameraHardware()
        nearbyManager.clearMediaStreams()
        _activePeerId.value = null
        _remoteVideoBitmap.value = null
        _localVideoBitmap.value = null
        isVideoFrameInFlight.set(false)
        isVideoDecodeInFlight.set(false)

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
            ttl = 6,
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
            val maxVol = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val curVol = am.getStreamVolume(AudioManager.STREAM_MUSIC)
            if (curVol in 0..(maxVol / 4)) {
                am.setStreamVolume(AudioManager.STREAM_MUSIC, (maxVol * 2) / 3, 0)
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
                        .setTargetResolution(Size(240, 320))
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
            if (_isVideoMuted.value || (now - lastFrameTimestamp) in 0L until VIDEO_INTERVAL_MS) {
                return
            }
            if (!isVideoFrameInFlight.compareAndSet(false, true)) {
                return
            }
            lastFrameTimestamp = now

            try {
                val jpegData = convertProxyToJpeg(imageProxy)
                if (jpegData != null && _callState.value == CallState.CONNECTED) {
                    val payload = transformStreamBytes(jpegData)
                    nearbyManager.sendDirectVideoFrame(payload)
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
            val rawJpeg = transformStreamBytes(encryptedBytes)
            val bmp = BitmapFactory.decodeByteArray(rawJpeg, 0, rawJpeg.size)
                ?: BitmapFactory.decodeByteArray(encryptedBytes, 0, encryptedBytes.size)

            if (bmp != null) {
                _remoteVideoBitmap.value = bmp
                frameCounter += 1
                byteCounter += encryptedBytes.size
            }
        } catch (t: Throwable) {}
    }

    private fun convertProxyToJpeg(image: ImageProxy): ByteArray? {
        try {
            val srcW = image.width
            val srcH = image.height
            if (srcW in 0..15 || srcH in 0..15) return null

            // Fast subsample to ~136px width so each JPEG is ~1.1 KB (fits in 1 single UDP packet!)
            val step = maxOf(1, srcW / 136)
            val outW = ((srcW / step) / 2) * 2
            val outH = ((srcH / step) / 2) * 2
            if (outW in 0..15 || outH in 0..15) return null

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

            val nv21 = ByteArray(outW * outH + (outW * outH) / 2)
            var idx = 0
            val yLim = yBuf.limit()

            for (r in 0 until outH) {
                val srcRow = (r * step) * yRowStride
                for (c in 0 until outW) {
                    val pos = srcRow + (c * step) * yPixStride
                    nv21[idx++] = if (pos in 0 until yLim) yBuf.get(pos) else 0
                }
            }

            val uvH = outH / 2
            val uvW = outW / 2
            val uLim = uBuf.limit()
            val vLim = vBuf.limit()

            for (r in 0 until uvH) {
                val uRow = (r * step) * uRowStride
                val vRow = (r * step) * vRowStride
                for (c in 0 until uvW) {
                    val uPos = uRow + (c * step) * uPixStride
                    val vPos = vRow + (c * step) * vPixStride
                    nv21[idx++] = if (vPos in 0 until vLim) vBuf.get(vPos) else 128.toByte()
                    nv21[idx++] = if (uPos in 0 until uLim) uBuf.get(uPos) else 128.toByte()
                }
            }

            val yuvImg = YuvImage(nv21, ImageFormat.NV21, outW, outH, null)
            val tempStream = ByteArrayOutputStream()
            yuvImg.compressToJpeg(Rect(0, 0, outW, outH), 55, tempStream)
            val tempBytes = tempStream.toByteArray()

            val decoded = BitmapFactory.decodeByteArray(tempBytes, 0, tempBytes.size) ?: return tempBytes
            val m = Matrix()
            val deg = image.imageInfo.rotationDegrees.toFloat()
            if (deg != 0f) m.postRotate(deg)
            if (_isFrontCamera.value) m.postScale(-1f, 1f)

            val finalBitmap = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, m, false)
            _localVideoBitmap.value = finalBitmap

            val finalStream = ByteArrayOutputStream()
            finalBitmap.compress(Bitmap.CompressFormat.JPEG, VIDEO_JPEG_QUALITY, finalStream)
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
            val recBufSize = maxOf(minBuf * 2, AUDIO_PACKET_BYTES * 4)

            // Use AudioSource.MIC so Android never mutes the mic when CameraX is active
            val rec = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                AUDIO_CHANNEL_IN,
                AUDIO_ENCODING,
                recBufSize
            )

            if (rec.state != AudioRecord.STATE_INITIALIZED) return

            rec.startRecording()
            audioRecord = rec

            audioRecordJob = scope.launch(Dispatchers.IO) {
                val packetBuf = ByteArray(AUDIO_PACKET_BYTES)
                while (isActive && (_callState.value == CallState.CONNECTED || _isPttTransmitting.value)) {
                    val shouldSend = (_callState.value == CallState.CONNECTED && !_isMicMuted.value) || _isPttTransmitting.value
                    if (shouldSend) {
                        val readBytes = try {
                            rec.read(packetBuf, 0, AUDIO_PACKET_BYTES)
                        } catch (t: Throwable) {
                            -1
                        }

                        if (readBytes in 1..Int.MAX_VALUE) {
                            val chunk = packetBuf.copyOf(readBytes)
                            val masked = transformStreamBytes(chunk)
                            nearbyManager.sendDirectAudioFrame(masked)
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
        try { audioRecord?.stop() } catch (t: Throwable) {}
        try { audioRecord?.release() } catch (t: Throwable) {}
        audioRecord = null
    }

    private fun initAudioPlayer() {
        synchronized(audioLock) {
            if (audioTrack != null) return
            try {
                val minBuf = AudioTrack.getMinBufferSize(SAMPLE_RATE, AUDIO_CHANNEL_OUT, AUDIO_ENCODING)
                val trackBufSize = maxOf(minBuf * 2, AUDIO_PACKET_BYTES * 6)

                // Use USAGE_MEDIA + MODE_NORMAL so sound ALWAYS plays loud out of the main speaker on Android 12-15
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
            val pcmData = transformStreamBytes(encryptedAudio)

            if (!inCall) {
                _isPttReceiving.value = true
                pttResetJob?.cancel()
                pttResetJob = scope.launch {
                    delay(600)
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
    }

    fun toggleCamera(lifecycleOwner: LifecycleOwner) {
        _isFrontCamera.value = !_isFrontCamera.value
        bindCameraCapture(lifecycleOwner)
    }
}
