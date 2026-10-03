package com.gstoreshift.app.drive

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import java.io.File

class QuotaExceededException : Exception("Drive quota exceeded for account")
class DriveApiException(code: Int, body: String?) : Exception("Drive API $code: $body")

data class UploadedFile(val id: String, val md5Checksum: String?, val size: Long)
data class StorageQuota(val limit: Long, val usage: Long) { val free get() = limit - usage }

/**
 * Minimal Drive v3 client over OkHttp + bearer token.
 * Raw HTTP (not the google-api-client) keeps resumable-upload control explicit
 * and avoids pulling heavy GMS Drive deps.
 */
class DriveClient(
    private val context: Context,
    private val okHttp: OkHttpClient,
    private val tokenProvider: suspend () -> String,
) {
    companion object {
        private const val API = "https://www.googleapis.com/drive/v3"
        private const val UPLOAD = "https://www.googleapis.com/upload/drive/v3"
        private val JSON = "application/json; charset=utf-8".toMediaType()
        private const val CHUNK = 8L * 1024 * 1024   // 8 MB, VAL-4 target
    }

    /** storageQuota for the token's account. VAL-1: validate with drive.file scope. */
    suspend fun storageQuota(): StorageQuota = withContext(Dispatchers.IO) {
        val resp = get("$API/about?fields=storageQuota")
        val q = JSONObject(resp).getJSONObject("storageQuota")
        StorageQuota(q.optLong("limit", 0), q.optLong("usage", 0))
    }

    /** Returns Drive folder id for GStoreShift/YYYY/MM, creating as needed. Cached per-run. */
    private val folderCache = mutableMapOf<String, String>()

    suspend fun ensureFolder(year: Int, month: Int): String {
        val root = folderOrCreate("GStoreShift", null)
        val y = folderOrCreate("%04d".format(year), root)
        return folderOrCreate("%02d".format(month), y)
    }

    private suspend fun folderOrCreate(name: String, parent: String?): String =
        folderCache["$parent/$name"] ?: run {
            val q = "name='$name' and mimeType='application/vnd.google-apps.folder'" +
                " and trashed=false" + (parent?.let { " and '$it' in parents" } ?: "")
            val found = JSONObject(get("$API/files?q=${Uri.encode(q)}&fields=files(id)"))
                .optJSONArray("files")
            val id = if (found != null && found.length() > 0) {
                found.getJSONObject(0).getString("id")
            } else {
                val meta = JSONObject().apply {
                    put("name", name)
                    put("mimeType", "application/vnd.google-apps.folder")
                    if (parent != null) put("parents", org.json.JSONArray(listOf(parent)))
                }
                JSONObject(post("$API/files?fields=id", meta.toString())).getString("id")
            }
            folderCache["$parent/$name"] = id
            id
        }

    /**
     * Resumable upload of local [file] into [folderId] as [remoteName].
     * [sessionUri] allows resume after process death (VAL-4): pass the persisted
     * session URI if the upload was interrupted.
     */
    suspend fun resumableUpload(
        file: File, remoteName: String, mimeType: String, folderId: String,
        sessionUri: String? = null,
    ): UploadedFile = withContext(Dispatchers.IO) {
        val session = sessionUri ?: startSession(remoteName, mimeType, folderId, file.length())
        uploadChunks(session, file, mimeType)
    }

    private suspend fun startSession(name: String, mime: String, folderId: String, size: Long): String =
        withContext(Dispatchers.IO) {
            val meta = JSONObject().apply {
                put("name", name)
                put("parents", org.json.JSONArray(listOf(folderId)))
            }
            val req = Request.Builder()
                .url("$UPLOAD/files?uploadType=resumable&fields=id,md5Checksum,size")
                .header("Authorization", "Bearer ${tokenProvider()}")
                .header("X-Upload-Content-Type", mime)
                .header("X-Upload-Content-Length", size.toString())
                .post(okhttp3.RequestBody.create(JSON, meta.toString()))
                .build()
            execute(req).let { resp ->
                if (!resp.isSuccessful) throw mapError(resp.code, resp.body?.string())
                resp.header("Location") ?: throw DriveApiException(resp.code, "no session uri")
            }
        }

    private fun uploadChunks(sessionUri: String, file: File, mime: String): UploadedFile {
        val total = file.length()
        var offset = 0L
        while (offset < total) {
            val end = minOf(offset + CHUNK, total) - 1
            val body = object : okhttp3.RequestBody() {
                override fun contentType() = mime.toMediaType()
                override fun contentLength() = end - offset + 1
                override fun writeTo(sink: okio.BufferedSink) {
                    file.inputStream().use { it.skip(offset); it.copyTo(sink.outputStream(), ((end - offset + 1).toInt())) }
                }
            }
            val req = Request.Builder().url(sessionUri)
                .header("Content-Range", "bytes $offset-$end/$total")
                .put(body).build()
            val resp = execute(req)
            when {
                resp.code == 308 -> { // Resume Incomplete — query where server stopped
                    offset = resp.header("Range")?.substringAfterLast('-')?.toLong()?.plus(1)
                        ?: queryUploadOffset(sessionUri, total)
                    resp.close()
                }
                resp.isSuccessful -> {
                    val json = JSONObject(resp.body!!.string())
                    return UploadedFile(
                        json.getString("id"),
                        json.optString("md5Checksum").ifEmpty { null },
                        json.optLong("size", total),
                    )
                }
                else -> throw mapError(resp.code, resp.body?.string())
            }
        }
        error("unreachable")
    }

    private fun queryUploadOffset(sessionUri: String, total: Long): Long {
        val req = Request.Builder().url(sessionUri)
            .header("Content-Range", "bytes */$total")
            .put(okhttp3.RequestBody.create(null, ByteArray(0))).build()
        return execute(req).use { r ->
            r.header("Range")?.substringAfterLast('-')?.toLong()?.plus(1) ?: 0L
        }
    }

    suspend fun fetchMd5(fileId: String): String? = withContext(Dispatchers.IO) {
        JSONObject(get("$API/files/$fileId?fields=md5Checksum"))
            .optString("md5Checksum").ifEmpty { null }
    }

    // ---- HTTP plumbing ----

    private suspend fun get(url: String): String = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(url)
            .header("Authorization", "Bearer ${tokenProvider()}").build()
        execute(req).use { r ->
            if (!r.isSuccessful) throw mapError(r.code, r.body?.string())
            r.body!!.string()
        }
    }

    private suspend fun post(url: String, json: String): String = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(url)
            .header("Authorization", "Bearer ${tokenProvider()}")
            .post(okhttp3.RequestBody.create(JSON, json)).build()
        execute(req).use { r ->
            if (!r.isSuccessful) throw mapError(r.code, r.body?.string())
            r.body!!.string()
        }
    }

    private fun execute(req: Request) = okHttp.newCall(req).execute()

    private fun mapError(code: Int, body: String?): Exception =
        if (code == 403 && body?.contains("storageQuotaExceeded") == true) QuotaExceededException()
        else DriveApiException(code, body)
}
