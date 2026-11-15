package com.example.macroautoclicker

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

data class ClickEvent(
    val x: Float,
    val y: Float,
    val delayMs: Long
)

class AutoClickerService : AccessibilityService() {

    private lateinit var windowManager: WindowManager
    private var floatingPanelView: LinearLayout? = null
    private var panelLayoutParams: WindowManager.LayoutParams? = null
    private var touchOverlayView: View? = null

    private var headerRowView: LinearLayout? = null
    private var contentContainer: LinearLayout? = null
    private var collapsedBubbleView: TextView? = null
    private var isCollapsed = false

    private val recordedClicks = mutableListOf<ClickEvent>()
    private var isRecording = false
    private var isPlaying = false
    private var lastEventTime = 0L

    private val handler = Handler(Looper.getMainLooper())
    private var currentStepIndex = 0

    private lateinit var statusText: TextView
    private lateinit var btnRecord: TextView
    private lateinit var btnPlay: TextView
    private lateinit var btnSave: TextView
    private lateinit var btnLoad: TextView

    private val prefsName = "MacroAutoClickerStorage"

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

    private fun createCompactButton(label: String, bgColorHex: String): TextView {
        val drawable = GradientDrawable().apply {
            setColor(Color.parseColor(bgColorHex))
            cornerRadius = 16f
        }
        return TextView(this).apply {
            text = label
            setTextColor(Color.WHITE)
            textSize = 11f
            gravity = Gravity.CENTER
            background = drawable
            setPadding(20, 14, 20, 14)
        }
    }

    private fun setupFloatingPanel() {
        val bgDrawable = GradientDrawable().apply {
            setColor(Color.parseColor("#CC18181B"))
            cornerRadius = 24f
            setStroke(2, Color.parseColor("#33FFFFFF"))
        }

        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = bgDrawable
            setPadding(16, 12, 16, 14)
        }

        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val dragHandle = TextView(this).apply {
            text = "⠿ Двигать"
            setTextColor(Color.parseColor("#99FFFFFF"))
            textSize = 10f
            setPadding(4, 2, 16, 2)
        }

        val minimizeBtnBg = GradientDrawable().apply {
            setColor(Color.parseColor("#33FFFFFF"))
            cornerRadius = 12f
        }

        val btnMinimize = TextView(this).apply {
            text = "—"
            setTextColor(Color.WHITE)
            textSize = 11f
            gravity = Gravity.CENTER
            background = minimizeBtnBg
            setPadding(18, 2, 18, 4)
            setOnClickListener { collapsePanel() }
        }

        headerRow.addView(dragHandle)
        headerRow.addView(btnMinimize)

