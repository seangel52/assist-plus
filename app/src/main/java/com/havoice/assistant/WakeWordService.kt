package com.havoice.assistant

import android.Manifest
import android.annotation.SuppressLint
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.ToneGenerator
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Listens for the owner's wake phrases in the background (Android requires a visible
 * notification for this) and opens the Assist sheet when one is heard. It lets go of the
 * microphone while Assist is open, and can sleep while the phone sits still.
 */
class WakeWordService : Service(), SensorEventListener {
    // A failure in the listener is logged rather than taking the whole app down.
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, e -> AppLog.e("Wake word listener failed", e) },
    )
    private var listenJob: Job? = null
    private lateinit var settings: HaSettings
    private var wakeLock: PowerManager.WakeLock? = null

    // Movement tracking for "sleep when still".
    private var lastMovedAt = SystemClock.elapsedRealtime()
    private var lastAccel: FloatArray? = null
    private var sleeping = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        settings = HaSettings(this)
        running = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_PAUSE -> {
                stopListening()
                updateNotification("Paused while Assist is open")
                return START_STICKY
            }
            ACTION_RESUME -> {
                // Already a foreground service; just pick the microphone back up.
                if (!sleeping) startListening()
                return START_STICKY
            }
        }
        try {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification(listeningText()),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0,
            )
        } catch (e: RuntimeException) {
            // Android only lets a microphone service start while the app is on screen. When the
            // system restarts it in the background (after the app was closed), ask instead.
            AppLog.w("Wake word couldn't start in the background: ${e.javaClass.simpleName}")
            offerRestart(this)
            stopSelf()
            return START_NOT_STICKY
        }
        startMotionWatch()
        startListening()
        return START_STICKY
    }

    override fun onDestroy() {
        stopListening()
        getSystemService(SensorManager::class.java).unregisterListener(this)
        scope.cancel()
        running = null
        AppLog.i("Wake word listening stopped (battery ${batteryPercent()}%)")
        super.onDestroy()
    }

    private fun listeningText(): String {
        val phrases = WakeWords.load(settings).joinToString(", ") { "\"${it.text}\"" }
        return "Listening for $phrases"
    }

    @SuppressLint("MissingPermission", "WakelockTimeout")
    private fun startListening() {
        if (listenJob?.isActive == true) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            AppLog.w("Wake word: no microphone permission")
            stopSelf()
            return
        }
        sleeping = false
        updateNotification(listeningText())
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "HaVoice:wake").apply { acquire() }
        listenJob = scope.launch {
            val phrases = WakeWords.load(settings)
            var nextBatteryLog = SystemClock.elapsedRealtime() + BATTERY_LOG_MS
            AppLog.i("Wake word listening started for ${phrases.size} phrase(s) (battery ${batteryPercent()}%)")
            while (isActive) {
                val (phrase, audio) = WakeWords.awaitWake(applicationContext, phrases, settings.wakeSensitivity) {
                    if (SystemClock.elapsedRealtime() > nextBatteryLog) {
                        nextBatteryLog += BATTERY_LOG_MS
                        AppLog.i("Wake word still listening (battery ${batteryPercent()}%)")
                    }
                } ?: return@launch
                if (accept(phrase, audio)) {
                    wake(phrase)
                    return@launch
                }
            }
        }
    }

    private fun stopListening() {
        listenJob?.cancel()
        listenJob = null
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
    }

    /** Whether a detection should wake Assist: phone lock and voice checks. */
    private fun accept(phrase: WakePhrase, audio: FloatArray): Boolean {
        val locked = getSystemService(KeyguardManager::class.java).isKeyguardLocked
        if (locked && !settings.wakeWhenLocked) {
            AppLog.i("Wake word \"${phrase.text}\" heard but the phone is locked; ignored")
            return false
        }
        val (mine, score) = WakeWords.voiceMatches(applicationContext, settings, phrase, audio)
        val scoreText = score?.let { ", voice %.2f".format(it) } ?: ""
        AppLog.i("Wake word \"${phrase.text}\" heard$scoreText${if (mine) "" else " — not your voice; ignored"}")
        return mine
    }

    /** Opens the Assist sheet, listening, and lets go of the mic until it closes. */
    private fun wake(phrase: WakePhrase) {
        stopListening()
        updateNotification("Paused while Assist is open")
        try {
            ToneGenerator(AudioManager.STREAM_MUSIC, 60).startTone(ToneGenerator.TONE_PROP_ACK, 150)
        } catch (e: RuntimeException) {
            // No tone is fine.
        }
        if (!Settings.canDrawOverlays(this)) {
            AppLog.w("Wake word: can't open Assist over other apps without the 'Display over other apps' permission")
        }
        startActivity(
            Intent(this, AssistActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(AssistActivity.EXTRA_FROM_WAKE_WORD, phrase.text)
                .putExtra(AssistActivity.EXTRA_WAKE_TOKEN, LAUNCH_TOKEN),
        )
    }

    private fun startMotionWatch() {
        val sensors = getSystemService(SensorManager::class.java)
        sensors.unregisterListener(this)
        if (!settings.wakeSleepWhenStill) return
        val accel = sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return
        lastMovedAt = SystemClock.elapsedRealtime()
        // Slow sampling, batched in the sensor hub, keeps this nearly free.
        sensors.registerListener(this, accel, SensorManager.SENSOR_DELAY_NORMAL, MOTION_BATCH_US)
        scope.launch {
            while (isActive) {
                delay(60_000)
                val stillFor = SystemClock.elapsedRealtime() - lastMovedAt
                if (!sleeping && listenJob?.isActive == true && stillFor > settings.wakeStillMinutes * 60_000L) {
                    sleeping = true
                    stopListening()
                    updateNotification("Sleeping — phone hasn't moved in ${settings.wakeStillMinutes} min")
                    AppLog.i("Wake word sleeping: phone still for ${settings.wakeStillMinutes} min (battery ${batteryPercent()}%)")
                }
            }
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        val v = event.values
        val previous = lastAccel
        lastAccel = floatArrayOf(v[0], v[1], v[2])
        if (previous == null) return
        val change = sqrt((v[0] - previous[0]).let { it * it } + (v[1] - previous[1]).let { it * it } + (v[2] - previous[2]).let { it * it })
        if (abs(change) > MOVEMENT_THRESHOLD) {
            lastMovedAt = SystemClock.elapsedRealtime()
            if (sleeping) {
                AppLog.i("Wake word waking up: phone moved")
                startListening()
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun batteryPercent() = getSystemService(BatteryManager::class.java).getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text))
    }

    private fun notification(text: String): Notification {
        ensureChannel(this)
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, SettingsActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, WakeWordService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Wake word")
            .setContentText(text)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(open)
            .addAction(0, "Turn off", stop)
            .build()
    }

    companion object {
        const val CHANNEL_ID = "wake_word"
        private const val NOTIFICATION_ID = 7
        private const val ACTION_STOP = "stop"
        private const val ACTION_PAUSE = "pause"
        private const val ACTION_RESUME = "resume"
        private const val BATTERY_LOG_MS = 30 * 60_000L
        private const val MOTION_BATCH_US = 10_000_000
        private const val MOVEMENT_THRESHOLD = 0.6f

        @Volatile
        private var running: WakeWordService? = null

        /**
         * A secret only this app knows, sent with wake word launches. Assist is exported (it is the
         * phone's assistant), so other apps could start it pretending to be a wake word; without
         * this token, that can't open it over the lock screen.
         */
        val LAUNCH_TOKEN: String = java.util.UUID.randomUUID().toString()

        val isRunning get() = running != null

        fun ensureChannel(context: Context) {
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Wake word", NotificationManager.IMPORTANCE_LOW),
            )
        }

        /** Starts listening, if wake words are on. Must be called while the app is on screen. */
        fun start(context: Context) {
            if (!HaSettings(context).wakeEnabled) return
            try {
                ContextCompat.startForegroundService(context, Intent(context, WakeWordService::class.java))
            } catch (e: RuntimeException) {
                AppLog.w("Wake word couldn't start: ${e.javaClass.simpleName}")
                offerRestart(context)
            }
        }

        /** Lets go of the mic (Assist is opening and needs it). */
        fun pause(context: Context) {
            if (running == null) return
            context.startService(Intent(context, WakeWordService::class.java).setAction(ACTION_PAUSE))
        }

        /** Picks the mic back up after Assist closes. */
        fun resume(context: Context) {
            if (running == null) return
            context.startService(Intent(context, WakeWordService::class.java).setAction(ACTION_RESUME))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, WakeWordService::class.java))
        }

        /** A notification that restarts listening with one tap (Android requires the tap). */
        fun offerRestart(context: Context) {
            ensureChannel(context)
            val tap = PendingIntent.getActivity(
                context,
                0,
                Intent(context, SettingsActivity::class.java).putExtra(SettingsActivity.EXTRA_START_WAKE, true),
                PendingIntent.FLAG_IMMUTABLE,
            )
            context.getSystemService(NotificationManager::class.java).notify(
                8,
                NotificationCompat.Builder(context, CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_launcher_foreground)
                    .setContentTitle("Wake word stopped")
                    .setContentText("Tap to start listening again")
                    .setContentIntent(tap)
                    .setAutoCancel(true)
                    .build(),
            )
        }
    }
}

/** After a phone restart Android won't let a microphone service start by itself; offer a one-tap restart. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED || !HaSettings(context).wakeEnabled) return
        WakeWordService.offerRestart(context)
    }
}
