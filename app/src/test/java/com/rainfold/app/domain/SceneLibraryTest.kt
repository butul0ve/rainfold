package com.rainfold.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class SceneLibraryTest {
    @Test
    fun `saving a named recipe selects an immutable copy of every mix field`() {
        val mix = NoiseMix(NoiseType.WHITE, 0.7f, true, 0.4f, true, true)
        val library = SceneLibrary().save("user:one", "  Вечер  ", mix, ScenePurpose.SLEEP)
        assertEquals(SoundScene("user:one", "Вечер", mix, ScenePurpose.SLEEP), library.selected)
        assertEquals(mix, library.selected!!.mix)
        assertFalse(library.selected!!.builtIn)
        assertEquals(5, SceneLibrary().scenes.size)
    }

    @Test
    fun `rename and favorites retain selection and sound recipe`() {
        val original = SceneLibrary().save("user:one", "Evening", NoiseMix(), ScenePurpose.SLEEP)
        val changed = original.rename("user:one", "Night").toggleFavorite("user:one")
        assertEquals("Night", changed.selected!!.name)
        assertEquals(original.selected!!.mix, changed.selected!!.mix)
        assertEquals(setOf("user:one"), changed.favorites)
        assertTrue(changed.toggleFavorite("user:one").favorites.isEmpty())
        assertEquals("Evening", original.selected!!.name)
    }

    @Test
    fun `deleting selected recipe removes dangling selection and favorite`() {
        val library = SceneLibrary().save("user:one", "One", NoiseMix(), ScenePurpose.FOCUS)
            .toggleFavorite("user:one").delete("user:one")
        assertEquals(null, library.selectedId)
        assertTrue(library.custom.isEmpty())
        assertTrue(library.favorites.isEmpty())
    }

    @Test
    fun `built in recipes cannot be renamed overwritten or deleted`() {
        val library = SceneLibrary()
        val id = library.scenes.first().id
        assertThrows(IllegalArgumentException::class.java) { library.rename(id, "Changed") }
        assertThrows(IllegalArgumentException::class.java) { library.delete(id) }
        assertThrows(IllegalArgumentException::class.java) { library.save(id, "Changed", NoiseMix(), ScenePurpose.SLEEP) }
        assertEquals(id, library.toggleFavorite(id).select(id).selectedId)
    }

    @Test
    fun `invalid names duplicate ids and absent ids are rejected without modifying library`() {
        val library = SceneLibrary().save("user:one", "One", NoiseMix(), ScenePurpose.FOCUS)
        listOf("", "   ", "x".repeat(61), "line\nbreak").forEach { name ->
            assertThrows(IllegalArgumentException::class.java) { library.rename("user:one", name) }
        }
        assertThrows(IllegalArgumentException::class.java) { library.save("user:one", "Two", NoiseMix(), ScenePurpose.FOCUS) }
        assertThrows(IllegalArgumentException::class.java) { library.select("missing") }
        assertThrows(IllegalArgumentException::class.java) { library.toggleFavorite("missing") }
        assertEquals("One", library.selected!!.name)
    }

    @Test
    fun `library limit rejects additional recipes while allowing rename and deletion`() {
        var library = SceneLibrary()
        repeat(SceneLibrary.MAX_CUSTOM_SCENES) { library = library.save("user:$it", "Scene $it", NoiseMix(), ScenePurpose.SLEEP) }
        val full = library
        assertThrows(IllegalArgumentException::class.java) { full.save("user:extra", "Extra", NoiseMix(), ScenePurpose.SLEEP) }
        assertEquals("Renamed", full.rename("user:0", "Renamed").custom.first().name)
        assertEquals(100, full.delete("user:0").save("user:extra", "Extra", NoiseMix(), ScenePurpose.SLEEP).custom.size)
    }
}