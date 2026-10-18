package com.example.audio.decoder

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import com.example.audio.model.AudioMetadata
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.max

class AudioDecoder(private val context: Context) {

    data class DecodeResult(
        val pcmFile: File,
        val metadata: AudioMetadata,
        val waveformPeaks: FloatArray
    )

    suspend fun decodeToStereo44100(
        uri: Uri,
        title: String,
        onProgress: (Float) -> Unit = {}
    ): DecodeResult = withContext(Dispatchers.IO) {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)
        } catch (e: Exception) {
            // Try with FileDescriptor if direct uri fails
            context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                extractor.setDataSource(pfd.fileDescriptor)
            } ?: throw e
        }

        var audioTrackIndex = -1
        var format: MediaFormat? = null
        for (i in 0 until extractor.trackCount) {
            val trackFormat = extractor.getTrackFormat(i)
            val mime = trackFormat.getString(MediaFormat.KEY_MIME) ?: ""
            if (mime.startsWith("audio/")) {
                audioTrackIndex = i
                format = trackFormat
                break
            }
        }

        if (audioTrackIndex == -1 || format == null) {
            extractor.release()
            throw IllegalArgumentException("No valid audio track found in selected file.")
        }

        extractor.selectTrack(audioTrackIndex)
        val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
        val sourceSampleRate = if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
            format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        } else 44100
        val sourceChannelCount = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
            format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        } else 2
        val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) {
            format.getLong(MediaFormat.KEY_DURATION)
        } else 0L

        val codec = MediaCodec.createDecoderByType(mime)
        codec.configure(format, null, null, 0)
        codec.start()

        val outputFile = File(context.cacheDir, "source_44100_stereo_${System.currentTimeMillis()}.pcm")
        val outStream = BufferedOutputStream(FileOutputStream(outputFile), 65536)

        val bufferInfo = MediaCodec.BufferInfo()
        var sawInputEOS = false
        var sawOutputEOS = false
        val timeoutUs = 5000L

        // Resampling & channel conversion state
        val targetSampleRate = 44100
        val targetChannels = 2
        val resampleRatio = sourceSampleRate.toDouble() / targetSampleRate.toDouble()
        var resamplePhase = 0.0

        // Peak extraction variables for 1200 visual points
        val peakBucketCount = 1200
        val estimatedTotalTargetFrames = if (durationUs > 0) {
            (durationUs * targetSampleRate / 1_000_000L)
        } else 44100L * 180L
        val framesPerPeakBucket = max(1L, estimatedTotalTargetFrames / peakBucketCount)
        val peaks = FloatArray(peakBucketCount)
        var currentBucket = 0
        var bucketFrameCount = 0L
        var currentBucketMax = 0f
        var totalOutputFrames = 0L

        // Audio sample sliding history for linear interpolation
        var prevLeft = 0f
        var prevRight = 0f
        var currLeft = 0f
        var currRight = 0f

        val shortBuffer = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)

        try {
            while (!sawOutputEOS) {
                if (!sawInputEOS) {
                    val inputBufferIndex = codec.dequeueInputBuffer(timeoutUs)
                    if (inputBufferIndex >= 0) {
                        val inputBuffer = codec.getInputBuffer(inputBufferIndex)
                        if (inputBuffer != null) {
                            val sampleSize = extractor.readSampleData(inputBuffer, 0)
                            if (sampleSize < 0) {
                                sawInputEOS = true
                                codec.queueInputBuffer(
                                    inputBufferIndex,
                                    0,
                                    0,
                                    0L,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM
                                )
                            } else {
                                val presentationTimeUs = extractor.sampleTime
                                codec.queueInputBuffer(
                                    inputBufferIndex,
                                    0,
                                    sampleSize,
                                    presentationTimeUs,
                                    0
                                )
                                extractor.advance()
                                if (durationUs > 0) {
                                    val progress = (presentationTimeUs.toFloat() / durationUs.toFloat()).coerceIn(0f, 0.95f)
                                    onProgress(progress)
                                }
                            }
                        }
                    }
                }

                val outputBufferIndex = codec.dequeueOutputBuffer(bufferInfo, timeoutUs)
                if (outputBufferIndex >= 0) {
                    val outputBuffer = codec.getOutputBuffer(outputBufferIndex)
                    if (outputBuffer != null && bufferInfo.size > 0) {
                        outputBuffer.position(bufferInfo.offset)
                        outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                        outputBuffer.order(ByteOrder.LITTLE_ENDIAN)

                        val shortView = outputBuffer.asShortBuffer()
                        val sampleCount = shortView.remaining()
                        val frameCount = sampleCount / sourceChannelCount

                        if (sourceSampleRate == targetSampleRate && sourceChannelCount == targetChannels) {
                            // Bit-perfect lossless fast path for native 44.1kHz stereo audio
                            for (f in 0 until frameCount) {
                                val sL = shortView.get()
                                val sR = shortView.get()
                                shortBuffer.clear()
                                shortBuffer.putShort(sL)
                                shortBuffer.putShort(sR)
                                outStream.write(shortBuffer.array())

                                val interpL = sL.toFloat() / 32768f
                                val interpR = sR.toFloat() / 32768f
                                val absMax = max(abs(interpL), abs(interpR))
                                if (absMax > currentBucketMax) currentBucketMax = absMax
                                bucketFrameCount++
                                if (bucketFrameCount >= framesPerPeakBucket && currentBucket < peakBucketCount) {
                                    peaks[currentBucket] = currentBucketMax
                                    currentBucket++
                                    currentBucketMax = 0f
                                    bucketFrameCount = 0L
                                }
                                totalOutputFrames++
                            }
                        } else {
                            // Resample & convert non-standard sample rates / channel layouts to 44.1kHz stereo
                            for (f in 0 until frameCount) {
                                prevLeft = currLeft
                                prevRight = currRight

                                if (sourceChannelCount == 1) {
                                    val s = shortView.get().toFloat() / 32768f
                                    currLeft = s
                                    currRight = s
                                } else {
                                    currLeft = shortView.get().toFloat() / 32768f
                                    currRight = shortView.get().toFloat() / 32768f
                                    for (ch in 2 until sourceChannelCount) {
                                        if (shortView.hasRemaining()) shortView.get()
                                    }
                                }

                                while (resamplePhase < 1.0) {
                                    val alpha = resamplePhase.toFloat()
                                    val interpL = (prevLeft + alpha * (currLeft - prevLeft)).coerceIn(-1f, 1f)
                                    val interpR = (prevRight + alpha * (currRight - prevRight)).coerceIn(-1f, 1f)

                                    val shortL = (interpL * 32767f).toInt().coerceIn(-32768, 32767).toShort()
                                    val shortR = (interpR * 32767f).toInt().coerceIn(-32768, 32767).toShort()

                                    shortBuffer.clear()
                                    shortBuffer.putShort(shortL)
                                    shortBuffer.putShort(shortR)
                                    outStream.write(shortBuffer.array())

                                    val absMax = max(abs(interpL), abs(interpR))
                                    if (absMax > currentBucketMax) currentBucketMax = absMax
                                    bucketFrameCount++
                                    if (bucketFrameCount >= framesPerPeakBucket && currentBucket < peakBucketCount) {
                                        peaks[currentBucket] = currentBucketMax
                                        currentBucket++
                                        currentBucketMax = 0f
                                        bucketFrameCount = 0L
                                    }

                                    totalOutputFrames++
                                    resamplePhase += resampleRatio
                                }
                                resamplePhase -= 1.0
                            }
                        }
                    }
                    codec.releaseOutputBuffer(outputBufferIndex, false)
                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        sawOutputEOS = true
                    }
                }
            }
        } finally {
            try {
                codec.stop()
                codec.release()
            } catch (_: Exception) {}
            try {
                extractor.release()
            } catch (_: Exception) {}
            outStream.flush()
            outStream.close()
        }

        // Fill remaining peak buckets if file ended early
        while (currentBucket < peakBucketCount) {
            peaks[currentBucket] = currentBucketMax
            currentBucket++
        }

        val calculatedDurationMs = (totalOutputFrames * 1000L) / targetSampleRate
        val metadata = AudioMetadata(
            title = title,
            durationMs = calculatedDurationMs,
            sampleRate = targetSampleRate,
            channels = targetChannels,
            totalFrames = totalOutputFrames,
            fileSizeFormatted = "${outputFile.length() / (1024 * 1024)} MB"
        )
        onProgress(1.0f)
        DecodeResult(outputFile, metadata, peaks)
    }
}
