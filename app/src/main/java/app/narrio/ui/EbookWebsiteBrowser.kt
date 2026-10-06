package app.narrio.ui

import android.graphics.Bitmap
import android.net.Uri
import android.webkit.*
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.narrio.data.AddonManifest
import app.narrio.data.EbookDownloadRequest

/** No native JavaScript bridge, file/content access, credentials, or external browser handoff. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EbookWebsiteBrowser(request: EbookWebsiteRequest, state: EbookWebsiteState, close: () -> Unit, download: (EbookDownloadRequest) -> Unit) {
    var browser by remember(request) { mutableStateOf<WebView?>(null) }
    var progress by remember(request) { mutableIntStateOf(0) }
    var host by remember(request) { mutableStateOf(Uri.parse(request.link.url).host.orEmpty()) }
    var pageError by remember(request) { mutableStateOf<String?>(null) }
    var pendingDownload by remember(request) { mutableStateOf<EbookDownloadRequest?>(null) }
    val latestDownload by rememberUpdatedState(download)
    val working by rememberUpdatedState(state.working)
    val goBack: () -> Unit = { if (browser?.canGoBack() == true) browser?.goBack() else close() }
    Dialog(close, DialogProperties(usePlatformDefaultWidth = false, dismissOnBackPress = false)) {
        BackHandler(onBack = goBack)
        Surface(Modifier.fillMaxSize().testTag("ebook-website")) {
            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                TopAppBar(title = { Column {
                    Text(request.link.name, style = MaterialTheme.typography.titleMedium)
                    Text(host, style = MaterialTheme.typography.bodySmall)
                } }, navigationIcon = { IconButton(onClick = goBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back in ebook website") } }, actions = {
                    IconButton({ browser?.reload() }, enabled = !state.working) { Icon(Icons.Rounded.Refresh, "Reload ebook website") }
                    IconButton(close) { Icon(Icons.Rounded.Close, "Close ebook website") }
                })
                Text(if (state.working) state.step else "Choose an EPUB or text download. Narrio will add it to this book.", Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.bodySmall)
                if (state.working) LinearProgressIndicator(Modifier.fillMaxWidth())
                else if (progress in 1..99) LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth())
                (state.error ?: pageError)?.let { Text(it, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                AndroidView(modifier = Modifier.fillMaxWidth().weight(1f), factory = { context ->
                    WebView(context).apply {
                        settings.apply {
                            javaScriptEnabled = true // Anna's Archive and its partners require browser verification.
                            domStorageEnabled = true
                            allowFileAccess = false
                            allowContentAccess = false
                            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                            setSupportMultipleWindows(false)
                            javaScriptCanOpenWindowsAutomatically = false
                            safeBrowsingEnabled = true
                        }
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
                        webChromeClient = object : WebChromeClient() {
                            override fun onProgressChanged(view: WebView, value: Int) { progress = value }
                            override fun onPermissionRequest(request: PermissionRequest) { request.deny() }
                        }
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(view: WebView, navigation: WebResourceRequest): Boolean {
                                if (runCatching { AddonManifest.secureUrl(navigation.url.toString()) }.isFailure) {
                                    pageError = "This website link is not a public HTTPS page. Choose another download link."
                                    return true
                                }
                                return false
                            }
                            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) { host = Uri.parse(url).host.orEmpty(); pageError = null }
                            override fun onReceivedError(view: WebView, resource: WebResourceRequest, error: WebResourceError) {
                                if (resource.isForMainFrame) pageError = "This ebook website couldn't load. Try reloading or refreshing its add-on in Settings."
                            }
                        }
                        setDownloadListener { url, userAgent, disposition, mime, size ->
                            if (!working && pendingDownload == null) {
                                if (size > app.narrio.data.BookTextParser.MAX_FILE_BYTES) pageError = "This ebook exceeds Narrio's 20 MB file limit."
                                else pendingDownload = EbookDownloadRequest(url, URLUtil.guessFileName(url, disposition, mime), mime.orEmpty(), userAgent.orEmpty(),
                                    CookieManager.getInstance().getCookie(url).orEmpty(), this.url.orEmpty(), request.link.name)
                            }
                        }
                        browser = this
                        loadUrl(request.link.url)
                    }
                }, onRelease = { it.stopLoading(); it.setDownloadListener(null); it.destroy(); browser = null })
            }
        }
        pendingDownload?.let { candidate ->
            AlertDialog(onDismissRequest = { pendingDownload = null }, title = { Text("Download ebook?") },
                text = { Text("${candidate.fileName}\n\nNarrio checks TorBox first, then uses the website download if needed. The ebook will be added to ${request.book.title}.") },
                confirmButton = { TextButton({ pendingDownload = null; latestDownload(candidate) }) { Text("Download ebook") } },
                dismissButton = { TextButton({ pendingDownload = null }) { Text("Cancel") } })
        }
    }
}
