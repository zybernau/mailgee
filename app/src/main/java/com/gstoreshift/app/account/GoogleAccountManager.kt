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
     * DEBUG DIAGNOSTIC: when false, sign in identity-only (no drive.file scope).
     * If identity-only succeeds but scoped sign-in fails with code 10, the OAuth
     * client + consent screen are fine and `drive.file` is simply not registered
     * on the OAuth consent screen in Google Cloud Console.
     */
    var includeDriveScope: Boolean = true

    fun signInOptions(): GoogleSignInOptions =
        GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .apply { if (includeDriveScope) requestScopes(DRIVE_SCOPE) }
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
