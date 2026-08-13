// SPDX-License-Identifier: GPL-3.0-or-later

package com.bpmapp.audio.audio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.app.Service.STOP_FOREGROUND_REMOVE
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.media.app.NotificationCompat.MediaStyle
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.bpmapp.audio.R
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import javax.inject.Inject

/**
 * Audio service that handles background playback and provides foreground notification.
 * This service integrates with the PlayerRepository for audio playback control.
 * Extends MediaSessionService to support Bluetooth media controls.
 */
@AndroidEntryPoint
class AudioService : MediaSessionService() {
    
    companion object {
        const val CHANNEL_ID = "audio_playback_channel"
        const val NOTIFICATION_ID = 1
        
        // Actions
        const val ACTION_PLAY = "action_play"
        const val ACTION_PAUSE = "action_pause"
        const val ACTION_SKIP_NEXT = "action_skip_next"
        const val ACTION_SKIP_PREVIOUS = "action_skip_previous"
        const val ACTION_STOP = "action_stop"
        const val ACTION_TOGGLE_SPEED_CORRECTION = "action_toggle_speed_correction"
    }

    @Inject lateinit var playerRepository: PlayerRepository
    @Inject lateinit var exoPlayer: ExoPlayer
    @Inject lateinit var tempoStretcher: TempoStretcher

