package com.example.audio.demucs

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.content.Context
import android.net.Uri
import android.util.Log
import com.example.audio.model.AudioMetadata
import com.example.audio.model.ExecutionBackend
import com.example.audio.model.KaraokeMode
import com.example.audio.model.ModelPreset
import com.example.audio.model.ModelState
import com.example.audio.model.SeparationProgress
import com.example.audio.model.SeparationStage
import com.example.audio.model.StemTrackState
import com.example.audio.model.StemType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.max

class DemucsOnnxSeparationEngine(
    private val context: Context,
    private val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()
) {
    companion object {
        const val TAG = "DemucsOnnxEngine"
        const val MIN_MODEL_SIZE_BYTES = 50_000_000L // 50 MB minimum threshold to prevent dummy/corrupt files

        // Demucs v4 FT segment frames: 343980 (approx 7.8 seconds at 44.1kHz stereo)
        const val DEMUCS_SEGMENT_FRAMES = 343980
        // Standard Demucs 25% overlap for artifact-free crossfades between chunks
        const val OVERLAP_FRAMES = 85995
        const val HOP_FRAMES = DEMUCS_SEGMENT_FRAMES - OVERLAP_FRAMES // 257985 frames (~5.85s hop)
        const val SAMPLE_RATE = 44100
        const val CHANNELS = 2
    }

    private var activePreset: ModelPreset = ModelPreset.HTDEMUCS_FT_VOCALS
    private var activeKaraokeMode: KaraokeMode = KaraokeMode.RESIDUAL_SUBTRACTION

    private val _modelState = MutableStateFlow(ModelState(activePreset = activePreset, karaokeMode = activeKaraokeMode))
    val modelState = _modelState.asStateFlow()

    private val _separationProgress = MutableStateFlow(SeparationProgress())
    val separationProgress = _separationProgress.asStateFlow()

    private var ortEnv: OrtEnvironment? = null
    private var ortSession: OrtSession? = null

    init {
        checkInstalledModel()
    }

    fun setModelPreset(preset: ModelPreset) {
        if (activePreset != preset) {
            activePreset = preset
            closeSession()
            System.gc()
            // If switching to vocals-only trained model, residual subtraction is mandatory
            if (preset.isVocalsOnlyTrained && activeKaraokeMode == KaraokeMode.NEURAL_SUM) {
                activeKaraokeMode = KaraokeMode.RESIDUAL_SUBTRACTION
            }
            checkInstalledModel()
        }
    }

    fun setKaraokeMode(mode: KaraokeMode) {
        // Enforce residual subtraction for vocals specialist model
        val finalMode = if (activePreset.isVocalsOnlyTrained) KaraokeMode.RESIDUAL_SUBTRACTION else mode
        activeKaraokeMode = finalMode
        _modelState.value = _modelState.value.copy(karaokeMode = finalMode)
    }

    fun getActivePreset(): ModelPreset = activePreset
    fun getKaraokeMode(): KaraokeMode = activeKaraokeMode

    fun getModelFileForPreset(preset: ModelPreset): File {
        val specificFile = File(context.filesDir, preset.fileName)
        if (specificFile.exists() && specificFile.length() >= MIN_MODEL_SIZE_BYTES) {
            return specificFile
        }
        // Fallback to legacy htdemucs_ft.onnx if it exists
        val legacyFile = File(context.filesDir, "htdemucs_ft.onnx")
        if (legacyFile.exists() && legacyFile.length() >= MIN_MODEL_SIZE_BYTES) {
            return legacyFile
        }
        return specificFile
    }

    fun checkInstalledModel() {
        val modelFile = getModelFileForPreset(activePreset)
        if (modelFile.exists() && modelFile.length() >= MIN_MODEL_SIZE_BYTES) {
            val sizeMb = modelFile.length() / (1024 * 1024)
            _modelState.value = _modelState.value.copy(
                isReady = true,
                modelFile = modelFile,
                statusMessage = "${activePreset.displayName} Ready ($sizeMb MB)",
                activePreset = activePreset,
                karaokeMode = activeKaraokeMode
            )
        } else {
            // Check if any other valid model is available
            val altPreset = if (activePreset == ModelPreset.HTDEMUCS_FT_VOCALS) ModelPreset.HTDEMUCS_4STEM else ModelPreset.HTDEMUCS_FT_VOCALS
            val altFile = File(context.filesDir, altPreset.fileName)
            val legacyFile = File(context.filesDir, "htdemucs_ft.onnx")

            val foundFile = when {
                altFile.exists() && altFile.length() >= MIN_MODEL_SIZE_BYTES -> {
                    activePreset = altPreset
                    altFile
                }
                legacyFile.exists() && legacyFile.length() >= MIN_MODEL_SIZE_BYTES -> legacyFile
                else -> null
            }

            if (foundFile != null) {
                val sizeMb = foundFile.length() / (1024 * 1024)
                _modelState.value = _modelState.value.copy(
                    isReady = true,
                    modelFile = foundFile,
                    statusMessage = "${activePreset.displayName} Ready ($sizeMb MB)",
                    activePreset = activePreset,
                    karaokeMode = activeKaraokeMode
                )
            } else {
                _modelState.value = _modelState.value.copy(
                    isReady = false,
                    modelFile = null,
                    statusMessage = if (modelFile.exists()) "Incomplete model (${modelFile.length() / (1024 * 1024)} MB < 50MB)" else "Model not installed",
                    activePreset = activePreset,
                    karaokeMode = activeKaraokeMode
                )
            }
        }
    }

    suspend fun downloadModel(
        preset: ModelPreset = activePreset,
        onProgressUpdate: (Float, String) -> Unit = { _, _ -> }
    ): Boolean = withContext(Dispatchers.IO) {
        if (_modelState.value.isDownloading) return@withContext false

        activePreset = preset
        _modelState.value = _modelState.value.copy(
            isDownloading = true,
            downloadProgress = 0f,
            activePreset = preset,
            statusMessage = "Connecting to Hugging Face for ${preset.displayName}..."
        )

        val destFile = File(context.filesDir, preset.fileName)
        val tempFile = File(context.filesDir, "${preset.fileName}.download")

        try {
            val request = Request.Builder().url(preset.downloadUrl).build()
            val response = okHttpClient.newCall(request).execute()

            if (!response.isSuccessful) {
                throw IllegalStateException("Download failed with HTTP ${response.code}: ${response.message}")
            }

            val body = response.body ?: throw IllegalStateException("Empty response body from server")
            val totalBytes = body.contentLength()
            var downloadedBytes = 0L

            val inputStream = body.byteStream()
            val outputStream = FileOutputStream(tempFile)

            val buffer = ByteArray(65536)
            var bytesRead: Int
            var lastTime = System.currentTimeMillis()
            var lastBytes = 0L

            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                if (!currentCoroutineContext().isActive) {
                    outputStream.close()
                    tempFile.delete()
                    _modelState.value = _modelState.value.copy(
                        isDownloading = false,
                        statusMessage = "Download cancelled by user"
                    )
                    return@withContext false
                }

                outputStream.write(buffer, 0, bytesRead)
                downloadedBytes += bytesRead

                val now = System.currentTimeMillis()
                val delta = now - lastTime
                if (delta >= 500L) {
                    val speedBytesSec = if (delta > 0) ((downloadedBytes - lastBytes) * 1000L) / delta else 0L
                    val speedText = if (speedBytesSec > 1024 * 1024) {
                        String.format("%.1f MB/s", speedBytesSec.toFloat() / (1024f * 1024f))
                    } else {
                        String.format("%d KB/s", speedBytesSec / 1024)
                    }

                    val progress = if (totalBytes > 0) downloadedBytes.toFloat() / totalBytes.toFloat() else 0f
                    _modelState.value = _modelState.value.copy(
                        downloadProgress = progress,
                        downloadSpeedText = speedText,
                        downloadedBytes = downloadedBytes,
                        totalBytes = totalBytes,
                        statusMessage = "Downloading: ${(progress * 100).toInt()}% ($speedText)"
                    )
                    onProgressUpdate(progress, speedText)

                    lastTime = now
                    lastBytes = downloadedBytes
                }
            }

            outputStream.flush()
            outputStream.close()
            inputStream.close()

            if (tempFile.length() < MIN_MODEL_SIZE_BYTES) {
                tempFile.delete()
                throw IllegalStateException("Downloaded model size (${tempFile.length() / (1024 * 1024)} MB) is below the minimum required 50 MB threshold.")
            }

            if (destFile.exists()) destFile.delete()
            if (!tempFile.renameTo(destFile)) {
                throw IllegalStateException("Failed to move downloaded model file into application storage.")
            }

            closeSession()
            checkInstalledModel()
            return@withContext true
        } catch (e: Exception) {
            tempFile.delete()
            Log.e(TAG, "Download error", e)
            _modelState.value = _modelState.value.copy(
                isDownloading = false,
                isReady = false,
                statusMessage = "Download Failed: ${e.localizedMessage ?: e.javaClass.simpleName}"
            )
            return@withContext false
        }
    }

    suspend fun importModelFromUri(uri: Uri): Boolean = withContext(Dispatchers.IO) {
        val destFile = File(context.filesDir, activePreset.fileName)
        val tempFile = File(context.filesDir, "${activePreset.fileName}.import")
        try {
            _modelState.value = _modelState.value.copy(
                isDownloading = true,
                statusMessage = "Importing local ONNX model file..."
            )
            context.contentResolver.openInputStream(uri)?.use { inStream ->
                FileOutputStream(tempFile).use { outStream ->
                    inStream.copyTo(outStream, 65536)
                }
            } ?: throw IllegalStateException("Cannot open input stream for selected URI: $uri")

            if (tempFile.length() < MIN_MODEL_SIZE_BYTES) {
                val sizeMb = tempFile.length() / (1024 * 1024)
                tempFile.delete()
                throw IllegalStateException("Imported model file is too small ($sizeMb MB). Minimum 50 MB required for Demucs v4 FT.")
            }

            if (destFile.exists()) destFile.delete()
            if (!tempFile.renameTo(destFile)) {
                throw IllegalStateException("Failed to save imported model file.")
            }

            closeSession()
            checkInstalledModel()
            true
        } catch (e: Exception) {
            tempFile.delete()
            Log.e(TAG, "Import error", e)
            _modelState.value = _modelState.value.copy(
                isDownloading = false,
                statusMessage = "Import Failed: ${e.localizedMessage}"
            )
            false
        }
    }

    private var currentBackend: ExecutionBackend = ExecutionBackend.CPU_LOW_RAM

    fun setExecutionBackend(backend: ExecutionBackend) {
        if (currentBackend != backend) {
            currentBackend = backend
            closeSession()
            System.gc()
        }
    }

    fun getExecutionBackend(): ExecutionBackend = currentBackend

    private fun getOrCreateSession(): Pair<OrtEnvironment, OrtSession> {
        val modelFile = _modelState.value.modelFile
            ?: getModelFileForPreset(activePreset).takeIf { it.exists() && it.length() >= MIN_MODEL_SIZE_BYTES }
            ?: throw IllegalStateException("Demucs ONNX model file not found or corrupted (<50MB). Please download or import a verified htdemucs_ft.onnx model.")

        if (ortEnv == null) {
            ortEnv = OrtEnvironment.getEnvironment()
        }
        val env = ortEnv!!

        if (ortSession == null) {
            closeSession()
            System.gc()

            val sessionOptions = OrtSession.SessionOptions().apply {
                setCPUArenaAllocator(false) // Mandatory for ALL backends to prevent 3GB+ arena OOM
                setMemoryPatternOptimization(false) // Mandatory for layer-by-layer tensor allocation/freeing
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.BASIC_OPT) // Prevents Constant Folding from duplicating 165MB FP16 weights into 316MB FP32 weights in RAM
                setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL)
                setIntraOpNumThreads(2) // Keep 2 threads so Android System UI never freezes
                setInterOpNumThreads(1)
                addConfigEntry("session.intra_op.allow_spinning", "0") // Stop 100% CPU thread spinning
                addConfigEntry("session.use_device_allocator_for_initializers", "1")
                addConfigEntry("session.disable_prepacking", "1") // Prevents duplicating 316MB of weights in RAM

                when (currentBackend) {
                    ExecutionBackend.CPU_LOW_RAM -> {
                        // Do NOT call addXnnpack(): XNNPACK ignores setCPUArenaAllocator(false) and duplicates all weights
                        Log.i(TAG, "Configured CPU (Low-RAM Safe) SessionOptions.")
                    }
                    ExecutionBackend.QNN_NPU -> {
                        addConfigEntry("ep.context_enable", "1")
                        addConfigEntry("ep.context_file_path", "${context.cacheDir.absolutePath}/qnn_htp_ctx.onnx")
                        try {
                            addQnn(mapOf(
                                "backend_path" to "libQnnHtp.so",
                                "enable_htp_fp16_precision" to "1",
                                "htp_performance_mode" to "burst",
                                "htp_graph_finalization_optimization_mode" to "1"
                            ))
                            Log.i(TAG, "QNN HTP NPU execution provider enabled (FP16, optimization mode 1).")
                        } catch (qnnErr: Exception) {
                            Log.w(TAG, "Failed to enable QNN HTP: ${qnnErr.message}")
                        }
                    }
                    ExecutionBackend.QNN_GPU -> {
                        addConfigEntry("ep.context_enable", "1")
                        addConfigEntry("ep.context_file_path", "${context.cacheDir.absolutePath}/qnn_gpu_ctx.onnx")
                        try {
                            addQnn(mapOf(
                                "backend_path" to "libQnnGpu.so"
                            ))
                            Log.i(TAG, "QNN Adreno GPU execution provider enabled successfully.")
                        } catch (qnnErr: Exception) {
                            Log.w(TAG, "Failed to enable QNN GPU: ${qnnErr.message}")
                        }
                    }
                }
            }
            Log.i(TAG, "Creating OrtSession from ${modelFile.absolutePath} (${modelFile.length()} bytes)")
            ortSession = env.createSession(modelFile.absolutePath, sessionOptions)

            val session = ortSession!!
            Log.i(TAG, "=== DEMUCS ONNX SESSION INSPECTION ===")
            for ((name, info) in session.inputInfo) {
                Log.i(TAG, "Input '$name': info=$info")
            }
            for ((name, info) in session.outputInfo) {
                Log.i(TAG, "Output '$name': info=$info")
            }
            Log.i(TAG, "======================================")
        }
        return Pair(env, ortSession!!)
    }

    data class SeparationResult(
        val vocalTrack: StemTrackState,
        val karaokeTrack: StemTrackState
    )

    suspend fun separate(
        sourcePcmFile: File,
        metadata: AudioMetadata
    ): SeparationResult = withContext(Dispatchers.IO) {
        if (!sourcePcmFile.exists() || sourcePcmFile.length() == 0L) {
            throw IllegalArgumentException("Source PCM file does not exist or is empty.")
        }

        _separationProgress.value = SeparationProgress(
            stage = SeparationStage.DECODING_RESAMPLING,
            progress = 0.05f,
            statusText = "Initializing Demucs ONNX Runtime session..."
        )

        val totalFrames = metadata.totalFrames
        if (totalFrames <= 0) {
            throw IllegalArgumentException("Invalid audio duration: totalFrames is $totalFrames")
        }

        // Calculate chunk boundaries: strictly DEMUCS_SEGMENT_FRAMES (343980) per chunk
        val chunkOffsets = mutableListOf<Long>()
        var offset = 0L
        while (offset < totalFrames) {
            chunkOffsets.add(offset)
            if (offset + DEMUCS_SEGMENT_FRAMES >= totalFrames) break
            offset += HOP_FRAMES
        }
        val totalChunks = max(1, chunkOffsets.size)

        val vocalOutputFile = File(context.cacheDir, "stem_vocals_${System.currentTimeMillis()}.pcm")
        val karaokeOutputFile = File(context.cacheDir, "stem_karaoke_${System.currentTimeMillis()}.pcm")

        val peakBucketCount = 1200
        val vocalPeaks = FloatArray(peakBucketCount)
        val karaokePeaks = FloatArray(peakBucketCount)

        // Fail-Fast: Initialize ONNX session
        val (env, session) = try {
            getOrCreateSession()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize ONNX session", e)
            _separationProgress.value = SeparationProgress(
                stage = SeparationStage.ERROR,
                statusText = "ONNX Init Failed: ${e.localizedMessage}",
                errorMessage = e.stackTraceToString()
            )
            throw e
        }

        val vocalRaf = RandomAccessFile(vocalOutputFile, "rw")
        val karaokeRaf = RandomAccessFile(karaokeOutputFile, "rw")
        val sourceRaf = RandomAccessFile(sourcePcmFile, "r")

        val totalBytes = totalFrames * 4L
        vocalRaf.setLength(totalBytes)
        karaokeRaf.setLength(totalBytes)

        val startTime = System.currentTimeMillis()

        // Allocate direct float buffer for zero-copy native tensor: shape [1, 2, DEMUCS_SEGMENT_FRAMES]
        val directInputByteBuffer = ByteBuffer.allocateDirect(1 * CHANNELS * DEMUCS_SEGMENT_FRAMES * 4)
            .order(ByteOrder.nativeOrder())
        val directInputFloatBuffer = directInputByteBuffer.asFloatBuffer()

        // Interleaved shorts buffer for disk read
        val chunkInputShorts = ShortArray(DEMUCS_SEGMENT_FRAMES * CHANNELS)
        val shortByteBuffer = ByteBuffer.allocate(DEMUCS_SEGMENT_FRAMES * CHANNELS * 2)
            .order(ByteOrder.LITTLE_ENDIAN)

        val inputName = session.inputNames.iterator().next()
        val outputName = session.outputNames.iterator().next()
        Log.i(TAG, "Demucs primary input: '$inputName', output: '$outputName', segmentFrames: $DEMUCS_SEGMENT_FRAMES")

        var lastInferenceMs = 0L

        val trailingOverlapVocL = FloatArray(OVERLAP_FRAMES)
        val trailingOverlapVocR = FloatArray(OVERLAP_FRAMES)
        val trailingOverlapKarL = FloatArray(OVERLAP_FRAMES)
        val trailingOverlapKarR = FloatArray(OVERLAP_FRAMES)

        try {
            for (chunkIdx in 0 until totalChunks) {
                if (!currentCoroutineContext().isActive) {
                    vocalOutputFile.delete()
                    karaokeOutputFile.delete()
                    _separationProgress.value = SeparationProgress(
                        stage = SeparationStage.IDLE,
                        statusText = "Separation cancelled by user"
                    )
                    throw InterruptedException("Separation cancelled by user")
                }

                val frameOffset = chunkOffsets[chunkIdx]
                val framesToRead = (totalFrames - frameOffset).coerceAtMost(DEMUCS_SEGMENT_FRAMES.toLong()).toInt()

                _separationProgress.value = SeparationProgress(
                    stage = SeparationStage.INFERRING_CHUNKS,
                    currentChunk = chunkIdx + 1,
                    totalChunks = totalChunks,
                    progress = (chunkIdx.toFloat() / totalChunks.toFloat()).coerceIn(0.05f, 0.95f),
                    statusText = "Demucs v4 FT: Chunk ${chunkIdx + 1} of $totalChunks...",
                    etaSeconds = calculateEta(startTime, chunkIdx, totalChunks),
                    inputNames = session.inputNames.joinToString(", "),
                    outputNames = session.outputNames.joinToString(", "),
                    tensorShapeInfo = "In: [1, 2, $DEMUCS_SEGMENT_FRAMES] -> Pending",
                    lastChunkInferenceMs = lastInferenceMs,
                    activeBackend = currentBackend.label
                )

                // 1. Read real interleaved 16-bit PCM frames from source audio on disk
                sourceRaf.seek(frameOffset * 4L)
                val bytesToRead = framesToRead * 4
                sourceRaf.readFully(shortByteBuffer.array(), 0, bytesToRead)
                shortByteBuffer.position(0)
                shortByteBuffer.asShortBuffer().get(chunkInputShorts, 0, framesToRead * CHANNELS)

                // 2. Exact Planar Stereo De-interleaving into direct FloatBuffer (Shape: [1, 2, DEMUCS_SEGMENT_FRAMES])
                directInputFloatBuffer.clear()
                // Left channel
                for (f in 0 until framesToRead) {
                    directInputFloatBuffer.put(chunkInputShorts[f * 2].toFloat() / 32768.0f)
                }
                for (f in framesToRead until DEMUCS_SEGMENT_FRAMES) {
                    directInputFloatBuffer.put(0.0f)
                }
                // Right channel
                for (f in 0 until framesToRead) {
                    directInputFloatBuffer.put(chunkInputShorts[f * 2 + 1].toFloat() / 32768.0f)
                }
                for (f in framesToRead until DEMUCS_SEGMENT_FRAMES) {
                    directInputFloatBuffer.put(0.0f)
                }
                directInputFloatBuffer.rewind()

                // 3 & 4. Strictly wrap OnnxTensor and OrtSession.Result in .use { ... } blocks to prevent native leaks
                OnnxTensor.createTensor(
                    env,
                    directInputFloatBuffer,
                    longArrayOf(1, CHANNELS.toLong(), DEMUCS_SEGMENT_FRAMES.toLong())
                ).use { inputTensor ->
                    val chunkRunStartTime = System.currentTimeMillis()
                    val results = try {
                        session.run(mapOf(inputName to inputTensor))
                    } catch (ortEx: Exception) {
                        Log.e(TAG, "ONNX Inference failed on chunk $chunkIdx", ortEx)
                        vocalOutputFile.delete()
                        karaokeOutputFile.delete()
                        _separationProgress.value = SeparationProgress(
                            stage = SeparationStage.ERROR,
                            statusText = "ONNX Run Failed: ${ortEx.localizedMessage}",
                            errorMessage = "Expected input: [1, 2, $DEMUCS_SEGMENT_FRAMES] for '$inputName'\nException: ${ortEx.stackTraceToString()}",
                            inputNames = session.inputNames.joinToString(", "),
                            outputNames = session.outputNames.joinToString(", "),
                            activeBackend = currentBackend.label
                        )
                        throw ortEx
                    }

                    results.use { res ->
                        val chunkInferenceMs = System.currentTimeMillis() - chunkRunStartTime
                        lastInferenceMs = chunkInferenceMs

                        val outputTensor = res.get(0) as OnnxTensor
                        val outTensorInfo = outputTensor.info as TensorInfo
                        val outputShape = outTensorInfo.shape
                        val outFloatBuffer = outputTensor.floatBuffer

                        _separationProgress.value = _separationProgress.value.copy(
                            tensorShapeInfo = "In: [1, 2, $DEMUCS_SEGMENT_FRAMES] -> Out: [${outputShape.joinToString()}]",
                            lastChunkInferenceMs = chunkInferenceMs
                        )

                        // 5. Inspect Model Output Stems dynamically
                        val is4Stem = (outputShape.size == 4 && outputShape[1] == 4L) || (outputShape.size == 3 && outputShape[0] == 4L)
                        val is2Stem = (outputShape.size == 4 && outputShape[1] == 2L) || (outputShape.size == 3 && outputShape[0] == 2L)

                        val canUseNeuralSum = is4Stem && !activePreset.isVocalsOnlyTrained
                        val useNeuralSum = canUseNeuralSum && (activeKaraokeMode == KaraokeMode.NEURAL_SUM)

                        val isLastChunk = (chunkIdx == totalChunks - 1)
                        val finalizedFramesCount = if (isLastChunk) framesToRead else minOf(HOP_FRAMES, framesToRead)

                        val outByteBuffer = ByteBuffer.allocate(finalizedFramesCount * 4).order(ByteOrder.LITTLE_ENDIAN)
                        val karByteBuffer = ByteBuffer.allocate(finalizedFramesCount * 4).order(ByteOrder.LITTLE_ENDIAN)

                        for (f in 0 until finalizedFramesCount) {
                            val rawVocL: Float
                            val rawVocR: Float
                            val rawKarL: Float
                            val rawKarR: Float

                            if (is4Stem) {
                                // In Demucs 4-stem: Stem 0=Drums, 1=Bass, 2=Other, 3=Vocals
                                rawVocL = outFloatBuffer.get((3 * 2 + 0) * DEMUCS_SEGMENT_FRAMES + f)
                                rawVocR = outFloatBuffer.get((3 * 2 + 1) * DEMUCS_SEGMENT_FRAMES + f)

                                if (useNeuralSum) {
                                    val drumsL = outFloatBuffer.get((0 * 2 + 0) * DEMUCS_SEGMENT_FRAMES + f)
                                    val drumsR = outFloatBuffer.get((0 * 2 + 1) * DEMUCS_SEGMENT_FRAMES + f)
                                    val bassL = outFloatBuffer.get((1 * 2 + 0) * DEMUCS_SEGMENT_FRAMES + f)
                                    val bassR = outFloatBuffer.get((1 * 2 + 1) * DEMUCS_SEGMENT_FRAMES + f)
                                    val otherL = outFloatBuffer.get((2 * 2 + 0) * DEMUCS_SEGMENT_FRAMES + f)
                                    val otherR = outFloatBuffer.get((2 * 2 + 1) * DEMUCS_SEGMENT_FRAMES + f)
                                    rawKarL = drumsL + bassL + otherL
                                    rawKarR = drumsR + bassR + otherR
                                } else {
                                    rawKarL = 0f
                                    rawKarR = 0f
                                }
                            } else if (is2Stem) {
                                // 2-stem model (Stem 0 = Karaoke, Stem 1 = Vocals)
                                rawVocL = outFloatBuffer.get((1 * 2 + 0) * DEMUCS_SEGMENT_FRAMES + f)
                                rawVocR = outFloatBuffer.get((1 * 2 + 1) * DEMUCS_SEGMENT_FRAMES + f)
                                rawKarL = outFloatBuffer.get((0 * 2 + 0) * DEMUCS_SEGMENT_FRAMES + f)
                                rawKarR = outFloatBuffer.get((0 * 2 + 1) * DEMUCS_SEGMENT_FRAMES + f)
                            } else {
                                // 1-stem vocal specialist model (Stem 0 = Vocals)
                                rawVocL = outFloatBuffer.get(0 * DEMUCS_SEGMENT_FRAMES + f)
                                rawVocR = outFloatBuffer.get(1 * DEMUCS_SEGMENT_FRAMES + f)
                                rawKarL = 0f
                                rawKarR = 0f
                            }

                            // 6. Overlap-Add Linear Crossfade for Vocals (and Neural Sum if selected)
                            val finalVocL: Float
                            val finalVocR: Float
                            val finalKarL: Float
                            val finalKarR: Float

                            if (chunkIdx > 0 && f < OVERLAP_FRAMES) {
                                val fadeWeight = f.toFloat() / OVERLAP_FRAMES.toFloat()
                                finalVocL = (trailingOverlapVocL[f] + rawVocL * fadeWeight).coerceIn(-1f, 1f)
                                finalVocR = (trailingOverlapVocR[f] + rawVocR * fadeWeight).coerceIn(-1f, 1f)

                                if (useNeuralSum) {
                                    finalKarL = (trailingOverlapKarL[f] + rawKarL * fadeWeight).coerceIn(-1f, 1f)
                                    finalKarR = (trailingOverlapKarR[f] + rawKarR * fadeWeight).coerceIn(-1f, 1f)
                                } else {
                                    // Clean post-overlap subtraction: pristine decoded PCM sample minus finalized vocal
                                    val origL = chunkInputShorts[f * 2].toFloat() / 32768.0f
                                    val origR = chunkInputShorts[f * 2 + 1].toFloat() / 32768.0f
                                    finalKarL = (origL - finalVocL).coerceIn(-1f, 1f)
                                    finalKarR = (origR - finalVocR).coerceIn(-1f, 1f)
                                }
                            } else {
                                finalVocL = rawVocL.coerceIn(-1f, 1f)
                                finalVocR = rawVocR.coerceIn(-1f, 1f)

                                if (useNeuralSum) {
                                    finalKarL = rawKarL.coerceIn(-1f, 1f)
                                    finalKarR = rawKarR.coerceIn(-1f, 1f)
                                } else {
                                    // Clean post-overlap subtraction: pristine decoded PCM sample minus finalized vocal
                                    val origL = chunkInputShorts[f * 2].toFloat() / 32768.0f
                                    val origR = chunkInputShorts[f * 2 + 1].toFloat() / 32768.0f
                                    finalKarL = (origL - finalVocL).coerceIn(-1f, 1f)
                                    finalKarR = (origR - finalVocR).coerceIn(-1f, 1f)
                                }
                            }

                            // 7. Write 16-bit PCM little-endian
                            outByteBuffer.putShort((finalVocL * 32767f).toInt().coerceIn(-32768, 32767).toShort())
                            outByteBuffer.putShort((finalVocR * 32767f).toInt().coerceIn(-32768, 32767).toShort())

                            karByteBuffer.putShort((finalKarL * 32767f).toInt().coerceIn(-32768, 32767).toShort())
                            karByteBuffer.putShort((finalKarR * 32767f).toInt().coerceIn(-32768, 32767).toShort())

                            val globalFrame = frameOffset + f
                            val peakIdx = ((globalFrame * peakBucketCount) / totalFrames).toInt().coerceIn(0, peakBucketCount - 1)
                            vocalPeaks[peakIdx] = max(vocalPeaks[peakIdx], abs(finalVocL))
                            karaokePeaks[peakIdx] = max(karaokePeaks[peakIdx], abs(finalKarL))
                        }

                        // Store trailing overlap region with fade-out weight for crossfade with next chunk
                        if (!isLastChunk && framesToRead > HOP_FRAMES) {
                            val overlapEnd = minOf(DEMUCS_SEGMENT_FRAMES, framesToRead)
                            for (f in HOP_FRAMES until overlapEnd) {
                                val relIdx = f - HOP_FRAMES
                                if (relIdx < OVERLAP_FRAMES) {
                                    val fadeOutWeight = 1.0f - (relIdx.toFloat() / OVERLAP_FRAMES.toFloat())
                                    val vocL = outFloatBuffer.get((if (is4Stem) 3 * 2 + 0 else if (is2Stem) 1 * 2 + 0 else 0) * DEMUCS_SEGMENT_FRAMES + f)
                                    val vocR = outFloatBuffer.get((if (is4Stem) 3 * 2 + 1 else if (is2Stem) 1 * 2 + 1 else 1) * DEMUCS_SEGMENT_FRAMES + f)
                                    trailingOverlapVocL[relIdx] = vocL * fadeOutWeight
                                    trailingOverlapVocR[relIdx] = vocR * fadeOutWeight

                                    if (useNeuralSum) {
                                        val dL = outFloatBuffer.get((0 * 2 + 0) * DEMUCS_SEGMENT_FRAMES + f)
                                        val dR = outFloatBuffer.get((0 * 2 + 1) * DEMUCS_SEGMENT_FRAMES + f)
                                        val bL = outFloatBuffer.get((1 * 2 + 0) * DEMUCS_SEGMENT_FRAMES + f)
                                        val bR = outFloatBuffer.get((1 * 2 + 1) * DEMUCS_SEGMENT_FRAMES + f)
                                        val oL = outFloatBuffer.get((2 * 2 + 0) * DEMUCS_SEGMENT_FRAMES + f)
                                        val oR = outFloatBuffer.get((2 * 2 + 1) * DEMUCS_SEGMENT_FRAMES + f)
                                        trailingOverlapKarL[relIdx] = (dL + bL + oL) * fadeOutWeight
                                        trailingOverlapKarR[relIdx] = (dR + bR + oR) * fadeOutWeight
                                    }
                                }
                            }
                        }

                        // Write finalized frames directly to disk for both stems
                        vocalRaf.seek(frameOffset * 4L)
                        vocalRaf.write(outByteBuffer.array(), 0, finalizedFramesCount * 4)

                        karaokeRaf.seek(frameOffset * 4L)
                        karaokeRaf.write(karByteBuffer.array(), 0, finalizedFramesCount * 4)
                    }
                }
            }
        } finally {
            vocalRaf.close()
            karaokeRaf.close()
            sourceRaf.close()
        }

        _separationProgress.value = SeparationProgress(
            stage = SeparationStage.READY,
            progress = 1.0f,
            statusText = "Separation Complete"
        )

        val vocalTrack = StemTrackState(
            stemType = StemType.VOCAL,
            title = "Vocals",
            isMuted = false,
            isSoloed = false,
            gain = 1.0f,
            waveformPeaks = vocalPeaks,
            pcmFile = vocalOutputFile
        )

        val karaokeTrack = StemTrackState(
            stemType = StemType.KARAOKE,
            title = "Karaoke (Instrumental)",
            isMuted = false,
            isSoloed = false,
            gain = 1.0f,
            waveformPeaks = karaokePeaks,
            pcmFile = karaokeOutputFile
        )

        SeparationResult(vocalTrack, karaokeTrack)
    }

    private fun calculateEta(startTime: Long, currentChunk: Int, totalChunks: Int): Long {
        if (currentChunk <= 0) return 0L
        val elapsed = System.currentTimeMillis() - startTime
        val msPerChunk = elapsed.toDouble() / currentChunk.toDouble()
        val remainingChunks = totalChunks - currentChunk
        return ((remainingChunks * msPerChunk) / 1000.0).toLong()
    }

    private fun closeSession() {
        try {
            ortSession?.close()
            ortSession = null
        } catch (_: Exception) {}
    }

    fun release() {
        closeSession()
        try {
            ortEnv?.close()
            ortEnv = null
        } catch (_: Exception) {}
    }
}
