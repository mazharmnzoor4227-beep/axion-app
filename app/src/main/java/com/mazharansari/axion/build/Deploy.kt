package com.mazharansari.axion.build

import com.mazharansari.axion.llm.Http
import kotlinx.coroutines.delay
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Where a site can go live with the user's own one-time token (addendum 2). */
enum class DeployProvider(val id: String, val title: String, val tokenPage: String, val tokenHint: String, val note: String) {
    NETLIFY("netlify", "Netlify", "https://app.netlify.com/user/applications#personal-access-tokens", "Personal access token",
        "Free · live in ~10 s · yourname.netlify.app"),
    VERCEL("vercel", "Vercel", "https://vercel.com/account/settings/tokens", "Account token", "Free · live in ~20 s · yourname.vercel.app"),
    CLOUDFLARE("cloudflare", "Cloudflare Pages", "https://dash.cloudflare.com/profile/api-tokens", "API token with Pages: Edit",
        "Free · needs Web (Node) in Developer tools · yourname.pages.dev"),
    GITHUB("github", "GitHub Pages", "https://github.com/settings/tokens/new?scopes=repo&description=Axion", "Token with repo scope",
        "Free · first publish takes ~1 min · you.github.io/name");
    companion object { fun byId(id: String?) = entries.firstOrNull { it.id == id } }
}

data class DeployResult(val url: String, val siteId: String?, val name: String)

class DeployException(msg: String) : Exception(msg)

/**
 * Deploy clients. Netlify, Vercel and GitHub Pages are plain HTTPS from the phone (no toolchain). Cloudflare Pages has no
 * simple upload API, so it runs Cloudflare's own `wrangler` inside Linux (needs Web/Node).
 * Request shapes are unit-tested (DeployTest); live deploys need the user's token and were not run from the test box.
 */
class Deployer(private val http: OkHttpClient = Http.download, private val api: Map<String, String> = emptyMap()) {
    private val JSONT = "application/json".toMediaType()
    private fun base(p: String, def: String) = api[p] ?: def

    /** Every file under [site] except hidden/generated junk, relative path → bytes. */
    fun collect(site: File): Map<String, File> = site.walkTopDown()
        .onEnter { it == site || (it.name !in ProjectFiles.HIDDEN && !it.name.startsWith(".")) }
        .filter { it.isFile && !it.name.startsWith(".") }.associateBy { it.relativeTo(site).path.replace('\\', '/') }

