package com.mazharansari.axion.auth

import android.app.Activity
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential

/** "Continue with Google": Android Credential Manager's account sheet → a Google ID token for Firebase (Kodex's approach). */
object GoogleIdToken {
    suspend fun request(activity: Activity, serverClientId: String): String {
        val option = GetGoogleIdOption.Builder()
            .setFilterByAuthorizedAccounts(false)
            .setServerClientId(serverClientId)
            .setAutoSelectEnabled(false)
            .build()
        val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
        val result = try {
            CredentialManager.create(activity).getCredential(context = activity, request = request)
        } catch (e: GetCredentialCancellationException) { throw SignInCancelled() }
        val cred = result.credential
        if (cred.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) throw IllegalStateException("Google didn't return an account. Please try again.")
        return GoogleIdTokenCredential.createFrom(cred.data).idToken
    }
}
