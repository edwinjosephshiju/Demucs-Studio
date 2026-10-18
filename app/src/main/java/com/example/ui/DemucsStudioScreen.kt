package com.example.ui

import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.audio.model.SeparationStage
import com.example.ui.components.ExportStemsDialog
import com.example.ui.components.ModelStatusCard
import com.example.ui.components.SeparationProgressDialog
import com.example.ui.components.TransportControlBar
import com.example.ui.components.WaveformCanvas
import com.example.ui.components.WaveformTrackLane
import com.example.ui.theme.KaraokeAmber
import com.example.ui.theme.StudioBackground
import com.example.ui.theme.StudioCardBorder
import com.example.ui.theme.StudioLedGreen
import com.example.ui.theme.StudioMuteRed
import com.example.ui.theme.StudioSurface
import com.example.ui.theme.StudioSurfaceElevated
import com.example.ui.theme.StudioTextMuted
import com.example.ui.theme.StudioTextPrimary
import com.example.ui.theme.StudioTextSecondary
import com.example.ui.theme.VocalCyan

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DemucsStudioScreen(
    viewModel: AudioSeparationViewModel
) {
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }

    val modelState by viewModel.modelState.collectAsState()
    val separationProgress by viewModel.separationProgress.collectAsState()
    val currentMetadata by viewModel.currentMetadata.collectAsState()
    val originalPeaks by viewModel.originalPeaks.collectAsState()
    val vocalTrack by viewModel.vocalTrack.collectAsState()
    val karaokeTrack by viewModel.karaokeTrack.collectAsState()
    val isPlaying by viewModel.isPlaying.collectAsState()
    val playbackProgress by viewModel.playbackProgress.collectAsState()
    val currentPositionMs by viewModel.currentPositionMs.collectAsState()
    val vocalVuLevel by viewModel.vocalVuLevel.collectAsState()
    val karaokeVuLevel by viewModel.karaokeVuLevel.collectAsState()
    val playbackMode by viewModel.playbackMode.collectAsState()
    val isDecoding by viewModel.isDecoding.collectAsState()
    val decodeProgress by viewModel.decodeProgress.collectAsState()
    val statusToast by viewModel.statusToast.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()
    val showExportDialog by viewModel.showExportDialog.collectAsState()
    val isExporting by viewModel.isExporting.collectAsState()
    val currentBackend by viewModel.currentBackend.collectAsState()

    // SAF Audio Picker
    val audioPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            val fileName = queryFileName(context.contentResolver, it) ?: "Selected_Audio"
            viewModel.loadAudioFromUri(it, fileName)
        }
    }

    // SAF ONNX Model Picker
    val modelPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { viewModel.importModel(it) }
    }

    LaunchedEffect(statusToast) {
        statusToast?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            viewModel.clearStatusToast()
        }
    }

    // Separation Progress Modal (Active only during actual inference/processing)
    if (separationProgress.stage != SeparationStage.IDLE && 
        separationProgress.stage != SeparationStage.READY && 
        separationProgress.stage != SeparationStage.ERROR) {
        SeparationProgressDialog(
            progress = separationProgress,
            onCancel = { viewModel.cancelSeparation() }
        )
    }

    // Export Dialog
    if (showExportDialog) {
        ExportStemsDialog(
            isExporting = isExporting,
            onDismiss = { viewModel.closeExportDialog() },
            onExport = { exportType ->
                viewModel.exportStems(exportType) { shareIntent ->
                    shareIntent?.let {
                        context.startActivity(Intent.createChooser(it, "Share Stems"))
                    }
                }
            }
        )
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = StudioBackground,
        contentWindowInsets = WindowInsets.safeDrawing,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(VocalCyan.copy(alpha = 0.2f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.GraphicEq,
                                contentDescription = "Demucs Studio",
                                tint = VocalCyan,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "DEMUCS STUDIO",
                                color = StudioTextPrimary,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                                letterSpacing = 1.sp
                            )
                            Text(
                                text = "V4 FT ONNX STEM SEPARATOR",
                                color = VocalCyan,
                                fontSize = 9.sp,
                                fontFamily = FontFamily.Monospace,
                                letterSpacing = 0.5.sp
                            )
                        }
                    }
                },
                actions = {
                    Button(
                        onClick = { audioPickerLauncher.launch("audio/*") },
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = VocalCyan),
                        modifier = Modifier
                            .padding(end = 8.dp)
                            .testTag("load_audio_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.UploadFile,
                            contentDescription = "Load Audio",
                            tint = Color.Black,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Load Audio", color = Color.Black, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = StudioSurface,
                    titleContentColor = StudioTextPrimary
                )
            )
        },
        bottomBar = {
            // Transport Dock is active once stems are separated
            if (vocalTrack != null && karaokeTrack != null) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    color = Color.Transparent
                ) {
                    TransportControlBar(
                        isPlaying = isPlaying,
                        currentPositionMs = currentPositionMs,
                        totalDurationMs = currentMetadata?.durationMs ?: 0L,
                        playbackProgress = playbackProgress,
                        playbackMode = playbackMode,
                        onTogglePlayPause = { viewModel.togglePlayPause() },
                        onSeek = { viewModel.seekTo(it) },
                        onReplay = { viewModel.seekTo(0f) },
                        onSelectMode = { viewModel.setPlaybackMode(it) },
                        onOpenExport = { viewModel.openExportDialog() }
                    )
                }
            }
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(top = 8.dp, bottom = 12.dp)
        ) {
            // Error Message Banner (Fail Fast Display)
            errorMessage?.let { err ->
                item {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .border(1.dp, StudioMuteRed, RoundedCornerShape(8.dp)),
                        color = StudioMuteRed.copy(alpha = 0.15f)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.ErrorOutline,
                                contentDescription = "Error",
                                tint = StudioMuteRed,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = err,
                                color = StudioTextPrimary,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(onClick = { viewModel.clearErrorMessage() }) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Dismiss",
                                    tint = StudioTextSecondary,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                }
            }

            // Real Decoding & Resampling Progress Banner
            if (isDecoding) {
                item {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .border(1.dp, StudioCardBorder, RoundedCornerShape(8.dp)),
                        color = StudioSurfaceElevated
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "DECODING & RESAMPLING 44.1kHz STEREO...",
                                    color = StudioTextPrimary,
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold
                                )
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    color = VocalCyan,
                                    strokeWidth = 2.dp
                                )
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                            LinearProgressIndicator(
                                progress = { decodeProgress },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(4.dp)
                                    .clip(RoundedCornerShape(2.dp)),
                                color = VocalCyan,
                                trackColor = StudioBackground
                            )
                        }
                    }
                }
            }

            // Zero State: Strict Gatekeeping (No Demo Data)
            if (currentMetadata == null) {
                item {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .border(1.dp, StudioCardBorder, RoundedCornerShape(12.dp)),
                        color = StudioSurfaceElevated
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(48.dp)
                                    .clip(CircleShape)
                                    .background(StudioSurface),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.AudioFile,
                                    contentDescription = "No Audio Loaded",
                                    tint = StudioTextSecondary,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                            Spacer(modifier = Modifier.height(10.dp))
                            Text(
                                text = "ACTION REQUIRED: LOAD AUDIO FILE",
                                color = StudioTextPrimary,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                                letterSpacing = 1.sp
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "Select any local audio file (MP3, WAV, M4A) via the Storage Access Framework. The audio will be decoded to 44.1kHz stereo PCM on-device.",
                                color = StudioTextSecondary,
                                fontSize = 11.sp,
                                lineHeight = 16.sp
                            )
                            Spacer(modifier = Modifier.height(14.dp))
                            Button(
                                onClick = { audioPickerLauncher.launch("audio/*") },
                                colors = ButtonDefaults.buttonColors(containerColor = VocalCyan),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.UploadFile,
                                    contentDescription = "Select Audio File",
                                    tint = Color.Black,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Select Audio File", color = Color.Black, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }

            // Current Loaded Track Banner
            currentMetadata?.let { meta ->
                item {
                    val canSeparate = modelState.isReady && !isDecoding
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .border(1.dp, StudioCardBorder, RoundedCornerShape(10.dp)),
                        color = StudioSurface
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = meta.title,
                                        color = StudioTextPrimary,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1
                                    )
                                    Text(
                                        text = "${meta.durationMs / 1000}s • 44.1 kHz • Stereo 16-bit PCM • ${meta.fileSizeFormatted}",
                                        color = StudioTextSecondary,
                                        fontSize = 10.sp,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }

                                Button(
                                    onClick = { viewModel.startSeparation() },
                                    enabled = canSeparate,
                                    shape = RoundedCornerShape(8.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = VocalCyan,
                                        disabledContainerColor = StudioSurfaceElevated
                                    ),
                                    modifier = Modifier.testTag("start_separation_button")
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.AutoAwesome,
                                        contentDescription = "Separate Stems",
                                        tint = if (canSeparate) Color.Black else StudioTextMuted,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "Separate Stems",
                                        color = if (canSeparate) Color.Black else StudioTextMuted,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }

                            if (!modelState.isReady) {
                                Spacer(modifier = Modifier.height(8.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Default.Info,
                                        contentDescription = "Notice",
                                        tint = KaraokeAmber,
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "Download or import Demucs v4 ONNX model (>50MB) below to enable separation.",
                                        color = KaraokeAmber,
                                        fontSize = 10.sp,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }
                            }
                        }
                    }
                }

                // Show source preview waveform if separation has not run yet
                if (vocalTrack == null || karaokeTrack == null) {
                    item {
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .border(1.dp, StudioCardBorder, RoundedCornerShape(10.dp)),
                            color = StudioSurfaceElevated
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(
                                    text = "SOURCE AUDIO WAVEFORM OVERVIEW",
                                    color = StudioTextSecondary,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(80.dp)
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(StudioBackground)
                                        .border(1.dp, StudioCardBorder, RoundedCornerShape(6.dp))
                                ) {
                                    WaveformCanvas(
                                        peaks = originalPeaks,
                                        playbackProgress = 0f,
                                        accentColor = StudioTextPrimary,
                                        isMuted = false
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Dual DAW-Style Waveform Track Lanes
            // Track 1: Vocals
            vocalTrack?.let { voc ->
                item {
                    WaveformTrackLane(
                        trackState = voc,
                        playbackProgress = playbackProgress,
                        vuLevel = vocalVuLevel,
                        accentColor = VocalCyan,
                        onSeek = { viewModel.seekTo(it) },
                        onToggleMute = { viewModel.toggleVocalMute() },
                        onToggleSolo = { viewModel.toggleVocalSolo() },
                        onGainChange = { viewModel.setVocalGain(it) }
                    )
                }
            }

            // Track 2: Karaoke (Instrumental)
            karaokeTrack?.let { kar ->
                item {
                    WaveformTrackLane(
                        trackState = kar,
                        playbackProgress = playbackProgress,
                        vuLevel = karaokeVuLevel,
                        accentColor = KaraokeAmber,
                        onSeek = { viewModel.seekTo(it) },
                        onToggleMute = { viewModel.toggleKaraokeMute() },
                        onToggleSolo = { viewModel.toggleKaraokeSolo() },
                        onGainChange = { viewModel.setKaraokeGain(it) }
                    )
                }
            }

            // Model Status, Preset, Karaoke Method & Hardware Backend Card
            item {
                ModelStatusCard(
                    modelState = modelState,
                    currentBackend = currentBackend,
                    onSelectBackend = { viewModel.setExecutionBackend(it) },
                    onSelectPreset = { viewModel.selectModelPreset(it) },
                    onSelectKaraokeMode = { viewModel.selectKaraokeMode(it) },
                    onDownloadModel = { viewModel.downloadModel(it) },
                    onImportLocalModel = { modelPickerLauncher.launch("*/*") }
                )
            }
        }
    }
}

private fun queryFileName(resolver: android.content.ContentResolver, uri: Uri): String? {
    return try {
        resolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (nameIndex != -1 && cursor.moveToFirst()) {
                cursor.getString(nameIndex)
            } else null
        }
    } catch (_: Exception) {
        null
    }
}
