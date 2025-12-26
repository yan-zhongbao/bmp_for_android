package com.example.metronome

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import android.os.IBinder
import android.os.Process
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlin.math.PI
import kotlin.math.roundToLong
import kotlin.math.sin

class MetronomeService : Service() {
    private var beatsPerMeasure = 4
    private var bpm = 120
    private var soundStyleId = SoundStyle.CLASSIC.id

    private var engine: MetronomeAudioEngine? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                bpm = intent.getIntExtra(EXTRA_BPM, 120).coerceIn(30, 240)
                beatsPerMeasure = intent.getIntExtra(EXTRA_BEATS, 4).coerceIn(1, 12)
                soundStyleId = intent.getIntExtra(EXTRA_SOUND_STYLE, SoundStyle.CLASSIC.id)
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
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startMetronome() {
        engine?.stop()
        engine = MetronomeAudioEngine()
        engine?.start(bpm, beatsPerMeasure, soundStyleId)
    }

    private fun stopMetronome() {
        engine?.stop()
        engine = null
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
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_content, bpm, beatsPerMeasure))
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(contentIntent)
            .addAction(0, getString(R.string.action_stop), stopIntent)
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
        const val EXTRA_SOUND_STYLE = "extra_sound_style"

        fun start(context: Context, bpm: Int, beats: Int, soundStyleId: Int) {
            val intent = Intent(context, MetronomeService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_BPM, bpm)
                putExtra(EXTRA_BEATS, beats)
                putExtra(EXTRA_SOUND_STYLE, soundStyleId)
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

private data class ClickProfile(
    val accent: ShortArray,
    val secondary: ShortArray,
    val regular: ShortArray
)

private class MetronomeAudioEngine {
    private val sampleRate = 44100
    private var audioTrack: AudioTrack? = null
    @Volatile private var running = false
    private var audioThread: Thread? = null

    fun start(bpm: Int, beatsPerMeasure: Int, soundStyleId: Int) {
        stop()
        val safeBpm = bpm.coerceIn(30, 240)
        val safeBeats = beatsPerMeasure.coerceIn(1, 12)
        val profile = buildProfile(soundStyleId)
        val minBufferBytes = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(2048)
        val bufferSamples = (minBufferBytes / 2).coerceAtLeast(1024)
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(bufferSamples * 2)
            .build()

        running = true
        audioTrack = track
        audioThread = Thread {
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
            val buffer = ShortArray(bufferSamples)
            val samplesPerBeat = sampleRate * 60.0 / safeBpm
            var nextBeatSample = 0.0
            var beatIndex = 0
            var totalSamplesWritten = 0L
            track.play()
            while (running) {
                buffer.fill(0)
                val bufferStart = totalSamplesWritten
                val bufferEnd = bufferStart + buffer.size
                while (nextBeatSample < bufferEnd) {
                    val beatSample = nextBeatSample.roundToLong()
                    val offset = (beatSample - bufferStart).toInt()
                    val level = AccentPattern.levelForBeat(beatIndex, safeBeats)
                    val click = when (level) {
                        AccentLevel.STRONG -> profile.accent
                        AccentLevel.SECONDARY -> profile.secondary
                        AccentLevel.REGULAR -> profile.regular
                    }
                    if (offset < buffer.size) {
                        val start = if (offset < 0) 0 else offset
                        val clickOffset = if (offset < 0) -offset else 0
                        val length = minOf(click.size - clickOffset, buffer.size - start)
                        if (length > 0) {
                            for (i in 0 until length) {
                                val mixed = buffer[start + i] + click[clickOffset + i]
                                buffer[start + i] = mixed.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                            }
                        }
                    }
                    beatIndex = (beatIndex + 1) % safeBeats
                    nextBeatSample += samplesPerBeat
                }
                var written = 0
                while (written < buffer.size && running) {
                    val result = track.write(buffer, written, buffer.size - written, AudioTrack.WRITE_BLOCKING)
                    if (result < 0) {
                        running = false
                        break
                    }
                    written += result
                }
                totalSamplesWritten += buffer.size
            }
            track.pause()
            track.flush()
            track.release()
        }
        audioThread?.isDaemon = true
        audioThread?.start()
    }

    fun stop() {
        running = false
        audioTrack?.pause()
        audioTrack?.flush()
        try {
            audioThread?.join(300)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        audioThread = null
        audioTrack = null
    }

    private fun buildProfile(soundStyleId: Int): ClickProfile {
        return when (SoundStyle.fromId(soundStyleId)) {
            SoundStyle.CLASSIC -> ClickProfile(
                accent = buildToneSample(1000.0, 18, 0.9f, 5.0),
                secondary = buildToneSample(900.0, 18, 0.7f, 5.0),
                regular = buildToneSample(780.0, 18, 0.55f, 5.0)
            )
            SoundStyle.SHORT -> ClickProfile(
                accent = buildToneSample(1400.0, 8, 0.85f, 7.5),
                secondary = buildToneSample(1200.0, 8, 0.65f, 7.5),
                regular = buildToneSample(1000.0, 8, 0.5f, 7.5)
            )
            SoundStyle.SOFT -> ClickProfile(
                accent = buildToneSample(700.0, 22, 0.5f, 4.0),
                secondary = buildToneSample(620.0, 22, 0.42f, 4.0),
                regular = buildToneSample(540.0, 22, 0.35f, 4.0)
            )
            SoundStyle.WOOD -> ClickProfile(
                accent = buildDualToneSample(900.0, 1200.0, 16, 0.8f, 6.0),
                secondary = buildDualToneSample(820.0, 1080.0, 16, 0.62f, 6.0),
                regular = buildDualToneSample(700.0, 980.0, 16, 0.5f, 6.0)
            )
            SoundStyle.DRUM -> ClickProfile(
                accent = buildToneSample(220.0, 50, 0.9f, 2.2),
                secondary = buildToneSample(200.0, 46, 0.7f, 2.2),
                regular = buildToneSample(170.0, 42, 0.55f, 2.2)
            )
            SoundStyle.METAL -> ClickProfile(
                accent = buildToneSample(2000.0, 12, 0.7f, 7.0),
                secondary = buildToneSample(1800.0, 12, 0.55f, 7.0),
                regular = buildToneSample(1600.0, 12, 0.4f, 7.0)
            )
        }
    }

    private fun buildToneSample(
        frequencyHz: Double,
        durationMs: Int,
        volume: Float,
        decay: Double
    ): ShortArray {
        val sampleCount = (sampleRate * durationMs / 1000.0).toInt().coerceAtLeast(1)
        val buffer = ShortArray(sampleCount)
        val omega = 2.0 * PI * frequencyHz / sampleRate
        for (i in 0 until sampleCount) {
            val envelope = kotlin.math.exp(-decay * i / sampleCount)
            val sample = (sin(omega * i) * envelope * volume * Short.MAX_VALUE).toInt()
            buffer[i] = sample.toShort()
        }
        return buffer
    }

    private fun buildDualToneSample(
        frequencyHzA: Double,
        frequencyHzB: Double,
        durationMs: Int,
        volume: Float,
        decay: Double
    ): ShortArray {
        val sampleCount = (sampleRate * durationMs / 1000.0).toInt().coerceAtLeast(1)
        val buffer = ShortArray(sampleCount)
        val omegaA = 2.0 * PI * frequencyHzA / sampleRate
        val omegaB = 2.0 * PI * frequencyHzB / sampleRate
        for (i in 0 until sampleCount) {
            val envelope = kotlin.math.exp(-decay * i / sampleCount)
            val sample = ((sin(omegaA * i) + 0.6 * sin(omegaB * i)) / 1.6) * envelope
            val value = (sample * volume * Short.MAX_VALUE).toInt()
            buffer[i] = value.toShort()
        }
        return buffer
    }
}
