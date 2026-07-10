package com.rainfold.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.rainfold.app.domain.NoiseMix
import com.rainfold.app.domain.NoiseType
import com.rainfold.app.player.NoisePlayer

class MainActivity : ComponentActivity() {
    private val player = NoisePlayer()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            var darkTheme by rememberSaveable { mutableStateOf(true) }
            RainfoldTheme(darkTheme = darkTheme) {
                var isPlaying by rememberSaveable { mutableStateOf(false) }
                var noiseType by rememberSaveable { mutableStateOf(NoiseType.PINK) }
                var volume by rememberSaveable { mutableStateOf(0.5f) }

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
                            .padding(horizontal = 16.dp),
                    ) {
                        AppHeader(
                            darkTheme = darkTheme,
                            onThemeToggle = { darkTheme = !darkTheme },
                            modifier = Modifier.align(Alignment.TopCenter),
                        )

                        PlaybackControls(
                            selected = noiseType,
                            isPlaying = isPlaying,
                            onSelect = { type ->
                                noiseType = type
                                if (isPlaying) {
                                    player.play(NoiseMix(noiseType = type, noiseVolume = volume))
                                }
                            },
                            onPlaybackToggle = {
                                if (isPlaying) {
                                    player.stop()
                                } else {
                                    player.play(NoiseMix(noiseType = noiseType, noiseVolume = volume))
                                }
                                isPlaying = !isPlaying
                            },
                            modifier = Modifier
                                .align(Alignment.CenterStart)
                                .padding(top = 100.dp),
                        )

                        VerticalVolume(
                            volume = volume,
                            onVolumeChange = {
                                volume = it
                                player.setVolume(it)
                            },
                            modifier = Modifier
                                .align(Alignment.CenterEnd)
                                .fillMaxHeight(),
                        )
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        player.stop()
        super.onDestroy()
    }
}

