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
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.util.Log
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
import java.util.concurrent.Executors

class WebRtcP2PManager(
    private val context: Context,
    private val nearbyManager: NearbyConnectionsManager
) {
    companion object {
        private const val TAG = "WebRtcP2P"
        private const val SAMPLE_RATE = 16000
        private const val AUDIO_CHANNEL_IN = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_CHANNEL_OUT = AudioFormat.CHANNEL_OUT_MONO
        private const val AUDIO_ENCODING = AudioFormat.ENCODING_PCM_16BIT
        private const val FRAME_INTERVAL_MS = 120L
    }

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

    private val _rttMs = MutableStateFlow(18)
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

    private var audioRecordJob: Job? = null
    private var audioTrack: AudioTrack? = null
    private var audioRecord: AudioRecord? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private val audioWriteLock = Any()

    private var frameCounter = 0
    private var byteCounter = 0
    private var lastStatsResetTime = System.currentTimeMillis()
    private var lastFrameSentTime = 0L

    init {
        scope.launch(Dispatchers.Default) {
            nearbyManager.incomingVideoFrames.collect { encryptedFrame ->
                handleIncomingEncryptedVideoFrame(encryptedFrame)
            }
        }

        scope.launch(Dispatchers.IO) {
            nearbyManager.incomingAudioPackets.collect { encryptedAudio ->
                handleIncomingEncryptedAudioPacket(encryptedAudio)
            }
        }

        scope.launch {
            while (isActive) {
                delay(1000)
                val now = System.currentTimeMillis()
                val elapsedMs = now - lastStatsResetTime
                if (elapsedMs in 1000L..Long.MAX_VALUE) {
                    val deltaSec = elapsedMs / 1000.0
                    _fps.value = (frameCounter / deltaSec).toInt()
                    _bitrateKbps.value = ((byteCounter * 8) / (deltaSec * 1024)).toInt()
                    frameCounter = 0
                    byteCounter = 0
                    lastStatsResetTime = now
                }
            }
        }
    }

    fun initiateCall(peerNodeId: String, peerName: String, type: CallType) {
        _activePeerId.value = peerNodeId
        _activePeerName.value = peerName
        _callType.value = type
        _isVideoMuted.value = (type == CallType.VOICE)
        _callState.value = CallState.OUTGOING_RINGING

        val signal = WebRtcSignalMessage(
            type = "OFFER",
            sdp = "v=0\r\no=OfflinePeer 0 0 IN IP4 127.0.0.1\r\ns=P2P-Encrypted-Call\r\nt=0 0",
            callType = type
        )
        sendSignal(peerNodeId, signal)
    }

    fun acceptIncomingCall() {
        val peerId = _activePeerId.value ?: return
        _callState.value = CallState.CONNECTED

        val signal = WebRtcSignalMessage(
            type = "ANSWER",
            sdp = "v=0\r\no=OfflinePeer 0 0 IN IP4 127.0.0.1\r\ns=P2P-Encrypted-Call-Answer\r\nt=0 0",
            callType = _callType.value
        )
        sendSignal(peerId, signal)
        startMediaEngines()
    }

    fun rejectOrEndCall() {
        val peerId = _activePeerId.value
        if (peerId != null) {
            val signal = WebRtcSignalMessage(type = "HANGUP")
            sendSignal(peerId, signal)
        }
        cleanupCallState()
    }

    private fun cleanupCallState() {
        stopMediaEngines()
        _callState.value = CallState.ENDED
        _activePeerId.value = null
        _remoteVideoBitmap.value = null
        _localVideoBitmap.value = null
        scope.launch {
            delay(300)
            if (_callState.value == CallState.ENDED) {
                _callState.value = CallState.IDLE
            }
        }
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
                    startMediaEngines()
                }
            }
            "HANGUP" -> {
                cleanupCallState()
            }
        }
    }

    private fun sendSignal(targetPeerId: String, signal: WebRtcSignalMessage) {
        val packet = MeshPacket(
            sourceNodeId = nearbyManager.localNodeId,
            sourceNodeName = nearbyManager.localNodeName,
            destinationNodeId = targetPeerId,
            ttl = 3,
            packetType = PacketType.WEBRTC_SIGNAL,
            payloadCiphertext = signal.toJson()
        )
        nearbyManager.sendMeshPacket(packet)
    }

    private fun startMediaEngines() {
        startAudioPlayback()
        startAudioRecording()
    }

    private fun stopMediaEngines() {
        stopAudioRecording()
        stopAudioPlayback()
        try {
            ContextCompat.getMainExecutor(context).execute {
                try { cameraProvider?.unbindAll() } catch (t: Throwable) {}
            }
        } catch (t: Throwable) {}
    }

    fun bindCameraCapture(lifecycleOwner: LifecycleOwner) {
        val hasCameraPerm = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasCameraPerm) return

        try {
            val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
            cameraProviderFuture.addListener({
                try {
                    cameraProvider = cameraProviderFuture.get()
                    val cameraSelector = if (_isFrontCamera.value) {
                        CameraSelector.DEFAULT_FRONT_CAMERA
                    } else {
                        CameraSelector.DEFAULT_BACK_CAMERA
                    }

                    val imageAnalysis = ImageAnalysis.Builder()
                        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()

                    imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
                        processCameraFrame(imageProxy)
                    }

                    cameraProvider?.unbindAll()
                    cameraProvider?.bindToLifecycle(lifecycleOwner, cameraSelector, imageAnalysis)
                } catch (t: Throwable) {
                    Log.e(TAG, "Camera bind err: ${t.message}")
                }
            }, ContextCompat.getMainExecutor(context))
        } catch (t: Throwable) {
            Log.e(TAG, "Camera init err: ${t.message}")
        }
    }

    private fun processCameraFrame(imageProxy: ImageProxy) {
        try {
            val now = System.currentTimeMillis()
            val isTooSoon = (now - lastFrameSentTime) in 0L until FRAME_INTERVAL_MS
            if (_isVideoMuted.value || isTooSoon) {
                return
            }
            lastFrameSentTime = now

            val jpegBytes = imageProxyToJpeg(imageProxy, quality = 42)
            if (jpegBytes != null && _callState.value == CallState.CONNECTED) {
                val peerId = _activePeerId.value
                val sessionKey = if (peerId != null) nearbyManager.getSessionKey(peerId) else null
                val frameToSend = if (sessionKey != null) {
                    try {
                        CryptoManager.encryptAesGcm(jpegBytes, sessionKey)
                    } catch (t: Throwable) {
                        jpegBytes
                    }
                } else {
                    jpegBytes
                }
                nearbyManager.sendDirectVideoFrame(frameToSend)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Frame process err: ${t.message}")
        } finally {
            try { imageProxy.close() } catch (t: Throwable) {}
        }
    }

    private fun handleIncomingEncryptedVideoFrame(encryptedFrame: ByteArray) {
        if (_callState.value != CallState.CONNECTED) return
        try {
            val peerId = _activePeerId.value
            val sessionKey = if (peerId != null) nearbyManager.getSessionKey(peerId) else null
            val decryptedBytes = if (sessionKey != null && encryptedFrame.size in 29..Int.MAX_VALUE) {
                try {
                    CryptoManager.decryptAesGcm(encryptedFrame, sessionKey)
                } catch (t: Throwable) {
                    encryptedFrame
                }
            } else {
                encryptedFrame
            }

            val bitmap = BitmapFactory.decodeByteArray(decryptedBytes, 0, decryptedBytes.size)
            if (bitmap != null) {
                _remoteVideoBitmap.value = bitmap
                frameCounter += 1
                byteCounter += encryptedFrame.size
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Decode video err: ${t.message}")
        }
    }

    private fun imageProxyToJpeg(image: ImageProxy, quality: Int): ByteArray? {
        try {
            val width = image.width
            val height = image.height
            if (width !in 16..4096 || height !in 16..4096) return null

            val yPlane = image.planes[0]
            val uPlane = image.planes[1]
            val vPlane = image.planes[2]

            val yBuffer = yPlane.buffer
            val uBuffer = uPlane.buffer
            val vBuffer = vPlane.buffer

            val yRowStride = yPlane.rowStride
            val yPixelStride = yPlane.pixelStride
            val uvRowStride = uPlane.rowStride
            val uvPixelStride = uPlane.pixelStride
            val vRowStride = vPlane.rowStride
            val vPixelStride = vPlane.pixelStride

            val nv21 = ByteArray(width * height + (width * height) / 2)
            var pos = 0

            val yLimit = yBuffer.limit()
            for (row in 0 until height) {
                val rowStart = row * yRowStride
                for (col in 0 until width) {
                    val idx = rowStart + col * yPixelStride
                    nv21[pos++] = if (idx in 0 until yLimit) yBuffer.get(idx) else 0
                }
            }

            val uvHeight = height / 2
            val uvWidth = width / 2
            val uLimit = uBuffer.limit()
            val vLimit = vBuffer.limit()

            for (row in 0 until uvHeight) {
                val uRowStart = row * uvRowStride
                val vRowStart = row * vRowStride
                for (col in 0 until uvWidth) {
                    val uIdx = uRowStart + col * uvPixelStride
                    val vIdx = vRowStart + col * vPixelStride
                    nv21[pos++] = if (vIdx in 0 until vLimit) vBuffer.get(vIdx) else 128.toByte()
                    nv21[pos++] = if (uIdx in 0 until uLimit) uBuffer.get(uIdx) else 128.toByte()
                }
            }

            val yuvImage = YuvImage(nv21, ImageFormat.NV21, width, height, null)
            val rawOut = ByteArrayOutputStream()
            yuvImage.compressToJpeg(Rect(0, 0, width, height), 60, rawOut)
            val rawBytes = rawOut.toByteArray()

            val rawBmp = BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size) ?: return rawBytes
            val matrix = Matrix()
            val rot = image.imageInfo.rotationDegrees.toFloat()
            if (rot != 0f) {
                matrix.postRotate(rot)
            }
            if (_isFrontCamera.value) {
                matrix.postScale(-1f, 1f)
            }
            val scale = minOf(240f / rawBmp.width.toFloat(), 320f / rawBmp.height.toFloat(), 1f)
            matrix.postScale(scale, scale)

            val finalBmp = Bitmap.createBitmap(rawBmp, 0, 0, rawBmp.width, rawBmp.height, matrix, true)
            _localVideoBitmap.value = finalBmp

            val finalOut = ByteArrayOutputStream()
            finalBmp.compress(Bitmap.CompressFormat.JPEG, quality, finalOut)
            return finalOut.toByteArray()
        } catch (t: Throwable) {
            Log.e(TAG, "YUV convert err: ${t.message}")
            return null
        }
    }

    @SuppressLint("MissingPermission")
    private fun startAudioRecording() {
        stopAudioRecording()
        val hasAudioPerm = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasAudioPerm) return

        try {
            val minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, AUDIO_CHANNEL_IN, AUDIO_ENCODING)
            val bufferSize = maxOf(minBufferSize, 2048)

            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                SAMPLE_RATE,
                AUDIO_CHANNEL_IN,
                AUDIO_ENCODING,
                bufferSize
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                return
            }

            audioRecord?.startRecording()

            audioRecordJob = scope.launch(Dispatchers.IO) {
                val buffer = ByteArray(1280)
                while (isActive && _callState.value == CallState.CONNECTED) {
                    if (!_isMicMuted.value) {
                        val read = try { audioRecord?.read(buffer, 0, buffer.size) ?: 0 } catch (t: Throwable) { 0 }
                        if (read in 1..Int.MAX_VALUE) {
                            val peerId = _activePeerId.value
                            val sessionKey = if (peerId != null) nearbyManager.getSessionKey(peerId) else null
                            val rawChunk = buffer.copyOf(read)
                            val encryptedAudio = if (sessionKey != null) {
                                try { CryptoManager.encryptAesGcm(rawChunk, sessionKey) } catch (t: Throwable) { rawChunk }
                            } else {
                                rawChunk
                            }
                            nearbyManager.sendDirectAudioFrame(encryptedAudio)
                        }
                    } else {
                        delay(40)
                    }
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "AudioRecord start err: ${t.message}")
        }
    }

    private fun stopAudioRecording() {
        audioRecordJob?.cancel()
        audioRecordJob = null
        try {
            audioRecord?.stop()
        } catch (t: Throwable) {}
        try {
            audioRecord?.release()
        } catch (t: Throwable) {}
        audioRecord = null
    }

    private fun startAudioPlayback() {
        stopAudioPlayback()
        try {
            val minBufferSize = AudioTrack.getMinBufferSize(SAMPLE_RATE, AUDIO_CHANNEL_OUT, AUDIO_ENCODING)
            val bufferSize = maxOf(minBufferSize, 4096)

            try {
                val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                audioManager?.mode = AudioManager.MODE_IN_COMMUNICATION
                audioManager?.isSpeakerphoneOn = _isSpeakerOn.value
            } catch (t: Throwable) {}

            audioTrack = AudioTrack.Builder()
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AUDIO_ENCODING)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AUDIO_CHANNEL_OUT)
                        .build()
                )
                .setBufferSizeInBytes(bufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            audioTrack?.play()
        } catch (t: Throwable) {
            Log.e(TAG, "AudioTrack start err: ${t.message}")
        }
    }

    private fun stopAudioPlayback() {
        synchronized(audioWriteLock) {
            try {
                audioTrack?.stop()
            } catch (t: Throwable) {}
            try {
                audioTrack?.release()
            } catch (t: Throwable) {}
            audioTrack = null
        }
    }

    private fun handleIncomingEncryptedAudioPacket(encryptedAudio: ByteArray) {
        if (_callState.value != CallState.CONNECTED) return
        val track = audioTrack ?: return
        try {
            val peerId = _activePeerId.value
            val sessionKey = if (peerId != null) nearbyManager.getSessionKey(peerId) else null
            val pcmAudio = if (sessionKey != null && encryptedAudio.size in 29..Int.MAX_VALUE) {
                try {
                    CryptoManager.decryptAesGcm(encryptedAudio, sessionKey)
                } catch (t: Throwable) {
                    encryptedAudio
                }
            } else {
                encryptedAudio
            }

            synchronized(audioWriteLock) {
                if (_callState.value == CallState.CONNECTED) {
                    track.write(pcmAudio, 0, pcmAudio.size)
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Audio play err: ${t.message}")
        }
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
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            audioManager?.isSpeakerphoneOn = _isSpeakerOn.value
        } catch (t: Throwable) {}
    }

    fun toggleCamera(lifecycleOwner: LifecycleOwner) {
        _isFrontCamera.value = !_isFrontCamera.value
        bindCameraCapture(lifecycleOwner)
    }
}
