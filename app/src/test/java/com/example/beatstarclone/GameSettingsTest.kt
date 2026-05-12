package com.example.beatstarclone

import org.junit.Assert.*
import org.junit.Test

class GameSettingsTest {

    @Test
    fun defaultHasCorrectValues() {
        val settings = GameSettings.DEFAULT
        assertEquals(15f, settings.tileSpeed, 0.001f)
        assertEquals(80f, settings.hitWindowPerfect, 0.001f)
        assertEquals(160f, settings.hitWindowGood, 0.001f)
        assertEquals(250f, settings.hitWindowOK, 0.001f)
        assertEquals(1.0f, settings.volume, 0.001f)
        assertEquals(0L, settings.audioOffsetMs)
    }

    @Test
    fun copyWithOneChange_preservesOtherFields() {
        val settings = GameSettings.DEFAULT
        val modified = settings.copy(tileSpeed = 20f)

        assertEquals(20f, modified.tileSpeed, 0.001f)
        assertEquals(80f, modified.hitWindowPerfect, 0.001f)
        assertEquals(160f, modified.hitWindowGood, 0.001f)
        assertEquals(250f, modified.hitWindowOK, 0.001f)
        assertEquals(1.0f, modified.volume, 0.001f)
        assertEquals(0L, modified.audioOffsetMs)
    }

    @Test
    fun copyCreatesIndependentInstance() {
        val original = GameSettings.DEFAULT
        val copy = original.copy(tileSpeed = 99f)

        assertEquals(15f, original.tileSpeed, 0.001f)
        assertEquals(99f, copy.tileSpeed, 0.001f)
    }

    @Test
    fun equalityForSameValues() {
        val settings1 = GameSettings(tileSpeed = 10f, hitWindowPerfect = 50f)
        val settings2 = GameSettings(tileSpeed = 10f, hitWindowPerfect = 50f)

        assertEquals(settings1, settings2)
    }

    @Test
    fun customConstructorValuesPreserved() {
        val settings = GameSettings(
            tileSpeed = 25f,
            hitWindowPerfect = 60f,
            hitWindowGood = 120f,
            hitWindowOK = 200f,
            volume = 0.5f,
            audioOffsetMs = 50L
        )

        assertEquals(25f, settings.tileSpeed, 0.001f)
        assertEquals(60f, settings.hitWindowPerfect, 0.001f)
        assertEquals(120f, settings.hitWindowGood, 0.001f)
        assertEquals(200f, settings.hitWindowOK, 0.001f)
        assertEquals(0.5f, settings.volume, 0.001f)
        assertEquals(50L, settings.audioOffsetMs)
    }
}
