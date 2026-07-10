package com.rainfold.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    var isPlaying by rememberSaveable { mutableStateOf(false) }
                    var noiseType by rememberSaveable { mutableStateOf(NoiseType.PINK) }
                    Column(
                        modifier = Modifier.fillMaxSize().padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text("Rainfold", style = MaterialTheme.typography.displaySmall)
                        Text("Endless soundscapes for sleep and focus")
                        NoiseTypeDropdown(selected = noiseType, onSelect = { noiseType = it })
                        Button(onClick = {
                            if (isPlaying) player.stop() else player.play(NoiseMix(noiseType = noiseType))
                            isPlaying = !isPlaying
                        }) {
                            Text(if (isPlaying) "Stop" else "Play")
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