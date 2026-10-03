package com.gstoreshift.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.gstoreshift.app.account.GoogleAccountManager
import com.gstoreshift.app.data.*
import com.gstoreshift.app.drive.DriveClient
import com.gstoreshift.app.migration.MigrationWorker
import com.gstoreshift.app.scan.MediaScanner
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

data class QuotaInfo(val total: Long, val used: Long, val free: Long)

class MainViewModel(app: Application) : AndroidViewModel(app) {

    companion object {
        /** Logcat tag the verifier subagent greps on: adb logcat -s GSTOR_TEST */
        const val LOG_TAG = "GSTOR_TEST"
        private fun logTest(event: String) = android.util.Log.i(LOG_TAG, event)
    }

    private val db = AppDatabase.build(app)
    private val accounts = GoogleAccountManager(app).apply {
        // DIAGNOSTIC BUILD: identity-only sign-in to isolate the code-10 cause.
        includeDriveScope = false
    }
    private val scanner = MediaScanner(app, db)
    private val okHttp = OkHttpClient()

    // ---- UI state ----

    val auxAccounts: StateFlow<List<AuxAccountEntity>> =
        db.auxAccounts().observeAll()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val monthRollup: StateFlow<List<MonthRollup>> =
        db.mediaItems().monthRollup()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val queue: StateFlow<List<MediaItemEntity>> =
        db.mediaItems().observeByState(
            listOf(ItemState.QUEUED, ItemState.UPLOADING, ItemState.VERIFIED, ItemState.FAILED)
        ).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning

    /** Reactive host account. */
    private val _hostEmail = MutableStateFlow(accounts.hostEmail())
    val hostEmail: StateFlow<String?> = _hostEmail

    /** Live quota values keyed by email (hosts + aux). */
    private val _quotas = MutableStateFlow<Map<String, QuotaInfo>>(emptyMap())
    val quotas: StateFlow<Map<String, QuotaInfo>> = _quotas

    /** One-off snackbar messages (errors, confirmations). */
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message
    fun clearMessage() { _message.value = null }

    private val _refreshingQuotas = MutableStateFlow(false)
    val refreshingQuotas: StateFlow<Boolean> = _refreshingQuotas

    // ---- Actions ----

    fun onHostSignedIn(email: String) {
        logTest("HOST_SIGNIN_OK email=$email")
        _hostEmail.value = email
        refreshQuota(email)
    }

    fun signOutHost() = viewModelScope.launch {
        runCatching { accounts.signOutAll() }
        _hostEmail.value = null
        logTest("HOST_SIGNED_OUT")
    }

    fun onAuxSignedIn(email: String) = viewModelScope.launch {
        accounts.registerAux(email)
        db.auxAccounts().upsert(AuxAccountEntity(email = email))
        refreshQuota(email)
        logTest("AUX_SIGNIN_OK email=$email")
        _message.value = "Added $email"
    }

    fun removeAux(email: String) = viewModelScope.launch {
        accounts.unregisterAux(email)
        db.auxAccounts().remove(email)
        _message.value = "Removed $email"
    }

    fun reportSignInError(role: String?, error: String) {
        logTest("SIGNIN_FAIL role=$role error=$error")
        _message.value = "Sign-in failed${role?.let { " ($it)" } ?: ""}: $error"
    }

    /** Refresh quota for one account; errors surfaced via [message]. */
    fun refreshQuota(email: String) = viewModelScope.launch {
        runCatching {
            val client = DriveClient(getApplication(), okHttp) { accounts.accessToken(email) }
            client.storageQuota()
        }.onSuccess { q ->
            _quotas.value = _quotas.value + (email to QuotaInfo(q.limit, q.usage, q.free))
            db.auxAccounts().upsert(
                AuxAccountEntity(email = email, isHost = email == _hostEmail.value,
                    quotaTotalBytes = q.limit, quotaUsedBytes = q.usage)
            )
            logTest("QUOTA_OK email=$email total=${q.limit} used=${q.usage} free=${q.free}")
        }.onFailure {
            logTest("QUOTA_FAIL email=$email error=${it.message}")
            _message.value = "Quota failed for $email: ${it.message ?: "no network/auth"}"
        }
    }

    fun refreshQuotas() = viewModelScope.launch {
        _refreshingQuotas.value = true
        val targets = listOfNotNull(_hostEmail.value) + auxAccounts.value.map { it.email }
        if (targets.isEmpty()) _message.value = "No accounts signed in yet."
        targets.forEach { email ->
            runCatching {
                val client = DriveClient(getApplication(), okHttp) { accounts.accessToken(email) }
                client.storageQuota()
            }.onSuccess { q ->
                _quotas.value = _quotas.value + (email to QuotaInfo(q.limit, q.usage, q.free))
                db.auxAccounts().upsert(
                    AuxAccountEntity(email = email, isHost = email == _hostEmail.value,
                        quotaTotalBytes = q.limit, quotaUsedBytes = q.usage)
                )
                logTest("QUOTA_OK email=$email total=${q.limit} used=${q.usage} free=${q.free}")
            }.onFailure {
                logTest("QUOTA_FAIL email=$email error=${it.message}")
                _message.value = "Quota failed for $email: ${it.message ?: "no network/auth"}"
            }
        }
        _refreshingQuotas.value = false
    }

    fun runScan() = viewModelScope.launch {
        _scanning.value = true
        try { _message.value = "Scanned ${scanner.scan()} items" }
        catch (t: Throwable) { _message.value = "Scan failed: ${t.message}" }
        finally { _scanning.value = false }
    }

    fun queueMonth(year: Int, month: Int) = viewModelScope.launch {
        db.mediaItems().byState(ItemState.SCANNED)
            .filter { it.year == year && it.month == month }
            .forEach { db.mediaItems().setState(it.mediaStoreId, ItemState.QUEUED) }
        _message.value = "Queued %04d-%02d".format(year, month)
    }

    fun startMigration(wifiOnly: Boolean) =
        MigrationWorker.enqueue(getApplication(), wifiOnly)
}
