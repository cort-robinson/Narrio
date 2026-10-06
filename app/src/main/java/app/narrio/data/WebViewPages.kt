package app.narrio.data

import android.content.Context
import android.view.View
import android.webkit.*
import kotlinx.coroutines.*
import kotlin.coroutines.resume

/**
 * An offscreen WebView with the in-app ebook browser's restrictions: no JavaScript bridge, file/content access,
 * permissions, pop-ups, downloads, or non-HTTPS navigation. It shares the browser's cookies, so a passed browser check
 * also speeds up later searches and the visible website.
 */
class WebViewPages(context: Context, private val pollMs: Long = 1_000) : BrowserPages {
    private val context = context.applicationContext

    override suspend fun read(url: String, script: String, timeoutMs: Long): String = withTimeout(timeoutMs) {
        withContext(Dispatchers.Main) {
            val web = WebView(context)
            try {
                web.settings.apply {
                    javaScriptEnabled = true // The website's browser check and its download countdown need JavaScript.
                    domStorageEnabled = true
                    allowFileAccess = false
                    allowContentAccess = false
                    mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                    setSupportMultipleWindows(false)
                    javaScriptCanOpenWindowsAutomatically = false
                    safeBrowsingEnabled = true
                }
                CookieManager.getInstance().setAcceptThirdPartyCookies(web, false)
                web.webChromeClient = object : WebChromeClient() {
                    override fun onPermissionRequest(request: PermissionRequest) { request.deny() }
                }
                web.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, navigation: WebResourceRequest) =
                        runCatching { AddonManifest.secureUrl(navigation.url.toString()) }.isFailure
                }
                // Pages lay out as on a phone screen even though nothing is shown.
                web.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(2400, View.MeasureSpec.EXACTLY))
                web.layout(0, 0, 1080, 2400)
                web.loadUrl(url)
                var value: String? = null
                while (value == null) {
                    delay(pollMs)
                    value = evaluate(web, script)
                }
                value
            } finally {
                web.stopLoading()
                web.destroy()
            }
        }
    }

    private suspend fun evaluate(web: WebView, script: String): String? = suspendCancellableCoroutine { continuation ->
        web.evaluateJavascript(script) { result ->
            // The result is JSON: "null" while the page isn't ready, otherwise the script's string.
            val value = runCatching { NarrioJson.parseToJsonElement(result.orEmpty()) }.getOrNull() as? kotlinx.serialization.json.JsonPrimitive
            continuation.resume(value?.takeIf { it.isString }?.content)
        }
    }
}
