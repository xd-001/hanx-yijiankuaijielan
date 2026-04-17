package com.my.app

import android.Manifest
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
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
            text = "✨ 启动并隐藏界面 ✨"
            textSize = 18f
            setPadding(0, 20, 0, 20)
            setOnClickListener {
                val intent = Intent(this@MainActivity, QuickService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(intent)
                } else {
                    startService(intent)
                }
                Toast.makeText(this@MainActivity, "已生效！下拉通知栏查看", Toast.LENGTH_SHORT).show()
                this@MainActivity.moveTaskToBack(true)
                this@MainActivity.finish()
            }
        }
        layout.addView(btn)

        val text = TextView(this).apply { 
            text = "请勾选常用应用（最多支持 30 个）："
            textSize = 15f
            setPadding(0, 30, 0, 20) 
        }
        layout.addView(text)

        val listView = ListView(this)
        layout.addView(listView)
        setContentView(layout)

        val pm = packageManager
        val apps = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN, null).addCategory(Intent.CATEGORY_LAUNCHER), 0)
        val prefs = getSharedPreferences("QuickPrefs", Context.MODE_PRIVATE)

        // 核心升级：带有图标的自定义列表适配器！
        val adapter = object : BaseAdapter() {
            override fun getCount() = apps.size
            override fun getItem(pos: Int) = apps[pos]
            override fun getItemId(pos: Int) = pos.toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
                val row = (convertView as? LinearLayout) ?: LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(20, 30, 20, 30)
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    
                    addView(ImageView(this@MainActivity).apply { id = 1001; layoutParams = LinearLayout.LayoutParams(100, 100) })
                    addView(TextView(this@MainActivity).apply { id = 1002; textSize = 16f; setPadding(40, 0, 0, 0); layoutParams = LinearLayout.LayoutParams(0, -2, 1f) })
                    addView(CheckBox(this@MainActivity).apply { id = 1003; isClickable = false })
                }
                val app = apps[position]
                val pkg = app.activityInfo.packageName
                
                row.findViewById<ImageView>(1001).setImageDrawable(app.loadIcon(pm))
                row.findViewById<TextView>(1002).text = app.loadLabel(pm)
                row.findViewById<CheckBox>(1003).isChecked = prefs.getBoolean(pkg, false)
                return row
            }
        }
        listView.adapter = adapter
        
        listView.setOnItemClickListener { _, view, position, _ ->
            val pkg = apps[position].activityInfo.packageName
            val isChecked = !prefs.getBoolean(pkg, false)
            prefs.edit().putBoolean(pkg, isChecked).apply()
            view.findViewById<CheckBox>(1003).isChecked = isChecked
        }
    }
}

class QuickService : Service() {
    override fun onBind(intent: Intent?) = null

    private fun drawableToBitmap(drawable: Drawable): Bitmap {
        if (drawable is BitmapDrawable && drawable.bitmap != null) return drawable.bitmap
        val bitmap = Bitmap.createBitmap(if (drawable.intrinsicWidth > 0) drawable.intrinsicWidth else 100, if (drawable.intrinsicHeight > 0) drawable.intrinsicHeight else 100, Bitmap.Config.ARGB_8888)
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
        var count = 0

        for (app in allApps) {
            val pkg = app.activityInfo.packageName
            // 扩容到了 30 个！
            if (prefs.getBoolean(pkg, false) && count < 30) { 
                val launchIntent = pm.getLaunchIntentForPackage(pkg)
                if (launchIntent != null) {
                    val pi = PendingIntent.getActivity(this, count, launchIntent, PendingIntent.FLAG_IMMUTABLE)
                    val bitmap = drawableToBitmap(app.loadIcon(pm))
                    
                    // 动态获取 XML 里那 30 个图标的位置
                    val resId = resources.getIdentifier("icon$count", "id", packageName)
                    if (resId != 0) {
                        remoteViews.setImageViewBitmap(resId, bitmap)
                        remoteViews.setViewVisibility(resId, View.VISIBLE)
                        remoteViews.setOnClickPendingIntent(resId, pi)
                        count++
                    }
                }
            }
        }

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, "quick_id")
        } else {
            Notification.Builder(this)
        }
        
        builder.setSmallIcon(android.R.color.transparent) // 核心：使用透明颜色替代难看的星星图标！
               .setContentTitle("") // 核心：清空自带的标题
               .setOngoing(true)
               .setCustomContentView(remoteViews) // 默认折叠视图
               .setCustomBigContentView(remoteViews) // 核心：展开大视图（有了这个才能显示 3 行！）

        startForeground(1, builder.build())
        return START_STICKY
    }
}