    fun zip(site: File): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { z -> collect(site).toSortedMap().forEach { (rel, f) -> z.putNextEntry(ZipEntry(rel)); f.inputStream().use { it.copyTo(z) }; z.closeEntry() } }
        return bos.toByteArray()
    }

    /** A site name hosts accept: lowercase, digits, dashes, 3–50 chars. */
    fun siteName(projectSlug: String, suffix: String = ""): String = (ProjectSlugSafe(projectSlug) + suffix).take(50).trim('-').let { if (it.length < 3) "axion-$it" else it }
    private fun ProjectSlugSafe(s: String) = s.lowercase().replace(Regex("[^a-z0-9-]"), "-").replace(Regex("-+"), "-").trim('-')

    private fun call(rb: Request.Builder): Pair<Int, String> = http.newCall(rb.header("User-Agent", Http.UA).build()).execute().use { it.code to (it.body?.string().orEmpty()) }
    private fun obj(s: String): JsonObject = runCatching { Json.parseToJsonElement(s).jsonObject }.getOrElse { JsonObject(emptyMap()) }
    private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull
    private fun fail(what: String, code: Int, body: String): Nothing {
        val msg = obj(body).let { it.str("message") ?: it.str("error_message") ?: (it["error"] as? JsonObject)?.str("message") ?: it.str("error") } ?: body.take(160)
        throw DeployException(when (code) { 401, 403 -> "$what: the token was rejected ($code). Check it in Build → Deploy settings."; else -> "$what failed ($code): $msg" })
    }

    // ── Netlify: create site once, then zip deploys ─────────────────────────────────────────────────────────────────
    suspend fun netlify(token: String, site: File, name: String, existingId: String?, progress: (String) -> Unit): DeployResult {
        val b = base("netlify", "https://api.netlify.com/api/v1")
        var id = existingId; var url: String? = null
        if (id != null) {
            val (c, body) = call(Request.Builder().url("$b/sites/$id").header("Authorization", "Bearer $token"))
            if (c == 200) url = obj(body).str("ssl_url") ?: obj(body).str("url") else id = null
        }
        if (id == null) {
            progress("Creating the site on Netlify")
            var tryName = name; var n = 0
            while (true) {
                val (c, body) = call(Request.Builder().url("$b/sites").header("Authorization", "Bearer $token")
                    .post(buildJsonObject { put("name", tryName) }.toString().toRequestBody(JSONT)))
                if (c in 200..299) { val o = obj(body); id = o.str("id"); url = o.str("ssl_url") ?: o.str("url"); break }
                if (c == 422 && n < 3) { n++; tryName = name.take(44) + "-" + (1000..9999).random(); continue }
                fail("Netlify site", c, body)
            }
        }
        progress("Uploading ${collect(site).size} files")
        val (c, body) = call(Request.Builder().url("$b/sites/$id/deploys").header("Authorization", "Bearer $token")
            .post(zip(site).toRequestBody("application/zip".toMediaType())))
        if (c !in 200..299) fail("Netlify deploy", c, body)
        val deployId = obj(body).str("id")
        progress("Netlify is publishing")
        repeat(60) {
            val (dc, db) = call(Request.Builder().url("$b/deploys/$deployId").header("Authorization", "Bearer $token"))
            val st = obj(db).str("state")
            if (dc == 200 && st == "ready") return DeployResult(obj(db).str("ssl_url") ?: url ?: "", id, name)
            if (st == "error") throw DeployException("Netlify deploy failed: " + (obj(db).str("error_message") ?: "unknown error"))
            delay(2000)
        }
        return DeployResult(url ?: "", id, name)
    }

    // ── Vercel: one request with every file inline (fine for small sites; Vercel limits inline bodies to ~100 MB) ──
    fun vercelBody(site: File, name: String): JsonObject = buildJsonObject {
        put("name", name); put("target", "production")
        putJsonObject("projectSettings") { put("framework", JsonNull) }
        putJsonArray("files") { collect(site).toSortedMap().forEach { (rel, f) -> addJsonObject { put("file", rel); put("data", Base64.getEncoder().encodeToString(f.readBytes())); put("encoding", "base64") } } }
    }

    suspend fun vercel(token: String, site: File, name: String, progress: (String) -> Unit): DeployResult {
        val b = base("vercel", "https://api.vercel.com")
        progress("Uploading ${collect(site).size} files to Vercel")
        val (c, body) = call(Request.Builder().url("$b/v13/deployments?skipAutoDetectionConfirmation=1").header("Authorization", "Bearer $token")
            .post(vercelBody(site, name).toString().toRequestBody(JSONT)))
        if (c !in 200..299) fail("Vercel deploy", c, body)
        val o = obj(body); val id = o.str("id")
        progress("Vercel is publishing")
        repeat(90) {
            val (dc, db) = call(Request.Builder().url("$b/v13/deployments/$id").header("Authorization", "Bearer $token"))
            val d = obj(db); val st = d.str("readyState") ?: d.str("status")
            if (dc == 200 && st == "READY") {
                val alias = (d["alias"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }?.minByOrNull { it.length }
                return DeployResult("https://" + (alias ?: d.str("url") ?: "$name.vercel.app"), d.str("projectId") ?: id, name)
            }
            if (st == "ERROR" || st == "CANCELED") throw DeployException("Vercel deploy failed: " + (d.str("errorMessage") ?: st))
            delay(2000)
        }
        return DeployResult("https://$name.vercel.app", id, name)
    }

    // ── GitHub Pages: repo + one commit of all files on gh-pages (Git Data API), then enable Pages ───────────────────
    suspend fun github(token: String, site: File, repo: String, progress: (String) -> Unit): DeployResult {
        val b = base("github", "https://api.github.com")
        fun gh(path: String) = Request.Builder().url("$b$path").header("Authorization", "Bearer $token").header("Accept", "application/vnd.github+json").header("X-GitHub-Api-Version", "2022-11-28")
        val (uc, ub) = call(gh("/user")); if (uc != 200) fail("GitHub sign-in", uc, ub)
        val owner = obj(ub).str("login") ?: throw DeployException("GitHub didn't say who this token belongs to")
        val (rc, _) = call(gh("/repos/$owner/$repo"))
        if (rc == 404) {
            progress("Creating repository $owner/$repo")
            val (cc, cb) = call(gh("/user/repos").post(buildJsonObject { put("name", repo); put("auto_init", true); put("description", "Made with Axion") }.toString().toRequestBody(JSONT)))
            if (cc !in 200..299) fail("GitHub repo", cc, cb)
            delay(1500)
        }
        val files = collect(site)
        progress("Uploading ${files.size} files to GitHub")
        val tree = buildJsonArray {
            for ((rel, f) in files.toSortedMap()) {
                val (bc, bb) = call(gh("/repos/$owner/$repo/git/blobs").post(buildJsonObject { put("content", Base64.getEncoder().encodeToString(f.readBytes())); put("encoding", "base64") }.toString().toRequestBody(JSONT)))
                if (bc !in 200..299) fail("GitHub upload", bc, bb)
                addJsonObject { put("path", rel); put("mode", "100644"); put("type", "blob"); put("sha", obj(bb).str("sha")) }
            }
            addJsonObject { put("path", ".nojekyll"); put("mode", "100644"); put("type", "blob"); put("content", "") }
        }
        val (tc, tb) = call(gh("/repos/$owner/$repo/git/trees").post(buildJsonObject { put("tree", tree) }.toString().toRequestBody(JSONT)))
        if (tc !in 200..299) fail("GitHub tree", tc, tb)
        val (hc, hb) = call(gh("/repos/$owner/$repo/git/ref/heads/gh-pages"))
        val parent = if (hc == 200) ((obj(hb)["object"] as? JsonObject)?.str("sha")) else null
        val (cc, cb) = call(gh("/repos/$owner/$repo/git/commits").post(buildJsonObject {
            put("message", "Publish from Axion"); put("tree", obj(tb).str("sha")); putJsonArray("parents") { parent?.let { add(it) } }
        }.toString().toRequestBody(JSONT)))
        if (cc !in 200..299) fail("GitHub commit", cc, cb)
        val sha = obj(cb).str("sha")
        val (fc, fb) = if (parent != null) call(gh("/repos/$owner/$repo/git/refs/heads/gh-pages").patch(buildJsonObject { put("sha", sha); put("force", true) }.toString().toRequestBody(JSONT)))
            else call(gh("/repos/$owner/$repo/git/refs").post(buildJsonObject { put("ref", "refs/heads/gh-pages"); put("sha", sha) }.toString().toRequestBody(JSONT)))
        if (fc !in 200..299) fail("GitHub branch", fc, fb)
        progress("Turning on GitHub Pages")
        val (pc, pb) = call(gh("/repos/$owner/$repo/pages").post(buildJsonObject { putJsonObject("source") { put("branch", "gh-pages"); put("path", "/") } }.toString().toRequestBody(JSONT)))
        if (pc !in 200..299 && pc != 409) fail("GitHub Pages", pc, pb)
        val url = obj(pb).str("html_url") ?: "https://$owner.github.io/$repo/"
        progress("Waiting for GitHub Pages to go live (about a minute)")
        repeat(45) { runCatching { call(Request.Builder().url(url).head()).first }.getOrNull()?.let { if (it == 200) return DeployResult(url, "$owner/$repo", repo) }; delay(4000) }
        return DeployResult(url, "$owner/$repo", repo)
    }

    // ── Cloudflare Pages: account id over HTTPS, upload with wrangler in Linux ──────────────────────────────────────
    fun cloudflareAccount(token: String): String {
        val (c, body) = call(Request.Builder().url(base("cloudflare", "https://api.cloudflare.com/client/v4") + "/accounts").header("Authorization", "Bearer $token"))
        if (c != 200) fail("Cloudflare", c, body)
        return ((obj(body)["result"] as? JsonArray)?.firstOrNull() as? JsonObject)?.str("id") ?: throw DeployException("This Cloudflare token can't see any account.")
    }

    fun wranglerCommand(guestSite: String, name: String) =
        "npx --yes wrangler@3 pages project create '$name' --production-branch main >/dev/null 2>&1; npx --yes wrangler@3 pages deploy '$guestSite' --project-name '$name' --branch main --commit-dirty=true 2>&1"

    companion object {
        fun sha1(f: File): String = MessageDigest.getInstance("SHA-1").digest(f.readBytes()).joinToString("") { "%02x".format(it) }
        /** The pages.dev link wrangler prints. */
        fun wranglerUrl(out: String, name: String): String = Regex("https://[a-z0-9.-]+\\.pages\\.dev").findAll(out).map { it.value }.lastOrNull()?.let { u ->
            if (u.removePrefix("https://").count { it == '.' } > 2) "https://$name.pages.dev" else u } ?: "https://$name.pages.dev"
    }
}
