package com.example.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.audio.decoder.AudioDecoder
import com.example.audio.demucs.DemucsOnnxSeparationEngine
import com.example.audio.export.WavAudioExporter
import com.example.audio.model.AudioMetadata
import com.example.audio.model.ExecutionBackend
import com.example.audio.model.ExportType
import com.example.audio.model.KaraokeMode
import com.example.audio.model.ModelPreset
import com.example.audio.model.ModelState
import com.example.audio.model.PlaybackMode
import com.example.audio.model.SeparationProgress
import com.example.audio.model.SeparationStage
import com.example.audio.model.StemTrackState
import com.example.audio.model.StemType
import com.example.audio.player.PcmSoftwareMixerEngine
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

class AudioSeparationViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        const val TAG = "AudioSeparationVM"
    }

    private val decoder = AudioDecoder(application)
    val separationEngine = DemucsOnnxSeparationEngine(application)
    val mixerEngine = PcmSoftwareMixerEngine(viewModelScope)
    private val exporter = WavAudioExporter(application)

    // Observable states
    val modelState: StateFlow<ModelState> = separationEngine.modelState
    val separationProgress: StateFlow<SeparationProgress> = separationEngine.separationProgress

    private val _currentBackend = MutableStateFlow(separationEngine.getExecutionBackend())
    val currentBackend: StateFlow<ExecutionBackend> = _currentBackend.asStateFlow()

    fun setExecutionBackend(backend: ExecutionBackend) {
        if (_currentBackend.value != backend) {
            _currentBackend.value = backend
            separationEngine.setExecutionBackend(backend)
            _statusToast.value = "Hardware Backend: ${backend.label}"
        }
    }

    val isPlaying: StateFlow<Boolean> = mixerEngine.isPlaying
    val playbackProgress: StateFlow<Float> = mixerEngine.playbackProgress
    val currentPositionMs: StateFlow<Long> = mixerEngine.currentPositionMs
    val vocalVuLevel: StateFlow<Float> = mixerEngine.vocalVuLevel
    val karaokeVuLevel: StateFlow<Float> = mixerEngine.karaokeVuLevel

    private val _currentMetadata = MutableStateFlow<AudioMetadata?>(null)
    val currentMetadata: StateFlow<AudioMetadata?> = _currentMetadata.asStateFlow()

    private val _originalPeaks = MutableStateFlow<FloatArray>(FloatArray(0))
    val originalPeaks: StateFlow<FloatArray> = _originalPeaks.asStateFlow()

    private val _vocalTrack = MutableStateFlow<StemTrackState?>(null)
    val vocalTrack: StateFlow<StemTrackState?> = _vocalTrack.asStateFlow()

    private val _karaokeTrack = MutableStateFlow<StemTrackState?>(null)
    val karaokeTrack: StateFlow<StemTrackState?> = _karaokeTrack.asStateFlow()

    private val _playbackMode = MutableStateFlow(PlaybackMode.FULL_MIX)
    val playbackMode: StateFlow<PlaybackMode> = _playbackMode.asStateFlow()

    private val _isDecoding = MutableStateFlow(false)
    val isDecoding: StateFlow<Boolean> = _isDecoding.asStateFlow()

    private val _decodeProgress = MutableStateFlow(0f)
    val decodeProgress: StateFlow<Float> = _decodeProgress.asStateFlow()

    private val _statusToast = MutableStateFlow<String?>(null)
    val statusToast: StateFlow<String?> = _statusToast.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val _showExportDialog = MutableStateFlow(false)
    val showExportDialog: StateFlow<Boolean> = _showExportDialog.asStateFlow()

    private val _isExporting = MutableStateFlow(false)
    val isExporting: StateFlow<Boolean> = _isExporting.asStateFlow()

    private var activeSourcePcmFile: File? = null
    private var separationJob: Job? = null

    // Strictly locked in uninitialized empty state on startup until user loads real files
    init {
        separationEngine.checkInstalledModel()
    }

    fun clearStatusToast() {
        _statusToast.value = null
    }

    fun clearErrorMessage() {
        _errorMessage.value = null
    }

    fun openExportDialog() {
        if (_vocalTrack.value == null && _karaokeTrack.value == null) {
            _statusToast.value = "Separate audio stems before exporting."
            return
        }
        _showExportDialog.value = true
    }

    fun closeExportDialog() {
        _showExportDialog.value = false
    }

    fun loadAudioFromUri(uri: Uri, fileName: String) {
        viewModelScope.launch {
            _isDecoding.value = true
            _decodeProgress.value = 0f
            _errorMessage.value = null
            mixerEngine.stop()
            try {
                val result = decoder.decodeToStereo44100(uri, fileName) { p ->
                    _decodeProgress.value = p
                }
                activeSourcePcmFile = result.pcmFile
                _currentMetadata.value = result.metadata
                _originalPeaks.value = result.waveformPeaks

                // Clear previous stem tracks until separation runs on this new track
                _vocalTrack.value = null
                _karaokeTrack.value = null
                mixerEngine.setStems(null, null, 0L)
                _statusToast.value = "Decoded: $fileName (${result.metadata.durationMs / 1000}s, 44.1kHz Stereo)"
            } catch (e: Exception) {
                Log.e(TAG, "Audio decode failed", e)
                _errorMessage.value = "Failed to decode audio: ${e.localizedMessage ?: e.javaClass.simpleName}"
                _statusToast.value = "Decode failed"
            } finally {
                _isDecoding.value = false
            }
        }
    }

    fun startSeparation() {
        val pcm = activeSourcePcmFile
        val meta = _currentMetadata.value

        // Strict UI & State Gatekeeping
        if (!separationEngine.modelState.value.isReady) {
            _statusToast.value = "Action Required: Download or import Demucs v4 FT ONNX model (>50MB) first."
            return
        }

        if (pcm == null || !pcm.exists() || meta == null) {
            _statusToast.value = "Action Required: Select an audio file (MP3 or WAV) to separate."
            return
        }

        mixerEngine.stop()
        separationJob?.cancel()
        _errorMessage.value = null

        separationJob = viewModelScope.launch {
            try {
                val result = separationEngine.separate(pcm, meta)
                _vocalTrack.value = result.vocalTrack
                _karaokeTrack.value = result.karaokeTrack

                mixerEngine.setStems(
                    result.vocalTrack.pcmFile,
                    result.karaokeTrack.pcmFile,
                    meta.totalFrames
                )
                _playbackMode.value = PlaybackMode.FULL_MIX
                _statusToast.value = "Separation completed successfully!"
            } catch (e: InterruptedException) {
                _statusToast.value = "Separation cancelled by user."
            } catch (e: Exception) {
                Log.e(TAG, "Separation pipeline failed", e)
                _errorMessage.value = "Separation Error: ${e.localizedMessage ?: e.javaClass.simpleName}"
                _statusToast.value = "Separation failed"
            }
        }
    }

    fun cancelSeparation() {
        separationJob?.cancel()
        separationJob = null
        _statusToast.value = "Separation cancelled."
    }

    fun selectModelPreset(preset: ModelPreset) {
        separationEngine.setModelPreset(preset)
        _statusToast.value = "Selected: ${preset.displayName}"
    }

    fun selectKaraokeMode(mode: KaraokeMode) {
        separationEngine.setKaraokeMode(mode)
        _statusToast.value = "Karaoke Mode: ${mode.label}"
    }

    fun downloadModel(preset: ModelPreset = separationEngine.getActivePreset()) {
        viewModelScope.launch {
            _errorMessage.value = null
            val success = separationEngine.downloadModel(preset) { _, _ -> }
            if (success) {
                _statusToast.value = "${preset.displayName} is verified and ready!"
            } else {
                val state = separationEngine.modelState.value
                _errorMessage.value = state.statusMessage
            }
        }
    }

    fun importModel(uri: Uri) {
        viewModelScope.launch {
            _errorMessage.value = null
            val success = separationEngine.importModelFromUri(uri)
            if (success) {
                _statusToast.value = "ONNX model imported and verified successfully!"
            } else {
                val state = separationEngine.modelState.value
                _errorMessage.value = state.statusMessage
            }
        }
    }

    fun togglePlayPause() {
        if (mixerEngine.isPlaying.value) {
            mixerEngine.pause()
        } else {
            mixerEngine.play()
        }
    }

    fun seekTo(fraction: Float) {
        mixerEngine.seekToFraction(fraction)
    }

    fun toggleVocalMute() {
        val current = _vocalTrack.value ?: return
        val newMuted = !current.isMuted
        _vocalTrack.value = current.copy(isMuted = newMuted)
        mixerEngine.isVocalMuted = newMuted
    }

    fun toggleKaraokeMute() {
        val current = _karaokeTrack.value ?: return
        val newMuted = !current.isMuted
        _karaokeTrack.value = current.copy(isMuted = newMuted)
        mixerEngine.isKaraokeMuted = newMuted
    }

    fun toggleVocalSolo() {
        val current = _vocalTrack.value ?: return
        val newSolo = !current.isSoloed
        _vocalTrack.value = current.copy(isSoloed = newSolo)
        mixerEngine.isVocalSolo = newSolo
    }

    fun toggleKaraokeSolo() {
        val current = _karaokeTrack.value ?: return
        val newSolo = !current.isSoloed
        _karaokeTrack.value = current.copy(isSoloed = newSolo)
        mixerEngine.isKaraokeSolo = newSolo
    }

    fun setVocalGain(gain: Float) {
        val current = _vocalTrack.value ?: return
        val clamped = gain.coerceIn(0f, 1.5f)
        _vocalTrack.value = current.copy(gain = clamped)
        mixerEngine.vocalGain = clamped
    }

    fun setKaraokeGain(gain: Float) {
        val current = _karaokeTrack.value ?: return
        val clamped = gain.coerceIn(0f, 1.5f)
        _karaokeTrack.value = current.copy(gain = clamped)
        mixerEngine.karaokeGain = clamped
    }

    fun setPlaybackMode(mode: PlaybackMode) {
        _playbackMode.value = mode
        val voc = _vocalTrack.value
        val kar = _karaokeTrack.value

        when (mode) {
            PlaybackMode.FULL_MIX -> {
                _vocalTrack.value = voc?.copy(isMuted = false, isSoloed = false)
                _karaokeTrack.value = kar?.copy(isMuted = false, isSoloed = false)
                mixerEngine.isVocalMuted = false
                mixerEngine.isKaraokeMuted = false
                mixerEngine.isVocalSolo = false
                mixerEngine.isKaraokeSolo = false
            }
            PlaybackMode.KARAOKE_ONLY -> {
                _vocalTrack.value = voc?.copy(isMuted = true, isSoloed = false)
                _karaokeTrack.value = kar?.copy(isMuted = false, isSoloed = false)
                mixerEngine.isVocalMuted = true
                mixerEngine.isKaraokeMuted = false
                mixerEngine.isVocalSolo = false
                mixerEngine.isKaraokeSolo = false
            }
            PlaybackMode.VOCALS_ONLY -> {
                _vocalTrack.value = voc?.copy(isMuted = false, isSoloed = false)
                _karaokeTrack.value = kar?.copy(isMuted = true, isSoloed = false)
                mixerEngine.isVocalMuted = false
                mixerEngine.isKaraokeMuted = true
                mixerEngine.isVocalSolo = false
                mixerEngine.isKaraokeSolo = false
            }
        }
    }

    fun exportStems(exportType: ExportType, onExportComplete: (Intent?) -> Unit) {
        viewModelScope.launch {
            _isExporting.value = true
            _errorMessage.value = null
            try {
                val title = _currentMetadata.value?.title ?: "Demucs_Stem"
                val result = exporter.exportStems(
                    title,
                    exportType,
                    _vocalTrack.value,
                    _karaokeTrack.value
                )
                _statusToast.value = "Exported ${result.exportedFiles.size} lossless WAV file(s)"
                _showExportDialog.value = false
                onExportComplete(result.shareIntent)
            } catch (e: Exception) {
                Log.e(TAG, "Export failed", e)
                _errorMessage.value = "Export Failed: ${e.localizedMessage ?: e.javaClass.simpleName}"
            } finally {
                _isExporting.value = false
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        mixerEngine.release()
        separationEngine.release()
    }
}
