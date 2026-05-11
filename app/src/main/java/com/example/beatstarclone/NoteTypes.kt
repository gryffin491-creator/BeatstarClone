package com.example.beatstarclone

enum class NoteType { TAP, HOLD, SWIPE }

enum class SwipeDirection { LEFT, RIGHT, UP }

data class HoldNote(val startTimestamp: Long, val endTimestamp: Long, val lane: Int) {
    val durationMs: Long get() = endTimestamp - startTimestamp
}

data class SwipeNote(val timestamp: Long, val lane: Int, val direction: SwipeDirection)
