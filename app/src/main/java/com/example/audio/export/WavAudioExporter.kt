package com.example.audio.export

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.example.audio.model.ExportType
import com.example.audio.model.StemTrackState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

class WavAudioExporter(private val context: Context) {

    data class ExportResult(
        val exportedFiles: List<File>,
        val shareIntent: Intent?
    )

    suspend fun exportStems(
        songTitle: String,
        exportType: ExportType,
        vocalTrack: StemTrackState?,
        karaokeTrack: StemTrackState?
    ): ExportResult = withContext(Dispatchers.IO) {
        val exportDir = File(context.cacheDir, "exports").apply { mkdirs() }
        val sanitizedTitle = songTitle.replace(Regex("[^a-zA-Z0-9._-]"), "_").take(40)
        val files = mutableListOf<File>()

        when (exportType) {
            ExportType.VOCAL_ONLY -> {
                vocalTrack?.pcmFile?.let { pcm ->
                    val wav = File(exportDir, "${sanitizedTitle}_Vocals.wav")
                    convertPcmToWav(pcm, wav, 44100, 2)
                    files.add(wav)
                }
            }
            ExportType.KARAOKE_ONLY -> {
                karaokeTrack?.pcmFile?.let { pcm ->
                    val wav = File(exportDir, "${sanitizedTitle}_Karaoke.wav")
                    convertPcmToWav(pcm, wav, 44100, 2)
                    files.add(wav)
                }
            }
            ExportType.BOTH_STEMS -> {
                vocalTrack?.pcmFile?.let { pcm ->
                    val wavVoc = File(exportDir, "${sanitizedTitle}_Vocals.wav")
                    convertPcmToWav(pcm, wavVoc, 44100, 2)
                    files.add(wavVoc)
                }
                karaokeTrack?.pcmFile?.let { pcm ->
                    val wavKar = File(exportDir, "${sanitizedTitle}_Karaoke.wav")
                    convertPcmToWav(pcm, wavKar, 44100, 2)
                    files.add(wavKar)
                }
            }
            ExportType.MIXED_MASTER -> {
                val vocPcm = vocalTrack?.pcmFile
                val karPcm = karaokeTrack?.pcmFile
                if (vocPcm != null && karPcm != null) {
                    val wavMix = File(exportDir, "${sanitizedTitle}_StudioMix.wav")
                    renderMixedWav(
                        vocPcm,
                        karPcm,
                        wavMix,
                        vocalGain = if (vocalTrack.isMuted) 0f else vocalTrack.gain,
                        karaokeGain = if (karaokeTrack.isMuted) 0f else karaokeTrack.gain
                    )
                    files.add(wavMix)
                }
            }
        }

        val shareIntent = if (files.isNotEmpty()) {
            createShareIntent(files)
        } else null

        ExportResult(files, shareIntent)
    }

    private fun convertPcmToWav(pcmFile: File, wavFile: File, sampleRate: Int, channels: Int) {
        val pcmDataLength = pcmFile.length()
        val totalDataLength = pcmDataLength + 36
        val byteRate = sampleRate * channels * 2

        val out = BufferedOutputStream(FileOutputStream(wavFile), 65536)
        writeWavHeader(out, totalDataLength, pcmDataLength, sampleRate, channels, byteRate)

        val `in` = BufferedInputStream(FileInputStream(pcmFile), 65536)
        val buffer = ByteArray(32768)
        var read: Int
        while (`in`.read(buffer).also { read = it } != -1) {
            out.write(buffer, 0, read)
        }
        `in`.close()
        out.flush()
        out.close()
    }

