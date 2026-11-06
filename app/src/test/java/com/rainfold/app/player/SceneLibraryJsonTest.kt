package com.rainfold.app.player

import com.rainfold.app.domain.NoiseMix
import com.rainfold.app.domain.NoiseType
import com.rainfold.app.domain.SceneLibrary
import com.rainfold.app.domain.ScenePurpose
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SceneLibraryJsonTest {
    @Test
    fun `versioned format preserves unicode escaping all layers favorites and last selection`() {
        val library = SceneLibrary().save("user:one", "Сон \"окно\" ☔", NoiseMix(NoiseType.BROWN, 0.23f, true, 0.67f, true, true), ScenePurpose.SLEEP)
            .save("user:two", "Focus", NoiseMix(), ScenePurpose.FOCUS)
            .toggleFavorite("user:one").toggleFavorite("builtin:quiet-focus").select("user:one")
        assertEquals(library, SceneLibraryJson.decode(SceneLibraryJson.encode(library)))
    }

    @Test
    fun `legacy preferences missing or malformed library start with intact built ins`() {
        listOf(null, "", "not json", "{}", "{\"version\":99}").forEach {
            assertEquals(SceneLibrary(), SceneLibraryJson.decode(it))
        }
    }

    @Test
    fun `damaged recipe is isolated and dangling ids are removed`() {
        val original = SceneLibrary().save("user:one", "One", NoiseMix(), ScenePurpose.SLEEP)
            .save("user:two", "Two", NoiseMix(rainEnabled = true), ScenePurpose.FOCUS)
            .toggleFavorite("user:one").toggleFavorite("user:two").select("user:one")
        val root = JSONObject(SceneLibraryJson.encode(original))
        root.getJSONArray("custom").getJSONObject(0).getJSONObject("mix").put("volume", 7)
        val restored = SceneLibraryJson.decode(root.toString())
        assertEquals(listOf(original.custom[1]), restored.custom)
        assertEquals(setOf("user:two"), restored.favorites)
        assertEquals(null, restored.selectedId)
    }

    @Test
    fun `duplicate identifiers unknown enum and blank names do not enter library`() {
        val library = SceneLibrary().save("user:one", "One", NoiseMix(), ScenePurpose.SLEEP)
        val root = JSONObject(SceneLibraryJson.encode(library))
        val entries = root.getJSONArray("custom")
        entries.put(JSONObject(entries.getJSONObject(0).toString()))
        entries.put(JSONObject(entries.getJSONObject(0).toString()).put("id", "user:two").put("purpose", "UNKNOWN"))
        entries.put(JSONObject(entries.getJSONObject(0).toString()).put("id", "user:three").put("name", "  "))
        assertEquals(library, SceneLibraryJson.decode(root.toString()))
        assertTrue(SceneLibraryJson.decode(root.toString()).custom.none { it.builtIn })
    }
}