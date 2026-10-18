package com.example.audio.model

import java.io.File

enum class StemType {
    VOCAL,
    KARAOKE,
    ORIGINAL
}

enum class PlaybackMode {
    FULL_MIX,
    KARAOKE_ONLY,
    VOCALS_ONLY
}

data class StemTrackState(
    val stemType: StemType,
    val title: String,
    val isMuted: Boolean = false,
    val isSoloed: Boolean = false,
    val gain: Float = 1.0f,
    val waveformPeaks: FloatArray = FloatArray(0),
    val vuLevel: Float = 0f,
    val pcmFile: File? = null
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as StemTrackState
        if (stemType != other.stemType) return false
        if (title != other.title) return false
        if (isMuted != other.isMuted) return false
        if (isSoloed != other.isSoloed) return false
        if (gain != other.gain) return false
        if (!waveformPeaks.contentEquals(other.waveformPeaks)) return false
        if (vuLevel != other.vuLevel) return false
        if (pcmFile != other.pcmFile) return false
        return true
    }

    override fun hashCode(): Int {
        var result = stemType.hashCode()
        result = 31 * result + title.hashCode()
        result = 31 * result + isMuted.hashCode()
        result = 31 * result + isSoloed.hashCode()
        result = 31 * result + gain.hashCode()
        result = 31 * result + waveformPeaks.contentHashCode()
        result = 31 * result + vuLevel.hashCode()
        result = 31 * result + (pcmFile?.hashCode() ?: 0)
        return result
    }
}

data class AudioMetadata(
    val title: String,
    val durationMs: Long,
    val sampleRate: Int = 44100,
    val channels: Int = 2,
    val totalFrames: Long = 0L,
    val fileSizeFormatted: String = ""
)

enum class SeparationStage {
    IDLE,
    DECODING_RESAMPLING,
    INFERRING_CHUNKS,
    EXTRACTING_KARAOKE,
    WRITING_STEMS,
    READY,
    ERROR
}

enum class ExecutionBackend(
    val id: String,
    val label: String,
    val subtitle: String
) {
    CPU_LOW_RAM(
        id = "cpu",
        label = "CPU (Low-RAM)",
        subtitle = "Low-memory sequential (no BFCArena OOM)"
    ),
    QNN_NPU(
        id = "qnn_npu",
        label = "QNN NPU (HTP)",
        subtitle = "Snapdragon Hexagon NPU FP16"
    ),
    QNN_GPU(
        id = "qnn_gpu",
        label = "QNN GPU (Adreno)",
        subtitle = "Qualcomm Adreno GPU FP16"
    )
}

data class SeparationProgress(
    val stage: SeparationStage = SeparationStage.IDLE,
    val currentChunk: Int = 0,
    val totalChunks: Int = 0,
    val progress: Float = 0f,
    val statusText: String = "Ready",
    val etaSeconds: Long = 0L,
    val errorMessage: String? = null,
    val inputNames: String = "",
    val outputNames: String = "",
    val tensorShapeInfo: String = "",
    val lastChunkInferenceMs: Long = 0L,
    val activeBackend: String = ""
)

enum class ModelPreset(
    val id: String,
    val displayName: String,
    val fileName: String,
    val downloadUrl: String,
    val description: String,
    val isVocalsOnlyTrained: Boolean
) {
    HTDEMUCS_FT_VOCALS(
        id = "htdemucs_ft_vocals",
        displayName = "HT-Demucs FT Vocals Specialist (166 MB)",
        fileName = "htdemucs_ft_vocals_fp16weights.onnx",
        downloadUrl = "https://huggingface.co/StemSplitio/htdemucs-ft-vocals-onnx/resolve/main/htdemucs_ft_vocals_fp16weights.onnx",
        description = "Fine-tuned vocal extraction specialist. Requires phase-perfect residual (Original - Vocals) for Karaoke.",
        isVocalsOnlyTrained = true
    ),
    HTDEMUCS_4STEM(
        id = "htdemucs_4stem",
        displayName = "HT-Demucs 4-Stem All-Trained (166 MB)",
        fileName = "htdemucs_fp16weights.onnx",
        downloadUrl = "https://huggingface.co/StemSplitio/htdemucs-onnx/resolve/main/htdemucs_fp16weights.onnx",
        description = "All 4 stems (Drums, Bass, Other, Vocals) trained. Supports both neural sum and residual Karaoke.",
        isVocalsOnlyTrained = false
    )
}

enum class KaraokeMode(
    val id: String,
    val label: String,
    val description: String
) {
    RESIDUAL_SUBTRACTION(
        id = "residual",
        label = "Residual Karaoke (Original - Vocals)",
        description = "Phase-perfect cancellation. Zero noise/drone. Mandatory for Vocals Specialist model."
    ),
    NEURAL_SUM(
        id = "neural_sum",
        label = "Neural Sum (Drums + Bass + Other)",
        description = "Sums non-vocal stems. Only available for 4-Stem trained models."
    )
}

data class ModelState(
    val isReady: Boolean = false,
    val isDownloading: Boolean = false,
    val downloadProgress: Float = 0f,
    val downloadSpeedText: String = "",
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val modelFile: File? = null,
    val statusMessage: String = "Model not installed",
    val activePreset: ModelPreset = ModelPreset.HTDEMUCS_FT_VOCALS,
    val karaokeMode: KaraokeMode = KaraokeMode.RESIDUAL_SUBTRACTION
)

enum class ExportType {
    VOCAL_ONLY,
    KARAOKE_ONLY,
    BOTH_STEMS,
    MIXED_MASTER
}
