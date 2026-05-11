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

        val speedMultiplier = when {
            progress >= 0.75f -> 1.6f
            progress >= 0.50f -> 1.4f
            progress >= 0.25f -> 1.2f
            else -> 1.0f
        }

        val spawnAheadMs = when {
            progress >= 0.75f -> 1400L
            progress >= 0.50f -> 1600L
            progress >= 0.25f -> 1800L
            else -> 2000L
        }

        val hitWindowMultiplier = when {
            progress >= 0.75f -> 0.85f
            progress >= 0.50f -> 0.90f
            progress >= 0.25f -> 0.95f
            else -> 1.0f
        }

        return DifficultyLevel(
            tileSpeed = baseTileSpeed * speedMultiplier,
            spawnAheadMs = spawnAheadMs,
            hitWindowMultiplier = hitWindowMultiplier
        )
    }
}
