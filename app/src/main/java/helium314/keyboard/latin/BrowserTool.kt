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
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import helium314.keyboard.latin.utils.DeviceProtectedUtils
import helium314.keyboard.latin.utils.showImeComposeDialog
import org.json.JSONArray
import org.json.JSONObject

private const val DESKTOP_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36"

// JavaScript to inject on page load to fix login security warnings and SPA compatibility.
// Overrides navigator properties that websites use to detect WebView.
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
        window.chrome = { runtime: {}, loadTimes: function(){}, csi: function(){} };
    }
    if (typeof window.chrome.webstore === 'undefined') {
        window.chrome.webstore = {};
    }
    // Fix navigator.connection for sites that check network info
    if (!navigator.connection) {
        Object.defineProperty(navigator, 'connection', {
            get: function() { return { effectiveType: '4g', rtt: 50, downlink: 10, saveData: false }; },
            configurable: true
        });
    }
    // Fix navigator.languages
    if (!navigator.languages || navigator.languages.length === 0) {
        Object.defineProperty(navigator, 'languages', {
            get: function() { return ['en-US', 'en']; },
            configurable: true
        });
    }
    // Fix permissions API query for notifications (Google login checks this)
    if (typeof navigator.permissions !== 'undefined') {
        var origQuery = navigator.permissions.query;
        navigator.permissions.query = function(desc) {
            if (desc && desc.name === 'notifications') {
                var perm = (typeof Notification !== 'undefined' && Notification.permission) ? Notification.permission : 'default';
                return Promise.resolve({ state: perm, onchange: null });
            }
            return origQuery.call(navigator.permissions, desc);
        };
    }
    // Fix missing WebGL context that some SPAs check
    if (!window.WebGLRenderingContext) {
        window.WebGLRenderingContext = function(){};
    }
    // Ensure window.outerWidth/Height are set (some sites check these)
    if (!window.outerWidth) window.outerWidth = window.innerWidth;
    if (!window.outerHeight) window.outerHeight = window.innerHeight;
    // Fix screen.availWidth/Height
    if (!screen.availWidth) screen.availWidth = screen.width;
    if (!screen.availHeight) screen.availHeight = screen.height;
})();
""".trimIndent()

// JavaScript to handle Enter key in search engines and forms.
// Only intercepts Enter for Google search box (input[name="q"]) and generic forms
// with explicit submit buttons, to avoid breaking chat inputs, textareas, etc.
private val ENTER_KEY_JS = """
(function() {
    document.addEventListener('keydown', function(e) {
        if (e.key === 'Enter' && !e.shiftKey && !e.ctrlKey && !e.altKey && !e.metaKey) {
            var el = e.target;
            if (!el) return;
            var tag = el.tagName ? el.tagName.toLowerCase() : '';
            if (tag !== 'input' && tag !== 'textarea') return;
            // Google search: click the search button when in the search box
            var isGoogleSearch = el.name === 'q' || el.getAttribute('aria-label') === 'Search' || el.title === 'Search';
            if (isGoogleSearch) {
                var searchBtn = document.querySelector('button[aria-label*="Search"], button[aria-label*="search"], input[name="btnK"], button.gNO89b');
                if (searchBtn) { e.preventDefault(); searchBtn.click(); return; }
            }
            // For other inputs inside a form with an explicit submit button, submit the form
            if (tag === 'input' && el.type !== 'text' && el.type !== 'search' && el.type !== 'url' && el.type !== 'email') return;
            var form = el.closest ? el.closest('form') : el.form;
            if (form) {
                var submitBtn = form.querySelector('button[type="submit"], input[type="submit"]');
                if (submitBtn) { e.preventDefault(); submitBtn.click(); return; }
            }
        }
    }, true);
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
                }
            }
            webView?.loadUrl(lastUrl)
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
            target = "https://www.google.com/search?q=" + android.net.Uri.encode(target)
        } else if (!target.startsWith("http")) {
            target = "https://$target"
        }
        webView.loadUrl(target)
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
                view?.evaluateJavascript(ENTER_KEY_JS, null)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                isLoading = false
                canGoBack = webView.canGoBack()
                canGoForward = webView.canGoForward()
                pageTitle = view?.title ?: url ?: ""
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
                            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_GO ||
                                actionId == android.view.inputmethod.EditorInfo.IME_NULL) {
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
                            // Navigate to Google Lens web interface in the WebView
                            webView.loadUrl("https://lens.google.com/")
                        },
                        leadingIcon = { Icon(painterResource(R.drawable.ic_lens), contentDescription = null) }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.browser_gemini_analyze)) },
                        onClick = {
                            browserMenuExpanded = false
                            // Navigate to Gemini web interface in the WebView
                            webView.loadUrl("https://gemini.google.com/app")
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
                    webView.loadUrl(url)
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