@Composable
private fun AppHeader(darkTheme: Boolean, onThemeToggle: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxWidth().padding(top = 44.dp)) {
        Column(
            modifier = Modifier.align(Alignment.TopCenter),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "Rainfold",
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = "Endless soundscapes for sleep and focus",
                modifier = Modifier.padding(top = 4.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        IconButton(
            onClick = onThemeToggle,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .size(32.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.secondaryContainer),
        ) {
            ThemeIcon(
                darkTheme = darkTheme,
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                cutoutColor = MaterialTheme.colorScheme.secondaryContainer,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun PlaybackControls(
    selected: NoiseType,
    isPlaying: Boolean,
    onSelect: (NoiseType) -> Unit,
    onPlaybackToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.width(168.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Text(
            text = "SOUND",
            modifier = Modifier.fillMaxWidth(),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.tertiary,
            textAlign = TextAlign.Center,
        )
        NoiseTypeDropdown(selected = selected, onSelect = onSelect)
        Button(
            onClick = onPlaybackToggle,
            modifier = Modifier.width(168.dp).height(56.dp),
            shape = RoundedCornerShape(18.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (isPlaying) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary,
                contentColor = if (isPlaying) MaterialTheme.colorScheme.onSecondary else MaterialTheme.colorScheme.onPrimary,
            ),
        ) {
            Text(
                text = if (isPlaying) "Stop" else "Play",
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
            modifier = Modifier.width(168.dp).height(56.dp),
            shape = RoundedCornerShape(18.dp),
            border = BorderStroke(
                width = 1.dp,
                brush = Brush.horizontalGradient(
                    listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.tertiary),
                ),
            ),
        ) {
            NoiseMarker(type = selected)
            Spacer(Modifier.width(10.dp))
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
                .width(184.dp)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(20.dp)),
        ) {
            NoiseType.entries.forEach { type ->
                Button(
                    onClick = {
                        onSelect(type)
                        expanded = false
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .padding(horizontal = 8.dp),
                    shape = RoundedCornerShape(14.dp),
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
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = noiseTypeLabel(type),
                        modifier = Modifier.weight(1f),
                        textAlign = TextAlign.Start,
                    )
                    if (type == selected) Text("Selected", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

private fun noiseTypeLabel(type: NoiseType): String = when (type) {
    NoiseType.WHITE -> "White noise"
    NoiseType.PINK -> "Pink noise"
    NoiseType.BROWN -> "Brown noise"
}

@Composable
private fun NoiseMarker(type: NoiseType) {
    val color = when (type) {
        NoiseType.WHITE -> Color(0xFFF5E9FF)
        NoiseType.PINK -> Color(0xFFFFA6C5)
        NoiseType.BROWN -> Color(0xFFE8AE72)
    }
    Canvas(modifier = Modifier.size(14.dp)) {
        drawCircle(color = color)
        drawCircle(color = Color.White.copy(alpha = 0.55f), style = Stroke(width = 1.dp.toPx()))
    }
}

@Composable
private fun VerticalVolume(volume: Float, onVolumeChange: (Float) -> Unit, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier = modifier.width(68.dp)) {
        val indicatorHeight = maxHeight * 0.5f
        Column(
            modifier = Modifier.align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "VOLUME",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.tertiary,
            )
            Spacer(Modifier.height(18.dp))
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
                text = "${(volume * 100).toInt()}%",
                modifier = Modifier.padding(top = 16.dp),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun ThemeIcon(darkTheme: Boolean, tint: Color, cutoutColor: Color, modifier: Modifier = Modifier) {
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
                drawLine(tint, start, end, strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
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
    Canvas(modifier = Modifier.size(18.dp)) {
        val y = if (expanded) size.height * 0.62f else size.height * 0.4f
        val direction = if (expanded) -1f else 1f
        drawLine(
            color = tint,
            start = Offset(size.width * 0.22f, y),
            end = Offset(size.width * 0.5f, y + direction * size.height * 0.22f),
            strokeWidth = 2.dp.toPx(),
            cap = StrokeCap.Round,
        )
        drawLine(
            color = tint,
            start = Offset(size.width * 0.5f, y + direction * size.height * 0.22f),
            end = Offset(size.width * 0.78f, y),
            strokeWidth = 2.dp.toPx(),
            cap = StrokeCap.Round,
        )
    }
}

@Composable
private fun RainfoldTheme(darkTheme: Boolean, content: @Composable () -> Unit) {
    val colors = if (darkTheme) {
        darkColorScheme(
            primary = Color(0xFFD3BCFF),
            onPrimary = Color(0xFF34106B),
            primaryContainer = Color(0xFF4C237F),
            onPrimaryContainer = Color(0xFFECDDFF),
            secondary = Color(0xFFFFB2C8),
            onSecondary = Color(0xFF5F1030),
            secondaryContainer = Color(0xFF792147),
            onSecondaryContainer = Color(0xFFFFD9E3),
            tertiary = Color(0xFF75E5D6),
            onTertiary = Color(0xFF003731),
            surface = Color(0xFF151326),
            onSurface = Color(0xFFE8E0F7),
            surfaceVariant = Color(0xFF24213B),
            onSurfaceVariant = Color(0xFFCBC2D8),
            surfaceContainerHigh = Color(0xFF302C49),
            surfaceContainerHighest = Color(0xFF3A3653),
        )
    } else {
        lightColorScheme(
            primary = Color(0xFF6D3FA3),
            onPrimary = Color.White,
            primaryContainer = Color(0xFFECDCFF),
            onPrimaryContainer = Color(0xFF291044),
            secondary = Color(0xFFA73760),
            onSecondary = Color.White,
            secondaryContainer = Color(0xFFFFD9E3),
            onSecondaryContainer = Color(0xFF3D001B),
            tertiary = Color(0xFF087B71),
            onTertiary = Color.White,
            surface = Color(0xFFFFF8FF),
            onSurface = Color(0xFF201A23),
            surfaceVariant = Color(0xFFF3E8F4),
            onSurfaceVariant = Color(0xFF4E4652),
            surfaceContainerHigh = Color(0xFFF9EFF9),
            surfaceContainerHighest = Color(0xFFF0E6F0),
        )
    }
    MaterialTheme(colorScheme = colors, content = content)
}