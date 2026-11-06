package com.rainfold.app.domain

import kotlinx.coroutines.flow.StateFlow

/** UI-facing command and observation port; implementations own their audio resources. */
interface PlaybackController {
    val state: StateFlow<PlaybackSnapshot>

    fun dispatch(command: PlaybackCommand)
}