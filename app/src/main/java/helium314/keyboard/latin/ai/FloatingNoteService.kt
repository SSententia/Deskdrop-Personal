// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.ai

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.ActionMode
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.Toast
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.NotificationCompat
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import helium314.keyboard.latin.LatinIME
import helium314.keyboard.latin.R
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext

class FloatingNoteService : Service() {

    companion object {
        private const val CHANNEL_ID = "deskdrop_floating_note"
        private const val NOTIFICATION_ID = 9002
        private const val TAG = "FloatingNoteService"

        // Callback for image attachment from NoteImagePickerActivity
        var imageAttachCallback: ((String) -> Unit)? = null
    }

    private var wm: WindowManager? = null
    private var composeView: android.view.View? = null
    private var lifecycleOwner: ServiceLifecycleOwner? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private var camouflageHandler = Handler(Looper.getMainLooper())
    private var camouflageRunnable: Runnable? = null

    // Periodic z-order heartbeat: re-adds the window to stay on top of competing overlays.
    // Paused while the user is editing to avoid disrupting the IME connection.
    private var heartbeatHandler = Handler(Looper.getMainLooper())
    private var heartbeatRunnable: Runnable? = null
    private var currentParams: WindowManager.LayoutParams? = null
    private var currentWrapper: android.view.View? = null
    @Volatile
    private var isEditingActive = false
    @Volatile
    private var isDragging = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        createNotificationChannel()

        val currentHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler(FloatingNoteExceptionHandler(currentHandler))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Handle image attachment from NoteImagePickerActivity
        if (intent?.action == "ATTACH_IMAGE") {
            val imageUri = intent.getStringExtra("imageUri")
            if (imageUri != null) {
                mainHandler.post { imageAttachCallback?.invoke(imageUri) }
            }
            return START_NOT_STICKY
        }

        val text = intent?.getStringExtra("text") ?: ""
        val x = intent?.getIntExtra("x", 100) ?: 100
        val y = intent?.getIntExtra("y", 200) ?: 200
        val width = intent?.getIntExtra("width", 250) ?: 250
        val height = intent?.getIntExtra("height", 200) ?: 200
        val delayMs = intent?.getIntExtra("delayMs", 0) ?: 0
        val camouflageDurationMs = intent?.getIntExtra("camouflageDurationMs", 0) ?: 0

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            Log.w(TAG, "SYSTEM_ALERT_WINDOW permission not granted - cannot show floating note")
            try {
                Toast.makeText(this, "Please grant 'Display over other apps' permission for floating notes", Toast.LENGTH_LONG).show()
                val permIntent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:${packageName}")
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(permIntent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to request overlay permission", e)
            }
            stopSelf()
            return START_NOT_STICKY
        }

        showNotification()
        if (delayMs > 0) {
            mainHandler.postDelayed({
                showFloatingNote(text, x, y, width, height, camouflageDurationMs)
            }, delayMs.toLong())
        } else {
            showFloatingNote(text, x, y, width, height, camouflageDurationMs)
        }

