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
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private val selectedApps = mutableListOf<String>()
    private var columnsPerRow = 6
    private lateinit var allApps: List<ResolveInfo>
    private lateinit var previewContainer: LinearLayout
    private lateinit var selectedListContainer: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }

        val pm = packageManager
        allApps = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN, null).addCategory(Intent.CATEGORY_LAUNCHER), 0)
        val prefs = getSharedPreferences("QuickPrefs", Context.MODE_PRIVATE)
        
        columnsPerRow = prefs.getInt("columns", 6)
        val savedApps = prefs.getString("selected_apps", "") ?: ""
        if (savedApps.isNotEmpty()) {
            selectedApps.addAll(savedApps.split(","))
        }

        val rootScroll = ScrollView(this)
        val mainLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(40, 40, 40, 80) }

        val btnStart = Button(this).apply {
            text = "🚀 保存并刷新通知栏"
            textSize = 18f
            setPadding(0, 30, 0, 30)
            setBackgroundColor(Color.parseColor("#4CAF50"))
            setTextColor(Color.WHITE)
            setOnClickListener {
                val intent = Intent(this@MainActivity, QuickService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
                Toast.makeText(this@MainActivity, "已生效！", Toast.LENGTH_SHORT).show()
                moveTaskToBack(true)
                finish()
            }
        }
        mainLayout.addView(btnStart)

        mainLayout.addView(TextView(this).apply { text = "👀 1:1 真实预览图："; textSize = 15f; setPadding(0, 40, 0, 10); setTextColor(Color.GRAY) })
        previewContainer = LinearLayout(this).apply { 
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#F0F0F0")) 
        }
        mainLayout.addView(previewContainer)

        mainLayout.addView(TextView(this).apply { text = "⚙️ 每行显示几个图标？ (4 ~ 10个)"; textSize = 14f; setPadding(0, 40, 0, 10); setTextColor(Color.GRAY) })
        val seekBar = SeekBar(this).apply {
            max = 6 
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

        val btnAdd = Button(this).apply {
            text = "➕ 打开网格极速多选"
            setPadding(0, 20, 0, 20)
            setOnClickListener { showGridAddDialog() }
        }
        mainLayout.addView(TextView(this).apply { text = "📝 已选队列 (上移/下移/删除)："; textSize = 15f; setPadding(0, 40, 0, 10); setTextColor(Color.GRAY) })
        mainLayout.addView(btnAdd)

        selectedListContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 20, 0, 0) }
        mainLayout.addView(selectedListContainer)

        rootScroll.addView(mainLayout)
        setContentView(rootScroll)
        refreshUI()
    }

    // 核心升级：精美的网格多选器！
    private fun showGridAddDialog() {
        val pm = packageManager
        
        // 动态构建一个网格视图
        val gridView = GridView(this).apply {
            numColumns = 4 // 一行展示 4 个 App，找起来极快
            verticalSpacing = 20
            horizontalSpacing = 10
            setPadding(20, 20, 20, 20)
        }

        // 把还没有选中的 App 过滤出来
        val unselectedApps = allApps.filter { !selectedApps.contains(it.activityInfo.packageName) }
        
        // 临时记录本次弹出框里用户点击打钩了哪些
        val tempSelectedPkgs = mutableSetOf<String>()

        val adapter = object : BaseAdapter() {
            override fun getCount() = unselectedApps.size
            override fun getItem(pos: Int) = unselectedApps[pos]
            override fun getItemId(pos: Int) = pos.toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
                val view = convertView ?: layoutInflater.inflate(R.layout.item_grid_app, parent, false)
                val app = unselectedApps[position]
                val pkg = app.activityInfo.packageName

                val imgIcon = view.findViewById<ImageView>(R.id.img_icon)
                val tvName = view.findViewById<TextView>(R.id.tv_name)
                val mask = view.findViewById<FrameLayout>(R.id.mask_checked)

                imgIcon.setImageDrawable(app.loadIcon(pm))
                tvName.text = app.loadLabel(pm)
                
                // 如果用户刚刚点击了它，显示绿色遮罩
                mask.visibility = if (tempSelectedPkgs.contains(pkg)) View.VISIBLE else View.GONE
                return view
            }
        }
        gridView.adapter = adapter

        // 点击网格图标时的交互
        gridView.setOnItemClickListener { _, view, position, _ ->
            val pkg = unselectedApps[position].activityInfo.packageName
            val mask = view.findViewById<FrameLayout>(R.id.mask_checked)
            if (tempSelectedPkgs.contains(pkg)) {
                tempSelectedPkgs.remove(pkg)
                mask.visibility = View.GONE
            } else {
                tempSelectedPkgs.add(pkg)
                mask.visibility = View.VISIBLE
            }
        }

        // 弹窗装载网格
        AlertDialog.Builder(this)
            .setTitle("像点泡泡纸一样连点你想加的 App：")
            .setView(gridView)
            .setPositiveButton("确认添加") { _, _ ->
                if (tempSelectedPkgs.isNotEmpty()) {
                    selectedApps.addAll(tempSelectedPkgs)
                    saveData()
                    refreshUI()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun dpToPx(dp: Float): Int = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, resources.displayMetrics).toInt()

    private fun refreshUI() {
        val pm = packageManager
        previewContainer.removeAllViews()
        var currentRow: LinearLayout? = null
        
        for (i in selectedApps.indices) {
            if (i % columnsPerRow == 0) {
                currentRow = LinearLayout(this).apply { 
                    orientation = LinearLayout.HORIZONTAL
                    weightSum = columnsPerRow.toFloat()
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(48f))
                }
                previewContainer.addView(currentRow)
            }
            val app = allApps.find { it.activityInfo.packageName == selectedApps[i] }
            val iconView = ImageView(this).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
                setPadding(dpToPx(2f), dpToPx(2f), dpToPx(2f), dpToPx(2f))
                scaleType = ImageView.ScaleType.FIT_CENTER
                app?.let { setImageDrawable(it.loadIcon(pm)) }
            }
            currentRow?.addView(iconView)
        }

        selectedListContainer.removeAllViews()
        for (i in selectedApps.indices) {
            val app = allApps.find { it.activityInfo.packageName == selectedApps[i] }
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, 15, 0, 15) }
            row.addView(ImageView(this).apply { layoutParams = LinearLayout.LayoutParams(100, 100); app?.let { setImageDrawable(it.loadIcon(pm)) } })
            row.addView(TextView(this).apply { text = app?.loadLabel(pm) ?: "未知"; textSize = 16f; setPadding(30, 0, 0, 0); layoutParams = LinearLayout.LayoutParams(0, -2, 1f) })
            row.addView(Button(this).apply { text = "↑"; layoutParams = LinearLayout.LayoutParams(120, -2); isEnabled = i > 0; setOnClickListener { swap(i, i - 1) } })
            row.addView(Button(this).apply { text = "↓"; layoutParams = LinearLayout.LayoutParams(120, -2); isEnabled = i < selectedApps.size - 1; setOnClickListener { swap(i, i + 1) } })
            row.addView(Button(this).apply { text = "X"; setTextColor(Color.RED); layoutParams = LinearLayout.LayoutParams(120, -2); setOnClickListener { selectedApps.removeAt(i); saveData(); refreshUI() } })
            selectedListContainer.addView(row)
        }
    }

    private fun swap(i: Int, j: Int) {
        val temp = selectedApps[i]; selectedApps[i] = selectedApps[j]; selectedApps[j] = temp; saveData(); refreshUI()
    }

    private fun saveData() {
        getSharedPreferences("QuickPrefs", Context.MODE_PRIVATE).edit().putInt("columns", columnsPerRow).putString("selected_apps", selectedApps.joinToString(",")).apply()
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
            val channel = NotificationChannel("quick_id", " ", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }

        val pm = packageManager
        val prefs = getSharedPreferences("QuickPrefs", Context.MODE_PRIVATE)
        val columns = prefs.getInt("columns", 6)
        val savedApps = prefs.getString("selected_apps", "") ?: ""
        val selectedPkgs = if (savedApps.isNotEmpty()) savedApps.split(",") else emptyList()

        val remoteViews = RemoteViews(packageName, R.layout.layout_notification)
        
        for (r in 0..4) {
            val rowId = resources.getIdentifier("row$r", "id", packageName)
            if (rowId != 0) remoteViews.setViewVisibility(rowId, View.GONE)
        }

        for (i in selectedPkgs.indices) {
            if (i >= 50) break 
            val row = i / columns 
            val col = i % columns 
            val slotIndex = row * 10 + col 
            
            val launchIntent = pm.getLaunchIntentForPackage(selectedPkgs[i])
            if (launchIntent != null && slotIndex < 50) {
                val pi = PendingIntent.getActivity(this, i, launchIntent, PendingIntent.FLAG_IMMUTABLE)
                try {
                    val bitmap = drawableToBitmap(pm.getApplicationInfo(selectedPkgs[i], 0).loadIcon(pm))
                    val resId = resources.getIdentifier("icon$slotIndex", "id", packageName)
                    val rowId = resources.getIdentifier("row$row", "id", packageName)
                    
                    if (resId != 0) {
                        remoteViews.setViewVisibility(rowId, View.VISIBLE)
                        remoteViews.setImageViewBitmap(resId, bitmap)
                        remoteViews.setViewVisibility(resId, View.VISIBLE)
                        remoteViews.setOnClickPendingIntent(resId, pi)
                    }
                } catch (e: Exception) {}
            }
        }

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(this, "quick_id") else Notification.Builder(this)
        
        builder.setSmallIcon(android.R.color.transparent)
               .setContentTitle(" ")
               .setContentText(" ")
               .setShowWhen(false)
               .setOngoing(true)
               .setCustomContentView(remoteViews)
               .setCustomBigContentView(remoteViews)

        startForeground(1, builder.build())
        return START_STICKY
    }
}
