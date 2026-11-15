package com.example.macroautoclicker

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

data class ClickEvent(
    val x: Float,
    val y: Float,
    val delayMs: Long
)

class AutoClickerService : AccessibilityService() {

    private lateinit var windowManager: WindowManager
    private var floatingPanelView: LinearLayout? = null
    private var touchOverlayView: View? = null

    private val recordedClicks = mutableListOf<ClickEvent>()
    private var isRecording = false
    private var isPlaying = false
    private var lastEventTime = 0L

    private val handler = Handler(Looper.getMainLooper())
    private var currentStepIndex = 0

    private lateinit var statusText: TextView
    private lateinit var btnRecord: Button
    private lateinit var btnPlay: Button

    override fun onServiceConnected() {
        super.onServiceConnected()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Выдайте разрешение 'Поверх других окон'", Toast.LENGTH_LONG).show()
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
            return
        }

        setupFloatingPanel()
    }

    private fun setupFloatingPanel() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#DD1E1E1E"))
            setPadding(24, 24, 24, 24)
        }

        statusText = TextView(this).apply {
            text = "Готов (Кликов: 0)"
            setTextColor(Color.WHITE)
            textSize = 12f
        }

        btnRecord = Button(this).apply {
            text = "● Запись"
            setOnClickListener { toggleRecording() }
        }

        btnPlay = Button(this).apply {
            text = "▶ Старт"
            setOnClickListener { togglePlayback() }
        }

        layout.addView(statusText)
        layout.addView(btnRecord)
        layout.addView(btnPlay)

        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 50
            y = 200
        }

        floatingPanelView = layout
        windowManager.addView(floatingPanelView, params)
    }

    private fun toggleRecording() {
        if (isPlaying) stopPlayback()

        if (!isRecording) {
            recordedClicks.clear()
            isRecording = true
            lastEventTime = System.currentTimeMillis()
            btnRecord.text = "■ Стоп"
            statusText.text = "Запись идет..."
            attachTouchRecorderOverlay()
        } else {
            isRecording = false
            btnRecord.text = "● Запись"
            statusText.text = "Сохранено: ${recordedClicks.size}"
            detachTouchRecorderOverlay()
        }
    }

    private fun attachTouchRecorderOverlay() {
        if (touchOverlayView != null) return

        val overlay = View(this).apply {
            setBackgroundColor(Color.parseColor("#11FF0000"))
            setOnTouchListener { _, event ->
                if (event.action == MotionEvent.ACTION_DOWN) {
                    val now = System.currentTimeMillis()
                    val delay = now - lastEventTime
                    lastEventTime = now

                    val x = event.rawX
                    val y = event.rawY
                    recordedClicks.add(ClickEvent(x, y, delay))
                    statusText.text = "Записано: ${recordedClicks.size}"

                    passThroughClick(x, y)
                }
                true
            }
        }

        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )

        touchOverlayView = overlay
        windowManager.removeView(floatingPanelView)
        windowManager.addView(touchOverlayView, params)
        windowManager.addView(floatingPanelView, floatingPanelView?.layoutParams)
    }

    private fun passThroughClick(x: Float, y: Float) {
        touchOverlayView?.visibility = View.GONE
        performTap(x, y) {
            if (isRecording) {
                touchOverlayView?.visibility = View.VISIBLE
            }
        }
    }

    private fun detachTouchRecorderOverlay() {
        touchOverlayView?.let {
            windowManager.removeView(it)
            touchOverlayView = null
        }
    }

    private fun togglePlayback() {
        if (isRecording) toggleRecording()

        if (!isPlaying) {
            if (recordedClicks.isEmpty()) {
                Toast.makeText(this, "Сначала запишите клики!", Toast.LENGTH_SHORT).show()
                return
            }
            isPlaying = true
            currentStepIndex = 0
            btnPlay.text = "■ Пауза"
            statusText.text = "Воспроизведение..."
            scheduleNextClick()
        } else {
            stopPlayback()
        }
    }

    private fun stopPlayback() {
        isPlaying = false
        handler.removeCallbacksAndMessages(null)
        btnPlay.text = "▶ Старт"
        statusText.text = "Остановлено (${recordedClicks.size})"
    }

    private fun scheduleNextClick() {
        if (!isPlaying || recordedClicks.isEmpty()) return

        val event = recordedClicks[currentStepIndex]
        handler.postDelayed({
            if (!isPlaying) return@postDelayed

            statusText.text = "Клик ${currentStepIndex + 1}/${recordedClicks.size}"
            performTap(event.x, event.y)

            currentStepIndex = (currentStepIndex + 1) % recordedClicks.size
            scheduleNextClick()
        }, event.delayMs)
    }

    private fun performTap(x: Float, y: Float, onComplete: (() -> Unit)? = null) {
        val path = Path().apply {
            moveTo(x, y)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 50))
            .build()

        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                onComplete?.invoke()
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                onComplete?.invoke()
            }
        }, null)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {
        stopPlayback()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopPlayback()
        detachTouchRecorderOverlay()
        floatingPanelView?.let { windowManager.removeView(it) }
    }
}
