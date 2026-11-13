package com.mazharansari.axion.build

import java.io.File

/**
 * P1-9 Builder: release-signing path for APKs.
 *
 * An Android project can be built as a RELEASE APK signed with the user's own keystore:
 * the keystore is picked in-app (file picker), the alias and passwords are typed into a secure
 * prompt, and the passwords live ONLY in memory ([CharArray]s) for the duration of that one
 * build — they are never written to disk, never stored (not even KeyVault-encrypted: there is
 * no opt-in for that yet, so every release build asks again), and never logged. The debug
 * path ([BuildHub.buildApk]) is exactly as it was.
 *
 * How the signing reaches Gradle: the Android Gradle Plugin's documented injected-signing
 * properties (`-Pandroid.injected.signing.*`). The full command line contains the passwords,
 * so it is NEVER logged or shown anywhere — [BuildHub.buildReleaseApk] logs only lines scrubbed
 * with [scrubbed], and the request is wiped ([clear]) in a finally block.
 *
 * The keystore file itself is staged into the phone workspace's private `.axion-signing/`
 * folder (outside every project, never in the repo, never in the APK) and deleted when the
 * build ends; the copy the file picker made in the app cache is deleted as soon as it is
 * staged. This feature NEVER touches Axion's own signing keystore
 * (../keys/keystore.properties / the release signing config in app/build.gradle.kts).
 */
data class ReleaseSigningRequest(
    /** A private copy of the user's keystore (app cache), staged then deleted by the build. */
    val keystoreFile: File,
    val alias: String,
    val storePassword: CharArray,
    val keyPassword: CharArray,
) {
    /** Pure: every problem with this request, in plain English for the prompt. */
    fun validate(): List<String> = buildList {
        if (!keystoreFile.isFile) add("Pick a keystore file first.")
        else if (keystoreFile.extension.lowercase() !in setOf("keystore", "jks"))
            add("That does not look like a keystore (expected a .keystore or .jks file).")
        if (alias.isBlank()) add("Enter the key alias.")
        if (storePassword.isEmpty()) add("Enter the keystore password.")
        if (keyPassword.isEmpty()) add("Enter the key password.")
    }

    /** All secrets this request carries, for scrubbing logs. Transient Strings — never stored. */
    fun secrets(): List<String> = listOf(String(storePassword), String(keyPassword)).filter { it.isNotEmpty() }

    /** Zero the passwords in memory. Called by the build in a finally block — and by the UI when it clears its fields. */
    fun clear() {
        storePassword.fill('\u0000')
        keyPassword.fill('\u0000')
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ReleaseSigningRequest) return false
        return keystoreFile == other.keystoreFile && alias == other.alias &&
            storePassword.contentEquals(other.storePassword) && keyPassword.contentEquals(other.keyPassword)
    }

    override fun hashCode(): Int {
        var r = keystoreFile.hashCode()
        r = 31 * r + alias.hashCode()
        r = 31 * r + storePassword.contentHashCode()
        r = 31 * r + keyPassword.contentHashCode()
        return r
    }

    companion object {
        /** Single-quote a value for sh (passwords may contain spaces, `$`, quotes…). Pure, unit-tested. */
        fun shellQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"

        /**
         * The AGP injected-signing `-P` arguments. The passwords are in here, so the resulting
         * command string must only ever be passed to the shell — log [scrubbed] instead.
         */
        fun injectedArgs(guestKeystorePath: String, alias: String, storePassword: CharArray, keyPassword: CharArray): List<String> = listOf(
            "-Pandroid.injected.signing.store.file=${shellQuote(guestKeystorePath)}",
            "-Pandroid.injected.signing.store.password=${shellQuote(String(storePassword))}",
            "-Pandroid.injected.signing.key.alias=${shellQuote(alias)}",
            "-Pandroid.injected.signing.key.password=${shellQuote(String(keyPassword))}",
        )

        /**
         * The exact guest-shell command for the release build (same shape as the verifier's
         * build check: pipefail so a failing Gradle is never reported as success, output
         * tailed so the log stays readable).
         */
        fun gradleCommand(guestProject: String, useGradlew: Boolean, injected: List<String>): String {
            val gradle = if (useGradlew) "./gradlew" else "gradle"
            return "set -o pipefail; cd ${shellQuote(guestProject)} && $gradle assembleRelease ${injected.joinToString(" ")} --console=plain 2>&1 | tail -n 60"
        }

        /** The command with every secret replaced by `****` — the only form that may be logged or shown. Pure, unit-tested. */
        fun scrubbed(command: String, secrets: List<String>): String {
            var out = command
            secrets.filter { it.isNotEmpty() }.sortedByDescending { it.length }.forEach { out = out.replace(it, "****") }
            return out
        }

        /**
         * Stage the picked keystore where the Linux guest can read it: the phone workspace's
         * private `.axion-signing/` folder — outside every project, never in the repo, never
         * in the APK. Returns the staged file (the caller deletes it after the build).
         */
        fun stageKeystore(source: File, workspaceHost: File, slug: String): File {
            val dir = File(workspaceHost, ".axion-signing").apply { mkdirs() }
            val dest = File(dir, "$slug.keystore")
            source.copyTo(dest, overwrite = true)
            return dest
        }

        /** Guest path of a staged keystore (the workspace is bound to /workspace in Linux). */
        fun guestKeystorePath(slug: String): String = "/workspace/.axion-signing/$slug.keystore"

        /** The release APK Gradle just built, newest first — a real artifact, not a claimed success. */
        fun findReleaseApk(dir: File): File? =
            File(dir, "app/build/outputs/apk/release").walkTopDown().filter { it.isFile && it.extension == "apk" }.maxByOrNull { it.lastModified() }
    }
}
