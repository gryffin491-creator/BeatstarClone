package com.example.beatstarclone

import org.junit.Assert.*
import org.junit.Test

class DifficultyManagerTest {

    @Test
    fun atTimeZero_returnsBaseValues() {
        val manager = DifficultyManager(100000L)
        val difficulty = manager.getDifficulty(0L, 10f)

        assertEquals(10f, difficulty.tileSpeed, 0.001f)
        assertEquals(2000L, difficulty.spawnAheadMs)
        assertEquals(1.0f, difficulty.hitWindowMultiplier, 0.001f)
    }

    @Test
    fun atMidpoint_returnsInterpolatedValues() {
        val manager = DifficultyManager(100000L)
        val difficulty = manager.getDifficulty(50000L, 10f)

        // speed = 10 * (1.0 + 0.6*0.5) = 10 * 1.3 = 13
        assertEquals(13f, difficulty.tileSpeed, 0.001f)
        // spawn = 2000 - (600 * 0.5) = 2000 - 300 = 1700
        assertEquals(1700L, difficulty.spawnAheadMs)
        // hitWindow = 1.0 - 0.15*0.5 = 0.925
        assertEquals(0.925f, difficulty.hitWindowMultiplier, 0.001f)
    }

    @Test
    fun atEnd_returnsMaxDifficultyValues() {
        val manager = DifficultyManager(100000L)
        val difficulty = manager.getDifficulty(100000L, 10f)

        // speed = 10 * 1.6 = 16
        assertEquals(16f, difficulty.tileSpeed, 0.001f)
        // spawn = 2000 - 600 = 1400
        assertEquals(1400L, difficulty.spawnAheadMs)
        // hitWindow = 1.0 - 0.15 = 0.85
        assertEquals(0.85f, difficulty.hitWindowMultiplier, 0.001f)
    }

    @Test
    fun beyondDuration_clampedToEndValues() {
        val manager = DifficultyManager(100000L)
        val difficulty = manager.getDifficulty(150000L, 10f)

        assertEquals(16f, difficulty.tileSpeed, 0.001f)
        assertEquals(1400L, difficulty.spawnAheadMs)
        assertEquals(0.85f, difficulty.hitWindowMultiplier, 0.001f)
    }

    @Test
    fun songDurationZero_returnsBaseValuesWithoutCrash() {
        val manager = DifficultyManager(0L)
        val difficulty = manager.getDifficulty(50000L, 10f)

        // With duration 0, progress is 0, so should return base values
        assertEquals(10f, difficulty.tileSpeed, 0.001f)
        assertEquals(2000L, difficulty.spawnAheadMs)
        assertEquals(1.0f, difficulty.hitWindowMultiplier, 0.001f)
    }
}
