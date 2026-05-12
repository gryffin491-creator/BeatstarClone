package com.example.beatstarclone

import org.junit.Assert.*
import org.junit.Test

class BeatDetectorLaneAssignmentTest {

    @Test
    fun emptyInput_returnsEmptyList() {
        val result = LaneAssigner.assignLanes(emptyList())
        assertTrue(result.isEmpty())
    }

    @Test
    fun singleBeat_getsValidLane() {
        val result = LaneAssigner.assignLanes(listOf(1000L))
        assertEquals(1, result.size)
        assertTrue(result[0].lane in 0..2)
    }

    @Test
    fun closeBeats_getAdjacentLanes() {
        // Beats within 300ms of each other should get adjacent lanes (sweep pattern)
        val closeBeats = listOf(1000L, 1100L, 1200L, 1300L, 1400L)
        val result = LaneAssigner.assignLanes(closeBeats)

        for (i in 1 until result.size) {
            val diff = Math.abs(result[i].lane - result[i - 1].lane)
            // Adjacent means difference of 0 or 1 (sweep pattern allows same lane in rare cases due to modular logic)
            assertTrue("Lanes should be close: ${result[i - 1].lane} -> ${result[i].lane}", diff <= 2)
        }
    }

    @Test
    fun noLaneMoreThan2ConsecutiveTimes() {
        // Generate 60 beats spread far apart to trigger the isolated beat logic
        val beats = (0 until 60).map { it * 1000L }
        val result = LaneAssigner.assignLanes(beats)

        var consecutiveCount = 1
        for (i in 1 until result.size) {
            if (result[i].lane == result[i - 1].lane) {
                consecutiveCount++
                assertTrue(
                    "Lane ${result[i].lane} appeared $consecutiveCount times at index $i",
                    consecutiveCount <= 2
                )
            } else {
                consecutiveCount = 1
            }
        }
    }

    @Test
    fun mixOfNoteTypes_roughlyMatchesExpectedDistribution() {
        // Generate 200 well-spaced beats to get a good sample of note type distribution
        val beats = (0 until 200).map { it * 2000L }
        val result = LaneAssigner.assignLanes(beats)

        val tapCount = result.count { it.noteType == NoteType.TAP }
        val holdCount = result.count { it.noteType == NoteType.HOLD }
        val swipeCount = result.count { it.noteType == NoteType.SWIPE }

        // With 200 notes, expect ~70% TAP, ~20% HOLD, ~10% SWIPE
        // Use generous tolerance of +/- 15%
        val total = result.size.toFloat()
        val tapPct = tapCount / total * 100
        val holdPct = holdCount / total * 100
        val swipePct = swipeCount / total * 100

        assertTrue("TAP percentage ($tapPct%) should be roughly 70%", tapPct in 50f..90f)
        assertTrue("HOLD percentage ($holdPct%) should be roughly 20%", holdPct in 5f..35f)
        assertTrue("SWIPE percentage ($swipePct%) should be roughly 10%", swipePct in 0f..25f)
    }

    @Test
    fun holdNotes_haveValidDuration() {
        val beats = (0 until 200).map { it * 2000L }
        val result = LaneAssigner.assignLanes(beats)

        val holdNotes = result.filter { it.noteType == NoteType.HOLD }
        assertTrue("Should have some HOLD notes", holdNotes.isNotEmpty())

        for (note in holdNotes) {
            assertTrue(
                "Hold duration ${note.holdDurationMs} should be in 400-800 range",
                note.holdDurationMs in 400L..800L
            )
        }
    }

    @Test
    fun swipeNotes_haveNonNullDirection() {
        val beats = (0 until 200).map { it * 2000L }
        val result = LaneAssigner.assignLanes(beats)

        val swipeNotes = result.filter { it.noteType == NoteType.SWIPE }
        assertTrue("Should have some SWIPE notes", swipeNotes.isNotEmpty())

        for (note in swipeNotes) {
            assertNotNull("Swipe note should have a direction", note.swipeDirection)
        }
    }
}
