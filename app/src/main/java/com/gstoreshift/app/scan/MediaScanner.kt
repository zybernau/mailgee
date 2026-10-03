package com.gstoreshift.app.scan

import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore
import com.gstoreshift.app.data.AppDatabase
import com.gstoreshift.app.data.ItemState
import com.gstoreshift.app.data.MediaItemEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.util.Calendar

/**
 * Enumerates device photos/videos via MediaStore, groups by Year/Month,
 * and computes content hashes used for dedup + post-upload verification.
 */
class MediaScanner(private val context: Context, private val db: AppDatabase) {

    private val projection = arrayOf(
        MediaStore.Files.FileColumns._ID,
        MediaStore.Files.FileColumns.DISPLAY_NAME,
        MediaStore.Files.FileColumns.MIME_TYPE,
        MediaStore.Files.FileColumns.SIZE,
        MediaStore.Files.FileColumns.DATE_TAKEN,
        MediaStore.Files.FileColumns.DATA,          // for source folder
        MediaStore.Files.FileColumns.MEDIA_TYPE,
    )

    suspend fun scan(): Int = withContext(Dispatchers.IO) {
        val selection = "${MediaStore.Files.FileColumns.MEDIA_TYPE} IN (?, ?)"
        val args = arrayOf(
            MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString(),
            MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString(),
        )
        val found = mutableListOf<MediaItemEntity>()
        context.contentResolver.query(
            MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL),
            projection, selection, args,
            "${MediaStore.Files.FileColumns.DATE_TAKEN} ASC",
        )?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
            val nameCol = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)
            val mimeCol = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MIME_TYPE)
            val sizeCol = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.SIZE)
            val dateCol = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_TAKEN)
            val pathCol = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATA)
            while (c.moveToNext()) {
                val dateMillis = c.getLong(dateCol).takeIf { it > 0 } ?: continue
                val cal = Calendar.getInstance().apply { timeInMillis = dateMillis }
                val path = c.getString(pathCol) ?: ""
                // Skip items already relocated by this app (ExclusionManager's target dir).
                if (path.contains("/Android/media/${context.packageName}/")) continue
                val id = c.getLong(idCol)
                found += MediaItemEntity(
                    mediaStoreId = id,
                    uriString = ContentUris.withAppendedId(
                        MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL), id
                    ).toString(),
                    displayName = c.getString(nameCol) ?: "unknown",
                    mimeType = c.getString(mimeCol) ?: "application/octet-stream",
                    sizeBytes = c.getLong(sizeCol),
                    dateTakenMillis = dateMillis,
                    year = cal.get(Calendar.YEAR),
                    month = cal.get(Calendar.MONTH) + 1,
                    sourceFolder = path.substringBeforeLast('/'),
                    state = ItemState.SCANNED,
                )
            }
        }
        db.mediaItems().upsertAll(found)
        found.size
    }

    /** MD5 of file content — matches Drive's md5Checksum for verification. */
    suspend fun md5Hex(item: MediaItemEntity): String = withContext(Dispatchers.IO) {
        item.md5Hex ?: run {
            val digest = MessageDigest.getInstance("MD5")
            context.contentResolver.openInputStream(android.net.Uri.parse(item.uriString))!!
                .use { input ->
                    val buf = ByteArray(1024 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        digest.update(buf, 0, n)
                    }
                }
            digest.digest().joinToString("") { "%02x".format(it) }.also {
                db.mediaItems().setHash(item.mediaStoreId, it)
            }
        }
    }
}
