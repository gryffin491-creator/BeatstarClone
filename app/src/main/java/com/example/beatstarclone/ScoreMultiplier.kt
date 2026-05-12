package com.example.beatstarclone

class ScoreMultiplier {

    private var combo: Int = 0
    private var multiplier: Int = 1

    fun onHit(): Int {
        combo++
        multiplier = when {
            combo >= 30 -> 8
            combo >= 15 -> 4
            combo >= 5 -> 2
            else -> 1
        }
        return multiplier
    }

    fun onMiss() {
        combo = 0
        multiplier = 1
    }

    fun getMultiplier(): Int = multiplier

    fun getCombo(): Int = combo

    fun reset() {
        combo = 0
        multiplier = 1
    }
}
