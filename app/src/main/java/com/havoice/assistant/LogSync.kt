package com.havoice.assistant

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.TimeUnit

/** Copies queued [AppLog] lines into Home Assistant's system log (logger "assist_plus"). */
object LogSync {
    private const val BATCH = 50

    // One sync at a time, or two could send the same queued lines.
    private val running = Mutex()

    /** Sends everything pending over an already-connected [client]. Returns how many were sent. */
    suspend fun sync(client: HaClient): Int = running.withLock {
        var sent = 0
        var batch = AppLog.peekPending(BATCH)
        while (batch.isNotEmpty()) {
            for (entry in batch) {
                client.writeSystemLog(
                    message = "[phone ${AppLog.timestamp(entry)}] ${entry.message}",
                    level = entry.level.haLevel,
                )
                AppLog.markSent(1)
                sent++
            }
            batch = AppLog.peekPending(BATCH)
        }
        sent
    }

    /** Hourly background sync that only connects when there is something to send. */
    fun schedule(context: Context) {
        val request = PeriodicWorkRequestBuilder<LogSyncWorker>(1, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork("log-sync", ExistingPeriodicWorkPolicy.KEEP, request)
    }
}

class LogSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val settings = HaSettings(applicationContext)
        if (AppLog.pendingCount() == 0 || !settings.isConfigured) return Result.success()
        val client = HaClient(settings.baseUrl, settings.token)
        return try {
            client.connect()
            LogSync.sync(client)
            Result.success()
        } catch (e: HaException) {
            // Not reachable right now (away from home, HA restarting); try next time.
            Result.success()
        } finally {
            client.close()
        }
    }
}
