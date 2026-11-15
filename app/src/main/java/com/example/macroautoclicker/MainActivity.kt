package com.example.macroautoclicker

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 64, 48, 48)
        }

        val title = TextView(this).apply {
            text = "Настройка Macro AutoClicker\n\nВыполните 2 шага ниже, чтобы появилась плавающая панель:"
            textSize = 18f
            setPadding(0, 0, 0, 48)
        }

        val btnOverlay = Button(this).apply {
            text = "Шаг 1: Разрешить показ поверх окон"
            setOnClickListener {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this@MainActivity)) {
                    val intent = Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                    startActivity(intent)
                } else {
                    Toast.makeText(this@MainActivity, "Разрешение уже выдано!", Toast.LENGTH_SHORT).show()
                }
            }
        }

        val btnAccessibility = Button(this).apply {
            text = "Шаг 2: Включить в Спец. возможностях"
            setOnClickListener {
                val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                startActivity(intent)
            }
        }

        layout.addView(title)
        layout.addView(btnOverlay)
        layout.addView(btnAccessibility)

        setContentView(layout)
    }
}
