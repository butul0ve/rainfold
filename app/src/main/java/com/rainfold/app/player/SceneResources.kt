package com.rainfold.app.player

import android.content.Context
import com.rainfold.app.R
import com.rainfold.app.domain.SoundScene

internal fun Context.sceneTitle(scene: SoundScene): String = when (scene.id) {
    "builtin:deep-sleep" -> getString(R.string.scene_deep_sleep)
    "builtin:rainy-window" -> getString(R.string.scene_rainy_window)
    "builtin:quiet-focus" -> getString(R.string.scene_quiet_focus)
    else -> scene.name
}