package com.example.beatstarclone

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.media.MediaPlayer
import android.util.Log
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

// 1. DATA CLASSES
data class Tile(var lane: Int, var y: Float, var isHit: Boolean = false, var hitTime: Long = 0L)
data class Note(val timestamp: Long, val lane: Int)

data class HitFeedback(
    val text: String,
    val x: Float,
    val y: Float,
    val alpha: Float,
    val createdAt: Long,
    val color: Int
)

data class LaneGlow(val lane: Int, val startTime: Long, val color: Int)
data class MissFlash(val lane: Int, val startTime: Long)
data class Particle(var x: Float, var y: Float, var vx: Float, var vy: Float, var alpha: Float, val createdAt: Long, val color: Int)

class ScreenTransition(
    val fromState: GameState,
    val toState: GameState,
    var progress: Float,
    val startTime: Long
)

enum class GameState { MAIN_MENU, SONG_SELECT, SETTINGS, PLAYING, PAUSED, GAME_OVER }

class GameView(context: Context, private val settings: GameSettings = GameSettings.DEFAULT) : SurfaceView(context), Runnable {

    // System Variables
    private var playing = false
    private var gameThread: Thread? = null
    private val surfaceHolder: SurfaceHolder = holder
    private val paint = Paint()

    // Thread safety lock
    private val lock = Any()

    // Game State
    @Volatile
    private var gameState = GameState.MAIN_MENU
    @Volatile
    private var consecutiveMisses = 0
    @Volatile
    private var beatsReady = false

    // Game Logic Variables
    private val tiles = CopyOnWriteArrayList<Tile>()
    private val songNotes = ArrayList<Note>()
    private var nextNoteIndex = 0

    // Settings
    private var currentSettings = settings.copy()
    private var perfectLineY = 0f
    private var score = 0
    private var isMissed = false

    // Combo counter
    private var combo = 0

    // Difficulty
    private var difficultyManager: DifficultyManager? = null
    private var currentDifficulty: DifficultyLevel? = null

    // Hit Feedback
    val hitFeedbacks = CopyOnWriteArrayList<HitFeedback>()

    // Lane Glow effects
    private val laneGlows = CopyOnWriteArrayList<LaneGlow>()

    // Miss Flash effects
    private val missFlashes = CopyOnWriteArrayList<MissFlash>()

    // Particles
    private val particles = CopyOnWriteArrayList<Particle>()

    // Combo animation
    private var comboAnimStartTime = 0L
    private var borderFlashTime = 0L

    // Screen Transition
    var currentTransition: ScreenTransition? = null

    // Song selection
    private var selectedSong: SongData = SongRepository.getSongs().first()

    // Audio
    private var mediaPlayer: MediaPlayer? = null
    private val appContext: Context = context.applicationContext

    // Button bounds for touch detection
    private val playButtonBounds = RectF()
    private val settingsButtonBounds = RectF()
    private val quitButtonBounds = RectF()
    private val backButtonBounds = RectF()
    private val songCardBounds = ArrayList<RectF>()
    private val pauseButtonBounds = RectF()
    private val resumeButtonBounds = RectF()
    private val restartButtonBounds = RectF()
    private val quitToMenuButtonBounds = RectF()

    // Settings screen state
    private val speedSliderBounds = RectF()
    private val volumeSliderBounds = RectF()
    private val hitWindowSmallBounds = RectF()
    private val hitWindowMediumBounds = RectF()
    private val hitWindowLargeBounds = RectF()
    private var draggingSlider: Int = -1 // -1 none, 0 speed, 1 volume
    private var hitWindowSelection: Int = 1 // 0=Small, 1=Medium, 2=Large

