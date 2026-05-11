package com.example.beatstarclone

data class DifficultyLevel(
    val tileSpeed: Float,
    val spawnAheadMs: Long,
    val hitWindowMultiplier: Float
)

class DifficultyManager(private val songDurationMs: Long) {

    fun getDifficulty(currentTimeMs: Long, baseTileSpeed: Float): DifficultyLevel {
        val progress = if (songDurationMs > 0) {
            (currentTimeMs.toFloat() / songDurationMs).coerceIn(0f, 1f)
        } else {
            0f
        }

        // Linear interpolation: smooth from 1.0 at start to 1.6 at end
        val speedMultiplier = 1.0f + 0.6f * progress

        // Linear interpolation: smooth from 2000 at start to 1400 at end
        val spawnAheadMs = (2000L - (600L * progress).toLong())

        // Linear interpolation: smooth from 1.0 at start to 0.85 at end
        val hitWindowMultiplier = 1.0f - 0.15f * progress

        return DifficultyLevel(
            tileSpeed = baseTileSpeed * speedMultiplier,
            spawnAheadMs = spawnAheadMs,
            hitWindowMultiplier = hitWindowMultiplier
        )
    }
}
