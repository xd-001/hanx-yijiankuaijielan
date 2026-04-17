package com.my.app

import android.Manifest
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // 1. 动态申请安卓13以上的通知权限
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }

        // 2. 搭建界面布局
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 50, 50, 50)
        }

        val btn = Button(this).apply {
            text = "开启 / 刷新通知栏"
            setOnClickListener {
                val intent = Intent(this@MainActivity, QuickService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(intent)
                } else {
                    startService(intent)
                }
                Toast.makeText(this@MainActivity, "已开启，请下拉通知栏查看", Toast.LENGTH_SHORT).show()
            }
        }
        layout.addView(btn)

        val text = TextView(this).apply { 
            text = "请勾选你要放在通知栏的应用\n（受安卓原生通知栏限制，最多只能显示前 3 个）："
            textSize = 16f
            setPadding(0, 30, 0, 20) 
        }
        layout.addView(text)

        val listView = ListView(this)
        layout.addView(listView)
        setContentView(layout)

        // 3. 获取手机里安装的所有 App
        val pm = packageManager
        val intent = Intent(Intent.ACTION_MAIN, null).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(intent, 0)
        
        val prefs = getSharedPreferences("QuickPrefs", Context.MODE_PRIVATE)
        val appNames = apps.map { it.loadLabel(pm).toString() }
        val adapter = ArrayAdapter(this, android.R.layout.simple_list_item_multiple_choice, appNames)
        listView.adapter = adapter
        listView.choiceMode = ListView.CHOICE_MODE_MULTIPLE

        // 4. 读取之前打钩的记录
        apps.forEachIndexed { index, resolveInfo ->
            if (prefs.getBoolean(resolveInfo.activityInfo.packageName, false)) {
                listView.setItemChecked(index, true)
            }
        }

        // 5. 打钩时保存记录
        listView.setOnItemClickListener { _, _, position, _ ->
            val pkg = apps[position].activityInfo.packageName
            val isChecked = listView.isItemChecked(position)
            prefs.edit().putBoolean(pkg, isChecked).apply()
        }
    }
}

class QuickService : Service() {
    override fun onBind(intent: Intent?) = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel("quick_id", "快捷启动", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, "quick_id")
        } else {
            Notification.Builder(this)
        }
        
        builder.setSmallIcon(android.R.drawable.star_on)
               .setContentTitle("快捷启动栏")
               .setContentText("点击下方文字直接打开应用")
               .setOngoing(true) // 设为常驻，防滑动清除

        val pm = packageManager
        val prefs = getSharedPreferences("QuickPrefs", Context.MODE_PRIVATE)
        val allApps = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN, null).addCategory(Intent.CATEGORY_LAUNCHER), 0)
        
        var count = 0
        for (app in allApps) {
            val pkg = app.activityInfo.packageName
            // 把勾选的 App 加到通知栏按钮里（系统原生限制最多3个按钮）
            if (prefs.getBoolean(pkg, false) && count < 3) { 
                val launchIntent = pm.getLaunchIntentForPackage(pkg)
                if (launchIntent != null) {
                    val pi = PendingIntent.getActivity(this, count, launchIntent, PendingIntent.FLAG_IMMUTABLE)
                    builder.addAction(android.R.drawable.ic_menu_send, app.loadLabel(pm).toString(), pi)
                    count++
                }
            }
        }

        startForeground(1, builder.build())
        return START_STICKY
    }
}
