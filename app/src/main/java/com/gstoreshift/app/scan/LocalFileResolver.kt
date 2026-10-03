package com.gstoreshift.app.scan

import android.content.Context
import android.net.Uri
import com.gstoreshift.app.data.MediaItemEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Uploads need a real [File] (chunked reads). MediaStore items are content://
 * URIs, so resolve to the on-disk path when readable, else stream-copy to an
 * app cache scratch file.
 */
object LocalFileResolver {

    suspend fun resolve(context: Context, item: MediaItemEntity): File = withContext(Dispatchers.IO) {
        // Fast path: direct path read (works for media files with READ_MEDIA_* grants).
        val direct = File("/proc/self/fd/0").let { // placeholder to keep path logic simple
            val path = item.sourceFolder + "/" + item.displayName
            File(path).takeIf { it.canRead() }
        }
        if (direct != null) return@withContext direct

        // Fallback: copy via ContentResolver into cache (cleaned by worker after upload).
        val scratch = File(context.cacheDir, "upl_${item.mediaStoreId}")
        context.contentResolver.openInputStream(Uri.parse(item.uriString))!!.use { input ->
            scratch.outputStream().use { input.copyTo(it, 1024 * 1024) }
        }
        scratch
    }

    fun cleanupScratch(context: Context, mediaStoreId: Long) {
        File(context.cacheDir, "upl_$mediaStoreId").delete()
    }
}
