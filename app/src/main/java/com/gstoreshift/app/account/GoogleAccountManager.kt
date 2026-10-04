package com.gstoreshift.app.account

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

/**
 * Manages the host account + N auxiliary accounts via Google Sign-In.
 *
 * Scope strategy (from docs/spike-findings.md §1): v1 uses Drive only.
 * Photos `photoslibrary.appendonly` may be added later behind a feature flag.
 */
class GoogleAccountManager(private val context: Context) {

    companion object {
        val DRIVE_SCOPE = Scope("https://www.googleapis.com/auth/drive.file")
        private const val PREFS = "gstoreshift_accounts"
        private const val KEY_AUX_EMAILS = "aux_emails"

        fun getReadableErrorMessage(statusCode: Int, rawMessage: String?, pkgName: String? = null, sha1: String? = null): String {
            return when (statusCode) {
                10 -> {
                    val pkgInfo = pkgName?.let { " Pkg: $it." } ?: ""
                    val shaInfo = sha1?.let { " SHA-1: $it." } ?: ""
                    "Code 10 (DEVELOPER_ERROR): OAuth Client ID / SHA-1 fingerprint mismatch in Google Cloud Console.$pkgInfo$shaInfo Ensure Google Drive API is enabled & debug SHA-1 is added under Android OAuth Credentials."
                }
                7 -> "Code 7 (NETWORK_ERROR): Network error during sign in. Check internet connection."
                12500 -> "Code 12500 (SIGN_IN_FAILED): Sign in failed. Verify Google Play Services is updated."
                12501 -> "Code 12501 (SIGN_IN_CANCELLED): Sign in cancelled by user."
                4 -> "Code 4 (SIGN_IN_REQUIRED): Sign in required."
                else -> "Code $statusCode: ${rawMessage ?: "Unknown error"}"
            }
        }
    }

    private val securePrefs by lazy {
        val key = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        EncryptedSharedPreferences.create(
            context, PREFS, key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    /**
     * Optional Web Client ID for OAuth server authentication or ID tokens.
     * Automatically attempts to read `default_web_client_id` from string resources if null.
     */
    var webClientId: String? = null

    /**
     * DEBUG DIAGNOSTIC: when false, sign in identity-only (no drive.file scope).
     * If identity-only succeeds but scoped sign-in fails with code 10, the OAuth
     * client + consent screen are fine and `drive.file` is simply not registered
     * on the OAuth consent screen in Google Cloud Console.
     */
    var includeDriveScope: Boolean = true

    private fun getWebClientIdFromResources(): String? {
        val resId = context.resources.getIdentifier("default_web_client_id", "string", context.packageName)
        return if (resId != 0) context.getString(resId) else null
    }

    /** Returns the SHA-1 certificate fingerprint of the app's signing key for Google Cloud Console setup. */
    fun getSigningSha1(): String {
        return try {
            val packageInfo = context.packageManager.getPackageInfo(
                context.packageName,
                android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES
            )
            val signatures = packageInfo.signingInfo?.apkContentsSigners
            val cert = signatures?.firstOrNull()?.toByteArray()
            if (cert != null) {
                val md = java.security.MessageDigest.getInstance("SHA-1")
                val digest = md.digest(cert)
                digest.joinToString(":") { "%02X".format(it) }
            } else "UNKNOWN"
        } catch (e: Exception) {
            "ERROR: ${e.message}"
        }
    }

    fun signInOptions(): GoogleSignInOptions =
        GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .apply {
                if (includeDriveScope) requestScopes(DRIVE_SCOPE)
                val clientId = webClientId ?: getWebClientIdFromResources()
                if (!clientId.isNullOrBlank()) {
                    requestIdToken(clientId)
                }
            }
            .build()

    fun client() = GoogleSignIn.getClient(context, signInOptions())

    /** Emails of registered auxiliary accounts. */
    fun auxEmails(): Set<String> =
        securePrefs.getStringSet(KEY_AUX_EMAILS, emptySet()) ?: emptySet()

    fun registerAux(email: String) {
        securePrefs.edit().putStringSet(KEY_AUX_EMAILS, auxEmails() + email).apply()
    }

    fun unregisterAux(email: String) {
        securePrefs.edit().putStringSet(KEY_AUX_EMAILS, auxEmails() - email).apply()
    }

    /** Current signed-in host account (device's primary sign-in for this app). */
    fun hostEmail(): String? = GoogleSignIn.getLastSignedInAccount(context)
        ?.takeIf { it.email !in auxEmails() }?.email

    /**
     * Fresh OAuth access token for [email] with the Drive scope, silently.
     * Uses GoogleAuthUtil via the play-services identity flow.
     */
    suspend fun accessToken(email: String): String = withContext(Dispatchers.IO) {
        com.google.android.gms.auth.GoogleAuthUtil.getToken(
            context,
            android.accounts.Account(email, "com.google"),
            "oauth2:${DRIVE_SCOPE.scopeUri}",
        )
    }

    /** Revokes app access and clears all registrations (sign out / reset). */
    suspend fun signOutAll() {
        client().signOut().await()
        securePrefs.edit().clear().apply()
    }
}
