package com.example.tareamov.ui

import android.app.PictureInPictureParams
import android.app.PendingIntent
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.graphics.drawable.Icon
import android.content.Intent
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.Rational
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
    private lateinit var btnFloatingMode: ImageView

    private var mediaPlayer: MediaPlayer? = null
    private var mediaPlayerPrepared: Boolean = false
    private var isControlsVisible = false
    private var isMuted = false
    private val uiHandler = Handler(Looper.getMainLooper())
    private var progressRunnable: Runnable? = null
    private val autoHideDelayMs = 3000L
    private var autoHideRunnable: Runnable? = null
    private var pendingUserSeekMs: Int? = null
    private var isScrubbing: Boolean = false
    private val ACTION_TOGGLE_PLAYBACK = "com.example.tareamov.action.TOGGLE_PIP_PLAYBACK"
    private var pipReceiver: BroadcastReceiver? = null

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
    btnFloatingMode = findViewById(R.id.btn_floating_mode)

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
            mediaPlayerPrepared = true
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
            mediaPlayerPrepared = false
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
        backButton.setOnClickListener {
            try {
                // Instead of finishing the app, navigate back to the VideoHomeFragment hosted by MainActivity
                val i = Intent(this, com.example.tareamov.MainActivity::class.java)
                i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                i.putExtra("open_video_home", true)
                startActivity(i)
                finish()
            } catch (e: Exception) {
                Log.w("VideoPlayerActivity", "Failed to navigate to VideoHomeFragment via MainActivity", e)
                finish()
            }
        }

        btnFloatingMode.setOnClickListener {
            try {
                val uriToSend = pathOrUri
                if (uriToSend.isNullOrEmpty()) {
                    Toast.makeText(this, "URI de video no disponible para modo flotante", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }

                // Prefer system Picture-in-Picture when available (Android O+). This keeps playback
                // visible after leaving the app. Otherwise fallback to the existing in-app floating container.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    try {
                        // Use current video view dimensions as aspect ratio hint
                        val w = if (videoView.width > 0) videoView.width else 16
                        val h = if (videoView.height > 0) videoView.height else 9
                        val builder = PictureInPictureParams.Builder()
                            .setAspectRatio(Rational(w, h))
                        // attach play/pause action
                        try {
                            val actions = createPipActions(videoView.isPlaying)
                            if (actions.isNotEmpty()) builder.setActions(actions)
                        } catch (_: Throwable) { }
                        // ensure receiver is registered
                        registerPipReceiver()
                        enterPictureInPictureMode(builder.build())
                        // Do not finish activity: leaving it in PIP keeps playback.
                    } catch (pipEx: Exception) {
                        Log.w("VideoPlayerActivity", "PIP failed, falling back to in-app floating", pipEx)
                        // fallback to MainActivity in-app floating
                        val i = Intent(this, com.example.tareamov.MainActivity::class.java)
                        i.putExtra("floating_video_path", uriToSend)
                        i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        startActivity(i)
                        finish()
                    }
                } else {
                    // Older devices: use the in-app floating fragment pathway
                    val i = Intent(this, com.example.tareamov.MainActivity::class.java)
                    i.putExtra("floating_video_path", uriToSend)
                    i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    startActivity(i)
                    finish()
                }
            } catch (e: Exception) {
                Log.e("VideoPlayerActivity", "Error initiating floating mode", e)
                Toast.makeText(this, "No se pudo abrir el modo flotante", Toast.LENGTH_SHORT).show()
            }
        }

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
        // Update desired mute state immediately
        isMuted = muted
        
        // Only apply volume if MediaPlayer is prepared
        if (mediaPlayerPrepared) {
            try {
                val volume = if (muted) 0f else 1f
                mediaPlayer?.setVolume(volume, volume)
                muteButton.setImageResource(if (muted) R.drawable.ic_sound_muted_minimal else R.drawable.ic_sound_minimal)
            } catch (e: IllegalStateException) {
                Log.e("VideoPlayerActivity", "Error setting volume, MediaPlayer might not be ready.", e)
                // Keep isMuted flag; volume will be applied later when prepared
                mediaPlayerPrepared = false
            }
        } else {
            // Not prepared yet: volume will be applied when onPreparedListener runs
            Log.d("VideoPlayerActivity", "MediaPlayer not prepared yet; saved mute state=$isMuted")
            // Still update button appearance
            muteButton.setImageResource(if (muted) R.drawable.ic_sound_muted_minimal else R.drawable.ic_sound_minimal)
        }
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
        // If the activity enters background but is in PIP mode, keep playback running.
        // Otherwise pause playback when the activity is fully paused.
        if (!isInPictureInPictureMode && videoView.isPlaying) videoView.pause()
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // Trigger PIP automatically when user leaves the activity (press Home) and video is playing
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && videoView.isPlaying && !isInPictureInPictureMode) {
            try {
                val w = if (videoView.width > 0) videoView.width else 16
                val h = if (videoView.height > 0) videoView.height else 9
                val builder = PictureInPictureParams.Builder()
                    .setAspectRatio(Rational(w, h))
                try {
                    val actions = createPipActions(videoView.isPlaying)
                    if (actions.isNotEmpty()) builder.setActions(actions)
                } catch (_: Throwable) { }
                registerPipReceiver()
                enterPictureInPictureMode(builder.build())
            } catch (t: Throwable) {
                Log.w("VideoPlayerActivity", "Auto PIP failed", t)
            }
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: android.content.res.Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        // Hide controls in PIP to keep the small window clean
        if (isInPictureInPictureMode) {
            // hide heavy UI
            findViewById<View>(R.id.topBar)?.visibility = View.GONE
            findViewById<View>(R.id.bottomBar)?.visibility = View.GONE
            controlsOverlay.visibility = View.GONE
            // update actions to reflect current playback state
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val actions = createPipActions(videoView.isPlaying)
                try {
                    setPictureInPictureParams(PictureInPictureParams.Builder().setActions(actions).build())
                } catch (_: Exception) { }
            }
        } else {
            // restore UI when returning
            findViewById<View>(R.id.topBar)?.visibility = View.VISIBLE
            findViewById<View>(R.id.bottomBar)?.visibility = View.VISIBLE
            controlsOverlay.visibility = View.VISIBLE
            // unregister receiver when exiting PIP
            unregisterPipReceiver()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        progressRunnable?.let { uiHandler.removeCallbacks(it) }
        unregisterPipReceiver()
        try { videoView.stopPlayback() } catch (_: Exception) { }
    }

    private fun flagsForPendingIntent(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else PendingIntent.FLAG_UPDATE_CURRENT
    }

    private fun createPipActions(isPlaying: Boolean): ArrayList<RemoteAction> {
        val actions = ArrayList<RemoteAction>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val iconRes = if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
                val icon = Icon.createWithResource(this, iconRes)
                val title = if (isPlaying) "Pause" else "Play"
                val desc = "Toggle playback"
                val intent = Intent(ACTION_TOGGLE_PLAYBACK)
                // keep intent generic (no explicit package) so the dynamically-registered receiver can receive it
                val pi = PendingIntent.getBroadcast(this, 0, intent, flagsForPendingIntent())
                actions.add(RemoteAction(icon, title, desc, pi))
            } catch (t: Throwable) {
                Log.w("VideoPlayerActivity", "Failed to create PIP action", t)
            }
        }
        return actions
    }

    private fun registerPipReceiver() {
        if (pipReceiver != null) return
        pipReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                Log.d("VideoPlayerActivity", "PIP action received: ${intent?.action}")
                if (intent?.action == ACTION_TOGGLE_PLAYBACK) {
                    try {
                        if (videoView.isPlaying) {
                            videoView.pause()
                        } else {
                            videoView.start()
                        }
                        // update PIP actions to reflect new state
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && isInPictureInPictureMode) {
                            val actions = createPipActions(videoView.isPlaying)
                            try {
                                setPictureInPictureParams(PictureInPictureParams.Builder().setActions(actions).build())
                            } catch (_: Exception) { }
                        }
                    } catch (t: Throwable) { t.printStackTrace() }
                }
            }
        }
        try {
            applicationContext.registerReceiver(pipReceiver, IntentFilter(ACTION_TOGGLE_PLAYBACK))
            Log.d("VideoPlayerActivity", "PIP receiver registered")
        } catch (t: Throwable) {
            Log.w("VideoPlayerActivity", "Failed to register PIP receiver", t)
        }
    }

    private fun unregisterPipReceiver() {
        pipReceiver?.let {
            try { applicationContext.unregisterReceiver(it) } catch (_: Exception) { }
            pipReceiver = null
            Log.d("VideoPlayerActivity", "PIP receiver unregistered")
        }
    }
}