        val bodyContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 6, 0, 0)
        }

        statusText = TextView(this).apply {
            text = "Готов (0)"
            setTextColor(Color.parseColor("#E0E0E0"))
            textSize = 10f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 8)
        }

        val row1 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

        btnRecord = createCompactButton("● Зап.", "#D9383A").apply {
            setOnClickListener { toggleRecording() }
        }

        val spacer1 = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(10, 1)
        }

        btnPlay = createCompactButton("▶ Старт", "#2E7D32").apply {
            setOnClickListener { togglePlayback() }
        }

        row1.addView(btnRecord)
        row1.addView(spacer1)
        row1.addView(btnPlay)

        val row2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, 8, 0, 0)
        }

        btnSave = createCompactButton("💾 Сохр.", "#3A3A3C").apply {
            setOnClickListener { showSaveMacroDialog() }
        }

        val spacer2 = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(10, 1)
        }

        btnLoad = createCompactButton("📂 Спис.", "#3A3A3C").apply {
            setOnClickListener { showLoadMacroDialog() }
        }

        row2.addView(btnSave)
        row2.addView(spacer2)
        row2.addView(btnLoad)

        bodyContainer.addView(statusText)
        bodyContainer.addView(row1)
        bodyContainer.addView(row2)

        val bubbleBg = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.parseColor("#5518181B"))
            setStroke(2, Color.parseColor("#55FFFFFF"))
        }

        val bubble = TextView(this).apply {
            text = "⚡"
            textSize = 13f
            gravity = Gravity.CENTER
            background = bubbleBg
            setPadding(18, 14, 18, 14)
            visibility = View.GONE
        }

        rootLayout.addView(headerRow)
        rootLayout.addView(bodyContainer)
        rootLayout.addView(bubble)

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
            x = 40
            y = 200
        }

        dragHandle.setOnTouchListener(createDragTouchListener(params, onClick = null))
        bubble.setOnTouchListener(createDragTouchListener(params, onClick = { expandPanel() }))

        headerRowView = headerRow
        contentContainer = bodyContainer
        collapsedBubbleView = bubble
        panelLayoutParams = params
        floatingPanelView = rootLayout
        windowManager.addView(floatingPanelView, params)
    }

    private fun createDragTouchListener(
        params: WindowManager.LayoutParams,
        onClick: (() -> Unit)?
    ): View.OnTouchListener {
        return object : View.OnTouchListener {
            private var initialX = 0
            private var initialY = 0
            private var initialTouchX = 0f
            private var initialTouchY = 0f
            private var isMoved = false

            override fun onTouch(v: View?, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = params.x
                        initialY = params.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        isMoved = false
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = (event.rawX - initialTouchX).toInt()
                        val dy = (event.rawY - initialTouchY).toInt()
                        if (abs(dx) > 8 || abs(dy) > 8) {
                            isMoved = true
                        }
                        params.x = initialX + dx
                        params.y = initialY + dy
                        windowManager.updateViewLayout(floatingPanelView, params)
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (!isMoved) {
                            onClick?.invoke()
                        }
                        return true
                    }
                }
                return false
            }
        }
    }

    private fun collapsePanel() {
        isCollapsed = true
        headerRowView?.visibility = View.GONE
        contentContainer?.visibility = View.GONE
        floatingPanelView?.background = null
        floatingPanelView?.setPadding(0, 0, 0, 0)
        collapsedBubbleView?.visibility = View.VISIBLE
        panelLayoutParams?.let { windowManager.updateViewLayout(floatingPanelView, it) }
    }

    private fun expandPanel() {
        isCollapsed = false
        val bgDrawable = GradientDrawable().apply {
            setColor(Color.parseColor("#CC18181B"))
            cornerRadius = 24f
            setStroke(2, Color.parseColor("#33FFFFFF"))
        }
        floatingPanelView?.background = bgDrawable
        floatingPanelView?.setPadding(16, 12, 16, 14)
        collapsedBubbleView?.visibility = View.GONE
        headerRowView?.visibility = View.VISIBLE
        contentContainer?.visibility = View.VISIBLE
        panelLayoutParams?.let { windowManager.updateViewLayout(floatingPanelView, it) }
    }

    private fun showSaveMacroDialog() {
        if (isRecording) toggleRecording()
        if (isPlaying) stopPlayback()

        if (recordedClicks.isEmpty()) {
            Toast.makeText(this, "Сначала запишите клики, чтобы сохранить!", Toast.LENGTH_SHORT).show()
            return
        }

        val input = EditText(this).apply {
            hint = "Например: Фарм ресурсов"
            setSingleLine(true)
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 12)
            addView(input)
        }

        val dialog = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Сохранить запись")
            .setMessage("Введите название для макроса (${recordedClicks.size} кликов):")
            .setView(container)
            .setPositiveButton("Сохранить") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) {
                    saveMacroToStorage(name, recordedClicks)
                    statusText.text = "Сохр: $name"
                    Toast.makeText(this, "Макрос «$name» сохранён!", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Название не может быть пустым", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Отмена", null)
            .create()

        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        dialog.window?.setType(overlayType)
        dialog.show()
    }

    private fun showLoadMacroDialog() {
        if (isRecording) toggleRecording()
        if (isPlaying) stopPlayback()

        val prefs = getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        val allMacros = prefs.all
        if (allMacros.isEmpty()) {
            Toast.makeText(this, "Нет сохранённых записей", Toast.LENGTH_SHORT).show()
            return
        }

        val macroNames = allMacros.keys.sorted().toTypedArray()

        val dialog = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Выберите запись")
            .setItems(macroNames) { _, which ->
                val selectedName = macroNames[which]
                val jsonString = prefs.getString(selectedName, null)
                if (jsonString != null) {
                    val loaded = parseMacroFromJson(jsonString)
                    recordedClicks.clear()
                    recordedClicks.addAll(loaded)
                    statusText.text = "$selectedName (${recordedClicks.size})"
                    Toast.makeText(this, "Загружено: «$selectedName»", Toast.LENGTH_SHORT).show()
                }
            }
            .setNeutralButton("Удалить запись...") { _, _ ->
                showDeleteMacroDialog(macroNames)
            }
            .setNegativeButton("Закрыть", null)
            .create()

        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        dialog.window?.setType(overlayType)
        dialog.show()
    }

    private fun showDeleteMacroDialog(macroNames: Array<String>) {
        val dialog = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Нажмите на запись для удаления")
            .setItems(macroNames) { _, which ->
                val nameToDelete = macroNames[which]
                getSharedPreferences(prefsName, Context.MODE_PRIVATE)
                    .edit()
                    .remove(nameToDelete)
                    .apply()
                Toast.makeText(this, "Удалено: «$nameToDelete»", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Отмена", null)
            .create()

        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        dialog.window?.setType(overlayType)
        dialog.show()
    }

    private fun saveMacroToStorage(name: String, clicks: List<ClickEvent>) {
        val jsonArray = JSONArray()
        for (click in clicks) {
            val obj = JSONObject().apply {
                put("x", click.x.toDouble())
                put("y", click.y.toDouble())
                put("delayMs", click.delayMs)
            }
            jsonArray.put(obj)
        }
        getSharedPreferences(prefsName, Context.MODE_PRIVATE)
            .edit()
            .putString(name, jsonArray.toString())
            .apply()
    }

    private fun parseMacroFromJson(jsonString: String): List<ClickEvent> {
        val list = mutableListOf<ClickEvent>()
        try {
            val jsonArray = JSONArray(jsonString)
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                list.add(
                    ClickEvent(
                        x = obj.getDouble("x").toFloat(),
                        y = obj.getDouble("y").toFloat(),
                        delayMs = obj.getLong("delayMs")
                    )
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return list
    }

    private fun toggleRecording() {
        if (isPlaying) stopPlayback()

        if (!isRecording) {
            recordedClicks.clear()
            isRecording = true
            lastEventTime = System.currentTimeMillis()
            btnRecord.text = "■ Стоп"
            statusText.text = "Запись..."
            attachTouchRecorderOverlay()
        } else {
            isRecording = false
            btnRecord.text = "● Зап."
            statusText.text = "Кликов: ${recordedClicks.size}"
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
        windowManager.addView(floatingPanelView, panelLayoutParams)
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
                Toast.makeText(this, "Сначала запишите или загрузите клики!", Toast.LENGTH_SHORT).show()
                return
            }
            isPlaying = true
            currentStepIndex = 0
            btnPlay.text = "■ Пауза"
            statusText.text = "Работает..."
            scheduleNextClick()
        } else {
            stopPlayback()
        }
    }

    private fun stopPlayback() {
        isPlaying = false
        handler.removeCallbacksAndMessages(null)
        btnPlay.text = "▶ Старт"
        statusText.text = "Пауза (${recordedClicks.size})"
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
