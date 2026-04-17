package com.my.app

import android.app.*
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(80, 80, 80, 80)
        }
        val title = TextView(this).apply {
            text = "欢迎使用快捷启动栏！\n点击下方按钮开启常驻通知栏。"
            textSize = 18f
            setPadding(0, 0, 0, 50)
        }
        val btn = Button(this).apply {
            text = "开启通知栏"
            setOnClickListener {
                val intent = Intent(this@MainActivity, QuickService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(intent)
                } else {
                    startService(intent)
                }
            }
        }
        layout.addView(title)
        layout.addView(btn)
        setContentView(layout)
    }
}

class QuickService : Service() {
    override fun onBind(intent: Intent?) = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel("quick_id", "快捷启动", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }

        val iSettings = packageManager.getLaunchIntentForPackage("com.android.settings") ?: Intent(android.provider.Settings.ACTION_SETTINGS)
        val pi1 = PendingIntent.getActivity(this, 1, iSettings, PendingIntent.FLAG_IMMUTABLE)

        val iBrowser = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_BROWSER)
        val pi2 = PendingIntent.getActivity(this, 2, iBrowser, PendingIntent.FLAG_IMMUTABLE)

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, "quick_id")
        } else {
            Notification.Builder(this)
        }
        
        val notification = builder
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("快捷启动栏")
            .setContentText("常驻运行中，点击下方按钮快速打开")
            .setOngoing(true) // 常驻通知栏，不可滑动清除
            .addAction(android.R.drawable.ic_menu_preferences, "打开设置", pi1)
            .addAction(android.R.drawable.ic_menu_search, "浏览器", pi2)
            .build()

        startForeground(1, notification)
        return START_STICKY
    }
}
