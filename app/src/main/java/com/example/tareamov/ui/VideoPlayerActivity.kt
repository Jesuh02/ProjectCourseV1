package com.example.tareamov.ui

import android.content.Intent
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.example.tareamov.R
import com.example.tareamov.util.UriPermissionManager

class VideoPlayerActivity : AppCompatActivity() {

    private lateinit var videoView: VideoView
    private lateinit var uriPermissionManager: UriPermissionManager

    private lateinit var controlsOverlay: FrameLayout
    private lateinit var playPauseOverlay: ImageView
    private lateinit var backButton: ImageView
    private lateinit var seekBar: SeekBar
    private lateinit var currentTime: TextView
    private lateinit var totalTime: TextView
    private lateinit var muteButton: ImageView
    private lateinit var likeButton: ImageView
    private lateinit var shareButton: ImageView
    private lateinit var titleText: TextView
    private lateinit var skipBackIcon: ImageView
    private lateinit var skipForwardIcon: ImageView

    private var mediaPlayer: MediaPlayer? = null
    private var isControlsVisible = false
    private var isMuted = false
    private val uiHandler = Handler(Looper.getMainLooper())
    private var progressRunnable: Runnable? = null
    private val autoHideDelayMs = 3000L
    private var autoHideRunnable: Runnable? = null
    private var pendingUserSeekMs: Int? = null
    private var isScrubbing: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_video_player)

        // Immersive fullscreen
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_FULLSCREEN
        )

        // Views
        videoView = findViewById(R.id.videoView)
        controlsOverlay = findViewById(R.id.controlsOverlay)
        playPauseOverlay = findViewById(R.id.playPauseOverlay)
        backButton = findViewById(R.id.backButton)
        seekBar = findViewById(R.id.seekBar)
        currentTime = findViewById(R.id.currentTime)
        totalTime = findViewById(R.id.totalTime)
        muteButton = findViewById(R.id.muteButton)
        titleText = findViewById(R.id.titleText)
        skipBackIcon = findViewById(R.id.skipBackIcon)
        skipForwardIcon = findViewById(R.id.skipForwardIcon)

        uriPermissionManager = UriPermissionManager(this)

        // Read intent extras from adapter
        val pathOrUri = intent.getStringExtra("video_path")
        val videoTitle = intent.getStringExtra("video_title") ?: getString(R.string.app_name)
        val videoDescription = intent.getStringExtra("video_description")
        val username = intent.getStringExtra("username")
        titleText.text = videoTitle

        Log.d("VideoPlayerActivity", "Received pathOrUri: $pathOrUri")
        Log.d("VideoPlayerActivity", "Received videoTitle: $videoTitle")

        val uri = try {
            when {
                pathOrUri.isNullOrBlank() -> {
                    Log.e("VideoPlayerActivity", "pathOrUri is null or blank")
                    null
                }
                pathOrUri.startsWith("content://") -> {
                    Log.d("VideoPlayerActivity", "Processing content URI: $pathOrUri")
                    Uri.parse(pathOrUri)
                }
                pathOrUri.startsWith("file://") -> {
                    Log.d("VideoPlayerActivity", "Processing file URI: $pathOrUri")
                    Uri.parse(pathOrUri)
                }
                pathOrUri.startsWith("android.resource://") -> {
                    Log.d("VideoPlayerActivity", "Processing resource URI: $pathOrUri")
                    Uri.parse(pathOrUri)
                }
                else -> {
                    Log.d("VideoPlayerActivity", "Processing file path: $pathOrUri")
                    val file = java.io.File(pathOrUri)
                    if (file.exists()) {
                        Uri.fromFile(file)
                    } else {
                        Log.e("VideoPlayerActivity", "File does not exist: $pathOrUri")
                        null
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("VideoPlayerActivity", "Error parsing URI: $pathOrUri", e)
            null
        }

        if (uri == null) {
            Log.e("VideoPlayerActivity", "Final URI is null for pathOrUri: $pathOrUri")
            Toast.makeText(this, "URI de video no válida: ${pathOrUri ?: "null"}", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        Log.d("VideoPlayerActivity", "Final processed URI: $uri")

        if (uri.scheme == "content" && !uriPermissionManager.hasPermissionForUri(uri)) {
            uriPermissionManager.takePersistablePermission(uri)
        }

        videoView.setVideoURI(uri)
        videoView.setOnPreparedListener { mp ->
            mediaPlayer = mp
            mp.isLooping = true
            totalTime.text = formatTime(mp.duration)
            seekBar.max = mp.duration

            // Ajuste explícito de aspecto para modo visualización (sin tocar)
            try {
                val videoW = mp.videoWidth
                val videoH = mp.videoHeight
                if (videoW > 0 && videoH > 0) {
                    val dm = resources.displayMetrics
                    val screenW = dm.widthPixels
                    val screenH = dm.heightPixels
                    val videoRatio = videoW.toFloat() / videoH
                    val screenRatio = screenW.toFloat() / screenH

                    val (targetW, targetH) = if (videoRatio > screenRatio) {
                        // Video más ancho: ajusta al ancho de pantalla, deja barras arriba/abajo si toca
                        val h = (screenW / videoRatio).toInt()
                        screenW to h
                    } else {
                        // Video más alto o cuadrado: ajusta a la altura, deja barras a los lados si toca
                        val w = (screenH * videoRatio).toInt()
                        w to screenH
                    }

                    val lp = videoView.layoutParams
                    lp.width = targetW
                    lp.height = targetH
                    videoView.layoutParams = lp
                }
            } catch (_: Exception) { }

            setMuted(isMuted)
            videoView.start()
            startProgressUpdater()

            // Sync when seek completes
            try {
                mp.setOnSeekCompleteListener {
                    if (!isScrubbing) {
                        val pos = videoView.currentPosition
                        currentTime.text = formatTime(pos)
                        seekBar.progress = pos
                    }
                }
            } catch (_: Exception) { }
        }

        videoView.setOnErrorListener { _, what, extra ->
            Log.e("VideoPlayerActivity", "Error playing video: what=$what, extra=$extra")
            Toast.makeText(this, "Error al reproducir el video", Toast.LENGTH_SHORT).show()
            finish()
            true
        }

    // Start with controls hidden
    hideControls(immediate = true)

    // Interaction: any tap shows controls and resets auto-hide timer
        controlsOverlay.setOnClickListener {
            showControls()
            // Also toggle play/pause feedback like many players do
            if (videoView.isPlaying) {
                videoView.pause()
                showCenterOverlay(R.drawable.ic_play_overlay)
            } else {
                videoView.start()
                showCenterOverlay(R.drawable.ic_pause_overlay)
            }
        }
        backButton.setOnClickListener { finish() }

        skipBackIcon.setOnClickListener { seekBy(-10_000) }
        skipForwardIcon.setOnClickListener { seekBy(10_000) }

    muteButton.setOnClickListener {
            isMuted = !isMuted
            setMuted(isMuted)
        }
        

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    // No buscar en caliente: solo actualiza tiempo mostrado y guarda posición
                    pendingUserSeekMs = progress
                    currentTime.text = formatTime(progress)
                    // Keep controls visible while scrubbing
                    showControls()
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {
                // Cancel auto hide while dragging
                cancelAutoHide()
                isScrubbing = true
                showControls()
            }
            override fun onStopTrackingTouch(sb: SeekBar?) {
                // Realiza el seek definitivo al soltar
                pendingUserSeekMs?.let { target ->
                    try { videoView.seekTo(target) } catch (_: Exception) {}
                }
                pendingUserSeekMs = null
                isScrubbing = false
                scheduleAutoHide()
            }
        })
    }

    private fun showControls() {
        cancelAutoHide()
        if (!isControlsVisible) {
            isControlsVisible = true
            fadeVisibility(findViewById(R.id.topBar), true)
            fadeVisibility(findViewById(R.id.bottomBar), true)
            fadeVisibility(skipBackIcon, true)
            fadeVisibility(skipForwardIcon, true)
        }
        scheduleAutoHide()
    }

    private fun hideControls(immediate: Boolean = false) {
        cancelAutoHide()
        if (isControlsVisible) {
            isControlsVisible = false
            if (immediate) {
                findViewById<View>(R.id.topBar).apply { alpha = 0f; visibility = View.GONE }
                findViewById<View>(R.id.bottomBar).apply { alpha = 0f; visibility = View.GONE }
                skipBackIcon.apply { alpha = 0f; visibility = View.GONE }
                skipForwardIcon.apply { alpha = 0f; visibility = View.GONE }
            } else {
                fadeVisibility(findViewById(R.id.topBar), false)
                fadeVisibility(findViewById(R.id.bottomBar), false)
                fadeVisibility(skipBackIcon, false)
                fadeVisibility(skipForwardIcon, false)
            }
        }
    }

    private fun fadeVisibility(view: View, show: Boolean) {
        val toAlpha = if (show) 1f else 0f
        val endVisibility = if (show) View.VISIBLE else View.GONE
        if (show) view.visibility = View.VISIBLE
        view.animate()
            .alpha(toAlpha)
            .setDuration(250)
            .withEndAction { view.visibility = endVisibility }
            .start()
    }

    private fun scheduleAutoHide() {
        cancelAutoHide()
        autoHideRunnable = Runnable { hideControls(immediate = false) }
        uiHandler.postDelayed(autoHideRunnable!!, autoHideDelayMs)
    }

    private fun cancelAutoHide() {
        autoHideRunnable?.let { uiHandler.removeCallbacks(it) }
    }

    private fun startProgressUpdater() {
        progressRunnable?.let { uiHandler.removeCallbacks(it) }
        progressRunnable = object : Runnable {
            override fun run() {
                try {
                    if (!isScrubbing) {
                        val pos = videoView.currentPosition
                        seekBar.progress = pos
                        currentTime.text = formatTime(pos)
                    }
                } finally {
                    uiHandler.postDelayed(this, 500)
                }
            }
        }
        uiHandler.post(progressRunnable!!)
    }

    private fun setMuted(muted: Boolean) {
        try {
            val volume = if (muted) 0f else 1f
            mediaPlayer?.setVolume(volume, volume)
            muteButton.setImageResource(if (muted) R.drawable.ic_sound_muted_minimal else R.drawable.ic_sound_minimal)
        } catch (_: Exception) {}
    }

    private fun seekBy(deltaMs: Int) {
    val duration = if (videoView.duration > 0) videoView.duration else seekBar.max
    val newPos = (videoView.currentPosition + deltaMs).coerceIn(0, duration)
        videoView.seekTo(newPos)
        currentTime.text = formatTime(newPos)
        showCenterOverlay(if (videoView.isPlaying) R.drawable.ic_pause_overlay else R.drawable.ic_play_overlay)
    showControls()
    }

    private fun showCenterOverlay(icon: Int) {
        playPauseOverlay.setImageResource(icon)
        playPauseOverlay.visibility = View.VISIBLE
        playPauseOverlay.alpha = 0.95f
        uiHandler.postDelayed({ playPauseOverlay.visibility = View.GONE }, 1000)
    }

    private fun formatTime(ms: Int): String {
        val totalSec = ms / 1000
        val m = totalSec / 60
        val s = totalSec % 60
        return String.format("%d:%02d", m, s)
    }

    override fun onPause() {
        super.onPause()
        if (videoView.isPlaying) videoView.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        progressRunnable?.let { uiHandler.removeCallbacks(it) }
        videoView.stopPlayback()
    }
}