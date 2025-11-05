package com.example.tareamov.ui

import android.net.Uri
import android.os.Bundle
import android.view.*
import android.widget.ImageButton
import android.widget.VideoView
import androidx.fragment.app.Fragment
import com.example.tareamov.R

class FloatingVideoPlayerFragment : Fragment() {
    private var videoUri: Uri? = null
    // Track last raw touch coordinates for smooth dragging
    private var lastRawX = 0f
    private var lastRawY = 0f

    companion object {
        private const val ARG_URI = "arg_video_uri"
        fun newInstance(uri: String?): FloatingVideoPlayerFragment {
            val f = FloatingVideoPlayerFragment()
            val b = Bundle()
            b.putString(ARG_URI, uri)
            f.arguments = b
            return f
        }
    }

    private lateinit var videoView: VideoView
    private lateinit var btnClose: ImageButton
    private lateinit var btnPlayPause: ImageButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val s = arguments?.getString(ARG_URI)
        videoUri = if (s.isNullOrEmpty()) null else Uri.parse(s)
        setRetainInstance(true)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val root = inflater.inflate(R.layout.fragment_floating_video_player, container, false)
        videoView = root.findViewById(R.id.floating_video_view)
        btnClose = root.findViewById(R.id.btn_close_floating)
        btnPlayPause = root.findViewById(R.id.btn_play_pause_floating)

        // Initialize controls
        btnClose.setOnClickListener { closeFloating() }
        btnPlayPause.setOnClickListener { togglePlayPause() }

        videoUri?.let { uri ->
            videoView.setVideoURI(uri)
            videoView.setOnPreparedListener { mediaPlayer ->
                mediaPlayer.isLooping = true
                videoView.start()
                btnPlayPause.setImageResource(android.R.drawable.ic_media_pause)
            }
        }

        // Draggable implementation — move the host container in the activity instead of the inner view.
        // This ensures the floating window (the activity's FrameLayout) is repositioned, not an inner full-size child.
        val card = root.findViewById<View>(R.id.floating_card)
        card.setOnTouchListener { v, ev ->
            val hostContainer = activity?.findViewById<View>(R.id.floating_video_container)
            val hostParent = hostContainer?.parent as? View
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lastRawX = ev.rawX
                    lastRawY = ev.rawY
                    v.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (hostContainer == null || hostParent == null) return@setOnTouchListener false
                    val dx = ev.rawX - lastRawX
                    val dy = ev.rawY - lastRawY

                    var newX = hostContainer.x + dx
                    var newY = hostContainer.y + dy

                    val maxX = (hostParent.width - hostContainer.width).toFloat().coerceAtLeast(0f)
                    val maxY = (hostParent.height - hostContainer.height).toFloat().coerceAtLeast(0f)

                    newX = newX.coerceIn(0f, maxX)
                    newY = newY.coerceIn(0f, maxY)

                    hostContainer.x = newX
                    hostContainer.y = newY

                    lastRawX = ev.rawX
                    lastRawY = ev.rawY
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.performClick()
                    true
                }
                else -> false
            }
        }

        return root
    }

    private fun togglePlayPause() {
        if (videoView.isPlaying) {
            videoView.pause()
            btnPlayPause.setImageResource(android.R.drawable.ic_media_play)
        } else {
            videoView.start()
            btnPlayPause.setImageResource(android.R.drawable.ic_media_pause)
        }
    }

    private fun closeFloating() {
        // Stop playback and remove fragment
        try { videoView.stopPlayback() } catch (t: Throwable) {}
        parentFragmentManager.beginTransaction().remove(this).commitAllowingStateLoss()
        // Also hide container if empty (MainActivity handles visibility)
        (activity as? com.example.tareamov.MainActivity)?.onFloatingPlayerClosed()
    }

    override fun onDestroyView() {
        try { videoView.stopPlayback() } catch (t: Throwable) {}
        super.onDestroyView()
    }
}
