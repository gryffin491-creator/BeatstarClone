package com.example.beatstarclone

import org.junit.Assert.*
import org.junit.Test

class HoldNoteTest {

    @Test
    fun holdNote_durationMsComputedCorrectly() {
        val holdNote = HoldNote(startTimestamp = 1000L, endTimestamp = 1500L, lane = 1)
        assertEquals(500L, holdNote.durationMs)
    }

    @Test
    fun holdNote_variousTimestamps_computesDurationCorrectly() {
        val note1 = HoldNote(startTimestamp = 0L, endTimestamp = 800L, lane = 0)
        assertEquals(800L, note1.durationMs)

        val note2 = HoldNote(startTimestamp = 5000L, endTimestamp = 5400L, lane = 2)
        assertEquals(400L, note2.durationMs)

        val note3 = HoldNote(startTimestamp = 10000L, endTimestamp = 12000L, lane = 1)
        assertEquals(2000L, note3.durationMs)
    }

    @Test
    fun noteTypeEnum_hasExactly3Values() {
        val values = NoteType.entries
        assertEquals(3, values.size)
        assertTrue(values.contains(NoteType.TAP))
        assertTrue(values.contains(NoteType.HOLD))
        assertTrue(values.contains(NoteType.SWIPE))
    }

    @Test
    fun swipeDirectionEnum_hasExactly3Values() {
        val values = SwipeDirection.entries
        assertEquals(3, values.size)
        assertTrue(values.contains(SwipeDirection.LEFT))
        assertTrue(values.contains(SwipeDirection.RIGHT))
        assertTrue(values.contains(SwipeDirection.UP))
    }

    @Test
    fun swipeNote_storesDirectionCorrectly() {
        val swipeLeft = SwipeNote(timestamp = 1000L, lane = 0, direction = SwipeDirection.LEFT)
        assertEquals(SwipeDirection.LEFT, swipeLeft.direction)

        val swipeRight = SwipeNote(timestamp = 2000L, lane = 1, direction = SwipeDirection.RIGHT)
        assertEquals(SwipeDirection.RIGHT, swipeRight.direction)

        val swipeUp = SwipeNote(timestamp = 3000L, lane = 2, direction = SwipeDirection.UP)
        assertEquals(SwipeDirection.UP, swipeUp.direction)
    }

    @Test
    fun noteDataClass_defaultsAreCorrect() {
        val note = Note(timestamp = 1000L, lane = 1)
        assertEquals(NoteType.TAP, note.noteType)
        assertEquals(0L, note.holdDurationMs)
        assertNull(note.swipeDirection)
    }
}