        return START_NOT_STICKY
    }

    private fun showNotification() {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_shortcut_chat)
            .setContentTitle("Floating Note Active")
            .setContentText("Tap to edit or close the note")
            .setOngoing(true)
            .build()

        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start FGS with specialUse, trying fallback", e)
            try {
                startForeground(NOTIFICATION_ID, notification)
            } catch (_: Exception) {}
        }
    }

    private fun showFloatingNote(
        initialText: String,
        initialX: Int,
        initialY: Int,
        widthDp: Int,
        heightDp: Int,
        camouflageDurationMs: Int
    ) {
        removeFloatingNote()

        val displayMetrics = resources.displayMetrics
        val density = displayMetrics.density
        val widthPx = (widthDp * density).toInt()
        val heightPx = (heightDp * density).toInt()

        val owner = ServiceLifecycleOwner()
        owner.handleLifecycleEvent(androidx.lifecycle.Lifecycle.Event.ON_CREATE)
        owner.handleLifecycleEvent(androidx.lifecycle.Lifecycle.Event.ON_START)
        owner.handleLifecycleEvent(androidx.lifecycle.Lifecycle.Event.ON_RESUME)
        lifecycleOwner = owner

        // System overlay with stable window flags: NOT_FOCUSABLE so overlay never steals
        // window focus, ALT_FOCUSABLE_IM so IME can route text input to EditText views within.
        val params = WindowManager.LayoutParams(
            widthPx,
            heightPx,
            if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
            WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = initialX
            y = initialY
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }

        // Wrapper FrameLayout that prevents action mode (to avoid BadTokenException).
        // Since ComposeView is final, we wrap it in a FrameLayout that overrides startActionMode.
        val wrapper = object : android.widget.FrameLayout(this) {
            override fun startActionMode(callback: ActionMode.Callback?): ActionMode? = null
            override fun startActionMode(callback: ActionMode.Callback?, type: Int): ActionMode? = null
        }
        wrapper.setViewTreeLifecycleOwner(owner)
        wrapper.setViewTreeSavedStateRegistryOwner(owner)

        val view = ComposeView(this).apply {
            setContent {
                // Use MutableState directly so we can update it from the AndroidView factory
                val isEditingState = remember { mutableStateOf(false) }
                var isEditing by isEditingState
                var isCamouflaged by remember { mutableStateOf(false) }
                var showCloseConfirmation by remember { mutableStateOf(false) }
                var editTextRef by remember { mutableStateOf<EditText?>(null) }
                var isBrowserMode by remember { mutableStateOf(false) }
                var browserUrlInput by remember { mutableStateOf("https://www.google.com") }
                var browserWebViewRef by remember { mutableStateOf<android.webkit.WebView?>(null) }
                val desktopUA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36"

                // Note persistence state
                var showMenu by remember { mutableStateOf(false) }
                var currentNoteId by remember { mutableStateOf<String?>(null) }
                var savedNotes by remember { mutableStateOf(NoteStorage.loadAllNotes(this@FloatingNoteService)) }
                var showSavedNotes by remember { mutableStateOf(false) }

                // Image attachment state
                var attachedImages by remember { mutableStateOf(listOf<Triple<String, String, String>>()) } // uri, label, id
                var showImageLabelDialog by remember { mutableStateOf(false) }
                var pendingImageUri by remember { mutableStateOf<String?>(null) }
                var imageLabelText by remember { mutableStateOf("") }

                // Image viewer state
                var showImageViewer by remember { mutableStateOf(false) }
                var viewerImageUri by remember { mutableStateOf<String?>(null) }
                var viewerImageLabel by remember { mutableStateOf("") }

                // Floating keyboard fallback state
                var showFallbackKeyboard by remember { mutableStateOf(false) }

                // Register image attachment callback from NoteImagePickerActivity
                DisposableEffect(Unit) {
                    imageAttachCallback = { uri ->
                        pendingImageUri = uri
                        showImageLabelDialog = true
                        imageLabelText = ""
                    }
                    onDispose { imageAttachCallback = null }
                }

                // Camouflage: only clear when user starts editing.
                // Timer is started by the ✓ confirmation button click only.
                LaunchedEffect(isEditing) {
                    if (isEditing && isCamouflaged) {
                        isCamouflaged = false
                        camouflageRunnable?.let { camouflageHandler.removeCallbacks(it) }
                    }
                }

                // Helper to start camouflage timer (called from ✓ button)
                val startCamouflageTimer: () -> Unit = {
                    if (camouflageDurationMs > 0) {
                        camouflageRunnable?.let { camouflageHandler.removeCallbacks(it) }
                        val run = Runnable { isCamouflaged = true }
                        camouflageRunnable = run
                        camouflageHandler.postDelayed(run, camouflageDurationMs.toLong())
                    }
                }

                val alphaVal by animateFloatAsState(
                    targetValue = if (isCamouflaged) 0.05f else 1.0f,
                    animationSpec = tween(durationMillis = 300),
                    label = "alpha"
                )

                // Dragging handler
                val onDrag: (Float, Float) -> Unit = { dx, dy ->
                    params.x += dx.toInt()
                    params.y += dy.toInt()
                    try {
                        wm?.updateViewLayout(wrapper, params)
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to update view position on drag", e)
                    }
                }

                Card(
                    modifier = Modifier
                        .fillMaxSize()
                        .alpha(alphaVal)
                        .shadow(8.dp, RoundedCornerShape(16.dp))
                        .clickable {
                            if (isCamouflaged) {
                                isCamouflaged = false
                                camouflageRunnable?.let { camouflageHandler.removeCallbacks(it) }
                            }
                        },
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = Color(0xE61E1E1E)
                    ),
                    border = BorderStroke(1.dp, Color(0x33FFFFFF))
                ) {
                    Box(Modifier.fillMaxSize()) {
                        // Main content Column
                        Column(modifier = Modifier.fillMaxSize()) {
                            // Header Bar
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(Color(0xFF2A2A2A))
                                    .pointerInput(Unit) {
                                        detectDragGestures(
                                            onDragStart = { isDragging = true },
                                            onDragEnd = { isDragging = false },
                                            onDragCancel = { isDragging = false },
                                            onDrag = { change, dragAmount ->
                                                change.consume()
                                                onDrag(dragAmount.x, dragAmount.y)
                                            }
                                        )
                                    }
                                    .padding(horizontal = 8.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // Drag Handle
                                Box(
                                    modifier = Modifier
                                        .width(30.dp)
                                        .height(4.dp)
                                        .background(Color(0x66FFFFFF), RoundedCornerShape(2.dp))
                                )

                                Spacer(modifier = Modifier.weight(1f))

                                // Note menu button (visible when not in browser mode)
                                if (!isBrowserMode) {
                                    Box {
                                        IconButton(
                                            onClick = { showMenu = true },
                                            modifier = Modifier.size(24.dp)
                                        ) {
                                            Text(
                                                text = "\u22EE",
                                                color = Color.White,
                                                fontSize = 14.sp
                                            )
                                        }
                                        DropdownMenu(
                                            expanded = showMenu,
                                            onDismissRequest = { showMenu = false }
                                        ) {
                                            DropdownMenuItem(
                                                text = { Text("Save Note") },
                                                onClick = {
                                                    showMenu = false
                                                    val noteText = editTextRef?.text?.toString() ?: ""
                                                    val title = noteText.take(50).lines().firstOrNull()?.trim() ?: "Untitled"
                                                    val note = SavedNote(
                                                        id = currentNoteId ?: java.util.UUID.randomUUID().toString(),
                                                        title = title.ifEmpty { "Untitled" },
                                                        content = noteText,
                                                        images = attachedImages.map { NoteImage(uri = it.first, label = it.second) }
                                                    )
                                                    NoteStorage.saveNote(this@FloatingNoteService, note)
                                                    currentNoteId = note.id
                                                    savedNotes = NoteStorage.loadAllNotes(this@FloatingNoteService)
                                                    Toast.makeText(this@FloatingNoteService, "Note saved", Toast.LENGTH_SHORT).show()
                                                }
                                            )
                                            DropdownMenuItem(
                                                text = { Text("New Note") },
                                                onClick = {
                                                    showMenu = false
                                                    // Auto-save current if it has content
                                                    val noteText = editTextRef?.text?.toString() ?: ""
                                                    if (noteText.isNotBlank() || attachedImages.isNotEmpty()) {
                                                        val title = noteText.take(50).lines().firstOrNull()?.trim() ?: "Untitled"
                                                        val note = SavedNote(
                                                            id = currentNoteId ?: java.util.UUID.randomUUID().toString(),
                                                            title = title.ifEmpty { "Untitled" },
                                                            content = noteText,
                                                            images = attachedImages.map { NoteImage(uri = it.first, label = it.second) }
                                                        )
                                                        NoteStorage.saveNote(this@FloatingNoteService, note)
                                                    }
                                                    currentNoteId = null
                                                    editTextRef?.setText("")
                                                    attachedImages = emptyList()
                                                    savedNotes = NoteStorage.loadAllNotes(this@FloatingNoteService)
                                                }
                                            )
                                            DropdownMenuItem(
                                                text = { Text("Load Notes") },
                                                onClick = {
                                                    showMenu = false
                                                    savedNotes = NoteStorage.loadAllNotes(this@FloatingNoteService)
                                                    showSavedNotes = true
                                                }
                                            )
                                            DropdownMenuItem(
                                                text = { Text("Attach Image") },
                                                onClick = {
                                                    showMenu = false
                                                    val intent = Intent(this@FloatingNoteService, NoteImagePickerActivity::class.java)
                                                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                                    startActivity(intent)
                                                }
                                            )
                                        }
                                    }
                                }

                                // Floating keyboard toggle (when editing)
                                if (isEditing) {
                                    Box(
                                        modifier = Modifier
                                            .size(24.dp)
                                            .clickable { showFallbackKeyboard = !showFallbackKeyboard }
                                            .background(
                                                if (showFallbackKeyboard) Color(0xFFFF9800) else Color(0xFF666666),
                                                CircleShape
                                            ),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = "\u2328",
                                            color = Color.White,
                                            fontSize = 11.sp
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(8.dp))
                                }

                                // Done/checkmark button — shown when the EditText has focus
                                                if (isEditing) {
                                                    Box(
                                                        modifier = Modifier
                                                            .size(24.dp)
                                                            .clickable {
                                                                // 1. Clear internal focus
                                                                editTextRef?.clearFocus()
                                                                // 2. Disconnect IME bridge and hide keyboard
                                                                val ime = LatinIME.getInstance()
                                                                ime?.setDialogEditText(null)
                                                                ime?.requestHideSelf(0)
                                                                isEditingState.value = false
                                                                isEditingActive = false
                                                                // 3. Auto-save the note
                                                                val noteText = editTextRef?.text?.toString() ?: ""
                                                                if (noteText.isNotBlank()) {
                                                                    val title = noteText.take(50).lines().firstOrNull()?.trim() ?: "Untitled"
                                                                    val note = SavedNote(
                                                                        id = currentNoteId ?: java.util.UUID.randomUUID().toString(),
                                                                        title = title.ifEmpty { "Untitled" },
                                                                        content = noteText,
                                                                        images = attachedImages.map { NoteImage(uri = it.first, label = it.second) }
                                                                    )
                                                                    NoteStorage.saveNote(this@FloatingNoteService, note)
                                                                    currentNoteId = note.id
                                                                    savedNotes = NoteStorage.loadAllNotes(this@FloatingNoteService)
                                                                }
                                                                // 4. Start camouflage timer (only on explicit confirmation)
                                                                startCamouflageTimer()
                                                            }
                                            .background(Color(0xFF4CAF50), CircleShape),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = "✓",
                                            color = Color.White,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(8.dp))
                                }

                                // Back-to-note button — shown when in browser mode
                                if (isBrowserMode) {
                                    Box(
                                        modifier = Modifier
                                            .size(24.dp)
                                            .clickable {
                                                isBrowserMode = false
                                                browserUrlInput = "https://www.google.com"
                                            }
                                            .background(Color(0xFFFF9800), CircleShape),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = "←",
                                            color = Color.White,
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(8.dp))
                                }

                                // Browser Button - toggle embedded browser
                                Box(
                                    modifier = Modifier
                                        .size(24.dp)
                                        .clickable {
                                            isBrowserMode = !isBrowserMode
                                            if (isBrowserMode) {
                                                isEditingState.value = false
                                                editTextRef?.clearFocus()
                                            }
                                        }
                                        .background(if (isBrowserMode) Color(0xFF4CAF50) else Color(0xFF2196F3), CircleShape),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "🌐",
                                        color = Color.White,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                                Spacer(modifier = Modifier.width(8.dp))

                                // Close Button
                                Box(
                                    modifier = Modifier
                                        .size(24.dp)
                                        .clickable { showCloseConfirmation = true }
                                        .background(Color(0xFFE53935), CircleShape),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "✕",
                                        color = Color.White,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }

                            // Attached image chips (shown in note mode only)
                            if (!isBrowserMode && attachedImages.isNotEmpty()) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 4.dp)
                                ) {
                                    attachedImages.forEach { (uri, label, id) ->
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clickable {
                                                    viewerImageUri = uri
                                                    viewerImageLabel = label
                                                    showImageViewer = true
                                                }
                                                .padding(vertical = 2.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = "\uD83D\uDDBC $label",
                                                color = Color(0xFF64B5F6),
                                                fontSize = 13.sp,
                                                fontWeight = FontWeight.Medium,
                                                modifier = Modifier.weight(1f)
                                            )
                                            Box(
                                                modifier = Modifier
                                                    .size(18.dp)
                                                    .clickable {
                                                        attachedImages = attachedImages.filter { it.third != id }
                                                    }
                                                    .background(Color(0xFF555555), CircleShape),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Text("\u2715", color = Color.White, fontSize = 8.sp)
                                            }
                                        }
                                    }
                                }
                            }

                            // Content Area - switches between note and browser
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f)
                            ) {
                                if (isBrowserMode) {
                                    // Browser mode: URL bar + WebView
                                    Column(modifier = Modifier.fillMaxSize().padding(4.dp)) {
                                        // URL Bar
                                        Row(
                                            modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            AndroidView(
                                                factory = { ctx ->
                                                    EditText(ctx).apply {
                                                        setText(browserUrlInput)
                                                        hint = "Enter URL or search..."
                                                        setHintTextColor(android.graphics.Color.argb(100, 255, 255, 255))
                                                        setSingleLine(true)
                                                        background = null
                                                        setTextColor(android.graphics.Color.WHITE)
                                                        textSize = 12f
                                                        layoutParams = ViewGroup.LayoutParams(
                                                            ViewGroup.LayoutParams.MATCH_PARENT,
                                                            ViewGroup.LayoutParams.WRAP_CONTENT
                                                        )
                                                        setOnEditorActionListener { _, actionId, _ ->
                                                            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_GO) {
                                                                browserUrlInput = text.toString()
                                                                var target = browserUrlInput.trim()
                                                                if (target.isNotEmpty()) {
                                                                    if (!target.contains(".") && !target.startsWith("http")) {
                                                                        target = "https://www.google.com/search?q=" + target
                                                                    } else if (!target.startsWith("http")) {
                                                                        target = "https://" + target
                                                                    }
                                                                    browserWebViewRef?.loadUrl(target)
                                                                }
                                                                true
                                                            } else false
                                                        }
                                                        setOnFocusChangeListener { v, hasFocus ->
                                                            if (hasFocus) {
                                                                val ime = LatinIME.getInstance()
                                                                ime?.setDialogEditText(v as EditText)
                                                            }
                                                        }
                                                    }
                                                },
                                                modifier = Modifier.weight(1f),
                                                update = { editText ->
                                                    if (editText.text.toString() != browserUrlInput) {
                                                        editText.setText(browserUrlInput)
                                                        editText.setSelection(browserUrlInput.length)
                                                    }
                                                }
                                            )
                                        }
                                        // WebView
                                        AndroidView(
                                            factory = { ctx ->
                                                // Reuse existing WebView to avoid memory leaks on toggle
                                                browserWebViewRef?.destroy()
                                                android.webkit.WebView(ctx).apply {
                                                    browserWebViewRef = this
                                                    settings.javaScriptEnabled = true
                                                    settings.domStorageEnabled = true
                                                    settings.loadWithOverviewMode = true
                                                    settings.useWideViewPort = true
                                                    settings.userAgentString = desktopUA
                                                    settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                                                    val cm = android.webkit.CookieManager.getInstance()
                                                    cm.setAcceptCookie(true)
                                                    cm.setAcceptThirdPartyCookies(this, true)
                                                    webViewClient = object : android.webkit.WebViewClient() {
                                                        override fun onPageStarted(view: android.webkit.WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                                                            super.onPageStarted(view, url, favicon)
                                                            url?.let { browserUrlInput = it }
                                                            // Inject anti-detection JS to fix login security warnings
                                                            val js = "(function() { var d='" + desktopUA.replace("'", "\'") + "'; Object.defineProperty(navigator,'userAgent',{get:function(){return d;},configurable:true}); Object.defineProperty(navigator,'platform',{get:function(){return 'Win32';},configurable:true}); Object.defineProperty(navigator,'vendor',{get:function(){return 'Google Inc.';},configurable:true}); if(typeof chrome==='undefined'){window.chrome={runtime:{},loadTimes:function(){},csi:function(){}};} if(typeof chrome.webstore==='undefined'){chrome.webstore={};} if(!navigator.connection){Object.defineProperty(navigator,'connection',{get:function(){return{effectiveType:'4g',rtt:50,downlink:10,saveData:false};},configurable:true});} if(!navigator.languages||navigator.languages.length===0){Object.defineProperty(navigator,'languages',{get:function(){return['en-US','en'];},configurable:true});} if(!window.outerWidth)window.outerWidth=window.innerWidth; if(!window.outerHeight)window.outerHeight=window.innerHeight; })();"
                                                            view?.evaluateJavascript(js, null)
                                                            // Inject Enter key handler for Google search box and forms with submit buttons
                                                            val enterJs = "(function(){document.addEventListener('keydown',function(e){if(e.key==='Enter'&&!e.shiftKey&&!e.ctrlKey&&!e.altKey&&!e.metaKey){var el=e.target;if(!el)return;var tag=el.tagName?el.tagName.toLowerCase():'';if(tag!=='input'&&tag!=='textarea')return;var isGoogle=el.name==='q'||el.getAttribute('aria-label')==='Search'||el.title==='Search';if(isGoogle){var sb=document.querySelector('button[aria-label*=\"Search\"],button[aria-label*=\"search\"],input[name=\"btnK\"],button.gNO89b');if(sb){e.preventDefault();sb.click();return;}}if(tag==='input'&&el.type!=='text'&&el.type!=='search'&&el.type!=='url'&&el.type!=='email')return;var form=el.closest?el.closest('form'):el.form;if(form){var btn=form.querySelector('button[type=\"submit\"],input[type=\"submit\"]');if(btn){e.preventDefault();btn.click();}}}},true);})();"
                                                            view?.evaluateJavascript(enterJs, null)
                                                        }
                                                    }
                                                    loadUrl(browserUrlInput)
                                                }
                                            },
                                            modifier = Modifier.weight(1f).fillMaxWidth()
                                        )
                                    }
                                } else {
                                    // Note mode: EditText
                                    AndroidView(
                                        factory = { ctx ->
                                            EditText(ctx).apply {
                                                editTextRef = this
                                                setText(initialText)
                                                setHint("Tap to note...")
                                                setHintTextColor(android.graphics.Color.argb(136, 255, 255, 255))
                                                background = null
                                                setTextColor(android.graphics.Color.WHITE)
                                                textSize = 16f
                                                includeFontPadding = false
                                                layoutParams = ViewGroup.LayoutParams(
                                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                                    ViewGroup.LayoutParams.MATCH_PARENT
                                                )
                                                setLineSpacing(0f, 1.15f)

                                                setOnFocusChangeListener { v, hasFocus ->
                                                    isEditingState.value = hasFocus
                                                    isEditingActive = hasFocus
                                                    val ime = LatinIME.getInstance()
                                                    if (hasFocus) {
                                                        ime?.setDialogEditText(this)
                                                        // Post-delay to let focus settle before showing keyboard,
                                                        // avoiding race conditions with the IME connection.
                                                        mainHandler.postDelayed({
                                                            ime?.startShowingInputView(true)
                                                        }, 100L)
                                                    } else {
                                                        ime?.setDialogEditText(null)
                                                        ime?.requestHideSelf(0)
                                                    }
                                                }

                                                setOnTouchListener { view, event ->
                                                    if (event.action == android.view.MotionEvent.ACTION_UP) {
                                                        val hadFocus = (view as EditText).hasFocus()
                                                        view.requestFocus()
                                                        val ime = LatinIME.getInstance()
                                                        ime?.setDialogEditText(view as EditText)
                                                        // If already focused (e.g., keyboard dismissed via back),
                                                        // the focus listener won't fire, so show keyboard here.
                                                        // Otherwise, OnFocusChangeListener handles it with a delay.
                                                        if (hadFocus) {
                                                            mainHandler.postDelayed({
                                                                ime?.startShowingInputView(true)
                                                            }, 100L)
                                                        }
                                                        isEditingState.value = true
                                                    }
                                                    false
                                                }
                                            }
                                        },
                                        modifier = Modifier.fillMaxSize().padding(12.dp)
                                    )
                                }
                            }
                        } // Column closes

                        // Close Confirmation Dialog overlay (sibling of Column, drawn on top)
                        if (showCloseConfirmation) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(Color(0x80000000))
                                    .clickable { /* block clicks through to note */ },
                                contentAlignment = Alignment.Center
                            ) {
                                Card(
                                    modifier = Modifier.widthIn(min = 200.dp, max = 260.dp),
                                    shape = RoundedCornerShape(16.dp),
                                    colors = CardDefaults.cardColors(
                                        containerColor = Color(0xFF2A2A2A)
                                    ),
                                    border = BorderStroke(1.dp, Color(0x33FFFFFF))
                                ) {
                                    Column(
                                        modifier = Modifier.padding(20.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally
                                    ) {
                                        Text(
                                            text = "Delete this note?",
                                            color = Color.White,
                                            fontSize = 16.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Spacer(modifier = Modifier.height(8.dp))
                                        Text(
                                            text = "The content will be lost.",
                                            color = Color(0xAAFFFFFF),
                                            fontSize = 13.sp
                                        )
                                        Spacer(modifier = Modifier.height(20.dp))
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceEvenly
                                        ) {
                                            // Cancel button
                                            Box(
                                                modifier = Modifier
                                                    .clickable { showCloseConfirmation = false }
                                                    .background(Color(0xFF555555), RoundedCornerShape(8.dp))
                                                    .padding(horizontal = 24.dp, vertical = 10.dp),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Text(
                                                    text = "Cancel",
                                                    color = Color.White,
                                                    fontSize = 14.sp
                                                )
                                            }
                                            // Delete button
                                            Box(
                                                modifier = Modifier
                                                    .clickable {
                                                        showCloseConfirmation = false
                                                        val ime = LatinIME.getInstance()
                                                        ime?.setDialogEditText(null)
                                                        ime?.requestHideSelf(0)
                                                        removeFloatingNote()
                                                        stopSelf()
                                                    }
                                                    .background(Color(0xFFE53935), RoundedCornerShape(8.dp))
                                                    .padding(horizontal = 24.dp, vertical = 10.dp),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Text(
                                                    text = "Delete",
                                                    color = Color.White,
                                                    fontSize = 14.sp,
                                                    fontWeight = FontWeight.Bold
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        // Saved Notes List overlay
                        if (showSavedNotes) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(Color(0x80000000))
                                    .clickable { showSavedNotes = false },
                                contentAlignment = Alignment.Center
                            ) {
                                Card(
                                    modifier = Modifier.widthIn(min = 240.dp, max = 300.dp).heightIn(max = 400.dp),
                                    shape = RoundedCornerShape(16.dp),
                                    colors = CardDefaults.cardColors(containerColor = Color(0xFF2A2A2A)),
                                    border = BorderStroke(1.dp, Color(0x33FFFFFF))
                                ) {
                                    Column(modifier = Modifier.padding(16.dp)) {
                                        Text(
                                            text = "Saved Notes",
                                            color = Color.White,
                                            fontSize = 16.sp,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier.padding(bottom = 8.dp)
                                        )
                                        if (savedNotes.isEmpty()) {
                                            Text(
                                                text = "No saved notes yet.",
                                                color = Color(0xAAFFFFFF),
                                                fontSize = 13.sp
                                            )
                                        } else {
                                            Column(modifier = Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                                                savedNotes.forEach { note ->
                                                    Row(
                                                        modifier = Modifier
                                                            .fillMaxWidth()
                                                            .clickable {
                                                                currentNoteId = note.id
                                                                editTextRef?.setText(note.content)
                                                                attachedImages = note.images.map {
                                                                    Triple(it.uri, it.label, java.util.UUID.randomUUID().toString())
                                                                }
                                                                showSavedNotes = false
                                                            }
                                                            .padding(vertical = 6.dp),
                                                        verticalAlignment = Alignment.CenterVertically
                                                    ) {
                                                        Column(modifier = Modifier.weight(1f)) {
                                                            Text(
                                                                text = note.title.ifEmpty { "Untitled" },
                                                                color = Color.White,
                                                                fontSize = 14.sp,
                                                                maxLines = 1
                                                            )
                                                            Text(
                                                                text = note.content.take(60).replace("\n", " "),
                                                                color = Color(0x88FFFFFF),
                                                                fontSize = 11.sp,
                                                                maxLines = 1
                                                            )
                                                        }
                                                        Box(
                                                            modifier = Modifier
                                                                .size(20.dp)
                                                                .clickable {
                                                                    NoteStorage.deleteNote(this@FloatingNoteService, note.id)
                                                                    savedNotes = NoteStorage.loadAllNotes(this@FloatingNoteService)
                                                                }
                                                                .background(Color(0xFFE53935), CircleShape),
                                                            contentAlignment = Alignment.Center
                                                        ) {
                                                            Text("\u2715", color = Color.White, fontSize = 10.sp)
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                        Spacer(modifier = Modifier.height(12.dp))
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clickable { showSavedNotes = false }
                                                .background(Color(0xFF555555), RoundedCornerShape(8.dp))
                                                .padding(vertical = 8.dp),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text("Close", color = Color.White, fontSize = 14.sp)
                                        }
                                    }
                                }
                            }
                        }

                        // Image Label Dialog overlay
                        if (showImageLabelDialog && pendingImageUri != null) {
                            var labelInputRef by remember { mutableStateOf<EditText?>(null) }
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(Color(0x80000000))
                                    .clickable { /* block clicks */ },
                                contentAlignment = Alignment.Center
                            ) {
                                Card(
                                    modifier = Modifier.widthIn(min = 220.dp, max = 280.dp),
                                    shape = RoundedCornerShape(16.dp),
                                    colors = CardDefaults.cardColors(containerColor = Color(0xFF2A2A2A)),
                                    border = BorderStroke(1.dp, Color(0x33FFFFFF))
                                ) {
                                    Column(
                                        modifier = Modifier.padding(20.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally
                                    ) {
                                        Text(
                                            text = "Image Label",
                                            color = Color.White,
                                            fontSize = 16.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Spacer(modifier = Modifier.height(12.dp))
                                        AndroidView(
                                            factory = { ctx ->
                                                EditText(ctx).apply {
                                                    labelInputRef = this
                                                    hint = "Enter label text..."
                                                    setHintTextColor(android.graphics.Color.argb(128, 128, 128, 128))
                                                    background = null
                                                    setTextColor(android.graphics.Color.WHITE)
                                                    textSize = 14f
                                                    setSingleLine(true)
                                                    setPadding(16, 12, 16, 12)
                                                    setOnFocusChangeListener { v, hasFocus ->
                                                        if (hasFocus) {
                                                            val ime = LatinIME.getInstance()
                                                            ime?.setDialogEditText(v as EditText)
                                                        }
                                                    }
                                                    addTextChangedListener(object : android.text.TextWatcher {
                                                        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                                                        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                                                            imageLabelText = s?.toString() ?: ""
                                                        }
                                                        override fun afterTextChanged(s: android.text.Editable?) {}
                                                    })
                                                }
                                            },
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                        Spacer(modifier = Modifier.height(16.dp))
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceEvenly
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .clickable {
                                                        labelInputRef?.let { LatinIME.getInstance()?.setDialogEditText(null) }
                                                        showImageLabelDialog = false
                                                        pendingImageUri = null
                                                    }
                                                    .background(Color(0xFF555555), RoundedCornerShape(8.dp))
                                                    .padding(horizontal = 20.dp, vertical = 10.dp),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Text("Cancel", color = Color.White, fontSize = 14.sp)
                                            }
                                            Box(
                                                modifier = Modifier
                                                    .clickable {
                                                        labelInputRef?.let { LatinIME.getInstance()?.setDialogEditText(null) }
                                                        val id = java.util.UUID.randomUUID().toString()
                                                        attachedImages = attachedImages + Triple(
                                                            pendingImageUri!!,
                                                            imageLabelText.ifEmpty { "Image" },
                                                            id
                                                        )
                                                        showImageLabelDialog = false
                                                        pendingImageUri = null
                                                    }
                                                    .background(Color(0xFF4CAF50), RoundedCornerShape(8.dp))
                                                    .padding(horizontal = 20.dp, vertical = 10.dp),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Text("Attach", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        // Image Viewer overlay
                        if (showImageViewer && viewerImageUri != null) {
                            val context = LocalContext.current
                            var imgScale by remember { mutableStateOf(1f) }
                            var imgOffsetX by remember { mutableStateOf(0f) }
                            var imgOffsetY by remember { mutableStateOf(0f) }
                            val bitmap = remember(viewerImageUri) {
                                try {
                                    val uri = Uri.parse(viewerImageUri!!)
                                    context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
                                } catch (_: Exception) { null }
                            }
                            Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
                                if (bitmap != null) {
                                    Image(
                                        bitmap = bitmap.asImageBitmap(),
                                        contentDescription = viewerImageLabel,
                                        contentScale = ContentScale.Fit,
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .graphicsLayer(
                                                scaleX = imgScale,
                                                scaleY = imgScale,
                                                translationX = imgOffsetX,
                                                translationY = imgOffsetY
                                            )
                                            .pointerInput(Unit) {
                                                detectTransformGestures { _, pan, zoom, _ ->
                                                    imgScale = (imgScale * zoom).coerceIn(0.5f, 5f)
                                                    imgOffsetX += pan.x
                                                    imgOffsetY += pan.y
                                                }
                                            }
                                    )
                                } else {
                                    Text(
                                        text = "Failed to load image",
                                        color = Color.Gray,
                                        modifier = Modifier.align(Alignment.Center)
                                    )
                                }
                                // Top bar with close + zoom controls
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(Color(0xCC000000))
                                        .padding(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(32.dp)
                                            .clickable { showImageViewer = false; viewerImageUri = null }
                                            .background(Color(0xFF555555), CircleShape),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text("\u2715", color = Color.White, fontSize = 14.sp)
                                    }
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = viewerImageLabel,
                                        color = Color.White,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Box(
                                        modifier = Modifier
                                            .size(32.dp)
                                            .clickable { imgScale = (imgScale / 1.3f).coerceAtLeast(0.5f) }
                                            .background(Color(0xFF555555), CircleShape),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text("\u2212", color = Color.White, fontSize = 18.sp)
                                    }
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Box(
                                        modifier = Modifier
                                            .size(32.dp)
                                            .clickable { imgScale = 1f; imgOffsetX = 0f; imgOffsetY = 0f }
                                            .background(Color(0xFF555555), CircleShape),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text("1:1", color = Color.White, fontSize = 10.sp)
                                    }
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Box(
                                        modifier = Modifier
                                            .size(32.dp)
                                            .clickable { imgScale = (imgScale * 1.3f).coerceAtMost(5f) }
                                            .background(Color(0xFF555555), CircleShape),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text("+", color = Color.White, fontSize = 18.sp)
                                    }
                                }
                            }
                        }

                        // Floating Keyboard fallback
                        if (showFallbackKeyboard && !isBrowserMode) {
                            Box(
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .fillMaxWidth()
                            ) {
                                val kbRows = listOf("QWERTYUIOP", "ASDFGHJKL", "ZXCVBNM")
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(Color(0xE62A2A2A))
                                        .padding(4.dp)
                                ) {
                                    kbRows.forEach { row ->
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.Center
                                        ) {
                                            row.forEach { char ->
                                                Box(
                                                    modifier = Modifier
                                                        .padding(2.dp)
                                                        .defaultMinSize(minWidth = 30.dp)
                                                        .height(36.dp)
                                                        .background(Color(0xFF444444), RoundedCornerShape(4.dp))
                                                        .clickable {
                                                            editTextRef?.let { et ->
                                                                val start = et.selectionStart.coerceAtLeast(0)
                                                                et.text.insert(start, char.toString())
                                                            }
                                                        },
                                                    contentAlignment = Alignment.Center
                                                ) {
                                                    Text(
                                                        text = char.toString(),
                                                        color = Color.White,
                                                        fontSize = 14.sp,
                                                        modifier = Modifier.padding(horizontal = 6.dp)
                                                    )
                                                }
                                            }
                                        }
                                    }
                                    // Bottom row: dismiss, space, backspace, enter
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.Center,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .padding(2.dp)
                                                .width(44.dp)
                                                .height(36.dp)
                                                .background(Color(0xFF666666), RoundedCornerShape(4.dp))
                                                .clickable { showFallbackKeyboard = false },
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text("\u2328", color = Color.White, fontSize = 14.sp)
                                        }
                                        Box(
                                            modifier = Modifier
                                                .padding(2.dp)
                                                .weight(1f)
                                                .height(36.dp)
                                                .background(Color(0xFF444444), RoundedCornerShape(4.dp))
                                                .clickable {
                                                    editTextRef?.let { et ->
                                                        val start = et.selectionStart.coerceAtLeast(0)
                                                        et.text.insert(start, " ")
                                                    }
                                                },
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text("Space", color = Color.White, fontSize = 12.sp)
                                        }
                                        Box(
                                            modifier = Modifier
                                                .padding(2.dp)
                                                .width(44.dp)
                                                .height(36.dp)
                                                .background(Color(0xFF555555), RoundedCornerShape(4.dp))
                                                .clickable {
                                                    editTextRef?.let { et ->
                                                        val start = et.selectionStart
                                                        if (start > 0) et.text.delete(start - 1, start)
                                                    }
                                                },
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text("\u232B", color = Color.White, fontSize = 16.sp)
                                        }
                                        Box(
                                            modifier = Modifier
                                                .padding(2.dp)
                                                .width(44.dp)
                                                .height(36.dp)
                                                .background(Color(0xFF4CAF50), RoundedCornerShape(4.dp))
                                                .clickable {
                                                    editTextRef?.let { et ->
                                                        val start = et.selectionStart.coerceAtLeast(0)
                                                        et.text.insert(start, "\n")
                                                    }
                                                },
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text("\u23CE", color = Color.White, fontSize = 16.sp)
                                        }
                                    }
                                }
                            }
                        }
                    } // Box closes
                } // Card closes
            } // setContent closes
        } // apply closes

        wrapper.addView(view, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))

        try {
            wm?.addView(wrapper, params)
            composeView = wrapper
            currentParams = params
            currentWrapper = wrapper
            startHeartbeat()
            Log.d(TAG, "Floating note successfully added to WindowManager")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add floating note to WindowManager: ${e.message}", e)
            lifecycleOwner?.let { owner ->
                try {
                    owner.handleLifecycleEvent(androidx.lifecycle.Lifecycle.Event.ON_DESTROY)
                } catch (_: Exception) {}
            }
            lifecycleOwner = null
            stopSelf()
        }
    }

    private class FloatingNoteExceptionHandler(private val default: Thread.UncaughtExceptionHandler?) : Thread.UncaughtExceptionHandler {
        override fun uncaughtException(t: Thread, e: Throwable) {
            val causeChain = generateSequence(e as Throwable?) { it.cause }.map { it.javaClass.simpleName }.toList()
            if (causeChain.contains("BadTokenException")) {
                Log.w(TAG, "Caught BadTokenException from floating toolbar - ignoring to prevent crash", e)
                return
            }
            val message = e.message ?: ""
            if (message.contains("BadTokenException") || message.contains("Unable to add window")) {
                Log.w(TAG, "Caught BadTokenException from window - ignoring to prevent crash", e)
                return
            }
            default?.uncaughtException(t, e)
        }
    }

    private fun startHeartbeat() {
        stopHeartbeat()
        val run = object : Runnable {
            override fun run() {
                // Skip heartbeat while user is editing or dragging
                if (isEditingActive || isDragging) {
                    heartbeatHandler.postDelayed(this, 3000L)
                    return
                }
                val wrapper = currentWrapper ?: return
                val params = currentParams ?: return
                try {
                    // updateViewLayout nudges z-order on many Android versions
                    // without destroying the view's state (no content loss).
                    wm?.updateViewLayout(wrapper, params)
                } catch (e: Exception) {
                    Log.w(TAG, "Heartbeat update failed, will retry", e)
                }
                heartbeatHandler.postDelayed(this, 3000L)
            }
        }
        heartbeatRunnable = run
        heartbeatHandler.postDelayed(run, 3000L)
    }

    private fun stopHeartbeat() {
        heartbeatRunnable?.let { heartbeatHandler.removeCallbacks(it) }
        heartbeatRunnable = null
    }

    private fun removeFloatingNote() {
        val cleanup = Runnable {
            stopHeartbeat()
            try {
                composeView?.let {
                    try { wm?.removeView(it) } catch (_: Exception) {}
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to remove floating note", e)
            }
            composeView = null
            currentParams = null
            currentWrapper = null
            lifecycleOwner?.let { owner ->
                try {
                    owner.handleLifecycleEvent(androidx.lifecycle.Lifecycle.Event.ON_DESTROY)
                } catch (_: Exception) {}
            }
            lifecycleOwner = null
            camouflageRunnable?.let { camouflageHandler.removeCallbacks(it) }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            cleanup.run()
        } else {
            mainHandler.post(cleanup)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        imageAttachCallback = null // Prevent static lambda leak
        removeFloatingNote()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Deskdrop Floating Note",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                setSound(null, null)
                enableVibration(false)
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }
}