// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import helium314.keyboard.latin.ai.AiServiceSync
import helium314.keyboard.latin.utils.DeviceProtectedUtils
import helium314.keyboard.latin.utils.showImeComposeDialog
import org.json.JSONArray
import org.json.JSONObject

/** Shared core: finds the latest screenshot, checks permission, returns the content URI or null. */
private fun getLatestScreenshotUri(context: android.content.Context, onPermissionError: () -> Unit): android.net.Uri? {
    val candidate = AiServiceSync.findLatestImageCandidate(context)
    if (candidate == null) {
        val hasPerm = if (android.os.Build.VERSION.SDK_INT >= 34) {
            androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_MEDIA_IMAGES) == android.content.pm.PackageManager.PERMISSION_GRANTED
        } else if (android.os.Build.VERSION.SDK_INT >= 33) {
            androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_MEDIA_IMAGES) == android.content.pm.PackageManager.PERMISSION_GRANTED
        } else {
            androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_EXTERNAL_STORAGE) == android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        if (hasPerm) {
            android.widget.Toast.makeText(context, R.string.browser_no_screenshot_found, android.widget.Toast.LENGTH_SHORT).show()
        } else {
            onPermissionError()
        }
        return null
    }
    return try {
        android.net.Uri.parse(candidate.uriString)
    } catch (_: Exception) {
        android.widget.Toast.makeText(context, R.string.browser_no_screenshot_found, android.widget.Toast.LENGTH_SHORT).show()
        null
    }
}

/** Build a share intent for the image URI with proper permission grants.
 *  @param targetPackage Package to target, or null to leave unrestricted (for chooser). */
private fun buildShareIntent(uri: android.net.Uri, targetPackage: String?, context: android.content.Context): android.content.Intent =
    android.content.Intent(android.content.Intent.ACTION_SEND).apply {
        type = "image/*"
        putExtra(android.content.Intent.EXTRA_STREAM, uri)
        clipData = android.content.ClipData.newUri(context.contentResolver, "Screenshot", uri)
        addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        if (targetPackage != null) setPackage(targetPackage)
    }

/** Try to start an intent. Returns true if successful. */
private fun tryStartActivity(ime: LatinIME, intent: android.content.Intent): Boolean = try {
    ime.startActivity(intent)
    true
} catch (_: Exception) {
    false
}

/** Finds the latest screenshot and launches Google Lens via intent. */
private fun launchLensWithLatestImage(ime: LatinIME) {
    val context = ime.applicationContext
    val uri = getLatestScreenshotUri(context) {
        android.widget.Toast.makeText(context, R.string.browser_permission_needed, android.widget.Toast.LENGTH_SHORT).show()
    } ?: return
    val intent = buildShareIntent(uri, "com.google.ar.lens", context)
    if (tryStartActivity(ime, intent)) return
    // Lens not installed — fall back to share chooser
    val chooser = buildShareIntent(uri, null /* no package filter */, context)
    if (!tryStartActivity(ime, android.content.Intent.createChooser(chooser, context.getString(R.string.browser_lens_scan)))) {
        android.widget.Toast.makeText(context, R.string.browser_no_lens_app, android.widget.Toast.LENGTH_SHORT).show()
    }
}

/** Finds the latest screenshot and launches Google Gemini via intent.
 *  Tries both the main Google app and the standalone Gemini app packages. */
private fun launchGeminiWithLatestImage(ime: LatinIME) {
    val context = ime.applicationContext
    val uri = getLatestScreenshotUri(context) {
        android.widget.Toast.makeText(context, R.string.browser_permission_needed, android.widget.Toast.LENGTH_SHORT).show()
    } ?: return
    val packages = listOf("com.google.android.googlequicksearchbox", "com.google.android.apps.bard")
    for (pkg in packages) {
        if (tryStartActivity(ime, buildShareIntent(uri, pkg, context))) return
    }
    // None of the direct packages worked — fall back to chooser
    val chooser = buildShareIntent(uri, null /* no package filter */, context)
    if (!tryStartActivity(ime, android.content.Intent.createChooser(chooser, context.getString(R.string.browser_gemini_analyze)))) {
        android.widget.Toast.makeText(context, R.string.browser_no_gemini_app, android.widget.Toast.LENGTH_SHORT).show()
    }
}

private const val DESKTOP_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36"

