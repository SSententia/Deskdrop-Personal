// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.ai

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationCompat
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import helium314.keyboard.latin.LatinIME
import helium314.keyboard.latin.R

/**
 * A separate floating keyboard window that types into the active floating note's EditText.
 * Launched from FloatingNoteService and positioned near the floating note.
 *
 * Communication:
 *  - Reads [FloatingNoteService.activeNoteEditText] to type characters into the note.
 *  - Closed together with the note via stopService from FloatingNoteService.
 */
class FloatingKeyboardService : Service() {

    companion object {
        private const val CHANNEL_ID = "deskdrop_floating_keyboard"
        private const val NOTIFICATION_ID = 9003
        private const val TAG = "FloatingKeyboardService"
    }

    private var wm: WindowManager? = null
    private var composeView: android.view.View? = null
    private var lifecycleOwner: ServiceLifecycleOwner? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    // Z-order heartbeat
    private var heartbeatHandler = Handler(Looper.getMainLooper())
    private var heartbeatRunnable: Runnable? = null
    private var currentParams: WindowManager.LayoutParams? = null
    private var currentWrapper: android.view.View? = null
    @Volatile
    private var isDragging = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val x = intent?.getIntExtra("x", 100) ?: 100
        val y = intent?.getIntExtra("y", 200) ?: 200
        val noteWidth = intent?.getIntExtra("noteWidth", 250) ?: 250
        val noteHeight = intent?.getIntExtra("noteHeight", 200) ?: 200

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !android.provider.Settings.canDrawOverlays(this)) {
            Log.w(TAG, "SYSTEM_ALERT_WINDOW permission not granted")
            val permIntent = Intent(
                android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                android.net.Uri.parse("package:$packageName")
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try { startActivity(permIntent) } catch (_: Exception) {}
            stopSelf()
            return START_NOT_STICKY
        }

        showNotification()
        showKeyboard(x, y, noteWidth, noteHeight)
        return START_NOT_STICKY
    }

    private fun showNotification() {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_shortcut_chat)
            .setContentTitle("Floating Keyboard Active")
            .setContentText("Tap to close the keyboard")
            .setOngoing(true)
            .build()

        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start FGS, trying fallback", e)
            try { startForeground(NOTIFICATION_ID, notification) } catch (_: Exception) {}
        }
    }

    private fun showKeyboard(initialX: Int, initialY: Int, noteWidth: Int, noteHeight: Int) {
        removeKeyboard()

        val displayMetrics = resources.displayMetrics
        val density = displayMetrics.density
        // Keyboard is wider than the note and positioned just below it
        val keyboardWidthDp = maxOf(noteWidth, 300)
        val keyboardHeightDp = 250
        val widthPx = (keyboardWidthDp * density).toInt()
        val heightPx = (keyboardHeightDp * density).toInt()

        // Position keyboard below the note with a small gap
        val keyboardX = initialX
        val keyboardY = initialY + (noteHeight * density).toInt() + (8 * density).toInt()

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
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = keyboardX
            y = keyboardY
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
        }

        val wrapper = object : android.widget.FrameLayout(this) {
            override fun startActionMode(callback: android.view.ActionMode.Callback?): android.view.ActionMode? = null
            override fun startActionMode(callback: android.view.ActionMode.Callback?, type: Int): android.view.ActionMode? = null
        }
        wrapper.setViewTreeLifecycleOwner(owner)
        wrapper.setViewTreeSavedStateRegistryOwner(owner)

        val view = ComposeView(this).apply {
            setContent {
                KeyboardContent(
                    onClose = {
                        removeKeyboard()
                        stopSelf()
                    },
                    onDrag = { dx, dy ->
                        params.x += dx.toInt()
                        params.y += dy.toInt()
                        try {
                            wm?.updateViewLayout(wrapper, params)
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to update view position on drag", e)
                        }
                    }
                )
            }
        }

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
            Log.d(TAG, "Floating keyboard added to WindowManager at ($keyboardX, $keyboardY)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add floating keyboard: ${e.message}", e)
            lifecycleOwner?.let { owner ->
                try { owner.handleLifecycleEvent(androidx.lifecycle.Lifecycle.Event.ON_DESTROY) } catch (_: Exception) {}
            }
            lifecycleOwner = null
            stopSelf()
        }
    }

    private fun startHeartbeat() {
        stopHeartbeat()
        val run = object : Runnable {
            override fun run() {
                if (isDragging) {
                    heartbeatHandler.postDelayed(this, 3000L)
                    return
                }
                val wrapper = currentWrapper ?: return
                val params = currentParams ?: return
                try {
                    wm?.updateViewLayout(wrapper, params)
                } catch (e: Exception) {
                    Log.w(TAG, "Heartbeat update failed", e)
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

    private fun removeKeyboard() {
        val cleanup = Runnable {
            stopHeartbeat()
            try {
                composeView?.let { try { wm?.removeView(it) } catch (_: Exception) {} }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to remove keyboard", e)
            }
            composeView = null
            currentParams = null
            currentWrapper = null
            lifecycleOwner?.let { owner ->
                try { owner.handleLifecycleEvent(androidx.lifecycle.Lifecycle.Event.ON_DESTROY) } catch (_: Exception) {}
            }
            lifecycleOwner = null
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            cleanup.run()
        } else {
            mainHandler.post(cleanup)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        removeKeyboard()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Deskdrop Floating Keyboard",
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

// ──────────────────────────────────────────────
//  Keyboard Compose UI (separate from Service)
// ──────────────────────────────────────────────

@Composable
private fun KeyboardContent(
    onClose: () -> Unit,
    onDrag: (Float, Float) -> Unit
) {
    val numberRow = listOf('1', '2', '3', '4', '5', '6', '7', '8', '9', '0')
    val qwertyRows = listOf(
        listOf('Q', 'W', 'E', 'R', 'T', 'Y', 'U', 'I', 'O', 'P'),
        listOf('A', 'S', 'D', 'F', 'G', 'H', 'J', 'K', 'L'),
        listOf('Z', 'X', 'C', 'V', 'B', 'N', 'M')
    )
    val symbolRow = listOf('.', ',', '?', '!', '"', '\'', '-', '_', '@', '#')

    val typeChar: (String) -> Unit = { text ->
        FloatingNoteService.activeNoteEditText?.get()?.let { et ->
            val start = et.selectionStart.coerceAtLeast(0)
            et.text.insert(start, text)
        }
    }

    Card(
        modifier = Modifier
            .fillMaxSize()
            .shadow(8.dp, RoundedCornerShape(16.dp)),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xE61E1E1E)),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0x33FFFFFF))
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Drag handle bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF2A2A2A))
                    .pointerInput(Unit) {
                        detectDragGestures(
                            onDragStart = { },
                            onDragEnd = { },
                            onDragCancel = { },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                onDrag(dragAmount.x, dragAmount.y)
                            }
                        )
                    }
                    .padding(horizontal = 8.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .width(30.dp)
                        .height(3.dp)
                        .background(Color(0x66FFFFFF), RoundedCornerShape(2.dp))
                )
                Spacer(modifier = Modifier.weight(1f))
                // Close button
                Box(
                    modifier = Modifier
                        .size(18.dp)
                        .clickable { onClose() }
                        .background(Color(0xFFE53935), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "✕",
                        color = Color.White,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // Key area
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(Color(0xE62A2A2A))
                    .padding(horizontal = 3.dp, vertical = 1.dp),
                verticalArrangement = Arrangement.SpaceEvenly
            ) {
                // Number row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center
                ) {
                    numberRow.forEach { char ->
                        KeyboardKey(
                            label = char.toString(),
                            modifier = Modifier.weight(1f),
                            color = Color(0xFF3A3A3A),
                            fontSize = 12,
                            onClick = { typeChar(char.toString()) }
                        )
                    }
                }

                // QWERTY rows
                qwertyRows.forEach { row ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center
                    ) {
                        row.forEach { char ->
                            KeyboardKey(
                                label = char.toString(),
                                modifier = Modifier.weight(1f),
                                onClick = { typeChar(char.toString()) }
                            )
                        }
                    }
                }

                // Symbol row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center
                ) {
                    symbolRow.forEach { char ->
                        KeyboardKey(
                            label = char.toString(),
                            modifier = Modifier.weight(1f),
                            color = Color(0xFF3A3A3A),
                            fontSize = 12,
                            onClick = { typeChar(char.toString()) }
                        )
                    }
                }

                // Bottom row: space, backspace, enter
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Space
                    KeyboardKey(
                        label = "Space",
                        modifier = Modifier.weight(3f),
                        fontSize = 11,
                        color = Color(0xFF444444),
                        onClick = { typeChar(" ") }
                    )
                    // Backspace
                    KeyboardKey(
                        label = "⌫",
                        modifier = Modifier.weight(1f),
                        color = Color(0xFF555555),
                        onClick = {
                            FloatingNoteService.activeNoteEditText?.get()?.let { et ->
                                val start = et.selectionStart
                                if (start > 0) et.text.delete(start - 1, start)
                            }
                        }
                    )
                    // Enter
                    KeyboardKey(
                        label = "⏎",
                        modifier = Modifier.weight(1f),
                        color = Color(0xFF4CAF50),
                        onClick = { typeChar("\n") }
                    )
                }
            }
        }
    }
}

@Composable
private fun KeyboardKey(
    label: String,
    modifier: Modifier,
    onClick: () -> Unit,
    color: Color = Color(0xFF444444),
    fontSize: Int = 13
) {
    Box(
        modifier = modifier
            .padding(2.dp)
            .height(34.dp)
            .background(color, RoundedCornerShape(5.dp))
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = Color.White,
            fontSize = fontSize.sp,
            fontWeight = if (label.length == 1) FontWeight.Normal else FontWeight.Medium,
            maxLines = 1
        )
    }
}