    init {
        mediaPlayer = MediaPlayer.create(appContext, selectedSong.resId)
        mediaPlayer?.setOnCompletionListener {
            synchronized(lock) {
                gameState = GameState.GAME_OVER
            }
        }
        Thread {
            try {
                val detector = BeatDetector(appContext)
                val beats = detector.detectBeats(selectedSong.resId)
                synchronized(songNotes) {
                    songNotes.addAll(beats)
                }
            } catch (e: Exception) {
                Log.w("GameView", "Beat detection failed, using fallback", e)
                synchronized(songNotes) {
                    generateAutoBeats(bpm = selectedSong.bpm, durationSecs = 240)
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

        // Initialize difficulty manager if needed
        if (difficultyManager == null) {
            val duration = mediaPlayer?.duration?.toLong() ?: 240000L
            difficultyManager = DifficultyManager(duration)
        }

        // Get current difficulty
        val currentTimeMs = mediaPlayer?.currentPosition?.toLong() ?: 0L
        val difficulty = difficultyManager?.getDifficulty(currentTimeMs, currentSettings.tileSpeed)
        currentDifficulty = difficulty
        val activeTileSpeed = difficulty?.tileSpeed ?: currentSettings.tileSpeed
        val activeSpawnAhead = difficulty?.spawnAheadMs ?: 2000L

        // 1. MUSIC SYNC LOGIC
        mediaPlayer?.let { player ->
            if (player.isPlaying) {
                val currentTime = player.currentPosition

                synchronized(songNotes) {
                    synchronized(lock) {
                        if (nextNoteIndex < songNotes.size) {
                            val nextNote = songNotes[nextNoteIndex]

                            if (currentTime >= nextNote.timestamp - activeSpawnAhead) {
                                spawnTile(nextNote.lane)
                                nextNoteIndex++
                            }
                        }
                    }
                }
            }
        }

        // 2. MOVE TILES
        for (tile in tiles) {
            if (tile.isHit) continue
            tile.y += activeTileSpeed

            // Remove if off screen
            if (tile.y > height) {
                tiles.remove(tile)
                if (!tile.isHit) {
                    synchronized(lock) {
                        score -= 10
                        consecutiveMisses++
                        combo = 0

                        if (consecutiveMisses >= 10) {
                            gameState = GameState.GAME_OVER
                            mediaPlayer?.pause()
                            return
                        }
                    }
                    isMissed = true
                    missFlashes.add(MissFlash(tile.lane, System.currentTimeMillis()))
                }
            }
        }

        // Remove hit tiles after animation completes (200ms)
        val now = System.currentTimeMillis()
        tiles.removeAll { tile -> tile.isHit && tile.hitTime > 0L && (now - tile.hitTime > 200) }

        // Update hit feedbacks - remove expired ones (800ms)
        hitFeedbacks.removeAll { feedback -> (now - feedback.createdAt) > 800 }

        // Update lane glows - remove expired ones (300ms)
        laneGlows.removeAll { glow -> (now - glow.startTime) > 300 }

        // Update miss flashes - remove expired ones (200ms)
        missFlashes.removeAll { flash -> (now - flash.startTime) > 200 }

        // Update particles
        for (particle in particles) {
            particle.x += particle.vx
            particle.y += particle.vy
            particle.alpha -= 8f
        }
        particles.removeAll { particle -> particle.alpha <= 0f }
    }

    private fun spawnTile(lane: Int) {
        val newTile = Tile(lane = lane, y = -300f)
        tiles.add(newTile)
    }

    private fun changeState(newState: GameState) {
        val oldState = gameState
        currentTransition = ScreenTransition(oldState, newState, 0f, System.currentTimeMillis())
        gameState = newState
    }

    private fun draw() {
        if (surfaceHolder.surface.isValid) {
            val canvas: Canvas = surfaceHolder.lockCanvas()

            when (gameState) {
                GameState.MAIN_MENU -> drawMainMenu(canvas)
                GameState.SONG_SELECT -> drawSongSelect(canvas)
                GameState.SETTINGS -> drawSettings(canvas)
                GameState.PLAYING -> drawPlayingScreen(canvas)
                GameState.PAUSED -> drawPauseScreen(canvas)
                GameState.GAME_OVER -> drawGameOverScreen(canvas)
            }

            // Apply transition overlay
            currentTransition?.let { transition ->
                val elapsed = System.currentTimeMillis() - transition.startTime
                val progress = (elapsed / 300f).coerceIn(0f, 1f)
                transition.progress = progress
                if (progress >= 1.0f) {
                    currentTransition = null
                }
            }

            surfaceHolder.unlockCanvasAndPost(canvas)
        }
    }

    private fun drawMainMenu(canvas: Canvas) {
        canvas.drawColor(Color.parseColor("#0D0D0D"))

        val centerX = width / 2f
        val buttonWidth = width * 0.6f
        val buttonHeight = 120f
        val buttonSpacing = 40f

        // Title
        paint.isAntiAlias = true
        paint.color = Color.parseColor("#00BCD4")
        paint.textSize = 100f
        paint.textAlign = Paint.Align.CENTER
        paint.style = Paint.Style.FILL
        canvas.drawText("Beatstar Clone", centerX, height / 3f, paint)

        // Buttons starting from center
        val startY = height / 2f

        // Play button
        val playLeft = centerX - buttonWidth / 2f
        val playTop = startY
        playButtonBounds.set(playLeft, playTop, playLeft + buttonWidth, playTop + buttonHeight)
        drawMenuButton(canvas, playButtonBounds, "Play", Color.parseColor("#00BCD4"))

        // Settings button
        val settingsTop = playTop + buttonHeight + buttonSpacing
        settingsButtonBounds.set(playLeft, settingsTop, playLeft + buttonWidth, settingsTop + buttonHeight)
        drawMenuButton(canvas, settingsButtonBounds, "Settings", Color.parseColor("#00BCD4"))

        // Quit button
        val quitTop = settingsTop + buttonHeight + buttonSpacing
        quitButtonBounds.set(playLeft, quitTop, playLeft + buttonWidth, quitTop + buttonHeight)
        drawMenuButton(canvas, quitButtonBounds, "Quit", Color.parseColor("#FF5252"))

        paint.textAlign = Paint.Align.LEFT
    }

    private fun drawMenuButton(canvas: Canvas, bounds: RectF, text: String, accentColor: Int) {
        paint.isAntiAlias = true
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 4f
        paint.color = accentColor
        canvas.drawRoundRect(bounds, 20f, 20f, paint)

        paint.style = Paint.Style.FILL
        paint.color = Color.WHITE
        paint.textSize = 50f
        paint.textAlign = Paint.Align.CENTER
        val textY = bounds.centerY() + 18f
        canvas.drawText(text, bounds.centerX(), textY, paint)
    }

    private fun drawSongSelect(canvas: Canvas) {
        canvas.drawColor(Color.parseColor("#0D0D0D"))

        paint.isAntiAlias = true
        paint.color = Color.parseColor("#00BCD4")
        paint.textSize = 80f
        paint.textAlign = Paint.Align.CENTER
        paint.style = Paint.Style.FILL
        canvas.drawText("Select Song", width / 2f, 150f, paint)

        val songs = SongRepository.getSongs()
        songCardBounds.clear()

        val cardPadding = 30f
        val cardHeight = 180f
        val cardSpacing = 20f
        var cardY = 220f

        for (song in songs) {
            val cardLeft = cardPadding
            val cardRight = width - cardPadding
            val cardBounds = RectF(cardLeft, cardY, cardRight, cardY + cardHeight)
            songCardBounds.add(cardBounds)

            // Card background
            paint.style = Paint.Style.FILL
            paint.color = Color.parseColor("#1A1A1A")
            canvas.drawRoundRect(cardBounds, 16f, 16f, paint)

            // Song title
            paint.color = Color.WHITE
            paint.textSize = 50f
            paint.textAlign = Paint.Align.LEFT
            canvas.drawText(song.title, cardLeft + 30f, cardY + 60f, paint)

            // Artist
            paint.color = Color.parseColor("#888888")
            paint.textSize = 36f
            canvas.drawText(song.artist, cardLeft + 30f, cardY + 105f, paint)

            // Difficulty badge
            paint.color = when (song.difficulty) {
                "Easy" -> Color.parseColor("#4CAF50")
                "Medium" -> Color.parseColor("#FFC107")
                "Hard" -> Color.parseColor("#F44336")
                else -> Color.WHITE
            }
            paint.textSize = 36f
            paint.textAlign = Paint.Align.RIGHT
            canvas.drawText(song.difficulty, cardRight - 30f, cardY + 60f, paint)

            // BPM
            paint.color = Color.parseColor("#888888")
            paint.textSize = 30f
            canvas.drawText("${song.bpm} BPM", cardRight - 30f, cardY + 105f, paint)

            cardY += cardHeight + cardSpacing
        }

        // Back button at bottom
        val backWidth = 200f
        val backHeight = 80f
        val backLeft = width / 2f - backWidth / 2f
        val backTop = height - 150f
        backButtonBounds.set(backLeft, backTop, backLeft + backWidth, backTop + backHeight)

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 3f
        paint.color = Color.parseColor("#888888")
        canvas.drawRoundRect(backButtonBounds, 16f, 16f, paint)

        paint.style = Paint.Style.FILL
        paint.color = Color.WHITE
        paint.textSize = 40f
        paint.textAlign = Paint.Align.CENTER
        canvas.drawText("Back", backButtonBounds.centerX(), backButtonBounds.centerY() + 14f, paint)

        paint.textAlign = Paint.Align.LEFT
    }

    private fun drawSettings(canvas: Canvas) {
        canvas.drawColor(Color.parseColor("#0D0D0D"))

        paint.isAntiAlias = true
        paint.color = Color.parseColor("#00BCD4")
        paint.textSize = 80f
        paint.textAlign = Paint.Align.CENTER
        paint.style = Paint.Style.FILL
        canvas.drawText("Settings", width / 2f, 150f, paint)

        val sliderLeft = 80f
        val sliderRight = width - 80f
        val sliderWidth = sliderRight - sliderLeft

        // Tile Speed slider
        var sliderY = 300f
        paint.color = Color.WHITE
        paint.textSize = 44f
        paint.textAlign = Paint.Align.LEFT
        canvas.drawText("Tile Speed: ${currentSettings.tileSpeed.toInt()}", sliderLeft, sliderY, paint)

        sliderY += 60f
        speedSliderBounds.set(sliderLeft, sliderY - 6f, sliderRight, sliderY + 6f)

        // Track
        paint.color = Color.parseColor("#333333")
        paint.style = Paint.Style.FILL
        canvas.drawRoundRect(RectF(sliderLeft, sliderY - 6f, sliderRight, sliderY + 6f), 6f, 6f, paint)

        // Fill
        val speedProgress = ((currentSettings.tileSpeed - 10f) / 15f).coerceIn(0f, 1f)
        val speedFillX = sliderLeft + sliderWidth * speedProgress
        paint.color = Color.parseColor("#00BCD4")
        canvas.drawRoundRect(RectF(sliderLeft, sliderY - 6f, speedFillX, sliderY + 6f), 6f, 6f, paint)

        // Knob
        paint.color = Color.WHITE
        canvas.drawCircle(speedFillX, sliderY, 20f, paint)

        // Volume slider
        sliderY += 120f
        paint.color = Color.WHITE
        paint.textSize = 44f
        paint.textAlign = Paint.Align.LEFT
        paint.style = Paint.Style.FILL
        val volumePercent = (currentSettings.volume * 100).toInt()
        canvas.drawText("Volume: $volumePercent%", sliderLeft, sliderY, paint)

        sliderY += 60f
        volumeSliderBounds.set(sliderLeft, sliderY - 6f, sliderRight, sliderY + 6f)

        // Track
        paint.color = Color.parseColor("#333333")
        canvas.drawRoundRect(RectF(sliderLeft, sliderY - 6f, sliderRight, sliderY + 6f), 6f, 6f, paint)

        // Fill
        val volumeProgress = currentSettings.volume.coerceIn(0f, 1f)
        val volumeFillX = sliderLeft + sliderWidth * volumeProgress
        paint.color = Color.parseColor("#00BCD4")
        canvas.drawRoundRect(RectF(sliderLeft, sliderY - 6f, volumeFillX, sliderY + 6f), 6f, 6f, paint)

        // Knob
        paint.color = Color.WHITE
        canvas.drawCircle(volumeFillX, sliderY, 20f, paint)

        // Hit Window options
        sliderY += 120f
        paint.color = Color.WHITE
        paint.textSize = 44f
        paint.textAlign = Paint.Align.LEFT
        paint.style = Paint.Style.FILL
        canvas.drawText("Hit Window:", sliderLeft, sliderY, paint)

        sliderY += 60f
        val optionWidth = (sliderWidth - 40f) / 3f
        val optionHeight = 70f

        // Small button
        hitWindowSmallBounds.set(sliderLeft, sliderY, sliderLeft + optionWidth, sliderY + optionHeight)
        drawOptionButton(canvas, hitWindowSmallBounds, "Small", hitWindowSelection == 0)

        // Medium button
        val medLeft = sliderLeft + optionWidth + 20f
        hitWindowMediumBounds.set(medLeft, sliderY, medLeft + optionWidth, sliderY + optionHeight)
        drawOptionButton(canvas, hitWindowMediumBounds, "Medium", hitWindowSelection == 1)

        // Large button
        val largeLeft = medLeft + optionWidth + 20f
        hitWindowLargeBounds.set(largeLeft, sliderY, largeLeft + optionWidth, sliderY + optionHeight)
        drawOptionButton(canvas, hitWindowLargeBounds, "Large", hitWindowSelection == 2)

        // Back button at bottom
        val backWidth = 200f
        val backHeight = 80f
        val backLeft = width / 2f - backWidth / 2f
        val backTop = height - 150f
        backButtonBounds.set(backLeft, backTop, backLeft + backWidth, backTop + backHeight)

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 3f
        paint.color = Color.parseColor("#888888")
        canvas.drawRoundRect(backButtonBounds, 16f, 16f, paint)

        paint.style = Paint.Style.FILL
        paint.color = Color.WHITE
        paint.textSize = 40f
        paint.textAlign = Paint.Align.CENTER
        canvas.drawText("Back", backButtonBounds.centerX(), backButtonBounds.centerY() + 14f, paint)

        paint.textAlign = Paint.Align.LEFT
    }

    private fun drawOptionButton(canvas: Canvas, bounds: RectF, text: String, selected: Boolean) {
        if (selected) {
            paint.style = Paint.Style.FILL
            paint.color = Color.parseColor("#00BCD4")
            canvas.drawRoundRect(bounds, 12f, 12f, paint)
            paint.color = Color.WHITE
        } else {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 3f
            paint.color = Color.parseColor("#888888")
            canvas.drawRoundRect(bounds, 12f, 12f, paint)
            paint.style = Paint.Style.FILL
            paint.color = Color.parseColor("#888888")
        }
        paint.textSize = 36f
        paint.textAlign = Paint.Align.CENTER
        canvas.drawText(text, bounds.centerX(), bounds.centerY() + 12f, paint)
    }

    private fun drawPlayingScreen(canvas: Canvas) {
        canvas.drawColor(Color.BLACK)

        val laneWidth = width / 3f
        perfectLineY = height * 0.8f

        // Draw Lanes
        paint.style = Paint.Style.FILL
        paint.color = Color.DKGRAY
        paint.strokeWidth = 5f
        paint.alpha = 255
        canvas.drawLine(laneWidth, 0f, laneWidth, height.toFloat(), paint)
        canvas.drawLine(laneWidth * 2, 0f, laneWidth * 2, height.toFloat(), paint)

        // Draw Miss Flashes (full-height red overlay on missed lanes)
        val currentTime = System.currentTimeMillis()
        for (flash in missFlashes) {
            val elapsed = currentTime - flash.startTime
            val flashAlpha = (76 * (1f - elapsed / 200f)).toInt().coerceIn(0, 76)
            val laneX = flash.lane * laneWidth
            paint.color = Color.parseColor("#FF5252")
            paint.alpha = flashAlpha
            paint.style = Paint.Style.FILL
            canvas.drawRect(laneX, 0f, laneX + laneWidth, height.toFloat(), paint)
        }
        paint.alpha = 255

        // Draw Lane Glows (at hit zone)
        for (glow in laneGlows) {
            val elapsed = currentTime - glow.startTime
            val glowAlpha = (180 * (1f - elapsed / 300f)).toInt().coerceIn(0, 180)
            val laneX = glow.lane * laneWidth
            paint.color = glow.color
            paint.alpha = glowAlpha
            paint.style = Paint.Style.FILL
            canvas.drawRect(laneX, perfectLineY - 50f, laneX + laneWidth, perfectLineY + 50f, paint)
        }
        paint.alpha = 255

        // Draw Perfect Line
        paint.color = Color.CYAN
        paint.strokeWidth = 10f
        paint.alpha = 255
        canvas.drawLine(0f, perfectLineY, width.toFloat(), perfectLineY, paint)

        // Draw Tiles (non-hit)
        paint.style = Paint.Style.FILL
        paint.color = if (isMissed) Color.RED else Color.GREEN
        paint.alpha = 255

        for (tile in tiles) {
            if (tile.isHit) continue
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

        // Draw hit-animating tiles (scale + fade)
        for (tile in tiles) {
            if (!tile.isHit || tile.hitTime == 0L) continue
            val elapsed = currentTime - tile.hitTime
            val animProgress = (elapsed / 200f).coerceIn(0f, 1f)
            val scale = 1.0f + 0.3f * animProgress
            val tileAlpha = (255 * (1f - animProgress)).toInt().coerceIn(0, 255)

            val tileX = tile.lane * laneWidth
            val padding = 20f
            val tileCenterX = tileX + laneWidth / 2f
            val tileCenterY = tile.y + 150f

            canvas.save()
            canvas.translate(tileCenterX, tileCenterY)
            canvas.scale(scale, scale)
            paint.color = Color.GREEN
            paint.alpha = tileAlpha
            paint.style = Paint.Style.FILL
            canvas.drawRect(
                -laneWidth / 2f + padding,
                -150f,
                laneWidth / 2f - padding,
                150f,
                paint
            )
            canvas.restore()
        }
        paint.alpha = 255

        // Reset Miss Flag after one frame
        isMissed = false

        // Draw Particles
        paint.style = Paint.Style.FILL
        for (particle in particles) {
            paint.color = particle.color
            paint.alpha = particle.alpha.toInt().coerceIn(0, 255)
            canvas.drawCircle(particle.x, particle.y, 5f, paint)
        }
        paint.alpha = 255

        // Draw Hit Feedbacks (floating text)
        for (feedback in hitFeedbacks) {
            val elapsed = currentTime - feedback.createdAt
            val animAlpha = (255 * (1f - elapsed / 800f)).toInt().coerceIn(0, 255)
            val animY = feedback.y - (elapsed * 0.15f)
            paint.color = feedback.color
            paint.alpha = animAlpha
            paint.textSize = 55f
            paint.textAlign = Paint.Align.CENTER
            paint.style = Paint.Style.FILL
            canvas.drawText(feedback.text, feedback.x, animY, paint)
        }
        paint.alpha = 255

        // Draw Score
        paint.color = Color.WHITE
        paint.textSize = 80f
        paint.textAlign = Paint.Align.LEFT
        paint.style = Paint.Style.FILL
        paint.alpha = 255
        canvas.drawText("Score: $score", 50f, 100f, paint)

        // Draw Combo counter with scale animation
        synchronized(lock) {
            if (combo >= 5) {
                val comboElapsed = currentTime - comboAnimStartTime
                val comboScale = if (comboElapsed < 150) {
                    1.0f + 0.4f * (1f - comboElapsed / 150f)
                } else {
                    1.0f
                }

                canvas.save()
                canvas.translate(50f, 185f)
                canvas.scale(comboScale, comboScale)
                // Glow/shadow in cyan
                paint.color = Color.parseColor("#00BCD4")
                paint.textSize = 70f
                paint.alpha = 100
                paint.textAlign = Paint.Align.LEFT
                canvas.drawText("x$combo", 0f, 0f, paint)
                // White text on top
                paint.color = Color.WHITE
                paint.textSize = 60f
                paint.alpha = 255
                canvas.drawText("x$combo", 0f, 0f, paint)
                canvas.restore()
            }
        }
        paint.alpha = 255

        // Draw border flash at combo milestones
        if (currentTime - borderFlashTime < 200) {
            val borderElapsed = currentTime - borderFlashTime
            val borderAlpha = (255 * (1f - borderElapsed / 200f)).toInt().coerceIn(0, 255)
            paint.color = Color.parseColor("#00BCD4")
            paint.alpha = borderAlpha
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 8f
            canvas.drawRect(4f, 4f, width.toFloat() - 4f, height.toFloat() - 4f, paint)
        }
        paint.alpha = 255
        paint.style = Paint.Style.FILL

        // Draw Pause button (two vertical bars) in top-right corner
        paint.color = Color.WHITE
        paint.style = Paint.Style.FILL
        val pauseX = width - 120f
        val pauseY = 40f
        pauseButtonBounds.set(pauseX - 20f, pauseY - 10f, pauseX + 40f, pauseY + 60f)
        canvas.drawRect(pauseX, pauseY, pauseX + 8f, pauseY + 50f, paint)
        canvas.drawRect(pauseX + 20f, pauseY, pauseX + 28f, pauseY + 50f, paint)

        paint.textAlign = Paint.Align.LEFT
    }

    private fun drawPauseScreen(canvas: Canvas) {
        // Draw the frozen game state first
        canvas.drawColor(Color.BLACK)

        val laneWidth = width / 3f
        perfectLineY = height * 0.8f

        // Draw Lanes
        paint.style = Paint.Style.FILL
        paint.color = Color.DKGRAY
        paint.strokeWidth = 5f
        canvas.drawLine(laneWidth, 0f, laneWidth, height.toFloat(), paint)
        canvas.drawLine(laneWidth * 2, 0f, laneWidth * 2, height.toFloat(), paint)

        // Draw Perfect Line
        paint.color = Color.CYAN
        paint.strokeWidth = 10f
        canvas.drawLine(0f, perfectLineY, width.toFloat(), perfectLineY, paint)

        // Draw Tiles (frozen)
        paint.color = Color.GREEN
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

        // Score
        paint.color = Color.WHITE
        paint.textSize = 80f
        paint.textAlign = Paint.Align.LEFT
        paint.style = Paint.Style.FILL
        canvas.drawText("Score: $score", 50f, 100f, paint)

        // Semi-transparent overlay
        paint.color = Color.argb(200, 0, 0, 0)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)

        // PAUSED text
        paint.color = Color.WHITE
        paint.textSize = 120f
        paint.textAlign = Paint.Align.CENTER
        canvas.drawText("PAUSED", width / 2f, height / 3f, paint)

        // Buttons
        val centerX = width / 2f
        val buttonWidth = width * 0.6f
        val buttonHeight = 120f
        val buttonSpacing = 40f
        val startY = height / 2f

        val resumeLeft = centerX - buttonWidth / 2f
        resumeButtonBounds.set(resumeLeft, startY, resumeLeft + buttonWidth, startY + buttonHeight)
        drawMenuButton(canvas, resumeButtonBounds, "Resume", Color.parseColor("#00BCD4"))

        val restartTop = startY + buttonHeight + buttonSpacing
        restartButtonBounds.set(resumeLeft, restartTop, resumeLeft + buttonWidth, restartTop + buttonHeight)
        drawMenuButton(canvas, restartButtonBounds, "Restart", Color.WHITE)

        val quitTop = restartTop + buttonHeight + buttonSpacing
        quitToMenuButtonBounds.set(resumeLeft, quitTop, resumeLeft + buttonWidth, quitTop + buttonHeight)
        drawMenuButton(canvas, quitToMenuButtonBounds, "Quit to Menu", Color.parseColor("#888888"))

        paint.textAlign = Paint.Align.LEFT
    }

    private fun drawGameOverScreen(canvas: Canvas) {
        canvas.drawColor(Color.BLACK)

        // Draw Game Over
        paint.isAntiAlias = true
        paint.color = Color.RED
        paint.textSize = 120f
        paint.textAlign = Paint.Align.CENTER
        paint.style = Paint.Style.FILL
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
        val action = event.actionMasked
        val touchX = event.x
        val touchY = event.y

        when (action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val pointerIndex = if (action == MotionEvent.ACTION_POINTER_DOWN) {
                    event.actionIndex
                } else {
                    0
                }
                val pX = event.getX(pointerIndex)
                val pY = event.getY(pointerIndex)

                when (gameState) {
                    GameState.MAIN_MENU -> handleMainMenuTouch(pX, pY)
                    GameState.SONG_SELECT -> handleSongSelectTouch(pX, pY)
                    GameState.SETTINGS -> handleSettingsDown(pX, pY)
                    GameState.PLAYING -> handlePlayingTouch(pX, pY)
                    GameState.PAUSED -> handlePausedTouch(pX, pY)
                    GameState.GAME_OVER -> {
                        resetGame()
                        synchronized(lock) {
                            changeState(GameState.MAIN_MENU)
                        }
                    }
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (gameState == GameState.SETTINGS) {
                    handleSettingsMove(touchX, touchY)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                if (gameState == GameState.SETTINGS) {
                    draggingSlider = -1
                }
            }
        }
        return true
    }

    private fun handleMainMenuTouch(x: Float, y: Float) {
        when {
            playButtonBounds.contains(x, y) -> {
                synchronized(lock) {
                    changeState(GameState.SONG_SELECT)
                }
            }
            settingsButtonBounds.contains(x, y) -> {
                synchronized(lock) {
                    changeState(GameState.SETTINGS)
                }
            }
            quitButtonBounds.contains(x, y) -> {
                val activity = context as? Activity
                activity?.finish()
            }
        }
    }

    private fun handleSongSelectTouch(x: Float, y: Float) {
        // Check song cards
        val songs = SongRepository.getSongs()
        for (i in songCardBounds.indices) {
            if (i < songs.size && songCardBounds[i].contains(x, y)) {
                selectedSong = songs[i]
                startGameWithSong()
                return
            }
        }
        // Check back button
        if (backButtonBounds.contains(x, y)) {
            synchronized(lock) {
                changeState(GameState.MAIN_MENU)
            }
        }
    }

    private fun handleSettingsDown(x: Float, y: Float) {
        // Check sliders with expanded touch area
        val expandedSpeed = RectF(speedSliderBounds)
        expandedSpeed.top -= 30f
        expandedSpeed.bottom += 30f
        val expandedVolume = RectF(volumeSliderBounds)
        expandedVolume.top -= 30f
        expandedVolume.bottom += 30f

        when {
            expandedSpeed.contains(x, y) -> {
                draggingSlider = 0
                updateSpeedFromTouch(x)
            }
            expandedVolume.contains(x, y) -> {
                draggingSlider = 1
                updateVolumeFromTouch(x)
            }
            hitWindowSmallBounds.contains(x, y) -> {
                hitWindowSelection = 0
                currentSettings = currentSettings.copy(
                    hitWindowPerfect = 56f,
                    hitWindowGood = 112f,
                    hitWindowOK = 175f
                )
            }
            hitWindowMediumBounds.contains(x, y) -> {
                hitWindowSelection = 1
                currentSettings = currentSettings.copy(
                    hitWindowPerfect = 80f,
                    hitWindowGood = 160f,
                    hitWindowOK = 250f
                )
            }
            hitWindowLargeBounds.contains(x, y) -> {
                hitWindowSelection = 2
                currentSettings = currentSettings.copy(
                    hitWindowPerfect = 104f,
                    hitWindowGood = 208f,
                    hitWindowOK = 325f
                )
            }
            backButtonBounds.contains(x, y) -> {
                synchronized(lock) {
                    changeState(GameState.MAIN_MENU)
                }
            }
        }
    }

    private fun handleSettingsMove(x: Float, y: Float) {
        when (draggingSlider) {
            0 -> updateSpeedFromTouch(x)
            1 -> updateVolumeFromTouch(x)
        }
    }

    private fun updateSpeedFromTouch(x: Float) {
        val progress = ((x - speedSliderBounds.left) / speedSliderBounds.width()).coerceIn(0f, 1f)
        val newSpeed = 10f + progress * 15f
        currentSettings = currentSettings.copy(tileSpeed = newSpeed)
    }

    private fun updateVolumeFromTouch(x: Float) {
        val progress = ((x - volumeSliderBounds.left) / volumeSliderBounds.width()).coerceIn(0f, 1f)
        currentSettings = currentSettings.copy(volume = progress)
        mediaPlayer?.setVolume(progress, progress)
    }

    private fun handlePlayingTouch(x: Float, y: Float) {
        // Check pause button
        if (pauseButtonBounds.contains(x, y)) {
            synchronized(lock) {
                changeState(GameState.PAUSED)
            }
            mediaPlayer?.pause()
            return
        }

        // Existing tile tap logic
        val laneWidth = width / 3f
        val touchedLane = (x / laneWidth).toInt()

        val hitWindowMult = currentDifficulty?.hitWindowMultiplier ?: 1.0f

        for (tile in tiles) {
            if (tile.lane == touchedLane && !tile.isHit) {
                val tileCenter = tile.y + 150f
                val distance = abs(tileCenter - perfectLineY)

                val effectiveOK = currentSettings.hitWindowOK * hitWindowMult
                val effectivePerfect = currentSettings.hitWindowPerfect * hitWindowMult
                val effectiveGood = currentSettings.hitWindowGood * hitWindowMult

                if (distance < effectiveOK) {
                    tile.isHit = true
                    tile.hitTime = System.currentTimeMillis()

                    val scoreGain: Int
                    val feedbackText: String
                    val feedbackColor: Int

                    when {
                        distance < effectivePerfect -> {
                            scoreGain = 150
                            feedbackText = "Perfect!"
                            feedbackColor = Color.parseColor("#FF00BCD4")
                        }
                        distance < effectiveGood -> {
                            scoreGain = 100
                            feedbackText = "Good!"
                            feedbackColor = Color.parseColor("#FF4CAF50")
                        }
                        else -> {
                            scoreGain = 50
                            feedbackText = "OK!"
                            feedbackColor = Color.parseColor("#FFFFC107")
                        }
                    }

                    synchronized(lock) {
                        score += scoreGain
                        consecutiveMisses = 0
                        combo++
                        comboAnimStartTime = System.currentTimeMillis()
                        if (combo == 10 || combo == 25 || combo == 50 || combo == 100) {
                            borderFlashTime = System.currentTimeMillis()
                        }
                    }

                    val feedbackX = tile.lane * laneWidth + laneWidth / 2f
                    val feedbackY = perfectLineY - 50f

                    // Floating hit text
                    hitFeedbacks.add(HitFeedback(
                        text = feedbackText,
                        x = feedbackX,
                        y = feedbackY,
                        alpha = 255f,
                        createdAt = System.currentTimeMillis(),
                        color = feedbackColor
                    ))

                    // Score popup near score area
                    hitFeedbacks.add(HitFeedback(
                        text = "+$scoreGain",
                        x = 200f,
                        y = 130f,
                        alpha = 255f,
                        createdAt = System.currentTimeMillis(),
                        color = Color.WHITE
                    ))

                    // Lane glow
                    laneGlows.add(LaneGlow(tile.lane, System.currentTimeMillis(), feedbackColor))

                    // Particle burst on Perfect hits
                    if (distance < effectivePerfect && particles.size < 100) {
                        val centerX = tile.lane * laneWidth + laneWidth / 2f
                        for (i in 0 until 10) {
                            val angle = Random.nextFloat() * (2f * Math.PI.toFloat())
                            val speed = 3f + Random.nextFloat() * 5f
                            particles.add(Particle(
                                x = centerX,
                                y = perfectLineY,
                                vx = cos(angle) * speed,
                                vy = sin(angle) * speed,
                                alpha = 255f,
                                createdAt = System.currentTimeMillis(),
                                color = Color.parseColor("#00BCD4")
                            ))
                        }
                    }

                    break
                }
            }
        }
    }

    private fun handlePausedTouch(x: Float, y: Float) {
        when {
            resumeButtonBounds.contains(x, y) -> {
                synchronized(lock) {
                    changeState(GameState.PLAYING)
                }
                mediaPlayer?.start()
            }
            restartButtonBounds.contains(x, y) -> {
                resetGame()
                synchronized(lock) {
                    changeState(GameState.MAIN_MENU)
                }
            }
            quitToMenuButtonBounds.contains(x, y) -> {
                resetGame()
                synchronized(lock) {
                    changeState(GameState.MAIN_MENU)
                }
            }
        }
    }

    private fun startGameWithSong() {
        tiles.clear()
        synchronized(songNotes) {
            songNotes.clear()
        }
        synchronized(lock) {
            score = 0
            nextNoteIndex = 0
            consecutiveMisses = 0
            combo = 0
        }
        isMissed = false
        hitFeedbacks.clear()
        laneGlows.clear()
        missFlashes.clear()
        particles.clear()
        comboAnimStartTime = 0L
        borderFlashTime = 0L
        currentTransition = null
        difficultyManager = null
        currentDifficulty = null

        mediaPlayer?.release()
        mediaPlayer = MediaPlayer.create(appContext, selectedSong.resId)
        mediaPlayer?.setVolume(currentSettings.volume, currentSettings.volume)
        mediaPlayer?.setOnCompletionListener {
            synchronized(lock) {
                gameState = GameState.GAME_OVER
            }
        }

        beatsReady = false
        Thread {
            try {
                val detector = BeatDetector(appContext)
                val beats = detector.detectBeats(selectedSong.resId)
                synchronized(songNotes) {
                    songNotes.addAll(beats)
                }
            } catch (e: Exception) {
                Log.w("GameView", "Beat detection failed, using fallback", e)
                synchronized(songNotes) {
                    generateAutoBeats(bpm = selectedSong.bpm, durationSecs = 240)
                }
            }
            beatsReady = true
            synchronized(lock) {
                changeState(GameState.PLAYING)
            }
            mediaPlayer?.start()
        }.start()
    }

    private fun resetGame() {
        tiles.clear()
        synchronized(songNotes) {
            songNotes.clear()
        }
        synchronized(lock) {
            score = 0
            nextNoteIndex = 0
            consecutiveMisses = 0
            combo = 0
        }
        isMissed = false
        hitFeedbacks.clear()
        laneGlows.clear()
        missFlashes.clear()
        particles.clear()
        comboAnimStartTime = 0L
        borderFlashTime = 0L
        currentTransition = null
        difficultyManager = null
        currentDifficulty = null

        mediaPlayer?.release()
        mediaPlayer = MediaPlayer.create(appContext, selectedSong.resId)
        mediaPlayer?.setVolume(currentSettings.volume, currentSettings.volume)
        mediaPlayer?.setOnCompletionListener {
            synchronized(lock) {
                gameState = GameState.GAME_OVER
            }
        }

        beatsReady = false
        Thread {
            try {
                val detector = BeatDetector(appContext)
                val beats = detector.detectBeats(selectedSong.resId)
                synchronized(songNotes) {
                    songNotes.addAll(beats)
                }
            } catch (e: Exception) {
                Log.w("GameView", "Beat detection failed, using fallback", e)
                synchronized(songNotes) {
                    generateAutoBeats(bpm = selectedSong.bpm, durationSecs = 240)
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
