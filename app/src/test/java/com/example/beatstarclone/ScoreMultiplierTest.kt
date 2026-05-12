package com.example.beatstarclone

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class ScoreMultiplierTest {

    private lateinit var multiplier: ScoreMultiplier

    @Before
    fun setUp() {
        multiplier = ScoreMultiplier()
    }

    @Test
    fun initialMultiplierIsOne() {
        assertEquals(1, multiplier.getMultiplier())
    }

    @Test
    fun after4Hits_multiplierStillX1() {
        repeat(4) { multiplier.onHit() }
        assertEquals(1, multiplier.getMultiplier())
    }

    @Test
    fun after5Hits_multiplierBecomesX2() {
        repeat(5) { multiplier.onHit() }
        assertEquals(2, multiplier.getMultiplier())
    }

    @Test
    fun after14Hits_multiplierStillX2() {
        repeat(14) { multiplier.onHit() }
        assertEquals(2, multiplier.getMultiplier())
    }

    @Test
    fun after15Hits_multiplierBecomesX4() {
        repeat(15) { multiplier.onHit() }
        assertEquals(4, multiplier.getMultiplier())
    }

    @Test
    fun after29Hits_multiplierStillX4() {
        repeat(29) { multiplier.onHit() }
        assertEquals(4, multiplier.getMultiplier())
    }

    @Test
    fun after30Hits_multiplierBecomesX8() {
        repeat(30) { multiplier.onHit() }
        assertEquals(8, multiplier.getMultiplier())
    }

    @Test
    fun after100Hits_multiplierStillX8() {
        repeat(100) { multiplier.onHit() }
        assertEquals(8, multiplier.getMultiplier())
    }

    @Test
    fun onMiss_resetsToX1AndCombo0() {
        repeat(30) { multiplier.onHit() }
        assertEquals(8, multiplier.getMultiplier())
        multiplier.onMiss()
        assertEquals(1, multiplier.getMultiplier())
        assertEquals(0, multiplier.getCombo())
    }

    @Test
    fun afterMiss_hittingAgainStartsFreshFromX1() {
        repeat(30) { multiplier.onHit() }
        multiplier.onMiss()
        multiplier.onHit()
        assertEquals(1, multiplier.getMultiplier())
        assertEquals(1, multiplier.getCombo())
    }

    @Test
    fun getCombo_tracksCorrectly() {
        assertEquals(0, multiplier.getCombo())
        multiplier.onHit()
        assertEquals(1, multiplier.getCombo())
        multiplier.onHit()
        assertEquals(2, multiplier.getCombo())
        multiplier.onHit()
        assertEquals(3, multiplier.getCombo())
    }
}
