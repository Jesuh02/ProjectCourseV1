package com.example.tareamov.util.player

import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.drawable.Icon
import android.os.Build
import android.util.Log
import android.util.Rational
import android.view.View
import androidx.annotation.RequiresApi
import com.example.tareamov.ui.VideoPlayerActivity

class PipManager(
    private val activity: VideoPlayerActivity,
    private val videoView: android.widget.VideoView
) {

    private val ACTION_TOGGLE_PLAYBACK = "com.example.tareamov.action.TOGGLE_PIP_PLAYBACK"
    private var pipReceiver: BroadcastReceiver? = null

    fun enterPipMode() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val w = if (videoView.width > 0) videoView.width else 16
                val h = if (videoView.height > 0) videoView.height else 9
                val aspectRatio = Rational(w, h)
                
                val builder = PictureInPictureParams.Builder()
                    .setAspectRatio(aspectRatio)
                
                updatePipActions(builder, videoView.isPlaying)
                
                registerReceiver()
                activity.enterPictureInPictureMode(builder.build())
            } catch (e: Exception) {
                Log.e("PipManager", "Error entering PiP", e)
            }
        }
    }

    fun onPictureInPictureModeChanged(isInPip: Boolean) {
        if (isInPip) {
            registerReceiver()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                updatePipParams(videoView.isPlaying)
            }
        } else {
            unregisterReceiver()
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    fun updatePipParams(isPlaying: Boolean) {
        try {
            val builder = PictureInPictureParams.Builder()
            updatePipActions(builder, isPlaying)
            activity.setPictureInPictureParams(builder.build())
        } catch (e: Exception) {
            Log.e("PipManager", "Error updating PiP params", e)
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun updatePipActions(builder: PictureInPictureParams.Builder, isPlaying: Boolean) {
        try {
            val iconRes = if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
            val title = if (isPlaying) "Pause" else "Play"
            val icon = Icon.createWithResource(activity, iconRes)
            
            val intent = Intent(ACTION_TOGGLE_PLAYBACK)
            val pendingIntent = PendingIntent.getBroadcast(
                activity, 
                0, 
                intent, 
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            
            val action = RemoteAction(icon, title, "Toggle Playback", pendingIntent)
            builder.setActions(listOf(action))
        } catch (e: Exception) {
            Log.e("PipManager", "Error creating PiP actions", e)
        }
    }

    private fun registerReceiver() {
        if (pipReceiver != null) return
        
        pipReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == ACTION_TOGGLE_PLAYBACK) {
                    if (videoView.isPlaying) {
                        videoView.pause()
                    } else {
                        videoView.start()
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        updatePipParams(videoView.isPlaying)
                    }
                }
            }
        }
        
        try {
            activity.registerReceiver(pipReceiver, IntentFilter(ACTION_TOGGLE_PLAYBACK))
        } catch (e: Exception) {
            Log.e("PipManager", "Failed to register receiver", e)
        }
    }

    fun unregisterReceiver() {
        pipReceiver?.let {
            try {
                activity.unregisterReceiver(it)
            } catch (e: Exception) {
                Log.e("PipManager", "Error unregistering receiver", e)
            }
            pipReceiver = null
        }
    }
}
