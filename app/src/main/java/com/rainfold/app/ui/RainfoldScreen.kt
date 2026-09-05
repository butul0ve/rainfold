package com.rainfold.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import com.rainfold.app.R
import com.rainfold.app.domain.NoiseType
import com.rainfold.app.domain.PlaybackState

@Composable
fun RainfoldScreen(
    state: PlaybackScreenState,
    onSelect: (NoiseType) -> Unit,
    onPlaybackToggle: () -> Unit,
    onVolumeChange: (Float) -> Unit,
) {
    var darkTheme by rememberSaveable { mutableStateOf(true) }
    RainfoldTheme(darkTheme = darkTheme) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                MaterialTheme.colorScheme.surface,
                                MaterialTheme.colorScheme.surfaceVariant,
                            ),
                        ),
                    )
                    .padding(horizontal = RainfoldTokens.size16),
            ) {
                AppHeader(
                    darkTheme,
                    { darkTheme = !darkTheme },
                    Modifier.align(Alignment.TopCenter)
                )
                PlaybackControls(
                    selected = state.mix.noiseType,
                    isPlaying = state.playback is PlaybackState.Playing,
                    enabled = state.connected,
                    onSelect = onSelect,
                    onPlaybackToggle = onPlaybackToggle,
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .padding(top = RainfoldTokens.size100),
                )
                if (state.audioFailed) {
                    Text(
                        stringResource(R.string.audio_error),
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(RainfoldTokens.size16),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                VerticalVolume(
                    volume = state.mix.noiseVolume,
                    onVolumeChange = onVolumeChange,
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .fillMaxHeight(),
                )
            }
        }
    }
}

@Composable
private fun AppHeader(
    darkTheme: Boolean,
    onThemeToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    val themeLabel = stringResource(if (darkTheme) R.string.light_theme else R.string.dark_theme)
    Box(modifier = modifier
        .fillMaxWidth()
        .padding(top = RainfoldTokens.size44)) {
        Column(
            modifier = Modifier.align(Alignment.TopCenter),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = stringResource(R.string.app_tagline),
                modifier = Modifier.padding(top = RainfoldTokens.size4),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        IconButton(
            onClick = onThemeToggle,
            modifier = Modifier
                .semantics { contentDescription = themeLabel }
                .align(Alignment.TopEnd)
                .size(RainfoldTokens.size32)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.secondaryContainer),
        ) {
            ThemeIcon(
                darkTheme = darkTheme,
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                cutoutColor = MaterialTheme.colorScheme.secondaryContainer,
                modifier = Modifier.size(RainfoldTokens.size18),
            )
        }
    }
}

