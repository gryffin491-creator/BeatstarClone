package com.example.beatstarclone // Make sure this matches your actual package name!

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.media.MediaPlayer
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.abs

// 1. DATA CLASSES
data class Tile(var lane: Int, var y: Float, var isHit: Boolean = false)
data class Note(val timestamp: Long, val lane: Int)

class GameView(context: Context) : SurfaceView(context), Runnable {

    // System Variables
    private var playing = false
    private var gameThread: Thread? = null
    private val surfaceHolder: SurfaceHolder = holder
    private val paint = Paint()

    // Game Logic Variables
    private val tiles = CopyOnWriteArrayList<Tile>()
    private val songNotes = ArrayList<Note>()
    private var nextNoteIndex = 0

    // Settings
    private val tileSpeed = 15f
    private var perfectLineY = 0f
    private var score = 0
    private var isMissed = false

    // Audio
    private var mediaPlayer: MediaPlayer? = null

    init {
        // LOAD AUDIO
        // Make sure the file is named 'unity.mp3' in res/raw/
        mediaPlayer = MediaPlayer.create(context, R.raw.beat)

        // --- THE "UNITY" BEAT MAP ---
        // The song starts quiet. Let's wait for the first melody kick (approx 2 seconds in).
        // 105 BPM = A beat roughly every 570ms.
// AUTOMATICALLY GENERATE THE WHOLE SONG
        generateAutoBeats(bpm = 105, durationSecs = 240) // 4 minutes
    }

    override fun run() {
        while (playing) {
            update()
            draw()
            control()
        }
    }

    init {
        // Load Audio
        mediaPlayer = MediaPlayer.create(context, R.raw.beat)

        // AUTOMATICALLY GENERATE THE WHOLE SONG
        generateAutoBeats(bpm = 105, durationSecs = 240) // 4 minutes
    }

    // --- THE AUTOMATION ENGINE ---
    private fun generateAutoBeats(bpm: Int, durationSecs: Int) {
        // 1. Calculate time between beats
        val msPerBeat = 60000 / bpm

        // 2. Calculate total beats in the song
        val totalBeats = (durationSecs * 1000) / msPerBeat

        var currentTimestamp = 2000L // Start at 2 seconds (Give player time to get ready)

        // 3. Loop to create notes
        for (i in 0 until totalBeats) {
            // 10% chance to SKIP a beat (create a rest)
            val isRest = (0..10).random() == 0 // 1 in 10 chance

            if (!isRest) {
                val randomLane = (0..2).random()
                songNotes.add(Note(currentTimestamp, randomLane))
            }

            currentTimestamp += msPerBeat
        }
    }

    private fun update() {
        // 1. MUSIC SYNC LOGIC
        mediaPlayer?.let { player ->
            if (player.isPlaying) {
                val currentTime = player.currentPosition

                if (nextNoteIndex < songNotes.size) {
                    val nextNote = songNotes[nextNoteIndex]

                    // Simple Sync: If song time >= note time, spawn it!
                    if (currentTime >= nextNote.timestamp - 2000) {
                        spawnTile(nextNote.lane)
                        nextNoteIndex++
                    }
                }
            }
        }

        // 2. MOVE TILES
        for (tile in tiles) {
            tile.y += tileSpeed

            // Remove if off screen
            if (tile.y > height) {
                tiles.remove(tile)
                if (!tile.isHit) {
                    score -= 10
                    isMissed = true // Triggers red flash
                }
            }
        }
    }

    private fun spawnTile(lane: Int) {
        val newTile = Tile(lane = lane, y = -300f)
        tiles.add(newTile)
    }

    private fun draw() {
        if (surfaceHolder.surface.isValid) {
            val canvas: Canvas = surfaceHolder.lockCanvas()

            // Background
            canvas.drawColor(Color.BLACK)

            val laneWidth = width / 3f
            perfectLineY = height * 0.8f

            // Draw Lanes
            paint.color = Color.DKGRAY
            paint.strokeWidth = 5f
            canvas.drawLine(laneWidth, 0f, laneWidth, height.toFloat(), paint)
            canvas.drawLine(laneWidth * 2, 0f, laneWidth * 2, height.toFloat(), paint)

            // Draw Perfect Line
            paint.color = Color.CYAN
            paint.strokeWidth = 10f
            canvas.drawLine(0f, perfectLineY, width.toFloat(), perfectLineY, paint)

            // Draw Tiles
            paint.color = if (isMissed) Color.RED else Color.GREEN

            for (tile in tiles) {
                val tileX = tile.lane * laneWidth
                val padding = 20f
                canvas.drawRect(
                    tileX + padding,
                    tile.y,
                    tileX + laneWidth - padding,
                    tile.y + 300f,
                    paint
                )
            }

            // Reset Miss Flag after one frame (so it doesn't stay red forever)
            isMissed = false

            // Draw Score
            paint.color = Color.WHITE
            paint.textSize = 80f
            canvas.drawText("Score: $score", 50f, 100f, paint)

            surfaceHolder.unlockCanvasAndPost(canvas)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_DOWN) {
            val laneWidth = width / 3f
            val touchedLane = (event.x / laneWidth).toInt()

            // Check hit
            for (tile in tiles) {
                if (tile.lane == touchedLane && !tile.isHit) {
                    val tileBottom = tile.y + 300f
                    val distance = abs(tileBottom - perfectLineY)

                    // Hit window: 150 pixels
                    if (distance < 150) {
                        tile.isHit = true
                        score += 100
                        tiles.remove(tile)
                        break
                    }
                }
            }
        }
        return true
    }

    private fun control() {
        try {
            Thread.sleep(17)
        } catch (e: InterruptedException) {
            e.printStackTrace()
        }
    }

    fun resume() {
        playing = true
        gameThread = Thread(this)
        gameThread?.start()
        mediaPlayer?.start()
    }

    fun pause() {
        playing = false
        try {
            gameThread?.join()
        } catch (e: InterruptedException) {
            e.printStackTrace()
        }
        mediaPlayer?.pause()
    }
}