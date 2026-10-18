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
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.audio.model.PlaybackMode
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
fun TransportControlBar(
    isPlaying: Boolean,
    currentPositionMs: Long,
    totalDurationMs: Long,
    playbackProgress: Float,
    playbackMode: PlaybackMode,
    onTogglePlayPause: () -> Unit,
    onSeek: (Float) -> Unit,
    onReplay: () -> Unit,
    onSelectMode: (PlaybackMode) -> Unit,
    onOpenExport: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .border(1.dp, StudioCardBorder, RoundedCornerShape(16.dp))
            .testTag("transport_control_bar"),
        color = StudioSurfaceElevated
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp)
        ) {
            // Master Scrub Timeline Slider
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = formatTimecode(currentPositionMs),
                    color = StudioTextPrimary,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.testTag("current_timecode")
                )

                Slider(
                    value = playbackProgress,
                    onValueChange = onSeek,
                    valueRange = 0f..1f,
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 8.dp)
                        .testTag("master_scrub_slider"),
                    colors = SliderDefaults.colors(
                        thumbColor = StudioLedGreen,
                        activeTrackColor = StudioLedGreen,
                        inactiveTrackColor = StudioCardBorder
                    )
                )

                Text(
                    text = formatTimecode(totalDurationMs),
                    color = StudioTextSecondary,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.testTag("total_timecode")
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Transport Actions & Channel Presets
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Replay & Play/Pause buttons
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = onReplay,
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(StudioSurface)
                            .testTag("replay_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Replay,
                            contentDescription = "Replay",
                            tint = StudioTextSecondary,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    // Large glowing Play/Pause button
                    Box(
                        modifier = Modifier
                            .size(52.dp)
                            .clip(CircleShape)
                            .background(if (isPlaying) StudioLedGreen else VocalCyan)
                            .clickable { onTogglePlayPause() }
                            .testTag("play_pause_button"),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (isPlaying) "Pause" else "Play",
                            tint = Color.Black,
                            modifier = Modifier.size(30.dp)
                        )
                    }
                }

                // Channel Presets Selector: FULL MIX / KARAOKE / VOCAL
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(StudioBackground)
                        .padding(2.dp)
                        .border(1.dp, StudioCardBorder, RoundedCornerShape(8.dp)),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ModePill(
                        label = "MIX",
                        isSelected = playbackMode == PlaybackMode.FULL_MIX,
                        activeColor = StudioLedGreen,
                        onClick = { onSelectMode(PlaybackMode.FULL_MIX) },
                        modifier = Modifier.testTag("mode_mix_pill")
                    )
                    ModePill(
                        label = "KARAOKE",
                        isSelected = playbackMode == PlaybackMode.KARAOKE_ONLY,
                        activeColor = KaraokeAmber,
                        onClick = { onSelectMode(PlaybackMode.KARAOKE_ONLY) },
                        modifier = Modifier.testTag("mode_karaoke_pill")
                    )
                    ModePill(
                        label = "VOCAL",
                        isSelected = playbackMode == PlaybackMode.VOCALS_ONLY,
                        activeColor = VocalCyan,
                        onClick = { onSelectMode(PlaybackMode.VOCALS_ONLY) },
                        modifier = Modifier.testTag("mode_vocal_pill")
                    )
                }

                // Export Button
                IconButton(
                    onClick = onOpenExport,
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(StudioSurface)
                        .testTag("export_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.FileDownload,
                        contentDescription = "Export Stems",
                        tint = StudioTextPrimary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun ModePill(
    label: String,
    isSelected: Boolean,
    activeColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(if (isSelected) activeColor else Color.Transparent)
            .clickable { onClick() }
            .padding(horizontal = 8.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = if (isSelected) Color.Black else StudioTextSecondary,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace
        )
    }
}

fun formatTimecode(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    val millis = (ms % 1000) / 10
    return String.format("%02d:%02d.%02d", minutes, seconds, millis)
}
