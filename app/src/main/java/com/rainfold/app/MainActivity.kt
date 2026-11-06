package com.rainfold.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rainfold.app.ui.PlaybackViewModel
import com.rainfold.app.ui.RainfoldScreen

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val viewModel: PlaybackViewModel = viewModel()
            val state by viewModel.state.collectAsStateWithLifecycle()
            RainfoldScreen(
                state,
                viewModel::selectNoise,
                viewModel::togglePlayback,
                viewModel::setVolume,
                viewModel::stop,
                viewModel::configure
            )
        }
    }
}