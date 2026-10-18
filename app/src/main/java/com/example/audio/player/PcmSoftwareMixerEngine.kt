package com.example.audio.player

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

class PcmSoftwareMixerEngine(
    private val scope: CoroutineScope
) {
    companion object {
        const val SAMPLE_RATE = 44100
        const val CHANNELS = 2
        const val BYTES_PER_FRAME = 4 // 16-bit stereo = 2 bytes * 2 channels
        const val BUFFER_FRAMES = 2048 // ~46ms buffer for ultra-low latency & zero phase drift
    }

    private var audioTrack: AudioTrack? = null
    private var playbackJob: Job? = null

    private var vocalRaf: RandomAccessFile? = null
    private var karaokeRaf: RandomAccessFile? = null

    private var totalFrames: Long = 0L
    private var currentFrame: Long = 0L

    // Playback state flows
    private val _isPlaying = MutableStateFlow(false)
    val isPlaying = _isPlaying.asStateFlow()

    private val _playbackProgress = MutableStateFlow(0f)
    val playbackProgress = _playbackProgress.asStateFlow()

    private val _currentPositionMs = MutableStateFlow(0L)
    val currentPositionMs = _currentPositionMs.asStateFlow()

    private val _vocalVuLevel = MutableStateFlow(0f)
    val vocalVuLevel = _vocalVuLevel.asStateFlow()

    private val _karaokeVuLevel = MutableStateFlow(0f)
    val karaokeVuLevel = _karaokeVuLevel.asStateFlow()

    // Fader & Mute/Solo parameters (atomic volatile for high-priority mixer loop)
    @Volatile var vocalGain: Float = 1.0f
    @Volatile var karaokeGain: Float = 1.0f
    @Volatile var isVocalMuted: Boolean = false
    @Volatile var isKaraokeMuted: Boolean = false
    @Volatile var isVocalSolo: Boolean = false
    @Volatile var isKaraokeSolo: Boolean = false
    @Volatile var isLooping: Boolean = true

    fun setStems(
        vocalFile: File?,
        karaokeFile: File?,
        frames: Long
    ) {
        stop()
        closeFiles()

        totalFrames = frames
        currentFrame = 0L
        _playbackProgress.value = 0f
        _currentPositionMs.value = 0L

        if (vocalFile != null && vocalFile.exists()) {
            vocalRaf = RandomAccessFile(vocalFile, "r")
        }
        if (karaokeFile != null && karaokeFile.exists()) {
            karaokeRaf = RandomAccessFile(karaokeFile, "r")
        }
    }

    private fun ensureAudioTrack() {
        if (audioTrack == null) {
            val minBufferSize = AudioTrack.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_STEREO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            val bufferSize = max(minBufferSize, BUFFER_FRAMES * BYTES_PER_FRAME * 2)

            audioTrack = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .build()
                )
                .setBufferSizeInBytes(bufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        }
    }

    fun play() {
        if (_isPlaying.value || totalFrames <= 0) return
        ensureAudioTrack()
        audioTrack?.play()
        _isPlaying.value = true

        playbackJob = scope.launch(Dispatchers.IO) {
            val vocalBuf = ByteArray(BUFFER_FRAMES * BYTES_PER_FRAME)
            val karaokeBuf = ByteArray(BUFFER_FRAMES * BYTES_PER_FRAME)
            val mixedShorts = ShortArray(BUFFER_FRAMES * CHANNELS)
            val vocalByteBuffer = ByteBuffer.wrap(vocalBuf).order(ByteOrder.LITTLE_ENDIAN)
            val karaokeByteBuffer = ByteBuffer.wrap(karaokeBuf).order(ByteOrder.LITTLE_ENDIAN)

            while (isActive && _isPlaying.value) {
                if (currentFrame >= totalFrames) {
                    if (isLooping) {
                        currentFrame = 0L
                    } else {
                        _isPlaying.value = false
                        break
                    }
                }

                val framesToRead = min(BUFFER_FRAMES.toLong(), totalFrames - currentFrame).toInt()
                val bytesToRead = framesToRead * BYTES_PER_FRAME

                val byteOffset = currentFrame * BYTES_PER_FRAME

                // Read Vocal chunk from disk
                var vRead = 0
                vocalRaf?.let {
                    synchronized(it) {
                        it.seek(byteOffset)
                        vRead = it.read(vocalBuf, 0, bytesToRead)
                    }
                }
                if (vRead < bytesToRead) {
                    vocalBuf.fill(0, max(0, vRead), bytesToRead)
                }

                // Read Karaoke chunk from disk
                var kRead = 0
                karaokeRaf?.let {
                    synchronized(it) {
                        it.seek(byteOffset)
                        kRead = it.read(karaokeBuf, 0, bytesToRead)
                    }
                }
                if (kRead < bytesToRead) {
                    karaokeBuf.fill(0, max(0, kRead), bytesToRead)
                }

                // Determine effective gain with solo/mute logic
                val effVocalGain = when {
                    isVocalMuted -> 0f
                    isKaraokeSolo && !isVocalSolo -> 0f
                    else -> vocalGain
                }

                val effKaraokeGain = when {
                    isKaraokeMuted -> 0f
                    isVocalSolo && !isKaraokeSolo -> 0f
                    else -> karaokeGain
                }

                vocalByteBuffer.position(0)
                karaokeByteBuffer.position(0)
                val vocalShorts = vocalByteBuffer.asShortBuffer()
                val karaokeShorts = karaokeByteBuffer.asShortBuffer()

                var vocSumSq = 0.0
                var karSumSq = 0.0

                // Software mixing loop with clipping protection
                val sampleCount = framesToRead * CHANNELS
                for (s in 0 until sampleCount) {
                    val vVal = vocalShorts.get().toFloat()
                    val kVal = karaokeShorts.get().toFloat()

                    vocSumSq += (vVal * vVal)
                    karSumSq += (kVal * kVal)

                    val mixed = (vVal * effVocalGain + kVal * effKaraokeGain)
                    mixedShorts[s] = mixed.toInt().coerceIn(-32768, 32767).toShort()
                }

                // Push to single AudioTrack sink
                audioTrack?.write(mixedShorts, 0, sampleCount)

                // Advance playhead
                currentFrame += framesToRead

                val progress = (currentFrame.toFloat() / totalFrames.toFloat()).coerceIn(0f, 1f)
                val posMs = (currentFrame * 1000L) / SAMPLE_RATE
                _playbackProgress.value = progress
                _currentPositionMs.value = posMs

                // Compute smoothed RMS for VU Meters
                val vocRms = if (sampleCount > 0) (sqrt(vocSumSq / sampleCount) / 32768.0).toFloat().coerceIn(0f, 1f) else 0f
                val karRms = if (sampleCount > 0) (sqrt(karSumSq / sampleCount) / 32768.0).toFloat().coerceIn(0f, 1f) else 0f
                _vocalVuLevel.value = vocRms * effVocalGain
                _karaokeVuLevel.value = karRms * effKaraokeGain
            }

            _isPlaying.value = false
            _vocalVuLevel.value = 0f
            _karaokeVuLevel.value = 0f
        }
    }

    fun pause() {
        _isPlaying.value = false
        playbackJob?.cancel()
        playbackJob = null
        try {
            audioTrack?.pause()
            audioTrack?.flush()
        } catch (_: Exception) {}
        _vocalVuLevel.value = 0f
        _karaokeVuLevel.value = 0f
    }

    fun seekToFraction(fraction: Float) {
        val targetFrame = (fraction.coerceIn(0f, 1f) * totalFrames).toLong()
        seekToFrame(targetFrame)
    }

    fun seekToPositionMs(ms: Long) {
        val targetFrame = (ms * SAMPLE_RATE) / 1000L
        seekToFrame(targetFrame)
    }

    private fun seekToFrame(frame: Long) {
        currentFrame = frame.coerceIn(0L, totalFrames)
        val progress = if (totalFrames > 0) (currentFrame.toFloat() / totalFrames.toFloat()) else 0f
        _playbackProgress.value = progress
        _currentPositionMs.value = (currentFrame * 1000L) / SAMPLE_RATE
        try {
            audioTrack?.pause()
            audioTrack?.flush()
            if (_isPlaying.value) {
                audioTrack?.play()
            }
        } catch (_: Exception) {}
    }

    fun stop() {
        pause()
        currentFrame = 0L
        _playbackProgress.value = 0f
        _currentPositionMs.value = 0L
    }

    private fun closeFiles() {
        try {
            vocalRaf?.close()
            vocalRaf = null
        } catch (_: Exception) {}
        try {
            karaokeRaf?.close()
            karaokeRaf = null
        } catch (_: Exception) {}
    }

    fun release() {
        stop()
        closeFiles()
        try {
            audioTrack?.release()
            audioTrack = null
        } catch (_: Exception) {}
    }
}
