package com.example.beatstarclone

data class GameSettings(
    val tileSpeed: Float = 15f,
    val hitWindowPerfect: Float = 80f,
    val hitWindowGood: Float = 160f,
    val hitWindowOK: Float = 250f,
    val volume: Float = 1.0f
) {
    companion object {
        val DEFAULT = GameSettings()
    }
}
