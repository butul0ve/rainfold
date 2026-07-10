package com.rainfold.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
                Surface {
                    var isPlaying by remember { mutableStateOf(false) }
                    var noiseType by remember { mutableStateOf(NoiseType.PINK) }
                    Column(
                        modifier = Modifier.fillMaxSize().padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text(text = "Rainfold", style = MaterialTheme.typography.displaySmall)
                        Text(text = "Endless soundscapes for sleep and focus")
                        Button(onClick = {
                            noiseType = NoiseType.entries[(noiseType.ordinal + 1) % NoiseType.entries.size]
                        }) {
                            Text(text = "${noiseType.name.lowercase().replaceFirstChar(Char::titlecase)} noise")
                        }
                        Button(
                            onClick = {
                                if (isPlaying) player.stop() else player.play(NoiseMix(noiseType = noiseType))
                                isPlaying = !isPlaying
                            },
                            colors = ButtonDefaults.buttonColors(),
                        ) {
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