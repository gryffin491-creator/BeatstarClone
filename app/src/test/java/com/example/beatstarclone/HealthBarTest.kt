package com.example.beatstarclone

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class HealthBarTest {

    private lateinit var healthBar: HealthBar

    @Before
    fun setUp() {
        healthBar = HealthBar()
    }

    @Test
    fun initialHealthIs1() {
        assertEquals(1.0f, healthBar.getHealth(), 0.001f)
    }

    @Test
    fun afterOneMiss_healthIs085() {
        healthBar.onMiss()
        assertEquals(0.85f, healthBar.getHealth(), 0.001f)
    }

    @Test
    fun afterEnoughMisses_isDeadIsTrue() {
        repeat(7) { healthBar.onMiss() }
        assertTrue(healthBar.isDead())
    }

    @Test
    fun healthNeverBelowZero() {
        repeat(20) { healthBar.onMiss() }
        assertEquals(0.0f, healthBar.getHealth(), 0.001f)
    }

    @Test
    fun onHit_regenersBy003() {
        healthBar.onMiss() // health = 0.85
        healthBar.onHit()  // health = 0.88
        assertEquals(0.88f, healthBar.getHealth(), 0.001f)
    }

    @Test
    fun healthNeverExceeds1() {
        healthBar.onHit()
        assertEquals(1.0f, healthBar.getHealth(), 0.001f)
    }

    @Test
    fun reset_restoresHealthTo1() {
        repeat(5) { healthBar.onMiss() }
        healthBar.reset()
        assertEquals(1.0f, healthBar.getHealth(), 0.001f)
    }

    @Test
    fun customDrainAndRegenValues() {
        val customBar = HealthBar(maxHealth = 1.0f, drainPerMiss = 0.5f, regenPerHit = 0.1f)
        customBar.onMiss()
        assertEquals(0.5f, customBar.getHealth(), 0.001f)
        customBar.onHit()
        assertEquals(0.6f, customBar.getHealth(), 0.001f)
    }

    @Test
    fun isDead_falseWhenHealthAboveZero() {
        healthBar.onMiss()
        assertFalse(healthBar.isDead())
    }

    @Test
    fun multipleHitsAtFullHealth_staysAt1() {
        repeat(10) { healthBar.onHit() }
        assertEquals(1.0f, healthBar.getHealth(), 0.001f)
    }
}
