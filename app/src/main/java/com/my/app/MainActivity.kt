package com.my.app

import android.Manifest
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private val selectedApps = mutableListOf<String>()
    private var columnsPerRow = 6
    private lateinit var allApps: List<ResolveInfo>
    
    // UI 控件
    private lateinit var previewContainer: LinearLayout
    private lateinit var selectedListContainer: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }

        // 加载数据
        val pm = packageManager
        allApps = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN, null).addCategory(Intent.CATEGORY_LAUNCHER), 0)
        val prefs = getSharedPreferences("QuickPrefs", Context.MODE_PRIVATE)
        
        columnsPerRow = prefs.getInt("columns", 6)
        val savedApps = prefs.getString("selected_apps", "") ?: ""
        if (savedApps.isNotEmpty()) {
            selectedApps.addAll(savedApps.split(","))
        }

        // --- 开始构建超强控制台界面 ---
        val rootScroll = ScrollView(this)
        val mainLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(40, 40, 40, 80) }

        // 1. 顶部启动按钮
        val btnStart = Button(this).apply {
            text = "🚀 保存并刷新通知栏 (点我生效)"
            textSize = 18f
            setPadding(0, 30, 0, 30)
            setBackgroundColor(Color.parseColor("#4CAF50"))
            setTextColor(Color.WHITE)
            setOnClickListener {
                val intent = Intent(this@MainActivity, QuickService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
                Toast.makeText(this@MainActivity, "已生效！下拉通知栏查看，App已隐藏", Toast.LENGTH_SHORT).show()
                moveTaskToBack(true)
                finish()
            }
        }
        mainLayout.addView(btnStart)

        // 2. 实时预览区
        mainLayout.addView(TextView(this).apply { text = "👀 实时预览图 (通知栏长这样)："; textSize = 16f; setPadding(0, 40, 0, 10); setTextColor(Color.GRAY) })
        previewContainer = LinearLayout(this).apply { 
            orientation = LinearLayout.VERTICAL
            setPadding(20, 20, 20, 20)
            setBackgroundColor(Color.parseColor("#E0E0E0")) // 灰色背景模拟通知栏
        }
        mainLayout.addView(previewContainer)

        // 3. 列数调节滑块
        mainLayout.addView(TextView(this).apply { text = "⚙️ 每行显示几个图标？ (4 ~ 10个)"; textSize = 16f; setPadding(0, 40, 0, 10); setTextColor(Color.GRAY) })
        val seekBar = SeekBar(this).apply {
            max = 6 // 范围 0~6，代表 4~10
            progress = columnsPerRow - 4
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    columnsPerRow = progress + 4
                    saveData()
                    refreshUI()
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }
        mainLayout.addView(seekBar)

        // 4. 添加应用按钮
        val btnAdd = Button(this).apply {
            text = "➕ 添加应用到列表"
            setPadding(0, 20, 0, 20)
            setOnClickListener { showAddDialog() }
        }
        mainLayout.addView(TextView(this).apply { text = "📝 自由排序列队 (上移/下移/删除)："; textSize = 16f; setPadding(0, 40, 0, 10); setTextColor(Color.GRAY) })
        mainLayout.addView(btnAdd)

        // 5. 选中的应用列表 (可排序)
        selectedListContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 20, 0, 0) }
        mainLayout.addView(selectedListContainer)

        rootScroll.addView(mainLayout)
        setContentView(rootScroll)

        // 初始化界面
        refreshUI()
    }

    // 弹出选择器
    private fun showAddDialog() {
        val pm = packageManager
        // 过滤掉已经选过的，只显示还没选的
        val unselectedApps = allApps.filter { !selectedApps.contains(it.activityInfo.packageName) }
        val names = unselectedApps.map { it.loadLabel(pm).toString() }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("请选择要添加的应用")
            .setItems(names) { _, which ->
                selectedApps.add(unselectedApps[which].activityInfo.packageName)
                saveData()
                refreshUI()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // 刷新预览图和排序列表
    private fun refreshUI() {
        val pm = packageManager
        
        // --- 刷新预览区 ---
        previewContainer.removeAllViews()
        var currentRow: LinearLayout? = null
        for (i in selectedApps.indices) {
            if (i % columnsPerRow == 0) {
                currentRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; weightSum = columnsPerRow.toFloat(); setPadding(0, 0, 0, 15) }
                previewContainer.addView(currentRow)
            }
            val pkg = selectedApps[i]
            val app = allApps.find { it.activityInfo.packageName == pkg }
            val iconView = ImageView(this).apply {
                layoutParams = LinearLayout.LayoutParams(0, 120, 1f)
                setPadding(10, 10, 10, 10)
                app?.let { setImageDrawable(it.loadIcon(pm)) }
            }
            currentRow?.addView(iconView)
        }

        // --- 刷新排序列表 ---
        selectedListContainer.removeAllViews()
        for (i in selectedApps.indices) {
            val pkg = selectedApps[i]
            val app = allApps.find { it.activityInfo.packageName == pkg }
            
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, 15, 0, 15)
            }
            // 图标
            row.addView(ImageView(this).apply { layoutParams = LinearLayout.LayoutParams(100, 100); app?.let { setImageDrawable(it.loadIcon(pm)) } })
            // 名字
            row.addView(TextView(this).apply { text = app?.loadLabel(pm) ?: "未知"; textSize = 16f; setPadding(30, 0, 0, 0); layoutParams = LinearLayout.LayoutParams(0, -2, 1f) })
            // 上移按钮
            row.addView(Button(this).apply { text = "↑"; layoutParams = LinearLayout.LayoutParams(120, -2); isEnabled = i > 0; setOnClickListener { swap(i, i - 1) } })
            // 下移按钮
            row.addView(Button(this).apply { text = "↓"; layoutParams = LinearLayout.LayoutParams(120, -2); isEnabled = i < selectedApps.size - 1; setOnClickListener { swap(i, i + 1) } })
            // 删除按钮
            row.addView(Button(this).apply { text = "X"; setTextColor(Color.RED); layoutParams = LinearLayout.LayoutParams(120, -2); setOnClickListener { selectedApps.removeAt(i); saveData(); refreshUI() } })

            selectedListContainer.addView(row)
        }
    }

    private fun swap(i: Int, j: Int) {
        val temp = selectedApps[i]
        selectedApps[i] = selectedApps[j]
        selectedApps[j] = temp
        saveData()
        refreshUI()
    }

    private fun saveData() {
        val prefs = getSharedPreferences("QuickPrefs", Context.MODE_PRIVATE)
        prefs.edit()
            .putInt("columns", columnsPerRow)
            .putString("selected_apps", selectedApps.joinToString(","))
            .apply()
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
        
        val columns = prefs.getInt("columns", 6)
        val savedApps = prefs.getString("selected_apps", "") ?: ""
        val selectedPkgs = if (savedApps.isNotEmpty()) savedApps.split(",") else emptyList()

        val remoteViews = RemoteViews(packageName, R.layout.layout_notification)
        
        // 先把 50 个槽位全部隐藏
        for (i in 0..49) {
            val resId = resources.getIdentifier("icon$i", "id", packageName)
            if (resId != 0) remoteViews.setViewVisibility(resId, View.GONE)
        }

        // 精准计算排版：把 App 塞进对应的槽位里
        for (i in selectedPkgs.indices) {
            if (i >= 50) break // 最多支持 50 个
            
            val pkg = selectedPkgs[i]
            val row = i / columns  // 第几排
            val col = i % columns  // 第几列
            val slotIndex = row * 10 + col // 映射到 XML 里的 icon0 ~ icon49
            
            val launchIntent = pm.getLaunchIntentForPackage(pkg)
            if (launchIntent != null && slotIndex < 50) {
                val pi = PendingIntent.getActivity(this, i, launchIntent, PendingIntent.FLAG_IMMUTABLE)
                
                try {
                    val appInfo = pm.getApplicationInfo(pkg, 0)
                    val bitmap = drawableToBitmap(appInfo.loadIcon(pm))
                    val resId = resources.getIdentifier("icon$slotIndex", "id", packageName)
                    
                    if (resId != 0) {
                        remoteViews.setImageViewBitmap(resId, bitmap)
                        remoteViews.setViewVisibility(resId, View.VISIBLE)
                        remoteViews.setOnClickPendingIntent(resId, pi)
                    }
                } catch (e: Exception) {}
            }
        }

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(this, "quick_id") else Notification.Builder(this)
        
        builder.setSmallIcon(android.R.color.transparent)
               .setContentTitle("")
               .setOngoing(true)
               .setCustomContentView(remoteViews)
               .setCustomBigContentView(remoteViews)

        startForeground(1, builder.build())
        return START_STICKY
    }
}
