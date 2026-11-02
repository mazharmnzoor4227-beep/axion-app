package com.mazharansari.axion.security

import android.content.Intent
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient

/**
 * v0.5.2: the Build preview shows code the AI wrote, so the WebView gets nothing it doesn't need:
 * no file:// or content:// access (the app's keys, chats and Linux files stay out of reach), no JavaScript bridge,
 * no mixed content, no saved form data; only the local preview server (127.0.0.1 / localhost) opens inside it —
 * any other https link opens in the phone's browser, and file:, content:, intent: and javascript: links are refused.
 */
object PreviewWeb {
    fun allowedInside(url: String?): Boolean {
        val u = runCatching { Uri.parse(url ?: return false) }.getOrNull() ?: return false
        return u.scheme == "http" && (u.host == "127.0.0.1" || u.host == "localhost") || (u.scheme == "about" && url == "about:blank")
    }

    fun opensOutside(url: String?): Boolean = url?.startsWith("https://") == true

    fun harden(w: WebView): WebView {
        w.settings.apply {
            javaScriptEnabled = true          // sites need it; there is no bridge for it to reach
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            @Suppress("DEPRECATION") allowFileAccessFromFileURLs = false
            @Suppress("DEPRECATION") allowUniversalAccessFromFileURLs = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            @Suppress("DEPRECATION") saveFormData = false
            @Suppress("DEPRECATION") savePassword = false
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            setGeolocationEnabled(false)
            loadWithOverviewMode = true; useWideViewPort = true
            if (android.os.Build.VERSION.SDK_INT >= 26) safeBrowsingEnabled = true
        }
        // remove any default interfaces some WebView versions add
        runCatching { w.removeJavascriptInterface("searchBoxJavaBridge_"); w.removeJavascriptInterface("accessibility"); w.removeJavascriptInterface("accessibilityTraversal") }
        w.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = route(view, request.url?.toString())
            @Deprecated("old API") override fun shouldOverrideUrlLoading(view: WebView, url: String?): Boolean = route(view, url)
        }
        return w
    }

    /** true = handled here (blocked or sent to the browser), false = let the preview load it. */
    internal fun route(view: WebView?, url: String?): Boolean {
        if (allowedInside(url)) return false
        if (opensOutside(url) && view != null) runCatching { view.context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        return true
    }
}
