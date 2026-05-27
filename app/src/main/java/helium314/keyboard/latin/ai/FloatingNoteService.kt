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
import android.view.inputmethod.InputMethodManager
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

class FloatingNoteService : Service() {

    companion object {
        private const val CHANNEL_ID = "deskdrop_floating_note"
        private const val NOTIFICATION_ID = 9002
        private const val TAG = "FloatingNoteService"
    }

    private var wm: WindowManager? = null
    private var composeView: android.view.View? = null
    private var lifecycleOwner: ServiceLifecycleOwner? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private var camouflageHandler = Handler(Looper.getMainLooper())
    private var camouflageRunnable: Runnable? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        createNotificationChannel()

        val currentHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler(FloatingNoteExceptionHandler(currentHandler))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
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
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:${packageName}")
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
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

        // Stable window flags: NOT_FOCUSABLE so overlay never steals window focus,
        // ALT_FOCUSABLE_IM so IME can route text input to EditText views within.
        // These flags are NEVER toggled — that was the source of Bug 1 and Bug 3.
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
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
                           WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN
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
                var editTextRef by remember { mutableStateOf<EditText?>(null) }

                // Camouflage timer logic
                LaunchedEffect(isEditing) {
                    if (camouflageDurationMs > 0) {
                        camouflageRunnable?.let { camouflageHandler.removeCallbacks(it) }
                        if (!isEditing) {
                            val run = Runnable { isCamouflaged = true }
                            camouflageRunnable = run
                            camouflageHandler.postDelayed(run, camouflageDurationMs.toLong())
                        } else {
                            isCamouflaged = false
                        }
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
                            }
                        },
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = Color(0xE61E1E1E)
                    ),
                    border = BorderStroke(1.dp, Color(0x33FFFFFF))
                ) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        // Header Bar
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color(0xFF2A2A2A))
                                .pointerInput(Unit) {
                                    detectDragGestures { change, dragAmount ->
                                        change.consume()
                                        onDrag(dragAmount.x, dragAmount.y)
                                    }
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

                            // Close Button
                            Box(
                                modifier = Modifier
                                    .size(24.dp)
                                    .clickable {
                                        val ime = LatinIME.getInstance()
                                        ime?.setDialogEditText(null)
                                        ime?.requestHideSelf(0)
                                        removeFloatingNote()
                                        stopSelf()
                                    }
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

                        // Text Area
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                                .padding(12.dp)
                        ) {
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

                                        // Handle internal focus changes
                                        setOnFocusChangeListener { v, hasFocus ->
                                            isEditingState.value = hasFocus
                                            val ime = LatinIME.getInstance()
                                            if (hasFocus) {
                                                ime?.setDialogEditText(this)
                                                // FORCE the keyboard to slide up
                                                ime?.startShowingInputView(true)
                                            } else {
                                                ime?.setDialogEditText(null)
                                                ime?.requestHideSelf(0)
                                            }
                                        }
                                        
                                        // Overlays sometimes swallow the first tap without triggering 
                                        // onFocusChange, so we explicitly catch the touch event.
                                        setOnTouchListener { view, event ->
                                            if (event.action == android.view.MotionEvent.ACTION_UP) {
                                                view.requestFocus()
                                                val ime = LatinIME.getInstance()
                                                ime?.setDialogEditText(view as EditText)
                                                ime?.startShowingInputView(true)
                                                isEditingState.value = true
                                            }
                                            false
                                        }
                                    }
                                },
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }
                }
            }
        }

        wrapper.addView(view, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))

        try {
            wm?.addView(wrapper, params)
            composeView = wrapper
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

    private fun removeFloatingNote() {
        val cleanup = Runnable {
            try {
                composeView?.let {
                    try { wm?.removeView(it) } catch (_: Exception) {}
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to remove floating note", e)
            }
            composeView = null
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
