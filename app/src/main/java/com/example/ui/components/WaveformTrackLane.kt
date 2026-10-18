package com.example.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.audio.model.StemTrackState
import com.example.audio.model.StemType
import com.example.ui.theme.StudioBackground
import com.example.ui.theme.StudioCardBorder
import com.example.ui.theme.StudioLedGreen
import com.example.ui.theme.StudioMuteRed
import com.example.ui.theme.StudioSoloYellow
import com.example.ui.theme.StudioSurface
import com.example.ui.theme.StudioSurfaceElevated
import com.example.ui.theme.StudioTextMuted
import com.example.ui.theme.StudioTextPrimary
import com.example.ui.theme.StudioTextSecondary
import kotlin.math.abs
import kotlin.math.max

@Composable
fun WaveformTrackLane(
    trackState: StemTrackState,
    playbackProgress: Float,
    vuLevel: Float,
    accentColor: Color,
    onSeek: (Float) -> Unit,
    onToggleMute: () -> Unit,
    onToggleSolo: () -> Unit,
    onGainChange: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val isVocal = trackState.stemType == StemType.VOCAL
    val trackTag = if (isVocal) "vocal" else "karaoke"

    val animatedVu by animateFloatAsState(
        targetValue = vuLevel.coerceIn(0f, 1f),
        label = "vuMeter"
    )

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .border(
                width = 1.dp,
                color = if (trackState.isSoloed) StudioSoloYellow else if (trackState.isMuted) StudioMuteRed.copy(alpha = 0.5f) else StudioCardBorder,
                shape = RoundedCornerShape(12.dp)
            )
            .testTag("${trackTag}_track_lane"),
        color = StudioSurfaceElevated
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            // Track Header: Icon, Name, Mute/Solo, Fader, VU Meter
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Title & Icon
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(accentColor.copy(alpha = 0.2f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (isVocal) Icons.Default.Mic else Icons.Default.MusicNote,
                            contentDescription = trackState.title,
                            tint = accentColor,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(
                            text = trackState.title.uppercase(),
                            color = StudioTextPrimary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            letterSpacing = 1.sp
                        )
                        Text(
                            text = if (trackState.isMuted) "MUTED" else if (trackState.isSoloed) "SOLO ACTIVE" else "GAIN: ${(trackState.gain * 100).toInt()}%",
                            color = if (trackState.isMuted) StudioMuteRed else if (trackState.isSoloed) StudioSoloYellow else StudioTextSecondary,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }

                // Controls: Mute, Solo, VU Meter
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Solo Button
                    StudioLedButton(
                        text = "S",
                        isActive = trackState.isSoloed,
                        activeColor = StudioSoloYellow,
                        onClick = onToggleSolo,
                        modifier = Modifier.testTag("${trackTag}_solo_btn")
                    )

                    Spacer(modifier = Modifier.width(6.dp))

                    // Mute Button
                    StudioLedButton(
                        text = "M",
                        isActive = trackState.isMuted,
                        activeColor = StudioMuteRed,
                        onClick = onToggleMute,
                        modifier = Modifier.testTag("${trackTag}_mute_btn")
                    )

                    Spacer(modifier = Modifier.width(8.dp))

                    // Horizontal VU Meter Bar
                    VuMeterLedBar(
                        level = animatedVu,
                        isMuted = trackState.isMuted,
                        modifier = Modifier
                            .width(52.dp)
                            .height(14.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Interactive Waveform Canvas
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(90.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(StudioBackground)
                    .border(1.dp, StudioCardBorder.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
                    .pointerInput(trackState) {
                        detectTapGestures { offset ->
                            val fraction = (offset.x / size.width.toFloat()).coerceIn(0f, 1f)
                            onSeek(fraction)
                        }
                    }
                    .pointerInput(trackState) {
                        detectDragGestures { change, _ ->
                            change.consume()
                            val fraction = (change.position.x / size.width.toFloat()).coerceIn(0f, 1f)
                            onSeek(fraction)
                        }
                    }
                    .testTag("${trackTag}_waveform_canvas")
            ) {
                WaveformCanvas(
                    peaks = trackState.waveformPeaks,
                    playbackProgress = playbackProgress,
                    accentColor = if (trackState.isMuted) StudioTextMuted else accentColor,
                    isMuted = trackState.isMuted
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            // Volume Gain Slider
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = if (trackState.isMuted || trackState.gain == 0f) Icons.Default.VolumeOff else Icons.Default.VolumeUp,
                    contentDescription = "Gain",
                    tint = StudioTextSecondary,
                    modifier = Modifier.size(16.dp)
                )
                Slider(
                    value = trackState.gain,
                    onValueChange = onGainChange,
                    valueRange = 0f..1.5f,
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 4.dp)
                        .testTag("${trackTag}_gain_slider"),
                    colors = SliderDefaults.colors(
                        thumbColor = accentColor,
                        activeTrackColor = accentColor,
                        inactiveTrackColor = StudioCardBorder
                    )
                )
                Text(
                    text = String.format("%.1fx", trackState.gain),
                    color = StudioTextSecondary,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.width(36.dp)
                )
            }
        }
    }
}

@Composable
fun StudioLedButton(
    text: String,
    isActive: Boolean,
    activeColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val bgColor by animateColorAsState(
        targetValue = if (isActive) activeColor else StudioSurface,
        label = "btnBg"
    )
    val textColor = if (isActive) Color.Black else StudioTextSecondary

    Box(
        modifier = modifier
            .size(32.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(bgColor)
            .border(
                width = 1.dp,
                color = if (isActive) activeColor else StudioCardBorder,
                shape = RoundedCornerShape(6.dp)
            )
            .pointerInput(Unit) {
                detectTapGestures { onClick() }
            },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = textColor,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace
        )
    }
}

@Composable
fun VuMeterLedBar(
    level: Float,
    isMuted: Boolean,
    modifier: Modifier = Modifier
) {
    val segmentCount = 10
    val activeSegments = if (isMuted) 0 else (level * segmentCount).toInt().coerceIn(0, segmentCount)

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(3.dp))
            .background(StudioBackground)
            .padding(1.dp),
        horizontalArrangement = Arrangement.spacedBy(1.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        for (i in 0 until segmentCount) {
            val isActive = i < activeSegments
            val segColor = when {
                !isActive -> StudioCardBorder.copy(alpha = 0.4f)
                i >= 8 -> StudioMuteRed
                i >= 6 -> StudioSoloYellow
                else -> StudioLedGreen
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(1.dp))
                    .background(segColor)
            )
        }
    }
}

