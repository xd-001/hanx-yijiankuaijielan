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
    private val hiddenApps = mutableSetOf<String>() 
    private var columnsPerRow = 6
    private var currentIconType = 0 
    private lateinit var allApps: List<ResolveInfo>
    private lateinit var previewContainer: LinearLayout
    private lateinit var selectedListContainer: LinearLayout

    private val iconNames = arrayOf("🫥 真正透明隐形 (黑科技推荐)", "⚙️ 系统圆环 (最强伪装)", "🔸 菱形小点", "❗ 提示感叹号", "🤖 默认应用图标")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }

        val pm = packageManager
        allApps = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN, null).addCategory(Intent.CATEGORY_LAUNCHER), 0)
        val prefs = getSharedPreferences("QuickPrefs", Context.MODE_PRIVATE)
        
        columnsPerRow = prefs.getInt("columns", 6)
        currentIconType = prefs.getInt("icon_type", 0) 
        val savedApps = prefs.getString("selected_apps", "") ?: ""
        if (savedApps.isNotEmpty()) {
            selectedApps.addAll(savedApps.split(","))
        }
        
        val savedHidden = prefs.getString("hidden_apps", "") ?: ""
        if (savedHidden.isNotEmpty()) {
            hiddenApps.addAll(savedHidden.split(","))
        }

        val rootScroll = ScrollView(this)
        val mainLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(40, 40, 40, 80) }

        val btnStart = Button(this).apply {
            text = "🚀 保存并开启/刷新通知栏"
            textSize = 16f
            setPadding(0, 30, 0, 30)
            setBackgroundColor(Color.parseColor("#4CAF50"))
            setTextColor(Color.WHITE)
            setOnClickListener {
                val intent = Intent(this@MainActivity, QuickService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
                Toast.makeText(this@MainActivity, "已生效！(已开启强制置顶防掉落)", Toast.LENGTH_SHORT).show()
                moveTaskToBack(true)
                finish()
            }
        }
        mainLayout.addView(btnStart)

        val btnStop = Button(this).apply {
            text = "🛑 不想用了，一键关闭通知"
            textSize = 16f
            setPadding(0, 30, 0, 30)
            setBackgroundColor(Color.parseColor("#F44336"))
            setTextColor(Color.WHITE)
            val params = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            params.setMargins(0, 20, 0, 0)
            layoutParams = params
            setOnClickListener {
                stopService(Intent(this@MainActivity, QuickService::class.java))
                getSystemService(NotificationManager::class.java).cancel(1)
                Toast.makeText(this@MainActivity, "已彻底关闭并清理！", Toast.LENGTH_SHORT).show()
            }
        }
        mainLayout.addView(btnStop)

        mainLayout.addView(TextView(this).apply { text = "👀 1:1 真实预览图："; textSize = 15f; setPadding(0, 40, 0, 10); setTextColor(Color.GRAY) })
        previewContainer = LinearLayout(this).apply { 
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#F0F0F0")) 
        }
        mainLayout.addView(previewContainer)

        mainLayout.addView(TextView(this).apply { text = "🎭 防社死：左上角通知图标伪装"; textSize = 15f; setPadding(0, 40, 0, 10); setTextColor(Color.GRAY) })
        val iconSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, iconNames)
            setSelection(currentIconType)
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    currentIconType = position
                    saveData()
                }
                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
        }
        mainLayout.addView(iconSpinner)

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

        val btnHideApps = Button(this).apply {
            text = "🚫 把不想看到的 App 关进黑名单"
            setTextColor(Color.parseColor("#666666"))
            setBackgroundColor(Color.parseColor("#E0E0E0"))
            val params = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            params.setMargins(0, 20, 0, 20)
            layoutParams = params
            setOnClickListener { showHideAppsDialog() }
        }
        mainLayout.addView(btnHideApps)

        val btnAdd = Button(this).apply {
            text = "➕ 打开网格极速多选"
            setPadding(0, 20, 0, 20)
            setOnClickListener { showGridAddDialog() }
        }
        mainLayout.addView(btnAdd)
        
        mainLayout.addView(TextView(this).apply { text = "📝 已选队列 (上移/下移/删除)："; textSize = 15f; setPadding(0, 40, 0, 10); setTextColor(Color.GRAY) })

        selectedListContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 20, 0, 0) }
        mainLayout.addView(selectedListContainer)

        rootScroll.addView(mainLayout)
        setContentView(rootScroll)
        refreshUI()
    }

    private fun showGridAddDialog() {
        val pm = packageManager
        val displayApps = allApps.filter { !hiddenApps.contains(it.activityInfo.packageName) }
        val tempSelectedPkgs = mutableSetOf<String>().apply { addAll(selectedApps) }

        val tvCounter = TextView(this).apply {
            text = "✨ 连点选择：已选 ${tempSelectedPkgs.size} / ${displayApps.size} 个"
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(0, 30, 0, 20)
            setTextColor(Color.parseColor("#4CAF50"))
            setTypeface(null, android.graphics.Typeface.BOLD)
        }

        val gridView = GridView(this).apply {
            numColumns = 4 
            verticalSpacing = 20
            horizontalSpacing = 10
            setPadding(20, 0, 20, 20)
        }

        val adapter = object : BaseAdapter() {
            override fun getCount() = displayApps.size
            override fun getItem(pos: Int) = displayApps[pos]
            override fun getItemId(pos: Int) = pos.toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
                val view = convertView ?: layoutInflater.inflate(R.layout.item_grid_app, parent, false)
                val app = displayApps[position]
                val pkg = app.activityInfo.packageName

                val imgIcon = view.findViewById<ImageView>(R.id.img_icon)
                val tvName = view.findViewById<TextView>(R.id.tv_name)
                val mask = view.findViewById<FrameLayout>(R.id.mask_checked)

                imgIcon.setImageDrawable(app.loadIcon(pm))
                tvName.text = app.loadLabel(pm)
                
                if (tempSelectedPkgs.contains(pkg)) {
                    mask.visibility = View.VISIBLE
                    mask.setBackgroundColor(Color.parseColor("#884CAF50")) 
                } else {
                    mask.visibility = View.GONE
                }
                return view
            }
        }
        gridView.adapter = adapter

        gridView.setOnItemClickListener { _, view, position, _ ->
            val pkg = displayApps[position].activityInfo.packageName
            val mask = view.findViewById<FrameLayout>(R.id.mask_checked)
            if (tempSelectedPkgs.contains(pkg)) {
                tempSelectedPkgs.remove(pkg)
                mask.visibility = View.GONE
            } else {
                tempSelectedPkgs.add(pkg)
                mask.visibility = View.VISIBLE
                mask.setBackgroundColor(Color.parseColor("#884CAF50"))
            }
            tvCounter.text = "✨ 连点选择：已选 ${tempSelectedPkgs.size} / ${displayApps.size} 个"
        }

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(tvCounter)
            addView(gridView)
        }

        AlertDialog.Builder(this)
            .setView(layout)
            .setPositiveButton("确认保存") { _, _ ->
                selectedApps.clear()
                selectedApps.addAll(tempSelectedPkgs)
                saveData()
                refreshUI()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showHideAppsDialog() {
        val pm = packageManager
        val displayApps = allApps
        val tempHiddenPkgs = mutableSetOf<String>().apply { addAll(hiddenApps) }

        val tvCounter = TextView(this).apply {
            text = "🚫 点击加入黑名单：已屏蔽 ${tempHiddenPkgs.size} 个"
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(0, 30, 0, 20)
            setTextColor(Color.parseColor("#F44336")) 
            setTypeface(null, android.graphics.Typeface.BOLD)
        }

        val gridView = GridView(this).apply {
            numColumns = 4 
            verticalSpacing = 20
            horizontalSpacing = 10
            setPadding(20, 0, 20, 20)
        }

        val adapter = object : BaseAdapter() {
            override fun getCount() = displayApps.size
            override fun getItem(pos: Int) = displayApps[pos]
            override fun getItemId(pos: Int) = pos.toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
                val view = convertView ?: layoutInflater.inflate(R.layout.item_grid_app, parent, false)
                val app = displayApps[position]
                val pkg = app.activityInfo.packageName

                val imgIcon = view.findViewById<ImageView>(R.id.img_icon)
                val tvName = view.findViewById<TextView>(R.id.tv_name)
                val mask = view.findViewById<FrameLayout>(R.id.mask_checked)

                imgIcon.setImageDrawable(app.loadIcon(pm))
                tvName.text = app.loadLabel(pm)
                
                if (tempHiddenPkgs.contains(pkg)) {
                    mask.visibility = View.VISIBLE
                    mask.setBackgroundColor(Color.parseColor("#88FF0000")) 
                } else {
                    mask.visibility = View.GONE
                }
                return view
            }
        }
        gridView.adapter = adapter

        gridView.setOnItemClickListener { _, view, position, _ ->
            val pkg = displayApps[position].activityInfo.packageName
            val mask = view.findViewById<FrameLayout>(R.id.mask_checked)
            if (tempHiddenPkgs.contains(pkg)) {
                tempHiddenPkgs.remove(pkg)
                mask.visibility = View.GONE
            } else {
                tempHiddenPkgs.add(pkg)
                mask.visibility = View.VISIBLE
                mask.setBackgroundColor(Color.parseColor("#88FF0000"))
            }
            tvCounter.text = "🚫 点击加入黑名单：已屏蔽 ${tempHiddenPkgs.size} 个"
        }

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(tvCounter)
            addView(gridView)
        }

        AlertDialog.Builder(this)
            .setView(layout)
            .setPositiveButton("确认保存黑名单") { _, _ ->
                hiddenApps.clear()
                hiddenApps.addAll(tempHiddenPkgs)
                selectedApps.removeAll(hiddenApps)
                saveData()
                refreshUI()
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
                setPadding(dpToPx(3f), dpToPx(3f), dpToPx(3f), dpToPx(3f))
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
        getSharedPreferences("QuickPrefs", Context.MODE_PRIVATE).edit()
            .putInt("columns", columnsPerRow)
            .putInt("icon_type", currentIconType)
            .putString("selected_apps", selectedApps.joinToString(","))
            .putString("hidden_apps", hiddenApps.joinToString(",")) 
            .apply()
    }
}

