package com.rainfold.app.domain

enum class ScenePurpose { SLEEP, FOCUS }

/** A recipe contains sound layers. Session duration and fades remain independent. */
data class SoundScene(
    val id: String,
    val name: String,
    val mix: NoiseMix,
    val purpose: ScenePurpose,
) {
    val builtIn: Boolean get() = id.startsWith("builtin:")
}

data class SceneLibrary(
    val custom: List<SoundScene> = emptyList(),
    val favorites: Set<String> = emptySet(),
    val selectedId: String? = null,
) {
    val scenes: List<SoundScene> get() = builtIns + custom
    val selected: SoundScene? get() = scenes.find { it.id == selectedId }

    fun select(id: String): SceneLibrary {
        require(scenes.any { it.id == id })
        return copy(selectedId = id)
    }

    fun save(id: String, name: String, mix: NoiseMix, purpose: ScenePurpose): SceneLibrary {
        require(id.startsWith("user:") && scenes.none { it.id == id })
        require(custom.size < MAX_CUSTOM_SCENES)
        val scene = SoundScene(id, checkedName(name), mix, purpose)
        return copy(custom = custom + scene, selectedId = id)
    }

    fun rename(id: String, name: String): SceneLibrary {
        require(custom.any { it.id == id })
        val title = checkedName(name)
        return copy(custom = custom.map { if (it.id == id) it.copy(name = title) else it })
    }

    fun delete(id: String): SceneLibrary {
        require(custom.any { it.id == id })
        return copy(custom = custom.filterNot { it.id == id }, favorites = favorites - id,
            selectedId = selectedId.takeUnless { it == id })
    }

    fun toggleFavorite(id: String): SceneLibrary {
        require(scenes.any { it.id == id })
        return copy(favorites = if (id in favorites) favorites - id else favorites + id)
    }

    companion object {
        const val MAX_NAME_LENGTH = 60
        const val MAX_CUSTOM_SCENES = 100
        val builtIns = listOf(
            SoundScene("builtin:deep-sleep", "Deep Sleep",
                NoiseMix(NoiseType.BROWN, 0.45f), ScenePurpose.SLEEP),
            SoundScene("builtin:rainy-window", "Rainy Window",
                NoiseMix(rainEnabled = true, rainVolume = 0.65f, noiseMuted = true), ScenePurpose.SLEEP),
            SoundScene("builtin:quiet-focus", "Quiet Focus",
                NoiseMix(NoiseType.PINK, 0.4f, true, 0.2f), ScenePurpose.FOCUS),
            SoundScene("builtin:cozy-storm", "Cozy Storm",
                NoiseMix(NoiseType.BROWN, 0.5f, true, 0.6f), ScenePurpose.SLEEP),
            SoundScene("builtin:airplane-cabin", "Airplane Cabin",
                NoiseMix(NoiseType.WHITE, 0.35f), ScenePurpose.FOCUS),
        )

        private fun checkedName(name: String): String = name.trim().also {
            require(it.isNotEmpty() && it.length <= MAX_NAME_LENGTH && it.none(Char::isISOControl))
        }
    }
}