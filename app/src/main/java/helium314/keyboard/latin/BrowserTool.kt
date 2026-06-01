// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import helium314.keyboard.latin.utils.showImeComposeDialog

private const val DESKTOP_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36"

object BrowserSession {
    private var webView: WebView? = null
    var lastUrl: String = "https://www.google.com"

    @SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
    fun getWebView(ime: LatinIME): WebView {
        if (webView == null) {
            webView = object : WebView(ime) {
                override fun startActionMode(callback: android.view.ActionMode.Callback?, type: Int): android.view.ActionMode? {
                    // Always return null for action modes to prevent FloatingToolbar BadTokenException crashes.
                    // This disables the selection menu, but selection handles remain usable.
                    // Keyboard toolbar buttons (Copy/Paste) will be used instead.
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
                // Use a modern desktop Chrome user agent to avoid websites blocking the mobile WebView
                settings.userAgentString = DESKTOP_USER_AGENT
                // Allow mixed content (HTTPS pages loading HTTP resources)
                settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                // Enable database storage for web apps
                settings.databaseEnabled = true
                // Allow cookies for login sessions
                val cookieManager = android.webkit.CookieManager.getInstance()
                cookieManager.setAcceptCookie(true)
                cookieManager.setAcceptThirdPartyCookies(this, true)
                webViewClient = object : WebViewClient() {
                    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                        super.onPageStarted(view, url, favicon)
                        url?.let { lastUrl = it }
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

    fun navigate() {
        var target = urlInput.trim()
        if (target.isEmpty()) return
        if (!target.contains(".") && !target.startsWith("http")) {
            target = "https://www.google.com/search?q=$target"
        } else if (!target.startsWith("http")) {
            target = "https://$target"
        }
        // Force desktop user agent on every navigation to prevent mobile reversion
        webView.loadUrl(target, mapOf("User-Agent" to DESKTOP_USER_AGENT))
    }

    LaunchedEffect(webView) {
        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                isLoading = true
                url?.let {
                    urlInput = it
                    BrowserSession.lastUrl = it
                }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                isLoading = false
                canGoBack = webView.canGoBack()
                canGoForward = webView.canGoForward()
            }

            // Force desktop user agent on every link click to prevent mobile reversion
            override fun shouldOverrideUrlLoading(view: WebView?, request: android.webkit.WebResourceRequest?): Boolean {
                if (request != null && view != null && request.isForMainFrame) {
                    val headers = mutableMapOf<String, String>()
                    // Copy existing request headers to preserve cookies/auth
                    request.requestHeaders?.forEach { (key, value) -> headers[key] = value }
                    // Override with desktop user agent
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
            IconButton(onClick = { webView.reload() }) {
                Icon(
                    painter = painterResource(R.drawable.ic_redo_rounded),
                    contentDescription = stringResource(R.string.browser_refresh)
                )
            }

            AndroidView(
                factory = { ctx ->
                    android.widget.EditText(ctx).apply {
                        hint = context.getString(R.string.browser_url_hint)
                        setSingleLine(true)
                        imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_GO
                        background = null // Remove underline
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
                        // When this EditText gets focus, tell the IME about it
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

            IconButton(onClick = { ime.getActiveDialog()?.dismiss() }) {
                Icon(
                    painter = painterResource(R.drawable.ic_close_rounded),
                    contentDescription = stringResource(R.string.browser_minimize)
                )
            }
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
                            // When WebView has focus, clear specific EditText tracking
                            // so LatinIME.getCurrentInputConnection() redirection takes over
                            ime.setDialogEditText(null)
                        }
                    }
                    // Ensure touch requests focus
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