@Composable
private fun PlaybackControls(
    selected: NoiseType,
    isPlaying: Boolean,
    enabled: Boolean,
    onSelect: (NoiseType) -> Unit,
    onPlaybackToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.width(RainfoldTokens.size168),
        verticalArrangement = Arrangement.spacedBy(RainfoldTokens.size18),
    ) {
        Text(
            text = stringResource(R.string.sound),
            modifier = Modifier.fillMaxWidth(),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.tertiary,
            textAlign = TextAlign.Center,
        )
        NoiseTypeDropdown(selected = selected, onSelect = onSelect)
        Button(
            onClick = onPlaybackToggle,
            enabled = enabled,
            modifier = Modifier
                .width(RainfoldTokens.size168)
                .height(RainfoldTokens.size56),
            shape = RainfoldTokens.controlShape,
            colors = ButtonDefaults.buttonColors(
                containerColor = if (isPlaying) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary,
                contentColor = if (isPlaying) MaterialTheme.colorScheme.onSecondary else MaterialTheme.colorScheme.onPrimary,
            ),
        ) {
            Text(
                text = stringResource(if (isPlaying) R.string.stop else R.string.play),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun NoiseTypeDropdown(selected: NoiseType, onSelect: (NoiseType) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(
            onClick = { expanded = true },
            modifier = Modifier
                .width(RainfoldTokens.size168)
                .height(RainfoldTokens.size56),
            shape = RainfoldTokens.controlShape,
            border = BorderStroke(
                width = RainfoldTokens.size1,
                brush = Brush.horizontalGradient(
                    listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.tertiary),
                ),
            ),
        ) {
            NoiseMarker(type = selected)
            Spacer(Modifier.width(RainfoldTokens.size10))
            Text(
                text = noiseTypeLabel(selected),
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.Start,
            )
            DropdownChevron(expanded = expanded, tint = MaterialTheme.colorScheme.primary)
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier
                .width(RainfoldTokens.size184)
                .background(
                    MaterialTheme.colorScheme.surfaceContainerHigh,
                    RainfoldTokens.menuShape
                ),
        ) {
            NoiseType.entries.forEach { type ->
                Button(
                    onClick = {
                        onSelect(type)
                        expanded = false
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(RainfoldTokens.size52)
                        .padding(horizontal = RainfoldTokens.size8),
                    shape = RainfoldTokens.menuItemShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (type == selected) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceContainerHigh
                        },
                        contentColor = if (type == selected) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                    ),
                ) {
                    NoiseMarker(type = type)
                    Spacer(Modifier.width(RainfoldTokens.size12))
                    Text(
                        text = noiseTypeLabel(type),
                        modifier = Modifier.weight(1f),
                        textAlign = TextAlign.Start,
                    )
                    if (type == selected) Text(
                        stringResource(R.string.noise_selected),
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
        }
    }
}

@Composable
private fun noiseTypeLabel(type: NoiseType): String = stringResource(
    when (type) {
        NoiseType.WHITE -> R.string.white_noise
        NoiseType.PINK -> R.string.pink_noise
        NoiseType.BROWN -> R.string.brown_noise
    }
)

@Composable
private fun NoiseMarker(type: NoiseType) {
    val color = when (type) {
        NoiseType.WHITE -> RainfoldTokens.whiteNoise
        NoiseType.PINK -> RainfoldTokens.pinkNoise
        NoiseType.BROWN -> RainfoldTokens.brownNoise
    }
    Canvas(modifier = Modifier.size(RainfoldTokens.size14)) {
        drawCircle(color = color)
        drawCircle(
            color = Color.White.copy(alpha = 0.55f),
            style = Stroke(width = RainfoldTokens.size1.toPx())
        )
    }
}

@Composable
private fun VerticalVolume(
    volume: Float,
    onVolumeChange: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val volumeLabel = stringResource(R.string.volume)
    BoxWithConstraints(modifier = modifier.width(RainfoldTokens.size68)) {
        val indicatorHeight = maxHeight * 0.5f
        Column(
            modifier = Modifier.align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(R.string.volume),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.tertiary,
            )
            Spacer(Modifier.height(RainfoldTokens.size18))
            BoxWithConstraints(
                modifier = Modifier
                    .height(indicatorHeight)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                Slider(
                    value = volume,
                    onValueChange = onVolumeChange,
                    modifier = Modifier
                        .semantics { contentDescription = volumeLabel }
                        .requiredWidth(maxHeight)
                        .graphicsLayer(rotationZ = -90f),
                    colors = SliderDefaults.colors(
                        thumbColor = MaterialTheme.colorScheme.primary,
                        activeTrackColor = MaterialTheme.colorScheme.primary,
                        inactiveTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    ),
                )
            }
            Text(
                text = stringResource(R.string.volume_percent, (volume * 100).toInt()),
                modifier = Modifier.padding(top = RainfoldTokens.size16),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun ThemeIcon(
    darkTheme: Boolean,
    tint: Color,
    cutoutColor: Color,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val radius = size.minDimension * 0.26f
        val center = Offset(size.width / 2f, size.height / 2f)
        if (darkTheme) {
            drawCircle(color = tint, radius = radius, center = center)
            repeat(8) { index ->
                val angle = Math.toRadians((index * 45).toDouble())
                val start = Offset(
                    center.x + kotlin.math.cos(angle).toFloat() * radius * 1.55f,
                    center.y + kotlin.math.sin(angle).toFloat() * radius * 1.55f,
                )
                val end = Offset(
                    center.x + kotlin.math.cos(angle).toFloat() * radius * 2.15f,
                    center.y + kotlin.math.sin(angle).toFloat() * radius * 2.15f,
                )
                drawLine(
                    tint,
                    start,
                    end,
                    strokeWidth = RainfoldTokens.size2.toPx(),
                    cap = StrokeCap.Round
                )
            }
        } else {
            drawCircle(color = tint, radius = radius, center = center)
            drawCircle(
                color = cutoutColor,
                radius = radius,
                center = Offset(center.x + radius * 0.45f, center.y - radius * 0.32f),
            )
        }
    }
}

@Composable
private fun DropdownChevron(expanded: Boolean, tint: Color) {
    Canvas(modifier = Modifier.size(RainfoldTokens.size18)) {
        val y = if (expanded) size.height * 0.62f else size.height * 0.4f
        val direction = if (expanded) -1f else 1f
        drawLine(
            color = tint,
            start = Offset(size.width * 0.22f, y),
            end = Offset(size.width * 0.5f, y + direction * size.height * 0.22f),
            strokeWidth = RainfoldTokens.size2.toPx(),
            cap = StrokeCap.Round,
        )
        drawLine(
            color = tint,
            start = Offset(size.width * 0.5f, y + direction * size.height * 0.22f),
            end = Offset(size.width * 0.78f, y),
            strokeWidth = RainfoldTokens.size2.toPx(),
            cap = StrokeCap.Round,
        )
    }
}