    private lateinit var notificationManager: NotificationManager
    private lateinit var mediaSession: MediaSession
    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.Main + serviceJob)
    
    // Track audio format for SoundTouch initialization
    private var currentSampleRate = 44100
    private var currentChannels = 2
    
    override fun onCreate() {
        super.onCreate()
        Log.d("AudioService", "onCreate called")
        
        // Initialize notification manager
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannel()
        
        // Initialize SoundTouch with default format
        // This will be updated when actual audio format is known
        tempoStretcher.initializeSoundTouch(currentSampleRate, currentChannels)
        
        // Initialize MediaSession for Bluetooth controls and notification
        mediaSession = MediaSession.Builder(this, exoPlayer)
            .setSessionActivity(
                PendingIntent.getActivity(
                    this, 
                    0, 
                    Intent(this, com.bpmapp.audio.MainActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    }, 
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
            .setId("AudioService")
            .build()
        
        // Activate the MediaSession to enable Bluetooth controls
        mediaSession.player = exoPlayer
        
        // Set up player listeners to update notification and handle audio format
        exoPlayer.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                Log.d("AudioService", "Playback state changed: $state")
                updateNotification()
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                Log.d("AudioService", "Media item transition")
                updateNotification()
            }
            
            override fun onAudioAttributesChanged(audioAttributes: androidx.media3.common.AudioAttributes) {
                // Audio attributes changed, may need to reinitialize SoundTouch
                Log.d("AudioService", "Audio attributes changed: ${audioAttributes}")
            }
        })
        
        // Start in foreground with notification
        Log.d("AudioService", "Starting foreground service with notification")
        try {
            startForeground(NOTIFICATION_ID, createNotification())
            Log.d("AudioService", "Foreground service started successfully")
        } catch (e: Exception) {
            Log.e("AudioService", "Failed to start foreground service", e)
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        // Return the existing MediaSession
        // Bluetooth controls are automatically handled through the player
        return mediaSession
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = getString(R.string.notification_channel_description)
            setShowBadge(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        
        notificationManager.createNotificationChannel(channel)
        Log.d("AudioService", "Notification channel created")
    }

    private fun createNotification(): Notification {
        Log.d("AudioService", "Creating notification")
        val currentTrack = playerRepository.currentTrack.value
        val isPlaying = exoPlayer.isPlaying
        
        // Create intent for opening the app
        val contentIntent = Intent(this, com.bpmapp.audio.MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentPendingIntent = PendingIntent.getActivity(
            this, 
            0, 
            contentIntent, 
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        // Create play/pause action
        val playPauseIntent = Intent(this, AudioService::class.java).apply {
            action = if (isPlaying) ACTION_PAUSE else ACTION_PLAY
        }
        val playPausePendingIntent = PendingIntent.getService(
            this, 
            0, 
            playPauseIntent, 
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        val playPauseIcon = if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play
        val playPauseText = if (isPlaying) getString(R.string.notification_action_pause) 
                          else getString(R.string.notification_action_play)
        
        val playPauseAction = NotificationCompat.Action.Builder(
            playPauseIcon, 
            playPauseText, 
            playPausePendingIntent
        ).build()
        
        // Create previous action
        val previousIntent = Intent(this, AudioService::class.java).apply {
            action = ACTION_SKIP_PREVIOUS
        }
        val previousPendingIntent = PendingIntent.getService(
            this, 
            1, 
            previousIntent, 
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val previousAction = NotificationCompat.Action.Builder(
            R.drawable.ic_previous, 
            getString(R.string.notification_action_previous), 
            previousPendingIntent
        ).build()
        
        // Create next action
        val nextIntent = Intent(this, AudioService::class.java).apply {
            action = ACTION_SKIP_NEXT
        }
        val nextPendingIntent = PendingIntent.getService(
            this, 
            2, 
            nextIntent, 
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val nextAction = NotificationCompat.Action.Builder(
            R.drawable.ic_next, 
            getString(R.string.notification_action_next), 
            nextPendingIntent
        ).build()
        
        // Create stop action
        val stopIntent = Intent(this, AudioService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 
            3, 
            stopIntent, 
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopAction = NotificationCompat.Action.Builder(
            R.drawable.ic_stop, 
            getString(R.string.stop), 
            stopPendingIntent
        ).build()
        
        // Create speed correction toggle action
        val speedCorrectionEnabled = playerRepository.isSpeedCorrectionEnabled()
        val speedToggleIntent = Intent(this, AudioService::class.java).apply {
            action = ACTION_TOGGLE_SPEED_CORRECTION
        }
        val speedTogglePendingIntent = PendingIntent.getService(
            this, 
            5, 
            speedToggleIntent, 
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val speedToggleAction = NotificationCompat.Action.Builder(
            if (speedCorrectionEnabled) R.drawable.ic_speed_on else R.drawable.ic_speed_off,
            if (speedCorrectionEnabled) getString(R.string.notification_action_speed_on)
            else getString(R.string.notification_action_speed_off),
            speedTogglePendingIntent
        ).build()
        
        val trackTitle = currentTrack?.mediaMetadata?.title ?: getString(R.string.now_playing)
        val trackArtist = currentTrack?.mediaMetadata?.artist ?: ""
        val contentText = if (trackArtist.isNotEmpty()) {
            "$trackTitle - $trackArtist"
        } else {
            trackTitle
        }
        
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(contentText)
            .setContentText(getString(R.string.app_name))
            .setSubText(getString(R.string.app_name))
            .setSmallIcon(R.drawable.ic_music_note)
            .setContentIntent(contentPendingIntent)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setOngoing(true)
            .setStyle(MediaStyle()
                .setShowActionsInCompactView(0, 1, 2))
            .addAction(previousAction)
            .addAction(playPauseAction)
            .addAction(nextAction)
            .addAction(speedToggleAction)
            .addAction(stopAction)
            .build()
    }

    private fun updateNotification() {
        Log.d("AudioService", "Updating notification")
        notificationManager.notify(NOTIFICATION_ID, createNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        intent?.action?.let { action ->
            when (action) {
                ACTION_PLAY -> {
                    exoPlayer.playWhenReady = true
                    updateNotification()
                }
                ACTION_PAUSE -> {
                    exoPlayer.playWhenReady = false
                    updateNotification()
                }
                ACTION_SKIP_NEXT -> {
                    playerRepository.playNext()
                    updateNotification()
                }
                ACTION_SKIP_PREVIOUS -> {
                    playerRepository.playPrevious()
                    updateNotification()
                }
                ACTION_STOP -> {
                    playerRepository.stop()
                    stopForeground(STOP_FOREGROUND_REMOVE)
                }
                ACTION_TOGGLE_SPEED_CORRECTION -> {
                    playerRepository.toggleSpeedCorrection()
                    updateNotification()
                }
            }
        }
        
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        playerRepository.stop()
        mediaSession.player.stop()
        mediaSession.release()
    }
    
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        stopSelf()
    }
}