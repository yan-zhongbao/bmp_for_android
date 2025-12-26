package com.example.metronome

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlin.math.roundToLong

class MetronomeService : Service() {
    private val scheduler = Executors.newSingleThreadScheduledExecutor()
    private var task: ScheduledFuture<*>? = null
    private var beatIndex = 0
    private var beatsPerMeasure = 4
    private var bpm = 120

    private val accentTone = ToneGenerator(AudioManager.STREAM_MUSIC, 90)
    private val regularTone = ToneGenerator(AudioManager.STREAM_MUSIC, 60)

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                bpm = intent.getIntExtra(EXTRA_BPM, 120).coerceIn(30, 240)
                beatsPerMeasure = intent.getIntExtra(EXTRA_BEATS, 4).coerceIn(1, 12)
                startForeground(NOTIFICATION_ID, buildNotification())
                startMetronome()
                updatePlayingState(true)
            }
            ACTION_STOP -> {
                stopMetronome()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                updatePlayingState(false)
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopMetronome()
        scheduler.shutdownNow()
        accentTone.release()
        regularTone.release()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startMetronome() {
        task?.cancel(true)
        beatIndex = 0
        val intervalMs = (60000.0 / bpm.coerceAtLeast(1)).roundToLong().coerceAtLeast(1)
        task = scheduler.scheduleAtFixedRate(
            {
                val isAccent = beatIndex % beatsPerMeasure == 0
                if (isAccent) {
                    accentTone.startTone(ToneGenerator.TONE_PROP_BEEP, 60)
                } else {
                    regularTone.startTone(ToneGenerator.TONE_PROP_BEEP, 40)
                }
                beatIndex = (beatIndex + 1) % beatsPerMeasure
            },
            0,
            intervalMs,
            TimeUnit.MILLISECONDS
        )
    }

    private fun stopMetronome() {
        task?.cancel(true)
        task = null
    }

    private fun buildNotification(): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or pendingIntentImmutableFlag()
        )

        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, MetronomeService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or pendingIntentImmutableFlag()
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Metronome running")
            .setContentText("BPM $bpm · $beatsPerMeasure beats")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(contentIntent)
            .addAction(0, "Stop", stopIntent)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < 26) {
            return
        }
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Metronome",
            NotificationManager.IMPORTANCE_LOW
        )
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun updatePlayingState(isPlaying: Boolean) {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_IS_PLAYING, isPlaying).apply()
    }

    private fun pendingIntentImmutableFlag(): Int {
        return if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0
    }

    companion object {
        private const val CHANNEL_ID = "metronome_channel"
        private const val NOTIFICATION_ID = 1002

        const val ACTION_START = "com.example.metronome.action.START"
        const val ACTION_STOP = "com.example.metronome.action.STOP"
        const val EXTRA_BPM = "extra_bpm"
        const val EXTRA_BEATS = "extra_beats"

        fun start(context: Context, bpm: Int, beats: Int) {
            val intent = Intent(context, MetronomeService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_BPM, bpm)
                putExtra(EXTRA_BEATS, beats)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, MetronomeService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }
}
