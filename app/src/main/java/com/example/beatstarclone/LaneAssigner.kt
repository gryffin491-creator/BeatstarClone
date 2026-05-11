package com.example.beatstarclone

internal object LaneAssigner {

    private const val NUM_LANES = 3
    private const val CLOSE_BEAT_THRESHOLD_MS = 300L

    fun assignLanes(beatTimestamps: List<Long>): List<Note> {
        if (beatTimestamps.isEmpty()) return emptyList()

        val notes = ArrayList<Note>(beatTimestamps.size)
        var lastLane = 1
        var consecutiveSameLane = 0
        val random = kotlin.random.Random(beatTimestamps.hashCode().toLong())

        var i = 0
        while (i < beatTimestamps.size) {
            val timestamp = beatTimestamps[i]
            val lane: Int

            if (i > 0 && timestamp - beatTimestamps[i - 1] <= CLOSE_BEAT_THRESHOLD_MS) {
                // Close beats: create a sweep pattern using adjacent lanes
                lane = when (lastLane) {
                    0 -> 1
                    1 -> if (i % 2 == 0) 0 else 2
                    2 -> 1
                    else -> 1
                }
            } else {
                // Isolated beat: pick a lane different from the last, using beat index for variety
                lane = when (i % 5) {
                    0 -> 0
                    1 -> 2
                    2 -> 1
                    3 -> 0
                    4 -> 2
                    else -> 1
                }
            }

            // Enforce: never same lane more than 2 in a row
            val finalLane = if (lane == lastLane && consecutiveSameLane >= 1) {
                // Pick a different lane
                (lane + 1 + (i % 2)) % NUM_LANES
            } else {
                lane
            }

            if (finalLane == lastLane) {
                consecutiveSameLane++
            } else {
                consecutiveSameLane = 0
            }

            lastLane = finalLane

            // Determine note type: ~20% HOLD, ~10% SWIPE, ~70% TAP
            val roll = random.nextInt(100)
            when {
                roll < 20 -> {
                    // Attempt HOLD note
                    val holdDuration = random.nextLong(400, 801)
                    val holdEnd = timestamp + holdDuration
                    // Check if hold would overlap the next beat
                    val nextTimestamp = if (i + 1 < beatTimestamps.size) beatTimestamps[i + 1] else Long.MAX_VALUE
                    if (holdEnd < nextTimestamp) {
                        notes.add(Note(timestamp, finalLane, NoteType.HOLD, holdDurationMs = holdDuration))
                    } else {
                        // Overlap detected, fall back to TAP
                        notes.add(Note(timestamp, finalLane, NoteType.TAP))
                    }
                }
                roll < 30 -> {
                    // SWIPE note
                    val direction = SwipeDirection.entries[random.nextInt(SwipeDirection.entries.size)]
                    notes.add(Note(timestamp, finalLane, NoteType.SWIPE, swipeDirection = direction))
                }
                else -> {
                    // TAP note
                    notes.add(Note(timestamp, finalLane, NoteType.TAP))
                }
            }

            i++
        }

        return notes
    }
}
