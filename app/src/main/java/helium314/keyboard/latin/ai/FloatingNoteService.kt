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
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationCompat
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
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

        // Install custom exception handler to catch BadTokenException from floating toolbar
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

        // Check SYSTEM_ALERT_WINDOW permission before showing overlay
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
                           WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN
        }

        // Wrapper FrameLayout that prevents action mode (to avoid BadTokenException).
        // Since ComposeView is final, we wrap it in a FrameLayout that overrides startActionMode.
        // The system trying to show a floating toolbar on our overlay window causes a BadTokenException
        // because the overlay window is created by a Service, not an Activity.
        val wrapper = object : android.widget.FrameLayout(this) {
            override fun startActionMode(callback: ActionMode.Callback?): ActionMode? = null
            override fun startActionMode(callback: ActionMode.Callback?, type: Int): ActionMode? = null
        }
        // Set lifecycle owner on the wrapper (root view) so Compose can find it via view tree traversal
        wrapper.setViewTreeLifecycleOwner(owner)
        wrapper.setViewTreeSavedStateRegistryOwner(owner)

        val view = ComposeView(this).apply {
            setContent {
                var text by remember { mutableStateOf(initialText) }
                var isFocused by remember { mutableStateOf(false) }
                var isCamouflaged by remember { mutableStateOf(false) }
                val focusRequester = remember { FocusRequester() }

                // Camouflage timer logic
                LaunchedEffect(isFocused) {
                    if (camouflageDurationMs > 0) {
                        camouflageRunnable?.let { camouflageHandler.removeCallbacks(it) }
                        if (!isFocused) {
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

                val onTouchCard = {
                    if (isCamouflaged) {
                        isCamouflaged = false
                    }
                    if ((params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) != 0) {
                        params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
                        try {
                            wm?.updateViewLayout(wrapper, params)
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to make window focusable", e)
                        }
                        focusRequester.requestFocus()
                    }
                }

                val onUnfocus = {
                    if ((params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) == 0) {
                        params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        try {
                            wm?.updateViewLayout(wrapper, params)
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to make window non-focusable", e)
                        }
                        try {
                            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                            imm.hideSoftInputFromWindow(wrapper.windowToken, 0)
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to hide soft input", e)
                        }
                        isFocused = false
                    }
                }

                Card(
                    modifier = Modifier
                        .fillMaxSize()
                        .alpha(alphaVal)
                        .shadow(8.dp, RoundedCornerShape(16.dp))
                        .clickable { onTouchCard() },
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
                            // Drag Handle (looks like small capsule)
                            Box(
                                modifier = Modifier
                                    .width(30.dp)
                                    .height(4.dp)
                                    .background(Color(0x66FFFFFF), RoundedCornerShape(2.dp))
                            )

                            Spacer(modifier = Modifier.weight(1f))

                            // Save/Lock checkmark button
                            if (isFocused) {
                                Box(
                                    modifier = Modifier
                                        .size(24.dp)
                                        .clickable { onUnfocus() }
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

                        // Text Area (TextField)
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                                .padding(12.dp)
                        ) {
                            BasicTextField(
                                value = text,
                                onValueChange = { text = it },
                                textStyle = TextStyle(
                                    color = Color.White,
                                    fontSize = 14.sp
                                ),
                                modifier = Modifier
                                    .fillMaxSize()
                                    .focusRequester(focusRequester)
                                    .onFocusChanged { state ->
                                        isFocused = state.isFocused
                                    },
                                cursorBrush = androidx.compose.ui.graphics.SolidColor(Color(0xFF00E5FF)),
                                decorationBox = { innerTextField ->
                                    Box(modifier = Modifier.fillMaxSize()) {
                                        if (text.isEmpty()) {
                                            Text(
                                                text = "Tap to note...",
                                                color = Color(0x88FFFFFF),
                                                fontSize = 14.sp
                                            )
                                        }
                                        innerTextField()
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }

        wrapper.addView(view, android.view.ViewGroup.LayoutParams(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            android.view.ViewGroup.LayoutParams.MATCH_PARENT
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

    /**
     * Custom uncaught exception handler for this service to prevent crashes from
     * BadTokenException when text is selected in the floating note.
     * This is a safety net; the primary defense is overriding startActionMode on the ComposeView.
     */
    private class FloatingNoteExceptionHandler(private val default: Thread.UncaughtExceptionHandler?) : Thread.UncaughtExceptionHandler {
        override fun uncaughtException(t: Thread, e: Throwable) {
            val causeChain = generateSequence(e as Throwable?) { it.cause }.map { it.javaClass.simpleName }.toList()
            if (causeChain.contains("BadTokenException")) {
                Log.w(TAG, "Caught BadTokenException from floating toolbar - ignoring to prevent crash", e)
                return
            }
            // Also catch WindowManager$BadTokenException (full qualified name check)
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
