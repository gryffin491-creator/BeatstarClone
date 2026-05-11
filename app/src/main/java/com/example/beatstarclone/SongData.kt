package com.example.beatstarclone

data class SongData(
    val resId: Int,
    val title: String,
    val artist: String,
    val difficulty: String,
    val bpm: Int
)

object SongRepository {
    fun getSongs(): List<SongData> = listOf(
        SongData(
            resId = R.raw.beat,
            title = "Beat",
            artist = "Unknown Artist",
            difficulty = "Medium",
            bpm = 105
        ),
        SongData(
            resId = R.raw.elektronomia_sky_high,
            title = "Sky High",
            artist = "Elektronomia",
            difficulty = "Hard",
            bpm = 128
        )
    )
}
