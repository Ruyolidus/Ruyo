package com.ruyo.ui

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.http.SslError
import android.webkit.*
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.zIndex
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.ruyo.web.WebAddress
import com.ruyo.web.WebChapter
import com.ruyo.web.WebImageDiscovery

@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun WebBrowserScreen(model: RuyoModel) {
    var address by rememberSaveable { mutableStateOf(model.webUrl) }
    var web by remember { mutableStateOf<WebView?>(null) }
    var progress by remember { mutableIntStateOf(100) }
    var failure by remember { mutableStateOf<String?>(null) }
    var candidates by remember { mutableStateOf<WebChapter?>(null) }
    var scanning by remember { mutableStateOf(false) }
    var generation by remember { mutableIntStateOf(0) }
    var dead by remember { mutableStateOf(false) }
    var canGoBack by remember { mutableStateOf(false) }
    var clearData by remember { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current
    val addressFocus = remember { FocusRequester() }
    LaunchedEffect(model.webAddressExpanded) {
        if (model.webAddressExpanded) { addressFocus.requestFocus(); keyboard?.show() }
        else keyboard?.hide()
    }
    fun navigate() {
        runCatching { WebAddress.normalize(address) }.onSuccess { url ->
            failure = null; address = url; model.webUrl = url
            keyboard?.hide(); model.webAddressExpanded = false
            if (dead) { dead = false; generation++ } else web?.loadUrl(url)
        }.onFailure { failure = it.message ?: "Enter a valid website address." }
    }
    BackHandler(canGoBack && !model.busy) { web?.goBack() }
    BackHandler(model.webAddressExpanded && !model.busy) { model.webAddressExpanded = false }
    Column(Modifier.fillMaxSize().imePadding().testTag("web-browser")) {
        // Keep native web content clipped below an opaque, independently drawn toolbar.
        if (model.webAddressExpanded) Surface(Modifier.fillMaxWidth().zIndex(1f), color = MaterialTheme.colorScheme.surface) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(address, { address = it }, singleLine = true,
                    placeholder = { Text("Paste a chapter link") }, label = { Text("Website address") },
                    leadingIcon = { Icon(AppIcons.Web, null, Modifier.size(20.dp)) },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                        focusedTextColor = MaterialTheme.colorScheme.onSurface,
                        unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                        focusedPlaceholderColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        unfocusedPlaceholderColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                    ),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go), keyboardActions = KeyboardActions(onGo = { navigate() }),
                    enabled = !model.busy, modifier = Modifier.weight(1f).heightIn(min = 60.dp).focusRequester(addressFocus).testTag("web-address"))
                TextButton(onClick = ::navigate, enabled = !model.busy, modifier = Modifier.testTag("web-go")) { Text("Go") }
            }
        }
        if (progress < 100) LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth().height(2.dp))
        failure?.let { error ->
            Surface(color = MaterialTheme.colorScheme.errorContainer) {
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(error, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
                    IconButton(onClick = { failure = null }) { Icon(AppIcons.Close, "Dismiss browser message", Modifier.size(18.dp)) }
                }
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth().clipToBounds()) {
            key(generation) {
                AndroidView(modifier = Modifier.fillMaxSize().clipToBounds(), factory = { context ->
                    WebView(context).apply {
                        settings.apply {
                            javaScriptEnabled = true
                            domStorageEnabled = true
                            allowFileAccess = false
                            allowContentAccess = false
                            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                            javaScriptCanOpenWindowsAutomatically = false
                            setSupportMultipleWindows(true)
                            mediaPlaybackRequiresUserGesture = true
                            setGeolocationEnabled(false)
                            safeBrowsingEnabled = true
                        }
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                                val allowed = runCatching { WebAddress.normalize(request.url.toString()) }.isSuccess
                                if (!allowed && request.isForMainFrame) failure = "This link cannot be opened here. Use an HTTPS chapter link."
                                return !allowed
                            }
                            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                                if (url != null && url != "about:blank") { model.webUrl = url; address = url; failure = null; candidates = null }
                                canGoBack = view.canGoBack()
                            }
                            override fun onPageFinished(view: WebView, url: String?) { canGoBack = view.canGoBack(); progress = 100 }
                            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                                if (request.isForMainFrame) { failure = "This page could not load. Check the address and connection, then retry."; progress = 100 }
                            }
                            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                                if (request.isForMainFrame) failure = "The site returned HTTP ${response.statusCode}. You can reload or try another chapter link."
                            }
                            override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                                handler.cancel(); failure = "This site's secure connection could not be verified."
                            }
                            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                                (view.parent as? android.view.ViewGroup)?.removeView(view)
                                view.destroy(); view.tag = "ruyo-disposed"
                                web = null; dead = true; progress = 100
                                failure = "The browser closed this page. Tap Go to reopen it."
                                return true
                            }
                        }
                        webChromeClient = object : WebChromeClient() {
                            override fun onProgressChanged(view: WebView, value: Int) { progress = value }
                            override fun onPermissionRequest(request: PermissionRequest) { request.deny() }
                        }
                        setDownloadListener { _, _, _, _, _ -> failure = "Use Find images to import chapter pages into Ruyo." }
                        web = this
                        if (model.webUrl.isNotBlank()) runCatching { loadUrl(WebAddress.normalize(model.webUrl)) }
                    }
                }, onRelease = { view ->
                    if (web === view) web = null
                    if (view.tag != "ruyo-disposed") { view.stopLoading(); view.webChromeClient = null; view.destroy() }
                })
            }
            if (model.webUrl.isBlank()) Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                EmptyState(AppIcons.Web, "Read from a website", "Tap the address icon at the top right to open a chapter. Browse here, or use Find images to save pages for offline reading.")
            }
        }
        Surface(Modifier.zIndex(1f), color = MaterialTheme.colorScheme.surface) {
            Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { web?.goBack() }, enabled = canGoBack && !model.busy) { Icon(AppIcons.Back, "Previous webpage") }
                IconButton(onClick = { failure = null; web?.reload() }, enabled = !model.busy && model.webUrl.isNotBlank() && !dead) { Icon(AppIcons.Refresh, "Reload webpage") }
                IconButton(onClick = { clearData = true }, enabled = !model.busy) { Icon(AppIcons.Trash, "Clear browsing data", Modifier.size(20.dp)) }
                Spacer(Modifier.weight(1f))
                Button(onClick = {
                    val view = web ?: return@Button
                    val expected = view.url ?: return@Button
                    scanning = true
                    view.evaluateJavascript(WebImageDiscovery.script) { result ->
                        scanning = false
                        if (model.route == "web" && view === web && view.url == expected) {
                            runCatching { WebImageDiscovery.parse(result, expected) }.onSuccess {
                                if (it.images.isEmpty()) failure = "No usable images found. Scroll to load the chapter, then try again. Canvas-only readers need a dedicated source adapter."
                                else candidates = it
                            }.onFailure { failure = it.message ?: "Could not find images on this page." }
                        }
                    }
                }, enabled = !model.busy && !scanning && model.webUrl.isNotBlank() && !dead, modifier = Modifier.testTag("find-web-images")) { Text(if (scanning) "Finding…" else "Find images") }
            }
        }
    }
    candidates?.let { source ->
        WebImagesSheet(source, dismiss = { candidates = null }) { selected ->
            val cookies = selected.associateWith { CookieManager.getInstance().getCookie(it).orEmpty() }
            val agent = web?.settings?.userAgentString.orEmpty()
            candidates = null
            model.importWeb(source, selected, cookies, agent)
        }
    }
    if (clearData) AlertDialog(onDismissRequest = { clearData = false }, title = { Text("Clear browsing data?") },
        text = { Text("This removes website cookies, browser history, and cached site data. Imported chapters stay in your library.") },
        confirmButton = { TextButton(onClick = {
            clearData = false; web?.stopLoading(); web?.clearCache(true); web?.clearHistory()
            CookieManager.getInstance().removeAllCookies { CookieManager.getInstance().flush() }
            WebStorage.getInstance().deleteAllData(); model.webUrl = ""; address = ""; generation++
        }) { Text("Clear") } }, dismissButton = { TextButton(onClick = { clearData = false }) { Text("Cancel") } })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun WebImagesSheet(source: WebChapter, dismiss: () -> Unit, import: (Set<String>) -> Unit) {
    var selected by remember(source) { mutableStateOf(source.images.filter { it.likelyPage }.map { it.url }.toSet()) }
    var showOther by remember(source) { mutableStateOf(false) }
    val otherCount = source.images.count { it.excludedReason != null }
    val visible = source.images.filter { showOther || it.excludedReason == null }
    ModalBottomSheet(onDismissRequest = dismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().testTag("web-images-review")) {
            Text("Chapter images", Modifier.padding(horizontal = 20.dp), style = MaterialTheme.typography.titleLarge)
            Text("${source.images.size - otherCount} chapter candidates · Check their thumbnails and order after downloading.", Modifier.padding(20.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (otherCount > 0) TextButton(onClick = { showOther = !showOther }, modifier = Modifier.padding(horizontal = 12.dp).testTag("show-other-web-images")) {
                Text(if (showOther) "Hide other website images ($otherCount)" else "Show other website images ($otherCount)")
            }
            Row(Modifier.padding(horizontal = 12.dp)) {
                TextButton(onClick = { selected = selected + visible.map { it.url }.toSet() }) { Text("Select all shown") }
                TextButton(onClick = { selected = emptySet() }) { Text("Clear selection") }
            }
            LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false).heightIn(max = 400.dp)) {
                itemsIndexed(visible, key = { _, image -> image.url }) { index, image ->
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(image.url in selected, { checked -> selected = if (checked) selected + image.url else selected - image.url })
                        Column(Modifier.weight(1f)) {
                            Text("${index + 1}. ${image.name}", maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                            Text(if (image.width > 0 && image.height > 0) "${image.width} × ${image.height}" else "Loads on demand", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            image.excludedReason?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                    }
                }
            }
            Button(onClick = { import(selected) }, enabled = selected.isNotEmpty(), modifier = Modifier.fillMaxWidth().padding(20.dp).height(48.dp).testTag("download-web-images")) { Text("Download ${selected.size} images") }
        }
    }
}
