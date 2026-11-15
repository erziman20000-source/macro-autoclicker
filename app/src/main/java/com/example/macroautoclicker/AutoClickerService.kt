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
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject

/**
 * Модель одного записанного нажатия на экран.
 */
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

    private val recordedClicks = mutableListOf<ClickEvent>()
    private var isRecording = false
    private var isPlaying = false
    private var lastEventTime = 0L

    private val handler = Handler(Looper.getMainLooper())
    private var currentStepIndex = 0

    // Элементы интерфейса плавающей панели
    private lateinit var statusText: TextView
    private lateinit var btnRecord: Button
    private lateinit var btnPlay: Button
    private lateinit var btnSave: Button
    private lateinit var btnLoad: Button

    // Имя файла настроек для хранения макросов в памяти телефона
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

    /**
     * Создает стильную плавающую панель управления с возможностью перетаскивания
     * и кнопками сохранения/загрузки макросов.
     */
    private fun setupFloatingPanel() {
        val bgDrawable = GradientDrawable().apply {
            setColor(Color.parseColor("#E61C1C1E"))
            cornerRadius = 28f
            setStroke(2, Color.parseColor("#44FFFFFF"))
        }

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = bgDrawable
            setPadding(24, 18, 24, 24)
        }

        // Верхняя полоска для перетаскивания панели пальцем
        val dragHandle = TextView(this).apply {
            text = "⠿ Перетащить"
            setTextColor(Color.parseColor("#AAAAAA"))
            textSize = 11f
            gravity = Gravity.CENTER
            setPadding(0, 4, 0, 12)
        }

        statusText = TextView(this).apply {
            text = "Готов (Кликов: 0)"
            setTextColor(Color.WHITE)
            textSize = 12f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 10)
        }

        btnRecord = Button(this).apply {
            text = "● Запись"
            textSize = 12f
            setOnClickListener { toggleRecording() }
        }

        btnPlay = Button(this).apply {
            text = "▶ Старт"
            textSize = 12f
            setOnClickListener { togglePlayback() }
        }

        // Горизонтальный ряд для кнопок сохранения и загрузки макросов
        val macroRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

        btnSave = Button(this).apply {
            text = "💾 Сохр."
            textSize = 11f
            setOnClickListener { showSaveMacroDialog() }
        }

        btnLoad = Button(this).apply {
            text = "📂 Макросы"
            textSize = 11f
            setOnClickListener { showLoadMacroDialog() }
        }

        macroRow.addView(btnSave)
        macroRow.addView(btnLoad)

        layout.addView(dragHandle)
        layout.addView(statusText)
        layout.addView(btnRecord)
        layout.addView(btnPlay)
        layout.addView(macroRow)

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

        // Логика перетаскивания панели при удержании шапки "⠿ Перетащить"
        dragHandle.setOnTouchListener(object : View.OnTouchListener {
            private var initialX = 0
            private var initialY = 0
            private var initialTouchX = 0f
            private var initialTouchY = 0f

            override fun onTouch(v: View?, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = params.x
                        initialY = params.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        params.x = initialX + (event.rawX - initialTouchX).toInt()
                        params.y = initialY + (event.rawY - initialTouchY).toInt()
                        windowManager.updateViewLayout(floatingPanelView, params)
                        return true
                    }
                }
                return false
            }
        })

        panelLayoutParams = params
        floatingPanelView = layout
        windowManager.addView(floatingPanelView, params)
    }

    /**
     * Открывает диалоговое окно поверх всех окон для ввода имени нового макроса.
     */
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

    /**
     * Открывает список всех сохранённых макросов для выбора или удаления.
     */
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

    /**
     * Окно для удаления ненужного макроса.
     */
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
            statusText.text = "Запись идет..."
            attachTouchRecorderOverlay()
        } else {
            isRecording = false
            btnRecord.text = "● Запись"
            statusText.text = "Записано: ${recordedClicks.size}"
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
