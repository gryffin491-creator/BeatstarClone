package com.example.beatstarclone

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.nio.ByteOrder

data class DecodeResult(val samples: ShortArray, val sampleRate: Int)

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
        val decodeResult = decodeAudioToSamples(rawResId)
        val beatTimestamps = detectBeatTimestamps(decodeResult.samples, decodeResult.sampleRate)
        return assignLanes(beatTimestamps)
    }

    private fun decodeAudioToSamples(rawResId: Int): DecodeResult {
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

        val pcmData = ArrayList<Short>(1024 * 1024)
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

                        if (channelCount == 2) {
                            // Downmix stereo to mono by averaging L+R pairs
                            var i = 0
                            while (i + 1 < shortCount) {
                                val left = shortBuffer.get().toInt()
                                val right = shortBuffer.get().toInt()
                                pcmData.add(((left + right) / 2).toShort())
                                i += 2
                            }
                        } else {
                            for (i in 0 until shortCount) {
                                pcmData.add(shortBuffer.get())
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

        return DecodeResult(pcmData.toShortArray(), sampleRate)
    }

    private fun detectBeatTimestamps(samples: ShortArray, sampleRate: Int): List<Long> {
        // Apply single-pole IIR low-pass filter to isolate bass/kick frequencies
        val alpha = (2.0 * Math.PI * LOW_PASS_CUTOFF_HZ) /
            (2.0 * Math.PI * LOW_PASS_CUTOFF_HZ + sampleRate)
        val filtered = DoubleArray(samples.size)
        filtered[0] = alpha * (samples[0].toDouble() / Short.MAX_VALUE)
        for (i in 1 until samples.size) {
            val sample = samples[i].toDouble() / Short.MAX_VALUE
            filtered[i] = alpha * sample + (1.0 - alpha) * filtered[i - 1]
        }

        // Compute energy per window from filtered (bass) signal
        val totalWindows = filtered.size / WINDOW_SIZE
        val windowEnergies = DoubleArray(totalWindows)
        for (windowIndex in 0 until totalWindows) {
            val startSample = windowIndex * WINDOW_SIZE
            var sumSquared = 0.0
            for (i in startSample until startSample + WINDOW_SIZE) {
                sumSquared += filtered[i] * filtered[i]
            }
            windowEnergies[windowIndex] = Math.sqrt(sumSquared / WINDOW_SIZE)
        }

        // Adaptive thresholding with peak-picking
        val beats = ArrayList<Long>()
        val energyHistory = ArrayList<Double>(HISTORY_SIZE)
        var lastBeatTimeMs = -MIN_BEAT_INTERVAL_MS * 2

        for (windowIndex in 0 until totalWindows) {
            val energy = windowEnergies[windowIndex]

            // Check if current window is a local peak (greater than neighbors)
            val isLocalPeak = when {
                windowIndex == 0 -> energy > windowEnergies.getOrElse(1) { 0.0 }
                windowIndex == totalWindows - 1 -> energy > windowEnergies[windowIndex - 1]
                else -> energy > windowEnergies[windowIndex - 1] &&
                    energy > windowEnergies[windowIndex + 1]
            }

            // Compute rolling average for adaptive threshold
            val averageEnergy = if (energyHistory.isNotEmpty()) {
                energyHistory.sum() / energyHistory.size
            } else {
                energy
            }

            val timestampMs = (windowIndex.toLong() * WINDOW_SIZE * 1000L) / sampleRate
            if (isLocalPeak &&
                energy > averageEnergy * THRESHOLD_MULTIPLIER &&
                timestampMs - lastBeatTimeMs >= MIN_BEAT_INTERVAL_MS
            ) {
                beats.add(timestampMs)
                lastBeatTimeMs = timestampMs
            }

            // Update history
            energyHistory.add(energy)
            if (energyHistory.size > HISTORY_SIZE) {
                energyHistory.removeAt(0)
            }
        }

        beats.sort()
        return beats
    }

    private fun assignLanes(beatTimestamps: List<Long>): List<Note> {
        if (beatTimestamps.isEmpty()) return emptyList()

        val notes = ArrayList<Note>(beatTimestamps.size)
        var lastLane = 1
        var consecutiveSameLane = 0

        for (i in beatTimestamps.indices) {
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
            notes.add(Note(timestamp, finalLane))
        }

        return notes
    }
}