@Composable
fun WaveformCanvas(
    peaks: FloatArray,
    playbackProgress: Float,
    accentColor: Color,
    isMuted: Boolean,
    modifier: Modifier = Modifier
) {
    Canvas(
        modifier = modifier.fillMaxSize()
    ) {
        val width = size.width
        val height = size.height
        val centerY = height / 2f

        // Draw centerline grid
        drawLine(
            color = Color(0x22FFFFFF),
            start = Offset(0f, centerY),
            end = Offset(width, centerY),
            strokeWidth = 1f
        )

        val totalBars = peaks.size.coerceAtLeast(1)
        val barWidth = max(1f, width / totalBars.toFloat())
        val progressX = width * playbackProgress.coerceIn(0f, 1f)

        // Draw waveform peaks
        for (i in 0 until totalBars) {
            val peak = if (peaks.isNotEmpty()) peaks[i].coerceIn(0f, 1f) else 0.1f
            val x = i * barWidth
            val barHeight = max(2f, peak * (height * 0.88f))
            val top = centerY - barHeight / 2f
            val bottom = centerY + barHeight / 2f

            val isPlayed = x <= progressX
            val barColor = if (isPlayed) {
                accentColor
            } else {
                accentColor.copy(alpha = if (isMuted) 0.15f else 0.4f)
            }

            drawLine(
                color = barColor,
                start = Offset(x, top),
                end = Offset(x, bottom),
                strokeWidth = max(1f, barWidth * 0.7f),
                cap = StrokeCap.Round
            )
        }

        // Draw Glowing Playhead
        drawLine(
            color = Color.White,
            start = Offset(progressX, 0f),
            end = Offset(progressX, height),
            strokeWidth = 2.5f
        )

        // Glowing indicator circle at top and bottom of playhead
        drawCircle(
            color = accentColor,
            radius = 4f,
            center = Offset(progressX, 4f)
        )
        drawCircle(
            color = accentColor,
            radius = 4f,
            center = Offset(progressX, height - 4f)
        )
    }
}
