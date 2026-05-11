package com.example.beatstarclone

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.media.MediaPlayer
import android.util.Log
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.abs

// 1. DATA CLASSES
data class Tile(var lane: Int, var y: Float, var isHit: Boolean = false)
data class Note(val timestamp: Long, val lane: Int)

enum class GameState { START, PLAYING, GAME_OVER }

class GameView(context: Context) : SurfaceView(context), Runnable {

    // System Variables
    private var playing = false
    private var gameThread: Thread? = null
    private val surfaceHolder: SurfaceHolder = holder
    private val paint = Paint()

    // Game State
    @Volatile
    private var gameState = GameState.START
    @Volatile
    private var consecutiveMisses = 0
    @Volatile
    private var beatsReady = false

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
    private val appContext: Context = context.applicationContext

    init {
        mediaPlayer = MediaPlayer.create(appContext, R.raw.beat)
        mediaPlayer?.setOnCompletionListener {
            gameState = GameState.GAME_OVER
        }
        Thread {
            try {
                val detector = BeatDetector(appContext)
                val beats = detector.detectBeats(R.raw.beat)
                synchronized(songNotes) {
                    songNotes.addAll(beats)
                }
            } catch (e: Exception) {
                Log.w("GameView", "Beat detection failed, using fallback", e)
                synchronized(songNotes) {
                    generateAutoBeats(bpm = 105, durationSecs = 240)
                }
            }
            beatsReady = true
        }.start()
    }

    override fun run() {
        while (playing) {
            update()
            draw()
            control()
        }
    }

    // --- THE AUTOMATION ENGINE ---
    private fun generateAutoBeats(bpm: Int, durationSecs: Int) {
        val msPerBeat = 60000 / bpm
        val totalBeats = (durationSecs * 1000) / msPerBeat

        var currentTimestamp = 2000L

        for (i in 0 until totalBeats) {
            val isRest = (0..10).random() == 0

            if (!isRest) {
                val randomLane = (0..2).random()
                songNotes.add(Note(currentTimestamp, randomLane))
            }

            currentTimestamp += msPerBeat
        }
    }

    private fun update() {
        if (gameState != GameState.PLAYING) return

        // 1. MUSIC SYNC LOGIC
        mediaPlayer?.let { player ->
            if (player.isPlaying) {
                val currentTime = player.currentPosition

                synchronized(songNotes) {
                    if (nextNoteIndex < songNotes.size) {
                        val nextNote = songNotes[nextNoteIndex]

                        if (currentTime >= nextNote.timestamp - 2000) {
                            spawnTile(nextNote.lane)
                            nextNoteIndex++
                        }
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
                    isMissed = true
                    consecutiveMisses++

                    if (consecutiveMisses >= 10) {
                        gameState = GameState.GAME_OVER
                        mediaPlayer?.pause()
                        return
                    }
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

            when (gameState) {
                GameState.START -> drawStartScreen(canvas)
                GameState.PLAYING -> drawPlayingScreen(canvas)
                GameState.GAME_OVER -> drawGameOverScreen(canvas)
            }

            surfaceHolder.unlockCanvasAndPost(canvas)
        }
    }

    private fun drawStartScreen(canvas: Canvas) {
        canvas.drawColor(Color.BLACK)

        val laneWidth = width / 3f

        // Draw faint lane lines for visual appeal
        paint.color = Color.argb(60, 100, 100, 100)
        paint.strokeWidth = 3f
        canvas.drawLine(laneWidth, 0f, laneWidth, height.toFloat(), paint)
        canvas.drawLine(laneWidth * 2, 0f, laneWidth * 2, height.toFloat(), paint)

        // Draw title
        paint.color = Color.CYAN
        paint.textSize = 100f
        paint.textAlign = Paint.Align.CENTER
        canvas.drawText("Beatstar Clone", width / 2f, height / 3f, paint)

        // Draw tap to start or analyzing message
        paint.color = Color.WHITE
        paint.textSize = 60f
        if (beatsReady) {
            canvas.drawText("Tap to Start", width / 2f, height / 2f, paint)
        } else {
            canvas.drawText("Analyzing audio...", width / 2f, height / 2f, paint)
        }

        paint.textAlign = Paint.Align.LEFT
    }

    private fun drawPlayingScreen(canvas: Canvas) {
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

        // Reset Miss Flag after one frame
        isMissed = false

        // Draw Score
        paint.color = Color.WHITE
        paint.textSize = 80f
        paint.textAlign = Paint.Align.LEFT
        canvas.drawText("Score: $score", 50f, 100f, paint)
    }

    private fun drawGameOverScreen(canvas: Canvas) {
        canvas.drawColor(Color.BLACK)

        // Draw Game Over
        paint.color = Color.RED
        paint.textSize = 120f
        paint.textAlign = Paint.Align.CENTER
        canvas.drawText("Game Over", width / 2f, height / 3f, paint)

        // Draw Score
        paint.color = Color.WHITE
        paint.textSize = 80f
        canvas.drawText("Score: $score", width / 2f, height / 2f, paint)

        // Draw Tap to Restart
        paint.color = Color.CYAN
        paint.textSize = 60f
        canvas.drawText("Tap to Restart", width / 2f, height * 0.65f, paint)

        paint.textAlign = Paint.Align.LEFT
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_DOWN) {
            when (gameState) {
                GameState.START -> {
                    if (beatsReady) {
                        gameState = GameState.PLAYING
                        mediaPlayer?.start()
                    }
                }
                GameState.GAME_OVER -> {
                    resetGame()
                    gameState = GameState.START
                }
                GameState.PLAYING -> {
                    val laneWidth = width / 3f
                    val touchedLane = (event.x / laneWidth).toInt()

                    synchronized(tiles) {
                        for (tile in tiles) {
                            if (tile.lane == touchedLane && !tile.isHit) {
                                val tileBottom = tile.y + 300f
                                val distance = abs(tileBottom - perfectLineY)

                                if (distance < 150) {
                                    tile.isHit = true
                                    score += 100
                                    tiles.remove(tile)
                                    consecutiveMisses = 0
                                    break
                                }
                            }
                        }
                    }
                }
            }
        }
        return true
    }

    private fun resetGame() {
        tiles.clear()
        synchronized(songNotes) {
            songNotes.clear()
        }
        score = 0
        nextNoteIndex = 0
        consecutiveMisses = 0
        isMissed = false

        mediaPlayer?.release()
        mediaPlayer = MediaPlayer.create(appContext, R.raw.beat)
        mediaPlayer?.setOnCompletionListener {
            gameState = GameState.GAME_OVER
        }

        beatsReady = false
        Thread {
            try {
                val detector = BeatDetector(appContext)
                val beats = detector.detectBeats(R.raw.beat)
                synchronized(songNotes) {
                    songNotes.addAll(beats)
                }
            } catch (e: Exception) {
                Log.w("GameView", "Beat detection failed, using fallback", e)
                synchronized(songNotes) {
                    generateAutoBeats(bpm = 105, durationSecs = 240)
                }
            }
            beatsReady = true
        }.start()
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
