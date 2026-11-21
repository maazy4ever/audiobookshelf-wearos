package dev.shelfwatch.data

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.shelfwatch.Graph
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.TimeUnit

data class SyncStatus(
    val lastSuccessAt: Long = 0L,
    val lastAttemptAt: Long = 0L,
    val lastError: String? = null,
)

class SyncManager(
    private val context: Context,
    private val api: AbsApi,
    private val progress: ProgressStore,
) {
    private val _status = MutableStateFlow(SyncStatus())
    val status = _status.asStateFlow()
    private val mutex = Mutex()

    fun reportSuccess() {
        val now = System.currentTimeMillis()
        _status.value = SyncStatus(lastSuccessAt = now, lastAttemptAt = now)
    }

    fun reportFailure(e: Throwable) {
        _status.update {
            it.copy(lastAttemptAt = System.currentTimeMillis(), lastError = e.message ?: e.javaClass.simpleName)
        }
    }

    /**
     * Überträgt alle offline gespeicherten Fortschritte.
     * Konfliktregel: Der neuere Stand gewinnt (Server-`lastUpdate` vs. lokaler Zeitstempel).
     */
    suspend fun pushPending(): Boolean = mutex.withLock {
        if (!Graph.prefs.isLoggedIn) return@withLock true
        var ok = true
        for ((id, p) in progress.dirtyItems()) {
            try {
                val server = api.progress(id)
                if (server != null && server.lastUpdate > p.lastUpdate) {
                    progress.adoptServer(server)
                    continue
                }
                api.updateProgress(id, p.currentTime, p.duration, p.isFinished)
                progress.markClean(id, p.lastUpdate)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ok = false
                reportFailure(e)
            }
        }
        if (ok) reportSuccess()
        ok
    }

    /** Hintergrund-Sync, sobald Netzwerk verfügbar ist (überlebt auch Neustarts). */
    fun schedule() {
        val req = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork("abs-progress-sync", ExistingWorkPolicy.KEEP, req)
    }
}

class SyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        if (Graph.progress.dirtyItems().isEmpty()) return Result.success()
        return if (Graph.sync.pushPending()) Result.success() else Result.retry()
    }
}
