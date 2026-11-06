package com.rainfold.app.player

import android.content.Context
import com.rainfold.app.R
import com.rainfold.app.domain.SoundScene

internal fun Context.sceneTitle(scene: SoundScene): String = when (scene.id) {
    "builtin:deep-sleep" -> getString(R.string.scene_deep_sleep)
    "builtin:rainy-window" -> getString(R.string.scene_rainy_window)
    "builtin:quiet-focus" -> getString(R.string.scene_quiet_focus)
    "builtin:cozy-storm" -> getString(R.string.scene_cozy_storm)
    "builtin:airplane-cabin" -> getString(R.string.scene_airplane_cabin)
    else -> scene.name
}

internal fun SoundScene.descriptionResource(): Int? = when (id) {
    "builtin:deep-sleep" -> R.string.scene_deep_sleep_description
    "builtin:rainy-window" -> R.string.scene_rainy_window_description
    "builtin:quiet-focus" -> R.string.scene_quiet_focus_description
    "builtin:cozy-storm" -> R.string.scene_cozy_storm_description
    "builtin:airplane-cabin" -> R.string.scene_airplane_cabin_description
    else -> null
}