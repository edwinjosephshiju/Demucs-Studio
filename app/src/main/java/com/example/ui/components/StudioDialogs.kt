package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.audio.model.ExportType
import com.example.audio.model.SeparationProgress
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

@Composable
fun SeparationProgressDialog(
    progress: SeparationProgress,
    onCancel: () -> Unit
) {
    Dialog(onDismissRequest = { /* Don't dismiss by tap outside to prevent accidental abort */ }) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .border(1.dp, StudioCardBorder, RoundedCornerShape(16.dp))
                .testTag("separation_progress_dialog"),
            color = StudioSurfaceElevated
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "SEPARATING AUDIO STEMS",
                    color = StudioTextPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 1.sp
                )

                Spacer(modifier = Modifier.height(16.dp))

                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(CircleShape)
                        .background(VocalCyan.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(
                        progress = { progress.progress },
                        modifier = Modifier.size(54.dp),
                        color = VocalCyan,
                        strokeWidth = 4.dp
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                Text(
                    text = "${(progress.progress * 100).toInt()}%",
                    color = VocalCyan,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )

                Text(
                    text = progress.statusText,
                    color = StudioTextSecondary,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace
                )

                if (progress.totalChunks > 0) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Chunk ${progress.currentChunk} of ${progress.totalChunks} • ETA: ${progress.etaSeconds}s",
                        color = StudioTextMuted,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                // On-Screen ONNX Proof Telemetry HUD
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(StudioBackground)
                        .border(1.dp, StudioCardBorder, RoundedCornerShape(8.dp)),
                    color = StudioBackground
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "ONNX RUNTIME PROOF",
                                color = VocalCyan,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace
                            )
                            if (progress.activeBackend.isNotEmpty()) {
                                Text(
                                    text = progress.activeBackend,
                                    color = StudioLedGreen,
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        }

                        if (progress.inputNames.isNotEmpty() || progress.outputNames.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Nodes: in['${progress.inputNames}'] ➔ out['${progress.outputNames}']",
                                color = StudioTextSecondary,
                                fontSize = 9.sp,
                                fontFamily = FontFamily.Monospace,
                                maxLines = 1
                            )
                        }

                        if (progress.tensorShapeInfo.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(3.dp))
                            Text(
                                text = "Tensors: ${progress.tensorShapeInfo}",
                                color = StudioTextSecondary,
                                fontSize = 9.sp,
                                fontFamily = FontFamily.Monospace,
                                maxLines = 1
                            )
                        }

                        if (progress.lastChunkInferenceMs > 0) {
                            Spacer(modifier = Modifier.height(3.dp))
                            Text(
                                text = "Chunk Latency: ${progress.lastChunkInferenceMs} ms/chunk",
                                color = StudioLedGreen,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                OutlinedButton(
                    onClick = onCancel,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("cancel_separation_button"),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Cancel,
                        contentDescription = "Cancel",
                        tint = StudioMuteRed,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Cancel Separation", color = StudioMuteRed, fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
fun ExportStemsDialog(
    isExporting: Boolean,
    onDismiss: () -> Unit,
    onExport: (ExportType) -> Unit
) {
    var selectedType by remember { mutableStateOf(ExportType.BOTH_STEMS) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .border(1.dp, StudioCardBorder, RoundedCornerShape(16.dp))
                .testTag("export_stems_dialog"),
            color = StudioSurfaceElevated
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.FileDownload,
                        contentDescription = "Export",
                        tint = StudioLedGreen,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "EXPORT AUDIO STEMS",
                        color = StudioTextPrimary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = 1.sp
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = "Format: Broadcast Standard WAV 16-bit / 44.1kHz Stereo (Lossless)",
                    color = StudioTextSecondary,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace
                )

                Spacer(modifier = Modifier.height(14.dp))

                ExportOptionRow(
                    title = "Both Stems (Vocals & Karaoke)",
                    description = "Two synchronized lossless WAV files",
                    isSelected = selectedType == ExportType.BOTH_STEMS,
                    accentColor = StudioLedGreen,
                    onClick = { selectedType = ExportType.BOTH_STEMS },
                    testTag = "export_option_both"
                )

                Spacer(modifier = Modifier.height(8.dp))

                ExportOptionRow(
                    title = "Karaoke Only (Instrumental)",
                    description = "Backing track with vocals removed",
                    isSelected = selectedType == ExportType.KARAOKE_ONLY,
                    accentColor = KaraokeAmber,
                    onClick = { selectedType = ExportType.KARAOKE_ONLY },
                    testTag = "export_option_karaoke"
                )

                Spacer(modifier = Modifier.height(8.dp))

                ExportOptionRow(
                    title = "Vocals Only (Acapella)",
                    description = "Isolated lead and vocal harmonies",
                    isSelected = selectedType == ExportType.VOCAL_ONLY,
                    accentColor = VocalCyan,
                    onClick = { selectedType = ExportType.VOCAL_ONLY },
                    testTag = "export_option_vocals"
                )

                Spacer(modifier = Modifier.height(8.dp))

                ExportOptionRow(
                    title = "Custom Studio Mix",
                    description = "Rendered with current faders and mute/solo",
                    isSelected = selectedType == ExportType.MIXED_MASTER,
                    accentColor = StudioTextPrimary,
                    onClick = { selectedType = ExportType.MIXED_MASTER },
                    testTag = "export_option_mix"
                )

                Spacer(modifier = Modifier.height(18.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("Cancel", color = StudioTextSecondary, fontSize = 12.sp)
                    }

                    Button(
                        onClick = { onExport(selectedType) },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("confirm_export_button"),
                        enabled = !isExporting,
                        colors = ButtonDefaults.buttonColors(containerColor = StudioLedGreen),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        if (isExporting) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                color = Color.Black,
                                strokeWidth = 2.dp
                            )
                        } else {
                            Text("Export & Share", color = Color.Black, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ExportOptionRow(
    title: String,
    description: String,
    isSelected: Boolean,
    accentColor: Color,
    onClick: () -> Unit,
    testTag: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (isSelected) StudioSurface else Color.Transparent)
            .border(
                1.dp,
                if (isSelected) accentColor else StudioCardBorder,
                RoundedCornerShape(8.dp)
            )
            .clickable { onClick() }
            .padding(10.dp)
            .testTag(testTag),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(
            selected = isSelected,
            onClick = onClick,
            colors = RadioButtonDefaults.colors(selectedColor = accentColor)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Column {
            Text(
                text = title,
                color = if (isSelected) StudioTextPrimary else StudioTextSecondary,
                fontSize = 12.sp,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                fontFamily = FontFamily.Monospace
            )
            Text(
                text = description,
                color = StudioTextMuted,
                fontSize = 10.sp
            )
        }
    }
}
