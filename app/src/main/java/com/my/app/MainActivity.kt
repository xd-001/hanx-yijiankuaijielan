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
    // 【P2】防社死功能已删除：不再读取/展示 icon_type 选择项。
    // 旧备份里的 icon_type 字段会被忽略，不影响加载（NotifyCore 固定用默认图标）。
    private lateinit var allApps: List<ResolveInfo>
    private lateinit var previewContainer: LinearLayout
    private lateinit var selectedListContainer: LinearLayout
    private lateinit var spinSort: Spinner   // 置顶按钮点击后需要同步切到「置顶优先」档
    private lateinit var rootScroll: ScrollView
    private lateinit var homePage: LinearLayout
    private lateinit var appsPage: LinearLayout
    private lateinit var settingsPage: LinearLayout
    private lateinit var tvStatus: TextView
    private lateinit var tvStatusSub: TextView
    private lateinit var btnPower: TextView
    private lateinit var chartView: UsageChartView
    private lateinit var tvChartNumber: TextView
    private lateinit var tvChartDelta: TextView
    private lateinit var segToday: TextView
    private lateinit var segSeven: TextView
    private lateinit var segAll: TextView
    private lateinit var navMonitor: TextView
    private lateinit var navApps: TextView
    private lateinit var navSettings: TextView
    private var chartRange = 0

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
        
        val savedHidden = prefs.getString("hidden_apps", "") ?: ""
        if (savedHidden.isNotEmpty()) {
            hiddenApps.addAll(savedHidden.split(","))
        }

        rootScroll = ScrollView(this)
        val pageHost = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        homePage = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(40, 32, 40, 110) }
        appsPage = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(40, 32, 40, 110); visibility = View.GONE }
        settingsPage = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(40, 32, 40, 110); visibility = View.GONE }
        pageHost.addView(homePage)
        pageHost.addView(appsPage)
        pageHost.addView(settingsPage)
        rootScroll.addView(pageHost)

        val rootFrame = FrameLayout(this)
        rootFrame.addView(
            rootScroll,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        )
        val bottomNav = buildBottomNav()
        rootFrame.addView(
            bottomNav,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                dpToPx(56f),
                Gravity.BOTTOM
            ).apply {
                leftMargin = dpToPx(28f)
                rightMargin = dpToPx(28f)
                bottomMargin = dpToPx(18f)
            }
        )
        setContentView(rootFrame)

        val mainLayout = settingsPage
        buildHomeDashboard()

        // 【P1】打开记录入口
        val btnRecords = Button(this).apply {
            text = "📖 打开记录 / 上限设置 / 清空"
            setTextColor(Color.parseColor("#333333"))
            setBackgroundColor(Color.parseColor("#E3F2FD"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 20, 0, 0) }
            setOnClickListener { showOpenRecordsDialog() }
        }
        // 记录入口统一放进设置页，放在备份按钮之后

        // 【新增】行为设置区：排序 / 翻页 / 震动
        settingsPage.addView(TextView(this).apply {
            text = "设置"
            textSize = 20f
            setTextColor(Color.parseColor("#1C1C1E"))
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, 8, 0, 18)
        })
        settingsPage.addView(Button(this).apply {
            text = "📱 编辑应用与排序"
            textSize = 15f
            setTextColor(Color.parseColor("#185FA5"))
            setBackgroundColor(Color.parseColor("#E6F1FB"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 12, 0, 22) }
            setOnClickListener { showPage(appsPage) }
        })
        mainLayout.addView(TextView(this).apply {
            text = "⚙️ 行为设置（改完立即生效，不动你的手动队列）"
            textSize = 15f
            setPadding(0, 40, 0, 10)
            setTextColor(Color.GRAY)
        })

        mainLayout.addView(TextView(this).apply {
            text = "🔥 排序方式："
            textSize = 14f
            setPadding(0, 16, 0, 0)
            setTextColor(Color.GRAY)
        })
        spinSort = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity, android.R.layout.simple_spinner_dropdown_item,
                arrayOf(
                    "全部自己排（手动队列顺序）",
                    "全部按打开频率",
                    "第一行定死 + 其余按频率",
                    "📌 置顶优先（队列里点 📌 指定，其余按手动顺序）"
                )
            )
            setSelection(NotifyCore.getSortMode(this@MainActivity), false)
        }
        mainLayout.addView(spinSort)
        spinSort.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                getSharedPreferences("QuickPrefs", Context.MODE_PRIVATE).edit()
                    .putInt("sort_mode", position).apply()
                NotifyCore.scheduleRefresh(this@MainActivity)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        val cbPaging = CheckBox(this).apply {
            text = "📄 开启翻页（右下角出现页码键）"
            textSize = 14f
            isChecked = NotifyCore.isPagingEnabled(this@MainActivity)
            setOnCheckedChangeListener { _, checked ->
                getSharedPreferences("QuickPrefs", Context.MODE_PRIVATE).edit()
                    .putBoolean("paging_enabled", checked)
                    .putInt("current_page", 0)   // 切模式时回到第一页
                    .apply()
                NotifyCore.scheduleRefresh(this@MainActivity)
            }
        }
        mainLayout.addView(cbPaging)

        mainLayout.addView(TextView(this).apply {
            text = "📑 翻页方式（需先开启翻页）："
            textSize = 14f
            setPadding(0, 16, 0, 0)
            setTextColor(Color.GRAY)
        })
        val spinPagingMode = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity, android.R.layout.simple_spinner_dropdown_item,
                arrayOf("整页翻（每页 19 个）", "只翻第二行（第一行 10 个不动）")
            )
            setSelection(NotifyCore.getPagingMode(this@MainActivity), false)
        }
        mainLayout.addView(spinPagingMode)
        spinPagingMode.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                getSharedPreferences("QuickPrefs", Context.MODE_PRIVATE).edit()
                    .putInt("paging_mode", position)
                    .putInt("current_page", 0)   // 换翻页方式也回第一页
                    .apply()
                NotifyCore.scheduleRefresh(this@MainActivity)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        mainLayout.addView(TextView(this).apply {
            text = "📳 点击震动反馈："
            textSize = 14f
            setPadding(0, 16, 0, 0)
            setTextColor(Color.GRAY)
        })
        val spinVibrate = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity, android.R.layout.simple_spinner_dropdown_item,
                arrayOf("关闭", "弱（推荐）", "强")
            )
            setSelection(NotifyCore.getVibrateMode(this@MainActivity), false)
        }
        mainLayout.addView(spinVibrate)
        spinVibrate.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                getSharedPreferences("QuickPrefs", Context.MODE_PRIVATE).edit()
                    .putInt("vibrate_mode", position).apply()
                Toast.makeText(
                    this@MainActivity,
                    when (position) {
                        NotifyCore.VIBRATE_OFF -> "震动已关闭"
                        NotifyCore.VIBRATE_STRONG -> "震动：强"
                        else -> "震动：弱"
                    }, Toast.LENGTH_SHORT
                ).show()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // 【新增】看门狗间隔：恢复速度与耗电的权衡
        mainLayout.addView(TextView(this).apply {
            text = "⏱️ 掉线自检间隔（越小恢复越快、越费电）"
            textSize = 14f
            setPadding(0, 16, 0, 0)
            setTextColor(Color.GRAY)
        })
        val wdValues = intArrayOf(1, 3, 5, 10, 30, NotifyCore.WATCHDOG_OFF)
        val wdLabels = arrayOf("1 分钟（默认）", "3 分钟", "5 分钟", "10 分钟", "30 分钟", "关闭自愈（只保留开机自启）")
        fun wdIndex(v: Int): Int {
            val i = wdValues.indexOf(v)
            return if (i >= 0) i else 0
        }
        val spinWatchdog = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, wdLabels)
            setSelection(wdIndex(NotifyCore.getWatchdogIntervalMin(this@MainActivity)), false)
        }
        mainLayout.addView(spinWatchdog)
        spinWatchdog.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val min = wdValues[position]
                NotifyCore.setWatchdogIntervalMin(this@MainActivity, min)
                Toast.makeText(
                    this@MainActivity,
                    if (min == NotifyCore.WATCHDOG_OFF) "自愈已关闭（重启后仍会自启）"
                    else "自检间隔：$min 分钟（Doze 休眠时系统可能延后到约 9 分钟）",
                    Toast.LENGTH_LONG
                ).show()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // 【新增】备份 / 导入 / 卸载清理
        mainLayout.addView(TextView(this).apply {
            text = "💾 配置备份（纯本地，不联网）"
            textSize = 15f
            setPadding(0, 40, 0, 10)
            setTextColor(Color.GRAY)
        })
        val btnExport = Button(this).apply {
            text = "导出配置到备份目录 + 剪贴板"
            textSize = 14f
            setTextColor(Color.parseColor("#333333"))
            setBackgroundColor(Color.parseColor("#E8F5E9"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 8, 0, 0) }
            setOnClickListener { exportConfig() }
        }
        mainLayout.addView(btnExport)

        val btnImport = Button(this).apply {
            text = "导入配置（选备份文件 / 粘贴文本）"
            textSize = 14f
            setTextColor(Color.parseColor("#333333"))
            setBackgroundColor(Color.parseColor("#FFF3E0"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 12, 0, 0) }
            setOnClickListener { showImportDialog() }
        }
        mainLayout.addView(btnImport)

        val btnPrune = Button(this).apply {
            text = "🧹 立即清理已卸载的应用"
            textSize = 14f
            setTextColor(Color.parseColor("#666666"))
            setBackgroundColor(Color.parseColor("#E0E0E0"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 12, 0, 0) }
            setOnClickListener { pruneNow() }
        }
        mainLayout.addView(btnPrune)
        settingsPage.addView(btnRecords)

        appsPage.addView(TextView(this).apply {
            text = "📱 编辑应用与排序"
            textSize = 20f
            setTextColor(Color.parseColor("#1F2937"))
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, 10, 0, 8)
        })

        // 【P2】"防社死"图标伪装 UI 已移除（作者确认无效）。
        // 旧配置里的 icon_type 字段被忽略，不会导致崩溃或行为漂移。

        appsPage.addView(TextView(this).apply { text = "⚙️ 每行显示几个图标？ (4 ~ 10个)"; textSize = 14f; setPadding(0, 24, 0, 10); setTextColor(Color.GRAY) })
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
        appsPage.addView(seekBar)

        val btnHideApps = Button(this).apply {
            text = "🚫 把不想看到的 App 关进黑名单"
            setTextColor(Color.parseColor("#666666"))
            setBackgroundColor(Color.parseColor("#E0E0E0"))
            val params = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            params.setMargins(0, 20, 0, 20)
            layoutParams = params
            setOnClickListener { showHideAppsDialog() }
        }
        appsPage.addView(btnHideApps)

        val btnAdd = Button(this).apply {
            text = "➕ 打开网格极速多选"
            setPadding(0, 20, 0, 20)
            setOnClickListener { showGridAddDialog() }
        }
        appsPage.addView(btnAdd)

        appsPage.addView(TextView(this).apply { text = "📝 已选队列 (上移/下移/删除)："; textSize = 15f; setPadding(0, 30, 0, 10); setTextColor(Color.GRAY) })

        selectedListContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 20, 0, 0) }
        appsPage.addView(selectedListContainer)

        updateStatus()
        refreshUI()
    }

    private fun showPage(page: View) {
        homePage.visibility = if (page === homePage) View.VISIBLE else View.GONE
        appsPage.visibility = if (page === appsPage) View.VISIBLE else View.GONE
        settingsPage.visibility = if (page === settingsPage) View.VISIBLE else View.GONE
        rootScroll.scrollTo(0, 0)
        updateNav(page)
        if (page === homePage) {
            updateStatus()
            updateDashboard()
        }
    }

    private fun updateStatus() {
        if (!::tvStatus.isInitialized) return
        val running = NotifyCore.isNotificationAlive(this)
        tvStatus.text = if (running) "● 运行中" else "● 已停止"
        tvStatus.setTextColor(Color.parseColor(if (running) "#6C5CE7" else "#FF6B6B"))
        tvStatusSub.text = "${selectedApps.size} 个应用 · 今日打开 ${todayOpenCount()} 次"
        btnPower.text = if (running) "Ⅱ" else "▶"
        btnPower.setTextColor(Color.parseColor(if (running) "#6C5CE7" else "#FF6B6B"))
        btnPower.background = roundedBg(
            Color.parseColor(if (running) "#EFECFE" else "#FCEBEB"),
            23f
        )
    }

    override fun onResume() {
        super.onResume()
        if (::selectedListContainer.isInitialized) {
            refreshUI()
            updateStatus()
            updateDashboard()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            ::appsPage.isInitialized && appsPage.visibility == View.VISIBLE -> showPage(settingsPage)
            ::settingsPage.isInitialized && settingsPage.visibility == View.VISIBLE -> showPage(homePage)
            else -> super.onBackPressed()
        }
    }

    private fun roundedBg(color: Int, radiusDp: Float, strokeColor: Int? = null): android.graphics.drawable.GradientDrawable =
        android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = dpToPx(radiusDp).toFloat()
            setColor(color)
            if (strokeColor != null) setStroke(dpToPx(1f), strokeColor)
        }

    private fun buildBottomNav(): LinearLayout {
        val nav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dpToPx(6f), dpToPx(6f), dpToPx(6f), dpToPx(6f))
            background = roundedBg(Color.argb(242, 255, 255, 255), 28f, Color.parseColor("#E5E5EA"))
        }
        navMonitor = navItem("监测") { showPage(homePage) }
        navApps = navItem("应用") { showPage(appsPage) }
        navSettings = navItem("设置") { showPage(settingsPage) }
        nav.addView(navMonitor, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
        nav.addView(navApps, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
        nav.addView(navSettings, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
        return nav
    }

    private fun navItem(label: String, onClick: () -> Unit): TextView = TextView(this).apply {
        text = label
        textSize = 12f
        gravity = Gravity.CENTER
        setTextColor(Color.parseColor("#8E8E93"))
        setOnClickListener { onClick() }
    }

    private fun buildHomeDashboard() {
        homePage.removeAllViews()

        val statusCard = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dpToPx(18f), dpToPx(16f), dpToPx(14f), dpToPx(16f))
            background = roundedBg(Color.WHITE, 20f)
        }
        val statusText = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        tvStatus = TextView(this).apply {
            text = "● 已停止"
            textSize = 15f
            setTypeface(null, android.graphics.Typeface.BOLD)
        }
        tvStatusSub = TextView(this).apply {
            text = "0 个应用 · 今日打开 0 次"
            textSize = 11f
            setTextColor(Color.parseColor("#8E8E93"))
            setPadding(0, 5, 0, 0)
        }
        statusText.addView(tvStatus)
        statusText.addView(tvStatusSub)
        statusCard.addView(statusText, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        btnPower = TextView(this).apply {
            text = "▶"
            textSize = 20f
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#6C5CE7"))
            background = roundedBg(Color.parseColor("#EFECFE"), 23f)
            setOnClickListener { toggleService() }
        }
        statusCard.addView(btnPower, LinearLayout.LayoutParams(dpToPx(46f), dpToPx(46f)))
        homePage.addView(
            statusCard,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { bottomMargin = dpToPx(12f) }
        )

        val seg = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dpToPx(4f), dpToPx(4f), dpToPx(4f), dpToPx(4f))
            background = roundedBg(Color.parseColor("#ECECEF"), 18f)
        }
        segToday = segmentItem("今日", 0)
        segSeven = segmentItem("7 天", 1)
        segAll = segmentItem("全部", 2)
        seg.addView(segToday, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
        seg.addView(segSeven, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
        seg.addView(segAll, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
        homePage.addView(
            seg,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(36f))
                .apply { bottomMargin = dpToPx(12f) }
        )

        val chartCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpToPx(18f), dpToPx(16f), dpToPx(18f), dpToPx(8f))
            background = roundedBg(Color.WHITE, 20f)
        }
        chartCard.addView(TextView(this).apply {
            text = "打开次数"
            textSize = 11f
            setTextColor(Color.parseColor("#8E8E93"))
        })
        val numRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.BOTTOM }
        tvChartNumber = TextView(this).apply {
            text = "0"
            textSize = 24f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(Color.parseColor("#1C1C1E"))
        }
        tvChartDelta = TextView(this).apply {
            text = "较昨日 +0"
            textSize = 11f
            setTextColor(Color.parseColor("#6C5CE7"))
            setPadding(dpToPx(8f), 0, 0, dpToPx(4f))
        }
        numRow.addView(tvChartNumber)
        numRow.addView(tvChartDelta)
        chartCard.addView(numRow)
        chartView = UsageChartView(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(100f))
        }
        chartCard.addView(chartView)
        homePage.addView(
            chartCard,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { bottomMargin = dpToPx(12f) }
        )

        val previewCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpToPx(18f), dpToPx(14f), dpToPx(18f), dpToPx(12f))
            background = roundedBg(Color.WHITE, 20f)
            setOnClickListener { showPage(appsPage) }
        }
        previewCard.addView(TextView(this).apply {
            text = "通知栏预览"
            textSize = 11f
            setTextColor(Color.parseColor("#8E8E93"))
        })
        previewContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(0, dpToPx(10f), 0, dpToPx(6f))
        }
        previewCard.addView(previewContainer)
        previewCard.addView(TextView(this).apply {
            text = "点按进入应用页"
            textSize = 11f
            setTextColor(Color.parseColor("#8E8E93"))
        })
        homePage.addView(
            previewCard,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        )

        updateSegments()
        updateStatus()
        updateDashboard()
        updateNav(homePage)
    }

    private fun segmentItem(label: String, range: Int): TextView = TextView(this).apply {
        text = label
        textSize = 12f
        gravity = Gravity.CENTER
        setTextColor(Color.parseColor("#8E8E93"))
        setOnClickListener {
            chartRange = range
            updateSegments()
            updateDashboard()
        }
    }

    private fun updateSegments() {
        if (!::segToday.isInitialized) return
        val items = listOf(segToday to 0, segSeven to 1, segAll to 2)
        for ((view, range) in items) {
            val active = chartRange == range
            view.background = if (active) roundedBg(Color.WHITE, 12f) else null
            view.setTextColor(Color.parseColor(if (active) "#1C1C1E" else "#8E8E93"))
            view.setTypeface(null, if (active) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
        }
    }

    private fun updateNav(page: View) {
        if (!::navMonitor.isInitialized) return
        val active = when (page) {
            homePage -> 0
            appsPage -> 1
            settingsPage -> 2
            else -> 0
        }
        val items = listOf(navMonitor to 0, navApps to 1, navSettings to 2)
        for ((view, index) in items) {
            val on = active == index
            view.background = if (on) roundedBg(Color.parseColor("#EFECFE"), 24f) else null
            view.setTextColor(Color.parseColor(if (on) "#6C5CE7" else "#8E8E93"))
            view.setTypeface(null, if (on) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
        }
    }

    private fun toggleService() {
        if (NotifyCore.isNotificationAlive(this)) {
            NotifyCore.setStoppedByUser(this, true)
            NotifyCore.cancelWatchdog(this)
            stopService(Intent(this, QuickService::class.java))
            getSystemService(NotificationManager::class.java).cancel(NotifyCore.NOTIFICATION_ID)
            Toast.makeText(this, "通知服务已停止", Toast.LENGTH_SHORT).show()
        } else {
            NotifyCore.setStoppedByUser(this, false)
            NotifyCore.startService(this)
            NotifyCore.scheduleWatchdog(this)
            Toast.makeText(this, "已开启通知服务", Toast.LENGTH_SHORT).show()
        }
        updateStatus()
        updateDashboard()
    }

    private fun todayStart(): Long =
        java.time.LocalDate.now().atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()

    private fun todayOpenCount(): Int {
        val start = todayStart()
        return NotifyCore.getOpenRecords(this).count { it.second >= start }
    }

    private fun yesterdayOpenCount(): Int {
        val start = todayStart()
        val from = start - 86_400_000L
        return NotifyCore.getOpenRecords(this).count { it.second in from until start }
    }

    private fun updateDashboard() {
        if (!::chartView.isInitialized) return
        val records = NotifyCore.getOpenRecords(this)
        val now = System.currentTimeMillis()
        val start = todayStart()
        val day = 86_400_000L

        val values: List<Int>
        val total: Int
        val left: String
        val right: String
        when (chartRange) {
            0 -> {
                total = records.count { it.second >= start }
                values = (11 downTo 0).map { h ->
                    val from = now - h * 3_600_000L
                    records.count { it.second in from until from + 3_600_000L }
                }
                left = "12h前"
                right = "现在"
            }
            1 -> {
                total = records.count { it.second >= start - 6 * day }
                values = (6 downTo 0).map { d ->
                    val from = start - d * day
                    records.count { it.second in from until from + day }
                }
                left = "7天前"
                right = "今天"
            }
            else -> {
                total = records.size
                values = (13 downTo 0).map { d ->
                    val from = start - d * day
                    records.count { it.second in from until from + day }
                }
                left = "14天前"
                right = "今天"
            }
        }
        tvChartNumber.text = total.toString()
        tvChartDelta.text = when (chartRange) {
            0 -> {
                val diff = todayOpenCount() - yesterdayOpenCount()
                if (diff >= 0) "较昨日 +$diff" else "较昨日 $diff"
            }
            1 -> "近 7 天"
            else -> "累计"
        }
        chartView.setData(values, left, right)
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
                // 同步清掉被拉黑 App 的置顶标记
                val pinned = NotifyCore.getPinnedApps(this@MainActivity)
                getSharedPreferences("QuickPrefs", Context.MODE_PRIVATE).edit()
                    .putString("pinned_apps", pinned.filter { it !in hiddenApps }.joinToString(","))
                    .apply()
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
        val previewApps = selectedApps.take(20)
        val previewRows = maxOf(1, (previewApps.size + columnsPerRow - 1) / columnsPerRow)
        val previewRowHeight = minOf(dpToPx(44f), dpToPx(200f) / previewRows).coerceAtLeast(dpToPx(24f))
        var currentRow: LinearLayout? = null

        for (i in previewApps.indices) {
            if (i % columnsPerRow == 0) {
                currentRow = LinearLayout(this).apply { 
                    orientation = LinearLayout.HORIZONTAL
                    weightSum = columnsPerRow.toFloat()
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, previewRowHeight)
                }
                previewContainer.addView(currentRow)
            }
            val app = allApps.find { it.activityInfo.packageName == previewApps[i] }
            val iconView = ImageView(this).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
                setPadding(dpToPx(4f), dpToPx(4f), dpToPx(4f), dpToPx(4f))
                scaleType = ImageView.ScaleType.FIT_CENTER
                background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = dpToPx(8f).toFloat()
                    setColor(Color.parseColor("#F3F4F6"))
                }
                clipToOutline = true
                app?.let { setImageDrawable(it.loadIcon(pm)) }
            }
            currentRow?.addView(iconView)
        }

        selectedListContainer.removeAllViews()
        val openCounts = NotifyCore.getOpenCounts(this)
        for (i in selectedApps.indices) {
            val app = allApps.find { it.activityInfo.packageName == selectedApps[i] }
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, 15, 0, 15) }
            row.addView(ImageView(this).apply { layoutParams = LinearLayout.LayoutParams(100, 100); app?.let { setImageDrawable(it.loadIcon(pm)) } })
            val pinnedApps = NotifyCore.getPinnedApps(this)
            val isPinned = selectedApps[i] in pinnedApps
            row.addView(TextView(this).apply {
                text = (app?.loadLabel(pm) ?: "未知").let { if (isPinned) "📌 $it" else it }
                textSize = 16f
                setPadding(30, 0, 0, 0)
                setTextColor(if (isPinned) Color.parseColor("#E65100") else Color.parseColor("#333333"))
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            })
            row.addView(TextView(this).apply {
                text = "${openCounts[selectedApps[i]] ?: 0} 次"
                textSize = 12f
                setTextColor(Color.parseColor("#6B7280"))
                setPadding(8, 0, 8, 0)
            })
            // 【新增】置顶开关：切 pinned_apps，不改手动队列；若当前不是置顶档则自动切过去
            row.addView(Button(this).apply {
                text = if (isPinned) "📌" else "☆"
                setTextColor(if (isPinned) Color.parseColor("#E65100") else Color.GRAY)
                layoutParams = LinearLayout.LayoutParams(120, -2)
                setOnClickListener { togglePin(i) }
            })
            row.addView(Button(this).apply { text = "↑"; layoutParams = LinearLayout.LayoutParams(120, -2); isEnabled = i > 0; setOnClickListener { swap(i, i - 1) } })
            row.addView(Button(this).apply { text = "↓"; layoutParams = LinearLayout.LayoutParams(120, -2); isEnabled = i < selectedApps.size - 1; setOnClickListener { swap(i, i + 1) } })
            row.addView(Button(this).apply {
                text = "X"; setTextColor(Color.RED); layoutParams = LinearLayout.LayoutParams(120, -2)
                setOnClickListener {
                    val removed = selectedApps.removeAt(i)
                    // 删除时同步清掉置顶记录，避免留下指向已删 App 的死数据
                    val pinned = NotifyCore.getPinnedApps(this@MainActivity).toMutableSet()
                    if (pinned.remove(removed)) {
                        getSharedPreferences("QuickPrefs", Context.MODE_PRIVATE).edit()
                            .putString("pinned_apps", pinned.joinToString(",")).apply()
                    }
                    saveData(); refreshUI()
                }
            })
            selectedListContainer.addView(row)
        }
        updateStatus()
        updateDashboard()
    }

    private fun swap(i: Int, j: Int) {
        val temp = selectedApps[i]; selectedApps[i] = selectedApps[j]; selectedApps[j] = temp; saveData(); refreshUI()
    }

    /** 置顶开关：只动 pinned_apps，绝不动 selectedApps；首次使用自动切到置顶档 */
    private fun togglePin(i: Int) {
        val pkg = selectedApps[i]
        val nowPinned = NotifyCore.togglePinned(this, pkg)
        val prefs = getSharedPreferences("QuickPrefs", Context.MODE_PRIVATE)
        val wasPinnedMode = NotifyCore.getSortMode(this) == NotifyCore.SORT_PINNED
        if (!wasPinnedMode) {
            prefs.edit().putInt("sort_mode", NotifyCore.SORT_PINNED).apply()
            spinSort.setSelection(NotifyCore.SORT_PINNED, false)   // false = 不触发 listener
            Toast.makeText(this, "已切到「📌 置顶优先」档", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(
                this,
                if (nowPinned) "已置顶（排到最前）" else "已取消置顶（回到原顺序）",
                Toast.LENGTH_SHORT
            ).show()
        }
        refreshUI()
        NotifyCore.scheduleRefresh(this)
    }

    private fun saveData() {
        getSharedPreferences("QuickPrefs", Context.MODE_PRIVATE).edit()
            .putInt("columns", columnsPerRow)
            // 【P2】icon_type 不再写入新值；旧值原样保留，兼容老备份
            .putString("selected_apps", selectedApps.joinToString(","))
            .putString("hidden_apps", hiddenApps.joinToString(","))
            .apply()
        // 【P1】保存后自动刷新通知（带 400ms 去抖，SeekBar 拖动不会刷爆）
        NotifyCore.scheduleRefresh(this)
    }

    // 【P1】打开记录：上限可调（关闭 / 100 / 1000 / 无限制）+ 查看 + 清空
    private fun showOpenRecordsDialog() {
        val limitLabels = arrayOf("关闭（不记录）", "100 条", "1000 条", "无限制")
        val limitValues = intArrayOf(
            NotifyCore.RECORD_OFF, 100, 1000, NotifyCore.RECORD_UNLIMITED
        )
        var selectedIndex = limitValues.indexOf(NotifyCore.getRecordLimit(this))
            .let { if (it < 0) 1 else it }   // 非法值兜底到 100 条

        val pm = packageManager
        val sdf = java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.getDefault())
        val tv = TextView(this).apply { textSize = 13f; setPadding(30, 16, 30, 16) }

        fun renderRecords() {
            val limit = limitValues[selectedIndex]
            val records = NotifyCore.getOpenRecords(this)
            fun labelOf(pkg: String): String = try {
                pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
            } catch (e: Exception) {
                pkg
            }
            tv.text = when {
                limit == NotifyCore.RECORD_OFF ->
                    "记录功能已关闭，历史记录也已清空。"
                records.isEmpty() ->
                    "暂无记录。点击通知栏里的图标后会自动记一笔。"
                else -> {
                    // 无限制时最多渲染 500 行，防止超长文本把对话框卡死（数据本身不裁剪）
                    val shown = records.take(500)
                    val counts = NotifyCore.getOpenCounts(this)
                    val summary = counts.entries.sortedByDescending { it.value }.take(30)
                        .joinToString("\n") { (pkg, n) -> "${labelOf(pkg)}  ·  $n 次" }
                    val header = buildString {
                        if (counts.isNotEmpty()) {
                            append("累计打开次数（按应用）：\n")
                            append(summary)
                            append("\n\n")
                        }
                        if (records.size > 500) {
                            append("共 ${records.size} 条，仅显示最近 500 条：\n")
                        } else {
                            append("共 ${records.size} 条：\n")
                        }
                    }
                    header + shown.joinToString("\n") { (pkg, ts) ->
                        "${labelOf(pkg)}  ·  ${sdf.format(java.util.Date(ts))}"
                    }
                }
            }
        }
        renderRecords()

        val scroll = ScrollView(this).apply { addView(tv) }
        val spinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity, android.R.layout.simple_spinner_dropdown_item, limitLabels
            )
            setSelection(selectedIndex, false)   // false = 初始化不触发回调
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 20, 40, 0)
            addView(TextView(this@MainActivity).apply {
                text = "记录上限（选「关闭」时不保存任何打开信息）"
                textSize = 14f
                setTextColor(Color.GRAY)
            })
            addView(spinner)
            addView(scroll)
        }

        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (position == selectedIndex) return
                selectedIndex = position
                val value = limitValues[position]
                NotifyCore.setRecordLimit(this@MainActivity, value)
                renderRecords()
                val msg = when (value) {
                    NotifyCore.RECORD_OFF -> "已关闭记录，历史已清空"
                    NotifyCore.RECORD_UNLIMITED -> "上限：无限制（数据会持续增长）"
                    else -> "记录上限：$value 条"
                }
                Toast.makeText(this@MainActivity, msg, Toast.LENGTH_SHORT).show()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        val builder = AlertDialog.Builder(this)
            .setTitle("应用打开记录（仅本地）")
            .setView(container)
            .setPositiveButton("关闭", null)

        if (NotifyCore.getRecordLimit(this) != NotifyCore.RECORD_OFF) {
            builder.setNeutralButton("清空记录") { _, _ ->
                NotifyCore.clearOpenRecords(this)
                renderRecords()
                Toast.makeText(this, "记录已清空", Toast.LENGTH_SHORT).show()
            }
        }
        builder.show()
    }

    // ===== 【新增】配置备份：专属目录 + 剪贴板，零权限、零联网 =====

    private fun exportConfig() {
        val text = NotifyCore.exportConfigText(this)
        val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.getDefault())
            .format(java.util.Date())
        val file = java.io.File(NotifyCore.backupDir(this), "QuickPrefs-$stamp.txt")
        try {
            file.writeText(text, Charsets.UTF_8)
            val cb = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cb.setPrimaryClip(android.content.ClipData.newPlainText("快捷通知配置", text))
            Toast.makeText(
                this,
                "已导出到：${file.absolutePath}\n并已复制到剪贴板",
                Toast.LENGTH_LONG
            ).show()
        } catch (e: Exception) {
            Toast.makeText(this, "导出失败：${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun showImportDialog() {
        val items: Array<CharSequence> = arrayOf("从备份文件导入", "从剪贴板粘贴")
        AlertDialog.Builder(this)
            .setTitle("导入配置")
            .setItems(items) { _, which ->
                if (which == 0) showFileImportDialog() else showPasteImportDialog()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showFileImportDialog() {
        val dir = NotifyCore.backupDir(this)
        val files = (dir.listFiles { f -> f.isFile && f.name.endsWith(".txt") } ?: emptyArray())
            .sortedByDescending { it.lastModified() }
        if (files.isEmpty()) {
            Toast.makeText(this, "备份目录里还没有文件，先导出一次", Toast.LENGTH_LONG).show()
            return
        }
        val names: Array<CharSequence> = files.map { it.name as CharSequence }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("选择备份文件（最新在前）")
            .setItems(names) { _, idx ->
                try {
                    applyImportedConfig(files[idx].readText(Charsets.UTF_8))
                } catch (e: Exception) {
                    Toast.makeText(this, "读取失败：${e.message}", Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun getClipboardText(): String = try {
        val cb = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        (cb.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString() ?: "").take(100_000)
    } catch (e: Exception) { "" }

    private fun showPasteImportDialog() {
        val et = EditText(this).apply {
            hint = "粘贴 # QuickNotify-Config-v1 开头的配置文本"
            setText(getClipboardText())
            minLines = 6
            maxLines = 12
            textSize = 13f
        }
        val scroll = ScrollView(this).apply {
            addView(et, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        }
        AlertDialog.Builder(this)
            .setTitle("粘贴配置文本")
            .setView(scroll)
            .setPositiveButton("导入") { _, _ -> applyImportedConfig(et.text.toString()) }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun applyImportedConfig(text: String) {
        val result = NotifyCore.importConfigText(this, text)
        Toast.makeText(this, result.message, Toast.LENGTH_LONG).show()
        if (!result.ok) return

        // 同步内存状态，否则界面还显示旧列表
        val prefs = getSharedPreferences("QuickPrefs", Context.MODE_PRIVATE)
        columnsPerRow = prefs.getInt("columns", 6)
        fun splitPref(key: String) = (prefs.getString(key, "") ?: "")
            .split(",").filter { it.isNotEmpty() }
        selectedApps.clear(); selectedApps.addAll(splitPref("selected_apps"))
        hiddenApps.clear(); hiddenApps.addAll(splitPref("hidden_apps"))
        refreshUI()

        // 按新间隔重排看门狗，并按新配置重建通知
        NotifyCore.cancelWatchdog(this)
        if (!NotifyCore.isStoppedByUser(this)) {
            if (NotifyCore.getWatchdogIntervalMin(this) != NotifyCore.WATCHDOG_OFF) {
                NotifyCore.scheduleWatchdog(this)
            }
            NotifyCore.refreshNotification(this)
        }
    }

    private fun pruneNow() {
        val removed = NotifyCore.pruneUninstalledApps(this, force = true)
        val prefs = getSharedPreferences("QuickPrefs", Context.MODE_PRIVATE)
        fun splitPref(key: String) = (prefs.getString(key, "") ?: "")
            .split(",").filter { it.isNotEmpty() }
        selectedApps.clear(); selectedApps.addAll(splitPref("selected_apps"))
        hiddenApps.clear(); hiddenApps.addAll(splitPref("hidden_apps"))
        refreshUI()
        if (removed > 0) NotifyCore.scheduleRefresh(this)
        Toast.makeText(
            this,
            if (removed > 0) "已清理 $removed 个已卸载的应用" else "没有需要清理的应用",
            Toast.LENGTH_SHORT
        ).show()
    }
}

// ================= 服务端核心代码 =================
class QuickService : Service() {
    override fun onBind(intent: Intent?) = null

    override fun onDestroy() {
        super.onDestroy()
        // 注意：系统直接杀进程时 onDestroy 未必调用；真正的兜底是看门狗 alarm。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        getSystemService(NotificationManager::class.java).cancel(NotifyCore.NOTIFICATION_ID)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 【P0】用户显式关过就不再重建（防止看门狗/STICKY 把它拉回来）
        if (NotifyCore.isStoppedByUser(this)) {
            stopSelf()
            return START_NOT_STICKY
        }

        // 一次性把完整通知（含图标网格）构建好再 startForeground，
        // 去掉原来的"空通知 → 子线程 notify 覆盖"两段式时序隐患
        startForeground(NotifyCore.NOTIFICATION_ID, NotifyCore.buildNotification(this))

        // 【P0】排上看门狗：进程被杀后按设置间隔自动重建通知（默认 60 秒）
        NotifyCore.scheduleWatchdog(this)

        return START_STICKY
    }
}