// JavaScript to inject on page load to fix login security warnings
// Overrides navigator properties that websites use to detect WebView
private val ANTI_DETECTION_JS = """
(function() {
    var d = "$DESKTOP_USER_AGENT";
    Object.defineProperty(navigator, 'userAgent', {
        get: function() { return d; },
        configurable: true
    });
    Object.defineProperty(navigator, 'platform', {
        get: function() { return 'Win32'; },
        configurable: true
    });
    Object.defineProperty(navigator, 'vendor', {
        get: function() { return 'Google Inc.'; },
        configurable: true
    });
    if (typeof window.chrome === 'undefined') {
        window.chrome = {};
    }
    if (typeof window.chrome.webstore === 'undefined') {
        window.chrome.webstore = {};
    }
})();
""".trimIndent()

// --- Bookmark persistence ---
private const val PREF_BOOKMARKS = "browser_bookmarks"

data class Bookmark(val title: String, val url: String)

private fun loadBookmarks(ime: LatinIME): List<Bookmark> {
    val prefs = DeviceProtectedUtils.getSharedPreferences(ime)
    val json = prefs.getString(PREF_BOOKMARKS, "[]") ?: "[]"
    return try {
        val arr = JSONArray(json)
        (0 until arr.length()).map { i ->
            val obj = arr.getJSONObject(i)
            Bookmark(obj.getString("title"), obj.getString("url"))
        }
    } catch (_: Exception) {
        emptyList()
    }
}

private fun saveBookmarks(ime: LatinIME, bookmarks: List<Bookmark>) {
    val arr = JSONArray()
    bookmarks.forEach { b ->
        arr.put(JSONObject().apply {
            put("title", b.title)
            put("url", b.url)
        })
    }
    DeviceProtectedUtils.getSharedPreferences(ime)
        .edit()
        .putString(PREF_BOOKMARKS, arr.toString())
        .apply()
}

private fun toggleBookmark(ime: LatinIME, title: String, url: String): Boolean {
    val existing = loadBookmarks(ime).toMutableList()
    val idx = existing.indexOfFirst { it.url == url }
    val added = if (idx >= 0) {
        existing.removeAt(idx)
        false
    } else {
        existing.add(Bookmark(title, url))
        true
    }
    saveBookmarks(ime, existing)
    return added
}

object BrowserSession {
    private var webView: WebView? = null
    var lastUrl: String = "https://www.google.com"

    @SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
    fun getWebView(ime: LatinIME): WebView {
        if (webView == null) {
            webView = object : WebView(ime) {
                override fun startActionMode(callback: android.view.ActionMode.Callback?, type: Int): android.view.ActionMode? {
                    return null
                }
                override fun startActionMode(callback: android.view.ActionMode.Callback?): android.view.ActionMode? {
                    return null
                }
            }.apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.loadWithOverviewMode = true
                settings.useWideViewPort = true
                settings.userAgentString = DESKTOP_USER_AGENT
                settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                settings.databaseEnabled = true
                // Enable pinch-to-zoom and built-in zoom
                settings.builtInZoomControls = true
                settings.displayZoomControls = false
                settings.setSupportZoom(true)
                val cookieManager = android.webkit.CookieManager.getInstance()
                cookieManager.setAcceptCookie(true)
                cookieManager.setAcceptThirdPartyCookies(this, true)
                webViewClient = object : WebViewClient() {
                    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                        super.onPageStarted(view, url, favicon)
                        url?.let { lastUrl = it }
                        view?.evaluateJavascript(ANTI_DETECTION_JS, null)
                    }
                    override fun shouldOverrideUrlLoading(view: WebView?, request: android.webkit.WebResourceRequest?): Boolean {
                        if (request != null && view != null && request.isForMainFrame) {
                            val headers = mutableMapOf<String, String>()
                            request.requestHeaders?.forEach { (key, value) -> headers[key] = value }
                            headers["User-Agent"] = DESKTOP_USER_AGENT
                            view.loadUrl(request.url.toString(), headers)
                            return true
                        }
                        return false
                    }
                }
            }
            webView?.loadUrl(lastUrl, mapOf("User-Agent" to DESKTOP_USER_AGENT))
        }
        return webView!!
    }

    fun cleanup() {
        webView?.destroy()
        webView = null
    }
}

fun showBrowserTool(ime: LatinIME) {
    showImeComposeDialog(
        ime = ime,
        chromeless = true,
        focusable = true,
        onDismiss = {
            ime.setDialogEditText(null)
        },
        content = {
            BrowserContent(ime)
        }
    )
}