    private fun renderMixedWav(
        vocPcm: File,
        karPcm: File,
        outFile: File,
        vocalGain: Float,
        karaokeGain: Float
    ) {
        val vocRaf = RandomAccessFile(vocPcm, "r")
        val karRaf = RandomAccessFile(karPcm, "r")
        val totalBytes = maxOf(vocPcm.length(), karPcm.length())
        val totalDataLength = totalBytes + 36
        val sampleRate = 44100
        val channels = 2
        val byteRate = sampleRate * channels * 2

        val out = BufferedOutputStream(FileOutputStream(outFile), 65536)
        writeWavHeader(out, totalDataLength, totalBytes, sampleRate, channels, byteRate)

        val bufSize = 16384
        val vocBuf = ByteArray(bufSize)
        val karBuf = ByteArray(bufSize)
        val outBuf = ByteBuffer.allocate(bufSize).order(ByteOrder.LITTLE_ENDIAN)

        var processed = 0L
        while (processed < totalBytes) {
            val toRead = minOf(bufSize.toLong(), totalBytes - processed).toInt()
            val vocRead = vocRaf.read(vocBuf, 0, toRead).coerceAtLeast(0)
            if (vocRead < toRead) vocBuf.fill(0, vocRead, toRead)

            val karRead = karRaf.read(karBuf, 0, toRead).coerceAtLeast(0)
            if (karRead < toRead) karBuf.fill(0, karRead, toRead)

            val vocShorts = ByteBuffer.wrap(vocBuf, 0, toRead).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
            val karShorts = ByteBuffer.wrap(karBuf, 0, toRead).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
            outBuf.clear()

            val samples = toRead / 2
            for (i in 0 until samples) {
                val v = vocShorts.get().toFloat()
                val k = karShorts.get().toFloat()
                val mixed = (v * vocalGain + k * karaokeGain).toInt().coerceIn(-32768, 32767).toShort()
                outBuf.putShort(mixed)
            }

            out.write(outBuf.array(), 0, toRead)
            processed += toRead
        }

        vocRaf.close()
        karRaf.close()
        out.flush()
        out.close()
    }

    private fun writeWavHeader(
        out: BufferedOutputStream,
        totalDataLen: Long,
        pcmDataLen: Long,
        sampleRate: Int,
        channels: Int,
        byteRate: Int
    ) {
        val header = ByteArray(44)
        header[0] = 'R'.code.toByte()
        header[1] = 'I'.code.toByte()
        header[2] = 'F'.code.toByte()
        header[3] = 'F'.code.toByte()
        header[4] = (totalDataLen and 0xff).toByte()
        header[5] = ((totalDataLen shr 8) and 0xff).toByte()
        header[6] = ((totalDataLen shr 16) and 0xff).toByte()
        header[7] = ((totalDataLen shr 24) and 0xff).toByte()
        header[8] = 'W'.code.toByte()
        header[9] = 'A'.code.toByte()
        header[10] = 'V'.code.toByte()
        header[11] = 'E'.code.toByte()
        header[12] = 'f'.code.toByte()
        header[13] = 'm'.code.toByte()
        header[14] = 't'.code.toByte()
        header[15] = ' '.code.toByte()
        header[16] = 16 // Subchunk1Size for PCM
        header[17] = 0
        header[18] = 0
        header[19] = 0
        header[20] = 1 // AudioFormat 1 = PCM
        header[21] = 0
        header[22] = channels.toByte()
        header[23] = 0
        header[24] = (sampleRate and 0xff).toByte()
        header[25] = ((sampleRate shr 8) and 0xff).toByte()
        header[26] = ((sampleRate shr 16) and 0xff).toByte()
        header[27] = ((sampleRate shr 24) and 0xff).toByte()
        header[28] = (byteRate and 0xff).toByte()
        header[29] = ((byteRate shr 8) and 0xff).toByte()
        header[30] = ((byteRate shr 16) and 0xff).toByte()
        header[31] = ((byteRate shr 24) and 0xff).toByte()
        header[32] = (channels * 2).toByte() // BlockAlign
        header[33] = 0
        header[34] = 16 // BitsPerSample
        header[35] = 0
        header[36] = 'd'.code.toByte()
        header[37] = 'a'.code.toByte()
        header[38] = 't'.code.toByte()
        header[39] = 'a'.code.toByte()
        header[40] = (pcmDataLen and 0xff).toByte()
        header[41] = ((pcmDataLen shr 8) and 0xff).toByte()
        header[42] = ((pcmDataLen shr 16) and 0xff).toByte()
        header[43] = ((pcmDataLen shr 24) and 0xff).toByte()
        out.write(header, 0, 44)
    }

    private fun createShareIntent(files: List<File>): Intent {
        val uris = ArrayList<Uri>()
        for (f in files) {
            val uri = try {
                FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    f
                )
            } catch (_: Exception) {
                Uri.fromFile(f)
            }
            uris.add(uri)
        }

        return if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).apply {
                type = "audio/wav"
                putExtra(Intent.EXTRA_STREAM, uris[0])
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = "audio/wav"
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
    }
}
