package com.rainfold.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
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
                Surface(modifier = Modifier.fillMaxSize()) {
                    var isPlaying by rememberSaveable { mutableStateOf(false) }
                    var noiseType by rememberSaveable { mutableStateOf(NoiseType.PINK) }
                    var volume by rememberSaveable { mutableStateOf(0.5f) }
                    Column(
                        modifier = Modifier.fillMaxSize().padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Rainfold", style = MaterialTheme.typography.displaySmall)
                            TextButton(onClick = { darkTheme = !darkTheme }) {
                                Text(if (darkTheme) "Light theme" else "Dark theme")
                            }
                        }
                        Text("Endless soundscapes for sleep and focus")
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                NoiseTypeDropdown(
                                    selected = noiseType,
                                    onSelect = { type ->
                                        noiseType = type
                                        if (isPlaying) player.play(NoiseMix(noiseType = type, noiseVolume = volume))
                                    },
                                )
                                Button(onClick = {
                                    if (isPlaying) player.stop() else player.play(NoiseMix(noiseType = noiseType, noiseVolume = volume))
                                    isPlaying = !isPlaying
                                }) {
                                    Text(if (isPlaying) "Stop" else "Play")
                                }
                            }
                            Spacer(Modifier.width(24.dp))
                            VerticalVolume(
                                volume = volume,
                                onVolumeChange = {
                                    volume = it
                                    player.setVolume(it)
                                },
                            )
                        }
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
@OptIn(ExperimentalMaterial3Api::class)
private fun NoiseTypeDropdown(selected: NoiseType, onSelect: (NoiseType) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        TextButton(onClick = { expanded = true }, modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)) {
            Text(noiseTypeLabel(selected))
            ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
        }
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            NoiseType.entries.forEach { type ->
                DropdownMenuItem(
                    text = { Text(noiseTypeLabel(type)) },
                    onClick = {
                        onSelect(type)
                        expanded = false
                    },
                )
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
private fun VerticalVolume(volume: Float, onVolumeChange: (Float) -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text("VOL", style = MaterialTheme.typography.labelSmall)
        Box(modifier = Modifier.height(176.dp).width(48.dp), contentAlignment = Alignment.Center) {
            Slider(
                value = volume,
                onValueChange = onVolumeChange,
                modifier = Modifier.width(176.dp).graphicsLayer(rotationZ = -90f),
            )
        }
        Text("${(volume * 100).toInt()}", style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun RainfoldTheme(darkTheme: Boolean, content: @Composable () -> Unit) {
    val colors = if (darkTheme) {
        darkColorScheme(
            primary = Color(0xFF9FE8DB),
            onPrimary = Color(0xFF003731),
            surface = Color(0xFF183440),
            onSurface = Color(0xFFE3F3F0),
        )
    } else {
        lightColorScheme(
            primary = Color(0xFF176E62),
            onPrimary = Color.White,
            surface = Color.White,
            onSurface = Color(0xFF17332E),
        )
    }
    MaterialTheme(colorScheme = colors, content = content)
}