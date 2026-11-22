package com.rainfold.app.player

import com.rainfold.app.domain.MixPreferences
import com.rainfold.app.domain.NoiseMix
import com.rainfold.app.domain.NoiseType
import com.rainfold.app.domain.SceneLibrary
import com.rainfold.app.domain.ScenePurpose
import com.rainfold.app.domain.SoundScene
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** Versioned local format; malformed recipes are skipped independently of intact recipes. */
internal object SceneLibraryJson {
    const val KEY = "sceneLibrary"

    fun encode(library: SceneLibrary): String = JSONObject().apply {
        put("version", 1)
        put("custom", JSONArray().apply {
            library.custom.forEach { scene -> put(JSONObject().apply {
                put("id", scene.id)
                put("name", scene.name)
                put("purpose", scene.purpose.name)
                put("mix", JSONObject(MixPreferences.encode(scene.mix)))
            }) }
        })
        put("favorites", JSONArray(library.favorites.toList()))
        put("selected", library.selectedId ?: JSONObject.NULL)
    }.toString()

    fun decode(encoded: String?): SceneLibrary {
        if (encoded == null) return SceneLibrary()
        return try {
            val root = JSONObject(encoded)
            if (root.getInt("version") != 1) return SceneLibrary()
            var library = SceneLibrary()
            val custom = root.optJSONArray("custom") ?: JSONArray()
            for (index in 0 until minOf(custom.length(), SceneLibrary.MAX_CUSTOM_SCENES)) {
                try {
                    val entry = custom.getJSONObject(index)
                    val mix = entry.getJSONObject("mix")
                    val sceneMix = NoiseMix(
                        NoiseType.valueOf(mix.getString("noise")),
                        mix.getDouble("volume").toFloat(), mix.getBoolean("rainEnabled"),
                        mix.getDouble("rainVolume").toFloat(), mix.getBoolean("noiseMuted"),
                        mix.getBoolean("rainMuted"),
                    )
                    library = library.save(entry.getString("id"), entry.getString("name"), sceneMix,
                        ScenePurpose.valueOf(entry.getString("purpose")))
                } catch (_: JSONException) {
                    // One damaged record must not hide other saved scenes.
                } catch (_: IllegalArgumentException) {
                    // Reject invalid identifiers, names, enum values and non-finite/out-of-range gains.
                }
            }
            val ids = library.scenes.map { it.id }.toSet()
            val favorites = root.optJSONArray("favorites") ?: JSONArray()
            library.copy(
                favorites = (0 until favorites.length()).map { favorites.optString(it) }.filter { it in ids }.toSet(),
                selectedId = root.optString("selected").takeIf { it in ids },
            )
        } catch (_: JSONException) {
            SceneLibrary()
        }
    }
}