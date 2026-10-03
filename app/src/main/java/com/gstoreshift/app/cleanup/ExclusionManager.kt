package com.gstoreshift.app.cleanup

import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import android.provider.Settings
import com.gstoreshift.app.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Post-verification cleanup + host backup exclusion.
 * Strategy per docs/spike-findings.md §3:
 *  1. Verified local delete via MediaStore batch delete request (needs user approval on API 30+).
 *  2. Optional relocate-to-app-media for users keeping local copies.
 *  3. Guided Photos-backup walkthrough for residual folders.
 */
class ExclusionManager(private val context: Context, private val db: AppDatabase) {

    /** Items in VERIFIED state ready for deletion approval. */
    suspend fun pendingCleanup(): List<MediaItemEntity> =
        db.mediaItems().byState(ItemState.VERIFIED)

    /**
     * Builds the system delete-confirmation intent. Launch from an Activity;
     * on RESULT_OK, call [confirmDeleted].
     */
    fun buildDeleteIntent(activity: Activity, items: List<MediaItemEntity>): PendingIntent {
        val uris = items.map { Uri.parse(it.uriString) }
        return MediaStore.createDeleteRequest(activity.contentResolver, uris)
    }

    suspend fun confirmDeleted(items: List<MediaItemEntity>) = withContext(Dispatchers.IO) {
        items.forEach { item ->
            db.mediaItems().setState(item.mediaStoreId, ItemState.LOCAL_DELETED)
            db.exclusions().add(
                ExclusionEntryEntity(item.sourceFolder, reason = "MIGRATED")
            )
        }
    }

    /** Relocate mode: move a kept local copy to the app-media dir Photos doesn't back up. */
    suspend fun relocateToAppMedia(item: MediaItemEntity): Boolean = withContext(Dispatchers.IO) {
        val src = File(item.sourceFolder, item.displayName)
        // Android/media/<pkg>/ is the package-private media dir — not indexed/backed up by Photos.
        val destDir = File(
            android.os.Environment.getExternalStorageDirectory(),
            "Android/media/${context.packageName}/GStoreShift/%04d/%02d".format(item.year, item.month),
        )
        if (!src.exists()) return@withContext false
        destDir.mkdirs()
        val ok = src.renameTo(File(destDir, item.displayName))
        if (ok) {
            db.mediaItems().setState(item.mediaStoreId, ItemState.RELOCATED)
            db.exclusions().add(ExclusionEntryEntity(item.sourceFolder, reason = "RELOCATED"))
        }
        ok
    }

    /**
     * VAL-2: best-effort deep link into Google Photos backup settings.
     * Falls back to Photos app details, then returns false so the UI can show
     * the annotated manual walkthrough.
     */
    fun openPhotosBackupSettings(activity: Activity): Boolean {
        val attempts = listOf(
            // Best-known internal route (not guaranteed; Photos versions vary).
            Intent(Intent.ACTION_VIEW).setClassName(
                "com.google.android.apps.photos",
                "com.google.android.apps.photos.backupsettings.ui.BackupCheckActivity",
            ),
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).setData(
                Uri.parse("package:com.google.android.apps.photos")
            ),
        )
        for (intent in attempts) {
            try { activity.startActivity(intent); return true } catch (_: Exception) {}
        }
        return false
    }
}
