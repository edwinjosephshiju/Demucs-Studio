package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.clickable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.audio.model.ExecutionBackend
import com.example.audio.model.KaraokeMode
import com.example.audio.model.ModelPreset
import com.example.audio.model.ModelState
import com.example.ui.theme.KaraokeAmber
import com.example.ui.theme.StudioBackground
import com.example.ui.theme.StudioCardBorder
import com.example.ui.theme.StudioLedGreen
import com.example.ui.theme.StudioSurface
import com.example.ui.theme.StudioSurfaceElevated
import com.example.ui.theme.StudioTextMuted
import com.example.ui.theme.StudioTextPrimary
import com.example.ui.theme.StudioTextSecondary
import com.example.ui.theme.VocalCyan

@Composable
fun ModelStatusCard(
    modelState: ModelState,
    currentBackend: ExecutionBackend,
    onSelectBackend: (ExecutionBackend) -> Unit,
    onSelectPreset: (ModelPreset) -> Unit,
    onSelectKaraokeMode: (KaraokeMode) -> Unit,
    onDownloadModel: (ModelPreset) -> Unit,
    onImportLocalModel: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .border(1.dp, StudioCardBorder, RoundedCornerShape(12.dp))
            .testTag("model_status_card"),
        color = StudioSurfaceElevated
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(if (modelState.isReady) StudioLedGreen.copy(alpha = 0.2f) else KaraokeAmber.copy(alpha = 0.2f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (modelState.isReady) Icons.Default.Verified else Icons.Default.Memory,
                            contentDescription = "Model Status",
                            tint = if (modelState.isReady) StudioLedGreen else KaraokeAmber,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = "DEMUCS V4 FT ONNX ENGINE",
                            color = StudioTextPrimary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                        Text(
                            text = modelState.statusMessage,
                            color = if (modelState.isReady) StudioLedGreen else StudioTextSecondary,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }

                if (modelState.isDownloading) {
                    CircularProgressIndicator(
                        progress = { modelState.downloadProgress },
                        modifier = Modifier.size(24.dp),
                        color = VocalCyan,
                        strokeWidth = 2.dp
                    )
                }
            }

            AnimatedVisibility(visible = modelState.isDownloading) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp)
                ) {
                    LinearProgressIndicator(
                        progress = { modelState.downloadProgress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp)),
                        color = VocalCyan,
                        trackColor = StudioBackground
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "${(modelState.downloadProgress * 100).toInt()}% (${modelState.downloadedBytes / (1024 * 1024)}MB / ${modelState.totalBytes / (1024 * 1024)}MB)",
                            color = StudioTextSecondary,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace
                        )
                        Text(
                            text = modelState.downloadSpeedText,
                            color = VocalCyan,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Model Architecture Selector
            Text(
                text = "DEMUCS MODEL PRESET",
                color = StudioTextSecondary,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 0.5.sp
            )
            Spacer(modifier = Modifier.height(6.dp))

            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                ModelPreset.values().forEach { preset ->
                    val isSelected = modelState.activePreset == preset
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .border(
                                1.dp,
                                if (isSelected) VocalCyan else StudioCardBorder,
                                RoundedCornerShape(8.dp)
                            )
                            .clickable { onSelectPreset(preset) }
                            .padding(8.dp),
                        color = if (isSelected) VocalCyan.copy(alpha = 0.12f) else StudioSurface
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = preset.displayName,
                                    color = if (isSelected) VocalCyan else StudioTextPrimary,
                                    fontSize = 11.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                    fontFamily = FontFamily.Monospace
                                )
                                Text(
                                    text = preset.description,
                                    color = StudioTextMuted,
                                    fontSize = 9.sp,
                                    fontFamily = FontFamily.Monospace,
                                    maxLines = 2
                                )
                            }
                        }
                    }
                }
            }

            if (!modelState.isReady && !modelState.isDownloading) {
                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { onDownloadModel(modelState.activePreset) },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("download_model_button"),
                        colors = ButtonDefaults.buttonColors(containerColor = VocalCyan),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.CloudDownload,
                            contentDescription = "Download",
                            tint = Color.Black,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Download ${if (modelState.activePreset == ModelPreset.HTDEMUCS_4STEM) "4-Stem" else "Vocals"} ONNX",
                            color = Color.Black,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    OutlinedButton(
                        onClick = onImportLocalModel,
                        modifier = Modifier.testTag("import_model_button"),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.FolderOpen,
                            contentDescription = "Import",
                            tint = StudioTextPrimary,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Import .onnx", color = StudioTextPrimary, fontSize = 11.sp)
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Karaoke Extraction Mode Selector
            Text(
                text = "KARAOKE EXTRACTION METHOD",
                color = StudioTextSecondary,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 0.5.sp
            )
            Spacer(modifier = Modifier.height(6.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                KaraokeMode.values().forEach { mode ->
                    val isSelected = modelState.karaokeMode == mode
                    val isEnabled = !(mode == KaraokeMode.NEURAL_SUM && modelState.activePreset.isVocalsOnlyTrained)

                    Surface(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .border(
                                1.dp,
                                if (isSelected) KaraokeAmber else StudioCardBorder,
                                RoundedCornerShape(8.dp)
                            )
                            .clickable(enabled = isEnabled) { onSelectKaraokeMode(mode) }
                            .padding(8.dp),
                        color = when {
                            !isEnabled -> StudioSurface.copy(alpha = 0.5f)
                            isSelected -> KaraokeAmber.copy(alpha = 0.15f)
                            else -> StudioSurface
                        }
                    ) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                text = if (mode == KaraokeMode.RESIDUAL_SUBTRACTION) "Residual (Orig - Voc)" else "Neural Sum",
                                color = when {
                                    !isEnabled -> StudioTextMuted
                                    isSelected -> KaraokeAmber
                                    else -> StudioTextPrimary
                                },
                                fontSize = 10.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                fontFamily = FontFamily.Monospace,
                                maxLines = 1
                            )
                            Text(
                                text = if (!isEnabled) "Requires 4-Stem" else if (mode == KaraokeMode.RESIDUAL_SUBTRACTION) "Phase-perfect" else "Sum of 3 stems",
                                color = StudioTextMuted,
                                fontSize = 8.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Execution Hardware Backend Selector
            Text(
                text = "EXECUTION HARDWARE BACKEND",
                color = StudioTextSecondary,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 0.5.sp
            )
            Spacer(modifier = Modifier.height(6.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("backend_selector"),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                ExecutionBackend.values().forEach { backend ->
                    val isSelected = currentBackend == backend
                    Surface(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .border(
                                1.dp,
                                if (isSelected) VocalCyan else StudioCardBorder,
                                RoundedCornerShape(8.dp)
                            )
                            .clickable { onSelectBackend(backend) }
                            .padding(vertical = 8.dp, horizontal = 4.dp),
                        color = if (isSelected) VocalCyan.copy(alpha = 0.15f) else StudioSurface
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = backend.label,
                                color = if (isSelected) VocalCyan else StudioTextPrimary,
                                fontSize = 10.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                fontFamily = FontFamily.Monospace,
                                maxLines = 1
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = currentBackend.subtitle,
                color = StudioTextMuted,
                fontSize = 9.sp,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}
