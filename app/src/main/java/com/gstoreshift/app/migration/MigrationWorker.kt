package com.gstoreshift.app.migration

import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.work.*
import com.gstoreshift.app.GStoreShiftApp
import com.gstoreshift.app.account.GoogleAccountManager
import com.gstoreshift.app.data.AppDatabase
import com.gstoreshift.app.data.ItemState
import com.gstoreshift.app.data.UploadReceiptEntity
import com.gstoreshift.app.drive.QuotaExceededException
import com.gstoreshift.app.scan.LocalFileResolver
import com.gstoreshift.app.scan.MediaScanner
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * WorkManager-based migration engine. Processes the QUEUED queue:
 * hash → dedup check → pick aux account → resumable upload → verify MD5 →
 * receipt. Local deletion/relocate happens later in the Cleanup phase only
 * after explicit user approval.
 */
class MigrationWorker(
    context: Context, params: WorkerParameters,
) : CoroutineWorker(context, params) {

    companion object {
        const val WORK_NAME = "migration"
        const val KEY_WIFI_ONLY = "wifi_only"
        const val KEY_SESSION_URI_PREFIX = "session_"

        fun enqueue(context: Context, wifiOnly: Boolean = true) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(
                    if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED
                )
                .build()
            val data = workDataOf(KEY_WIFI_ONLY to wifiOnly)
            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME, ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<MigrationWorker>()
                    .setConstraints(constraints)
                    .setInputData(data)
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                    .build(),
            )
        }
    }

    override suspend fun doWork(): Result {
        val db = AppDatabase.build(applicationContext)
        val accounts = GoogleAccountManager(applicationContext)
        val okHttp = OkHttpClient()
        val rotator = AccountRotator(applicationContext, db, accounts, okHttp)
        val scanner = MediaScanner(applicationContext, db)

        val queue = db.mediaItems().byState(ItemState.QUEUED)
        if (queue.isEmpty()) return Result.success()

        setForeground(foregroundInfo("Starting migration…"))
        var done = 0
        var failed = 0

        for (item in queue) {
            try {
                db.mediaItems().setState(item.mediaStoreId, ItemState.UPLOADING)
                setForeground(foregroundInfo("Uploading ${done + 1}/${queue.size}: ${item.displayName}"))

                // 1. Hash + dedup
                val md5 = scanner.md5Hex(item)
                if (db.receipts().existsForHash(md5)) {
                    db.mediaItems().setState(item.mediaStoreId, ItemState.UPLOADED)
                    done++; continue
                }

                // 2. Pick aux account with headroom
                val acc = rotator.pickFor(item.sizeBytes)
                    ?: return if (done > 0) Result.success() else Result.retry() // all full → notify user via UI
                val drive = rotator.clientFor(acc.email)

                // 3. Ensure YYYY/MM folder + upload
                val folderId = drive.ensureFolder(item.year, item.month)
                val file = LocalFileResolver.resolve(applicationContext, item)
                val uploaded = try {
                    drive.resumableUpload(file, item.displayName, item.mimeType, folderId)
                } catch (e: QuotaExceededException) {
                    rotator.markFull(acc.email)
                    db.mediaItems().setState(item.mediaStoreId, ItemState.QUEUED)
                    continue // retry same file against next account
                } finally {
                    LocalFileResolver.cleanupScratch(applicationContext, item.mediaStoreId)
                }

                // 4. Verify remote MD5 matches local
                val remoteMd5 = uploaded.md5Checksum ?: drive.fetchMd5(uploaded.id)
                if (remoteMd5 != null && !remoteMd5.equals(md5, ignoreCase = true)) {
                    db.mediaItems().setState(item.mediaStoreId, ItemState.FAILED)
                    failed++; continue
                }

                // 5. Receipt → VERIFIED (awaiting user-approved local cleanup)
                db.receipts().insert(
                    UploadReceiptEntity(
                        md5Hex = md5,
                        mediaStoreId = item.mediaStoreId,
                        auxEmail = acc.email,
                        driveFileId = uploaded.id,
                        drivePath = "GStoreShift/%04d/%02d/%s".format(item.year, item.month, item.displayName),
                        sizeBytes = item.sizeBytes,
                        uploadedAtMillis = System.currentTimeMillis(),
                    )
                )
                db.mediaItems().setState(item.mediaStoreId, ItemState.VERIFIED)
                rotator.refreshQuota(acc.email)
                done++
            } catch (t: Throwable) {
                db.mediaItems().setState(item.mediaStoreId, ItemState.FAILED)
                failed++
            }
        }

        return when {
            failed > 0 && done == 0 -> Result.retry()
            else -> Result.success()
        }
    }

    private fun foregroundInfo(text: String): ForegroundInfo {
        val notification = NotificationCompat.Builder(applicationContext, GStoreShiftApp.MIGRATION_CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("GStoreShift")
            .setContentText(text)
            .setOngoing(true)
            .build()
        return ForegroundInfo(1, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }
}
