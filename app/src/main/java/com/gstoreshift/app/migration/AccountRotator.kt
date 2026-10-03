package com.gstoreshift.app.migration

import android.content.Context
import com.gstoreshift.app.account.GoogleAccountManager
import com.gstoreshift.app.data.AppDatabase
import com.gstoreshift.app.data.AuxAccountEntity
import com.gstoreshift.app.drive.DriveClient
import com.gstoreshift.app.drive.QuotaExceededException
import com.gstoreshift.app.drive.StorageQuota
import okhttp3.OkHttpClient

/**
 * Picks the auxiliary account that should receive the next file:
 * the active account with the most free space that still has
 * (file size × 1.05) headroom. Marks exhausted accounts full and rotates.
 */
class AccountRotator(
    private val context: Context,
    private val db: AppDatabase,
    private val accounts: GoogleAccountManager,
    private val okHttp: OkHttpClient,
) {
    companion object { const val HEADROOM = 1.05 }

    private val quotaCache = mutableMapOf<String, StorageQuota>()

    fun clientFor(email: String): DriveClient = DriveClient(context, okHttp) {
        accounts.accessToken(email)
    }

    suspend fun refreshQuota(email: String) {
        val q = clientFor(email).storageQuota()
        quotaCache[email] = q
        db.auxAccounts().updateQuota(email, q.limit, q.usage)
    }

    suspend fun pickFor(sizeBytes: Long): AuxAccountEntity? {
        var candidates = db.auxAccounts().activeAux()
        if (candidates.isEmpty()) return null
        // Refresh stale quotas lazily.
        candidates.forEach { if (it.email !in quotaCache) runCatching { refreshQuota(it.email) } }
        return candidates
            .filter { acc ->
                val q = quotaCache[acc.email] ?: return@filter true
                q.free >= sizeBytes * HEADROOM
            }
            .maxByOrNull { quotaCache[it.email]?.free ?: Long.MAX_VALUE }
    }

    /** Called when an upload throws [QuotaExceededException]. */
    suspend fun markFull(email: String) {
        db.auxAccounts().setFull(email, true)
        quotaCache.remove(email)
    }
}
