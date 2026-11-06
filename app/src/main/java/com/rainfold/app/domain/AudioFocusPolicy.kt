package com.rainfold.app.domain

/** Remembers playback intent across a temporary interruption, independently of Android focus codes. */
class AudioFocusPolicy {
    private var resumePending = false

    fun onTransientLoss(wasPlaying: Boolean) {
        resumePending = resumePending || wasPlaying
    }

    fun onUserCommand(command: PlaybackCommand) {
        when (command) {
            PlaybackCommand.Pause, PlaybackCommand.Stop -> cancelResume()

            else -> Unit
        }
    }

    fun onGain(): Boolean {
        val resume = resumePending
        resumePending = false
        return resume
    }

    fun cancelResume() {
        resumePending = false
    }
}