// ================= 服务端核心代码 =================
class QuickService : Service() {
    override fun onBind(intent: Intent?) = null

    override fun onDestroy() {
        super.onDestroy()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            stopForeground(true)
        }
        getSystemService(NotificationManager::class.java).cancel(1)
    }

    private fun drawableToBitmap(drawable: Drawable): Bitmap {
        val safeSize = 80 
        val bitmap = Bitmap.createBitmap(safeSize, safeSize, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, safeSize, safeSize)
        drawable.draw(canvas)
        return bitmap
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val manager = getSystemService(NotificationManager::class.java)
        val prefs = getSharedPreferences("QuickPrefs", Context.MODE_PRIVATE)
        val currentIconType = prefs.getInt("icon_type", 0) 
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel("quick_top_v2", "置顶快捷通知", NotificationManager.IMPORTANCE_HIGH)
            channel.setShowBadge(false)
            channel.setSound(null, null) 
            channel.enableVibration(false)
            manager.createNotificationChannel(channel)
        }

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(this, "quick_top_v2") else Notification.Builder(this)
        
        // 💥 终极防拦截：现场纯代码手绘图标，完美骗过国产系统的审查！
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && currentIconType != 4) {
            // 创建一张被系统认可的透明底画板
            val bmp = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            val paint = android.graphics.Paint().apply {
                isAntiAlias = true
                color = Color.WHITE 
                style = android.graphics.Paint.Style.FILL
            }

            when (currentIconType) {
                0 -> { /* 透明隐形：什么都不画，交一张完全空白合法的白卷！*/ }
                1 -> { // 圆环伪装
                    canvas.drawCircle(50f, 50f, 35f, paint)
                    paint.xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.CLEAR)
                    canvas.drawCircle(50f, 50f, 15f, paint) 
                }
                2 -> { // 菱形伪装
                    canvas.save()
                    canvas.rotate(45f, 50f, 50f)
                    canvas.drawRect(30f, 30f, 70f, 70f, paint)
                    canvas.restore()
                }
                3 -> { // 提示符感叹号
                    canvas.drawRect(42f, 15f, 58f, 65f, paint)
                    canvas.drawRect(42f, 75f, 58f, 90f, paint)
                }
            }
            builder.setSmallIcon(android.graphics.drawable.Icon.createWithBitmap(bmp))
        } else {
            builder.setSmallIcon(android.R.drawable.sym_def_app_icon) // 选了默认图标
        }

        builder.setContentTitle(" ")
               .setContentText(" ")
               .setShowWhen(false)
               .setOngoing(true)
               .setCategory(Notification.CATEGORY_SERVICE) 
               .setSortKey("0000") 

        startForeground(1, builder.build())

        Thread {
            val pm = packageManager
            val columns = prefs.getInt("columns", 6)
            val savedApps = prefs.getString("selected_apps", "") ?: ""
            val selectedPkgs = if (savedApps.isNotEmpty()) savedApps.split(",") else emptyList()

            val remoteViews = RemoteViews(packageName, R.layout.layout_notification)
            
            for (r in 0..1) {
                val rowId = resources.getIdentifier("row$r", "id", packageName)
                if (rowId != 0) {
                    remoteViews.setViewVisibility(rowId, View.GONE)
                }
            }

            for (i in selectedPkgs.indices) {
                if (i >= 20) break 
                val row = i / columns 
                val col = i % columns 
                val slotIndex = row * 10 + col 
                
                val launchIntent = pm.getLaunchIntentForPackage(selectedPkgs[i])
                if (launchIntent != null && slotIndex < 20) {
                    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    val pi = PendingIntent.getActivity(this, i, launchIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
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

            builder.setCustomContentView(remoteViews) 
                   .setCustomBigContentView(remoteViews) 
            
            manager.notify(1, builder.build())
        }.start()

        return START_STICKY
    }
}