@Composable
fun BrowserContent(ime: LatinIME) {
    val context = LocalContext.current
    val webView = remember { BrowserSession.getWebView(ime) }
    var urlInput by remember { mutableStateOf(BrowserSession.lastUrl) }
    var canGoBack by remember { mutableStateOf(webView.canGoBack()) }
    var canGoForward by remember { mutableStateOf(webView.canGoForward()) }
    var isLoading by remember { mutableStateOf(false) }
    var pageTitle by remember { mutableStateOf("") }
    var showBookmarks by remember { mutableStateOf(false) }

    // Bookmark state
    var bookmarks by remember { mutableStateOf(loadBookmarks(ime)) }

    fun navigate() {
        var target = urlInput.trim()
        if (target.isEmpty()) return
        if (!target.contains(".") && !target.startsWith("http")) {
            target = "https://www.google.com/search?q=$target"
        } else if (!target.startsWith("http")) {
            target = "https://$target"
        }
        webView.loadUrl(target, mapOf("User-Agent" to DESKTOP_USER_AGENT))
    }

    // Initialize settings for zoom support
    LaunchedEffect(webView) {
        webView.settings.builtInZoomControls = true
        webView.settings.displayZoomControls = false
        webView.settings.setSupportZoom(true)
    }

    LaunchedEffect(webView) {
        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                isLoading = true
                url?.let {
                    urlInput = it
                    BrowserSession.lastUrl = it
                }
                pageTitle = view?.title ?: url ?: ""
                view?.evaluateJavascript(ANTI_DETECTION_JS, null)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                isLoading = false
                canGoBack = webView.canGoBack()
                canGoForward = webView.canGoForward()
                pageTitle = view?.title ?: url ?: ""
            }

            override fun shouldOverrideUrlLoading(view: WebView?, request: android.webkit.WebResourceRequest?): Boolean {
                if (request != null && view != null && request.isForMainFrame) {
                    val headers = mutableMapOf<String, String>()
                    request.requestHeaders?.forEach { (key, value) -> headers[key] = value }
                    headers["User-Agent"] = DESKTOP_USER_AGENT
                    view.loadUrl(request.url.toString(), headers)
                    return true
                }
                return false
            }
        }
    }

    Column(modifier = Modifier.fillMaxWidth().height(450.dp)) {
        // Navigation Bar
        Row(
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = { if (webView.canGoBack()) webView.goBack() },
                enabled = canGoBack
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_arrow_left_rounded),
                    contentDescription = stringResource(R.string.browser_back),
                    tint = if (canGoBack) MaterialTheme.colorScheme.primary else Color.Gray
                )
            }
            IconButton(
                onClick = { if (webView.canGoForward()) webView.goForward() },
                enabled = canGoForward
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_arrow_left_rounded),
                    contentDescription = stringResource(R.string.browser_forward),
                    modifier = Modifier.graphicsLayer(rotationZ = 180f),
                    tint = if (canGoForward) MaterialTheme.colorScheme.primary else Color.Gray
                )
            }
            AndroidView(
                factory = { ctx ->
                    android.widget.EditText(ctx).apply {
                        hint = context.getString(R.string.browser_url_hint)
                        setSingleLine(true)
                        imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_GO
                        background = null
                        layoutParams = android.view.ViewGroup.LayoutParams(
                            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                            android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                        )
                        setOnEditorActionListener { _, actionId, _ ->
                            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_GO) {
                                urlInput = text.toString()
                                navigate()
                                true
                            } else false
                        }
                        addTextChangedListener(object : android.text.TextWatcher {
                            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                                urlInput = s?.toString() ?: ""
                            }
                            override fun afterTextChanged(s: android.text.Editable?) {}
                        })
                        setOnFocusChangeListener { v, hasFocus ->
                            if (hasFocus) {
                                ime.setDialogEditText(v as android.widget.EditText)
                            }
                        }
                    }
                },
                modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
                update = { editText ->
                    if (editText.text.toString() != urlInput) {
                        editText.setText(urlInput)
                        editText.setSelection(urlInput.length)
                    }
                }
            )

            // Bookmark button - tap to toggle bookmark, long-press to show bookmark list
            Box(
                modifier = Modifier
                    .combinedClickable(
                        onClick = {
                            val added = toggleBookmark(ime, pageTitle, urlInput)
                            bookmarks = loadBookmarks(ime)
                            if (added) {
                                android.widget.Toast.makeText(context, R.string.browser_bookmark_save, android.widget.Toast.LENGTH_SHORT).show()
                            } else {
                                android.widget.Toast.makeText(context, R.string.browser_bookmark_remove, android.widget.Toast.LENGTH_SHORT).show()
                            }
                        },
                        onLongClick = { showBookmarks = !showBookmarks }
                    )
                    .size(48.dp),
                contentAlignment = Alignment.Center
            ) {
                val isBm = bookmarks.any { it.url == urlInput }
                Icon(
                    painter = painterResource(R.drawable.ic_link),
                    contentDescription = stringResource(R.string.browser_bookmark),
                    modifier = Modifier.size(24.dp),
                    tint = if (isBm) Color(0xFFFFC107) else MaterialTheme.colorScheme.primary
                )
            }

            // Menu with refresh + zoom controls
            Box {
                var browserMenuExpanded by remember { mutableStateOf(false) }
                IconButton(onClick = { browserMenuExpanded = true }) {
                    Text(
                        text = "\u22EE",
                        fontSize = 18.sp,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                DropdownMenu(
                    expanded = browserMenuExpanded,
                    onDismissRequest = { browserMenuExpanded = false }
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.browser_refresh)) },
                        onClick = { webView.reload(); browserMenuExpanded = false },
                        leadingIcon = { Icon(painterResource(R.drawable.ic_redo_rounded), contentDescription = null) }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.browser_zoom_in)) },
                        onClick = { webView.zoomIn(); browserMenuExpanded = false },
                        leadingIcon = { Icon(painterResource(R.drawable.ic_plus), contentDescription = null) }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.browser_zoom_out)) },
                        onClick = { webView.zoomOut(); browserMenuExpanded = false },
                        leadingIcon = { Icon(painterResource(R.drawable.ic_minus), contentDescription = null) }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.browser_lens_scan)) },
                        onClick = {
                            browserMenuExpanded = false
                            launchLensWithLatestImage(ime)
                        },
                        leadingIcon = { Icon(painterResource(R.drawable.ic_lens), contentDescription = null) }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.browser_gemini_analyze)) },
                        onClick = {
                            browserMenuExpanded = false
                            launchGeminiWithLatestImage(ime)
                        },
                        leadingIcon = { Icon(painterResource(R.drawable.ic_gemini), contentDescription = null) }
                    )
                }
            }

            IconButton(onClick = { ime.getActiveDialog()?.dismiss() }) {
                Icon(
                    painter = painterResource(R.drawable.ic_close_rounded),
                    contentDescription = stringResource(R.string.browser_minimize)
                )
            }
        }

        // Bookmark list panel (shown when toggled)
        if (showBookmarks) {
            BookmarkListPanel(
                bookmarks = bookmarks,
                currentUrl = urlInput,
                onLoadUrl = { url ->
                    webView.loadUrl(url, mapOf("User-Agent" to DESKTOP_USER_AGENT))
                    showBookmarks = false
                },
                onDelete = { url ->
                    val list = loadBookmarks(ime).toMutableList()
                    list.removeAll { it.url == url }
                    saveBookmarks(ime, list)
                    bookmarks = list
                }
            )
        }

        // WebView area
        AndroidView(
            factory = {
                (webView.parent as? FrameLayout)?.removeView(webView)
                webView.apply {
                    isFocusable = true
                    isFocusableInTouchMode = true
                    setOnFocusChangeListener { _, hasFocus ->
                        if (hasFocus) {
                            ime.setDialogEditText(null)
                        }
                    }
                    setOnTouchListener { v, _ ->
                        v.requestFocus()
                        false
                    }
                }
                webView
            },
            modifier = Modifier.weight(1f).fillMaxWidth()
        )
    }
}

@Composable
private fun BookmarkListPanel(
    bookmarks: List<Bookmark>,
    currentUrl: String,
    onLoadUrl: (String) -> Unit,
    onDelete: (String) -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 200.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 4.dp
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            Text(
                text = stringResource(R.string.browser_bookmarks),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(bottom = 4.dp)
            )
            if (bookmarks.isEmpty()) {
                Text(
                    text = stringResource(R.string.browser_no_bookmarks),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray,
                    modifier = Modifier.padding(8.dp)
                )
            } else {
                LazyColumn {
                    itemsIndexed(bookmarks) { _, bookmark ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onLoadUrl(bookmark.url) }
                                .padding(vertical = 6.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            val isActive = bookmark.url == currentUrl
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = bookmark.title.ifEmpty { bookmark.url },
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    color = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = bookmark.url,
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    color = Color.Gray
                                )
                            }
                            IconButton(
                                onClick = { onDelete(bookmark.url) },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_close_rounded),
                                    contentDescription = stringResource(R.string.delete),
                                    modifier = Modifier.size(16.dp),
                                    tint = Color.Gray
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
