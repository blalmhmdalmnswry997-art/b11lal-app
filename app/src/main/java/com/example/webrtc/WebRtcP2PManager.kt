package com.example.webrtc

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
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
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

class WebRtcP2PManager(
    private val context: Context,
    private val nearbyManager: NearbyConnectionsManager
) {
    companion object {
        private const val SAMPLE_RATE = 16000
        private const val AUDIO_CHANNEL_IN = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_CHANNEL_OUT = AudioFormat.CHANNEL_OUT_MONO
        private const val AUDIO_ENCODING = AudioFormat.ENCODING_PCM_16BIT
        private const val AUDIO_PACKET_BYTES = 2560
        private const val VAD_ENERGY_THRESHOLD = 160
        private const val VIDEO_INTERVAL_MS = 75L
        private const val VIDEO_JPEG_QUALITY = 52
        private const val VIDEO_SHORT_SIDE = 176
    }

    private val streamMask = byteArrayOf(0x4A, 0x2C, 0x6F, 0x19, 0x5D, 0x32, 0x1E, 0x0B)

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

    private val audioJitterBuffer = Channel(capacity = 4, onBufferOverflow = BufferOverflow.DROP_OLDEST) { _: ByteArray -> }

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
                audioJitterBuffer.trySend(packetBytes)
            }
        }

        scope.launch(Dispatchers.IO) {
            for (encryptedAudio in audioJitterBuffer) {
                playJitterBufferedAudio(encryptedAudio)
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

    private fun maskBytes(input: ByteArray): ByteArray {
        val out = ByteArray(input.size)
        val mLen = streamMask.size
        for (i in input.indices) {
            out[i] = (input[i].toInt() xor streamMask[i % mLen].toInt()).toByte()
        }
        return out
    }

    private fun processAudioVadAndBoost(pcmBytes: ByteArray, length: Int): Boolean {
        if (length in 0..3) return false
        var sumAbs = 0L
        var sampleCount = 0
        var i = 0
        while (i + 1 in 0 until length) {
            val lo = pcmBytes[i].toInt() and 0xFF
            val hi = pcmBytes[i + 1].toInt()
            var sample = (hi shl 8) or lo

            val magnitude = abs(sample)
            sumAbs += magnitude
            sampleCount += 1

            if (magnitude in 0..130) {
                sample = 0
            } else {
                sample = ((sample * 14) / 10).coerceIn(-32700, 32700)
            }

            pcmBytes[i] = (sample and 0xFF).toByte()
            pcmBytes[i + 1] = ((sample shr 8) and 0xFF).toByte()
            i += 2
        }
        val avgEnergy = if (sampleCount in 1..Int.MAX_VALUE) (sumAbs / sampleCount).toInt() else 0
        return avgEnergy in VAD_ENERGY_THRESHOLD..Int.MAX_VALUE
    }

    fun toggleWalkieTalkieMode() {
        val nextState = !_isWalkieTalkieEnabled.value
        _isWalkieTalkieEnabled.value = nextState
        if (!nextState && _isPttTransmitting.value) {
            stopPttTransmission()
        }
    }

    fun startPttTransmission() {
        if (_callState.value != CallState.IDLE || _isPttTransmitting.value || _isPttReceiving.value) return
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
        sendSignalPacket("*", offer)
    }

    fun acceptIncomingCall() {
        if (_isPttTransmitting.value) stopPttTransmission()
        _callState.value = CallState.CONNECTED
        nearbyManager.clearMediaStreams()

        val answer = WebRtcSignalMessage(
            type = "ANSWER",
            sdp = "v=0;o=CipherMesh;s=DirectAnswer",
            callType = _callType.value
        )
        sendSignalPacket("*", answer)
        startCallMedia()
    }

    fun rejectOrEndCall() {
        resetCallSession()
        // Broadcast HANGUP to all connected peers twice so the other phone stops ringing immediately
        scope.launch(Dispatchers.IO) {
            val hangup = WebRtcSignalMessage(type = "HANGUP")
            sendSignalPacket("*", hangup)
            delay(150)
            sendSignalPacket("*", hangup)
        }
    }

    private fun resetCallSession() {
        _isPttTransmitting.value = false
        _callState.value = CallState.ENDED
        stopCallMedia()
        unbindCameraHardware()
        nearbyManager.clearMediaStreams()
        while (audioJitterBuffer.tryReceive().isSuccess) {}
        _activePeerId.value = null
        _remoteVideoBitmap.value = null
        _localVideoBitmap.value = null
        isVideoFrameInFlight.set(false)
        isVideoDecodeInFlight.set(false)

        scope.launch {
            delay(150)
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
                    nearbyManager.clearMediaStreams()
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
            ttl = 12,
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
                val jpegData = encodeFastRotatedNv21SinglePass(imageProxy)
                if (jpegData != null && _callState.value == CallState.CONNECTED) {
                    nearbyManager.sendDirectVideoFrame(maskBytes(jpegData))
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
            val rawJpeg = maskBytes(encryptedBytes)
            val bmp = BitmapFactory.decodeByteArray(rawJpeg, 0, rawJpeg.size)
                ?: BitmapFactory.decodeByteArray(encryptedBytes, 0, encryptedBytes.size)

            if (bmp != null) {
                _remoteVideoBitmap.value = bmp
                frameCounter += 1
                byteCounter += encryptedBytes.size
            }
        } catch (t: Throwable) {}
    }

    private fun encodeFastRotatedNv21SinglePass(image: ImageProxy): ByteArray? {
        try {
            val srcW = image.width
            val srcH = image.height
            if (srcW in 0..15 || srcH in 0..15) return null

            val minSide = minOf(srcW, srcH)
            val step = maxOf(1, minSide / VIDEO_SHORT_SIDE)
            val sampW = ((srcW / step) / 2) * 2
            val sampH = ((srcH / step) / 2) * 2
            if (sampW in 0..15 || sampH in 0..15) return null

            val deg = ((image.imageInfo.rotationDegrees % 360) + 360) % 360
            val mirror = _isFrontCamera.value

            val outW = if (deg == 90 || deg == 270) sampH else sampW
            val outH = if (deg == 90 || deg == 270) sampW else sampH

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

            val yLim = yBuf.limit()
            val uLim = uBuf.limit()
            val vLim = vBuf.limit()

            val ySize = outW * outH
            val nv21 = ByteArray(ySize + (ySize / 2))

            for (sy in 0 until sampH) {
                val srcY = sy * step
                val yRowOff = srcY * yRowStride
                for (sx in 0 until sampW) {
                    val pos = yRowOff + (sx * step) * yPixStride
                    val yByte = if (pos in 0 until yLim) yBuf.get(pos) else 0

                    var dx = when (deg) {
                        90 -> sampH - 1 - sy
                        270 -> sy
                        180 -> sampW - 1 - sx
                        else -> sx
                    }
                    val dy = when (deg) {
                        90 -> sx
                        270 -> sampW - 1 - sx
                        180 -> sampH - 1 - sy
                        else -> sy
                    }
                    if (mirror) dx = outW - 1 - dx
                    nv21[dy * outW + dx] = yByte
                }
            }

            val uvSampH = sampH / 2
            val uvSampW = sampW / 2
            val uvOutW = outW / 2

            for (uy in 0 until uvSampH) {
                val srcUvY = uy * step
                val uRowOff = srcUvY * uRowStride
                val vRowOff = srcUvY * vRowStride
                for (ux in 0 until uvSampW) {
                    val srcUvX = ux * step
                    val uPos = uRowOff + srcUvX * uPixStride
                    val vPos = vRowOff + srcUvX * vPixStride

                    val vByte = if (vPos in 0 until vLim) vBuf.get(vPos) else 128.toByte()
                    val uByte = if (uPos in 0 until uLim) uBuf.get(uPos) else 128.toByte()

                    var dux = when (deg) {
                        90 -> uvSampH - 1 - uy
                        270 -> uy
                        180 -> uvSampW - 1 - ux
                        else -> ux
                    }
                    val duy = when (deg) {
                        90 -> ux
                        270 -> uvSampW - 1 - ux
                        180 -> uvSampH - 1 - uy
                        else -> uy
                    }
                    if (mirror) dux = uvOutW - 1 - dux

                    val uvIndex = ySize + (duy * uvOutW + dux) * 2
                    if (uvIndex + 1 in 0 until nv21.size) {
                        nv21[uvIndex] = vByte
                        nv21[uvIndex + 1] = uByte
                    }
                }
            }

            val yuvImg = YuvImage(nv21, ImageFormat.NV21, outW, outH, null)
            val jpegStream = ByteArrayOutputStream(4096)
            yuvImg.compressToJpeg(Rect(0, 0, outW, outH), VIDEO_JPEG_QUALITY, jpegStream)
            val jpegBytes = jpegStream.toByteArray()

            val previewBmp = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)
            if (previewBmp != null) {
                _localVideoBitmap.value = previewBmp
            }
            return jpegBytes
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
            val recBufSize = maxOf(minBuf * 2, AUDIO_PACKET_BYTES * 3)

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
                if (NoiseSuppressor.isAvailable()) noiseSuppressor = NoiseSuppressor.create(sId)?.apply { enabled = true }
                if (AcousticEchoCanceler.isAvailable()) echoCanceler = AcousticEchoCanceler.create(sId)?.apply { enabled = true }
                if (AutomaticGainControl.isAvailable()) gainControl = AutomaticGainControl.create(sId)?.apply { enabled = true }
            } catch (t: Throwable) {}

            rec.startRecording()
            audioRecord = rec

            audioRecordJob = scope.launch(Dispatchers.IO) {
                val packetBuf = ByteArray(AUDIO_PACKET_BYTES)
                var hangover = 4

                while (isActive && (_callState.value == CallState.CONNECTED || _isPttTransmitting.value)) {
                    val shouldSend = (_callState.value == CallState.CONNECTED && !_isMicMuted.value) || _isPttTransmitting.value
                    if (shouldSend) {
                        var totalRead = 0
                        while (totalRead in 0 until AUDIO_PACKET_BYTES && isActive) {
                            val r = try { rec.read(packetBuf, totalRead, AUDIO_PACKET_BYTES - totalRead) } catch (t: Throwable) { -1 }
                            if (r in 1..Int.MAX_VALUE) totalRead += r else break
                        }

                        if (totalRead in 1..Int.MAX_VALUE) {
                            val chunk = packetBuf.copyOf(totalRead)
                            val hasVoice = processAudioVadAndBoost(chunk, chunk.size)
                            if (hasVoice || _isPttTransmitting.value) {
                                hangover = 4
                            } else if (hangover in 1..Int.MAX_VALUE) {
                                hangover -= 1
                            }

                            if (hasVoice || hangover in 1..Int.MAX_VALUE || _isPttTransmitting.value) {
                                nearbyManager.sendDirectAudioFrame(maskBytes(chunk))
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
                val trackBufSize = maxOf(minBuf * 2, AUDIO_PACKET_BYTES * 3)

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

    private fun playJitterBufferedAudio(encryptedAudio: ByteArray) {
        val inCall = (_callState.value == CallState.CONNECTED)
        if (!inCall && !_isWalkieTalkieEnabled.value) return

        if (audioTrack == null) {
            ensureAudibleSpeakerVolume()
            initAudioPlayer()
        }
        val track = audioTrack ?: return

        try {
            val pcmData = maskBytes(encryptedAudio)

            if (!inCall) {
                _isPttReceiving.value = true
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
