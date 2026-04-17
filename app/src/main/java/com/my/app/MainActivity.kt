package com.my.app

import android.Manifest
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 40, 40, 40)
        }

        val btn = Button(this).apply {
            text = "✨ 启动/刷新 并隐藏界面 ✨"
            textSize = 18f
            setPadding(0, 20, 0, 20)
            setOnClickListener {
                val intent = Intent(this@MainActivity, QuickService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(intent)
                } else {
                    startService(intent)
                }
                Toast.makeText(this@MainActivity, "已生效！App已隐藏至后台", Toast.LENGTH_SHORT).show()
                
                // 关键两行代码：点击后瞬间退到后台并关闭界面
                moveTaskToBack(true)
                finish()
            }
        }
        layout.addView(btn)

        val text = TextView(this).apply { 
            text = "请勾选你要放在通知栏的应用\n（选完后点击上方按钮即可）："
            textSize = 15f
            setPadding(0, 30, 0, 20) 
        }
        layout.addView(text)

        val listView = ListView(this)
        layout.addView(listView)
        setContentView(layout)

        val pm = packageManager
        val intent = Intent(Intent.ACTION_MAIN, null).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(intent, 0)
        
        val prefs = getSharedPreferences("QuickPrefs", Context.MODE_PRIVATE)
        val appNames = apps.map { it.loadLabel(pm).toString() }
        val adapter = ArrayAdapter(this, android.R.layout.simple_list_item_multiple_choice, appNames)
        listView.adapter = adapter
        listView.choiceMode = ListView.CHOICE_MODE_MULTIPLE

        apps.forEachIndexed { index, resolveInfo ->
            if (prefs.getBoolean(resolveInfo.activityInfo.packageName, false)) {
                listView.setItemChecked(index, true)
            }
        }

        listView.setOnItemClickListener { _, _, position, _ ->
            val pkg = apps[position].activityInfo.packageName
            val isChecked = listView.isItemChecked(position)
            prefs.edit().putBoolean(pkg, isChecked).apply()
        }
    }
}

class QuickService : Service() {
    override fun onBind(intent: Intent?) = null

    private fun drawableToBitmap(drawable: Drawable): Bitmap {
        if (drawable is BitmapDrawable && drawable.bitmap != null) {
            return drawable.bitmap
        }
        val bitmap = Bitmap.createBitmap(
            if (drawable.intrinsicWidth > 0) drawable.intrinsicWidth else 100,
            if (drawable.intrinsicHeight > 0) drawable.intrinsicHeight else 100,
            Bitmap.Config.ARGB_8888
        )
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, canvas.width, canvas.height)
        drawable.draw(canvas)
        return bitmap
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel("quick_id", "快捷启动", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }

        val pm = packageManager
        val prefs = getSharedPreferences("QuickPrefs", Context.MODE_PRIVATE)
        val allApps = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN, null).addCategory(Intent.CATEGORY_LAUNCHER), 0)
        
        val remoteViews = RemoteViews(packageName, R.layout.layout_notification)
        val iconIds = arrayOf(R.id.icon0, R.id.icon1, R.id.icon2, R.id.icon3, R.id.icon4, R.id.icon5, R.id.icon6, R.id.icon7, R.id.icon8, R.id.icon9)
        var count = 0

        for (app in allApps) {
            val pkg = app.activityInfo.packageName
            if (prefs.getBoolean(pkg, false) && count < 10) { 
                val launchIntent = pm.getLaunchIntentForPackage(pkg)
                if (launchIntent != null) {
                    val pi = PendingIntent.getActivity(this, count, launchIntent, PendingIntent.FLAG_IMMUTABLE)
                    val iconDrawable = app.loadIcon(pm)
                    val bitmap = drawableToBitmap(iconDrawable)
                    
                    remoteViews.setImageViewBitmap(iconIds[count], bitmap)
                    remoteViews.setViewVisibility(iconIds[count], View.VISIBLE)
                    remoteViews.setOnClickPendingIntent(iconIds[count], pi)
                    count++
                }
            }
        }

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, "quick_id")
        } else {
            Notification.Builder(this)
        }
        
        builder.setSmallIcon(android.R.drawable.star_on)
               .setOngoing(true)
               .setCustomContentView(remoteViews)

        startForeground(1, builder.build())
        return START_STICKY
    }
}
