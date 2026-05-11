package com.example.beatstarclone

class HealthBar(
    private val maxHealth: Float = 1.0f,
    private val drainPerMiss: Float = 0.15f,
    private val regenPerHit: Float = 0.03f
) {

    private var health: Float = maxHealth

    fun onHit() {
        health = (health + regenPerHit).coerceAtMost(maxHealth)
    }

    fun onMiss() {
        health = (health - drainPerMiss).coerceAtLeast(0.0f)
    }

    fun getHealth(): Float = health

    fun isDead(): Boolean = health <= 0f

    fun reset() {
        health = maxHealth
    }
}
