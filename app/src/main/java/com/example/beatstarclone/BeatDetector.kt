package com.example.beatstarclone

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.nio.ByteOrder

class BeatDetector(private val context: Context) {

    companion object {
        private const val WINDOW_SIZE = 2048
        private const val HISTORY_SIZE = 43
        private const val THRESHOLD_MULTIPLIER = 1.5
        private const val MIN_BEAT_INTERVAL_MS = 280L
        private const val NUM_LANES = 3
        private const val CLOSE_BEAT_THRESHOLD_MS = 300L
        private const val LOW_PASS_CUTOFF_HZ = 150.0
    }

    fun detectBeats(rawResId: Int): List<Note> {
        val beatTimestamps = decodeAndDetect(rawResId)
        return assignLanes(beatTimestamps)
    }

    /**
     * Streaming beat detection: decodes audio and processes it in fixed-size chunks,
     * never holding the entire PCM in memory. Peak memory usage is just a few KB
     * (the window buffer + energy history) instead of 350MB+ for a full song.
     */
    private fun decodeAndDetect(rawResId: Int): List<Long> {
        val extractor = MediaExtractor()
        val afd = context.resources.openRawResourceFd(rawResId)
        extractor.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
        afd.close()

        // Find audio track
        var audioTrackIndex = -1
        var audioFormat: MediaFormat? = null
        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("audio/")) {
                audioTrackIndex = i
                audioFormat = format
                break
            }
        }

        if (audioTrackIndex == -1 || audioFormat == null) {
            extractor.release()
            throw IllegalStateException("No audio track found in resource")
        }

        extractor.selectTrack(audioTrackIndex)

        val sampleRate = if (audioFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
            audioFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        } else {
            44100
        }

        val channelCount = if (audioFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
            audioFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        } else {
            1
        }

        val mime = audioFormat.getString(MediaFormat.KEY_MIME)
            ?: throw IllegalStateException("No MIME type for audio track")
        val codec = MediaCodec.createDecoderByType(mime)
        codec.configure(audioFormat, null, null, 0)
        codec.start()

        // Low-pass filter coefficient
        val alpha = (2.0 * Math.PI * LOW_PASS_CUTOFF_HZ) /
            (2.0 * Math.PI * LOW_PASS_CUTOFF_HZ + sampleRate)

        // Streaming state: window buffer for energy computation
        val windowBuffer = DoubleArray(WINDOW_SIZE)
        var windowPos = 0
        var windowIndex = 0

        // IIR filter state
        var prevFiltered = 0.0

        // Beat detection state
        val beats = ArrayList<Long>()
        val energyHistory = ArrayList<Double>(HISTORY_SIZE)
        var lastBeatTimeMs = -MIN_BEAT_INTERVAL_MS * 2
        var prevWindowEnergy = 0.0
        var prevPrevWindowEnergy = 0.0

        val bufferInfo = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false
        val timeoutUs = 10000L

        try {
            while (!outputDone) {
                // Feed input buffers
                if (!inputDone) {
                    val inputBufferIndex = codec.dequeueInputBuffer(timeoutUs)
                    if (inputBufferIndex >= 0) {
                        val inputBuffer = codec.getInputBuffer(inputBufferIndex)
                        if (inputBuffer != null) {
                            val sampleSize = extractor.readSampleData(inputBuffer, 0)
                            if (sampleSize < 0) {
                                codec.queueInputBuffer(
                                    inputBufferIndex, 0, 0, 0,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM
                                )
                                inputDone = true
                            } else {
                                val presentationTimeUs = extractor.sampleTime
                                codec.queueInputBuffer(
                                    inputBufferIndex, 0, sampleSize, presentationTimeUs, 0
                                )
                                extractor.advance()
                            }
                        }
                    }
                }

                // Drain output buffers
                val outputBufferIndex = codec.dequeueOutputBuffer(bufferInfo, timeoutUs)
                if (outputBufferIndex >= 0) {
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        outputDone = true
                    }

                    val outputBuffer = codec.getOutputBuffer(outputBufferIndex)
                    if (outputBuffer != null && bufferInfo.size > 0) {
                        outputBuffer.position(bufferInfo.offset)
                        outputBuffer.limit(bufferInfo.offset + bufferInfo.size)

                        val shortBuffer = outputBuffer.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                        val shortCount = shortBuffer.remaining()

                        // Process samples inline: apply low-pass filter and accumulate into window
                        if (channelCount == 2) {
                            var i = 0
                            while (i + 1 < shortCount) {
                                val left = shortBuffer.get().toInt()
                                val right = shortBuffer.get().toInt()
                                val monoSample = ((left + right) / 2).toDouble() / Short.MAX_VALUE

                                // Apply single-pole IIR low-pass filter
                                val filtered = alpha * monoSample + (1.0 - alpha) * prevFiltered
                                prevFiltered = filtered

                                // Accumulate into window buffer
                                windowBuffer[windowPos] = filtered
                                windowPos++

                                // When window is full, compute energy and run beat detection
                                if (windowPos >= WINDOW_SIZE) {
                                    val energy = computeWindowEnergy(windowBuffer)
                                    processWindow(
                                        energy, windowIndex, sampleRate,
                                        energyHistory, beats,
                                        prevWindowEnergy, prevPrevWindowEnergy,
                                        lastBeatTimeMs
                                    )?.let { beatTime ->
                                        lastBeatTimeMs = beatTime
                                    }
                                    prevPrevWindowEnergy = prevWindowEnergy
                                    prevWindowEnergy = energy
                                    windowIndex++
                                    windowPos = 0
                                }

                                i += 2
                            }
                        } else {
                            for (i in 0 until shortCount) {
                                val rawSample = shortBuffer.get().toDouble() / Short.MAX_VALUE

                                // Apply single-pole IIR low-pass filter
                                val filtered = alpha * rawSample + (1.0 - alpha) * prevFiltered
                                prevFiltered = filtered

                                // Accumulate into window buffer
                                windowBuffer[windowPos] = filtered
                                windowPos++

                                // When window is full, compute energy and run beat detection
                                if (windowPos >= WINDOW_SIZE) {
                                    val energy = computeWindowEnergy(windowBuffer)
                                    processWindow(
                                        energy, windowIndex, sampleRate,
                                        energyHistory, beats,
                                        prevWindowEnergy, prevPrevWindowEnergy,
                                        lastBeatTimeMs
                                    )?.let { beatTime ->
                                        lastBeatTimeMs = beatTime
                                    }
                                    prevPrevWindowEnergy = prevWindowEnergy
                                    prevWindowEnergy = energy
                                    windowIndex++
                                    windowPos = 0
                                }
                            }
                        }
                    }

                    codec.releaseOutputBuffer(outputBufferIndex, false)
                }
            }
        } finally {
            codec.stop()
            codec.release()
            extractor.release()
        }

        beats.sort()
        return beats
    }

    private fun computeWindowEnergy(windowBuffer: DoubleArray): Double {
        var sumSquared = 0.0
        for (sample in windowBuffer) {
            sumSquared += sample * sample
        }
        return Math.sqrt(sumSquared / WINDOW_SIZE)
    }

    /**
     * Process a single window's energy for beat detection.
     * Returns the beat timestamp if a beat was detected, null otherwise.
     */
    private fun processWindow(
        energy: Double,
        windowIndex: Int,
        sampleRate: Int,
        energyHistory: ArrayList<Double>,
        beats: ArrayList<Long>,
        prevWindowEnergy: Double,
        prevPrevWindowEnergy: Double,
        lastBeatTimeMs: Long
    ): Long? {
        // Check if current window is a local peak (greater than previous window)
        // For the streaming approach, we detect peaks by comparing to the previous window.
        // A peak is when current energy > previous energy and previous energy >= the one before that
        // (i.e., previous was rising and now we are at a peak or the previous was a peak).
        // Simplified: current energy > previous energy means we are still rising, so the
        // actual peak detection checks if previous was a peak: prev > prevPrev and prev > current.
        // However, since we process one window at a time, we detect a beat at windowIndex-1
        // when prevWindowEnergy > prevPrevWindowEnergy and prevWindowEnergy > energy.
        val isLocalPeak = if (windowIndex >= 2) {
            prevWindowEnergy > prevPrevWindowEnergy && prevWindowEnergy > energy
        } else if (windowIndex == 1) {
            prevWindowEnergy > energy
        } else {
            false
        }

        // Compute rolling average for adaptive threshold
        val averageEnergy = if (energyHistory.isNotEmpty()) {
            energyHistory.sum() / energyHistory.size
        } else {
            energy
        }

        // The peak is at windowIndex-1 (the previous window)
        val peakWindowIndex = windowIndex - 1
        val timestampMs = (peakWindowIndex.toLong() * WINDOW_SIZE * 1000L) / sampleRate

        var result: Long? = null
        if (isLocalPeak &&
            prevWindowEnergy > averageEnergy * THRESHOLD_MULTIPLIER &&
            timestampMs - lastBeatTimeMs >= MIN_BEAT_INTERVAL_MS
        ) {
            beats.add(timestampMs)
            result = timestampMs
        }

        // Update history with current energy
        energyHistory.add(energy)
        if (energyHistory.size > HISTORY_SIZE) {
            energyHistory.removeAt(0)
        }

        return result
    }

    internal fun assignLanes(beatTimestamps: List<Long>): List<Note> {
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
