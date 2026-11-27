package com.my.app

import android.Manifest
import android.app.*
import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
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
    private lateinit var appsPreviewContainer: LinearLayout
    private lateinit var selectedListContainer: LinearLayout
    private lateinit var rootScroll: ScrollView
    private lateinit var homePage: LinearLayout
    private lateinit var appsPage: LinearLayout
    private lateinit var settingsPage: LinearLayout
    private lateinit var aboutPage: LinearLayout
    private lateinit var guidePage: LinearLayout
    private lateinit var tvStatus: TextView
    private lateinit var tvStatusSub: TextView
    private lateinit var btnPower: TextView
    private lateinit var chartView: UsageChartView
    private lateinit var tvChartNumber: TextView
    private lateinit var tvChartDelta: TextView
    private lateinit var segToday: TextView
    private lateinit var segSeven: TextView
    private lateinit var segMonth: TextView
    private lateinit var segAll: TextView
    private lateinit var rankContainer: LinearLayout
    private lateinit var tvRankTitle: TextView
    private lateinit var navMonitor: TextView
    private lateinit var navApps: TextView
    private lateinit var navSettings: TextView
    private var chartRange = 0
    // 外观令牌：背景档位与强调色各自独立，用户爱怎么搭就怎么搭
    private lateinit var palette: AppTheme

    private val RANK_COLLAPSED = 6

    private val REQ_EXPORT_USAGE = 3001
    private val REQ_IMPORT_USAGE = 3002

    private lateinit var bottomNav: LinearLayout
    private lateinit var statusFloat: FrameLayout
    private var navHidden = false

    // 状态卡：位置（顶部/底部）与形态（卡片/细条/仅按钮）是两个独立开关
    companion object {
        // 重建 Activity 时跨实例传递「回到哪一页、滚到哪」
        private var restorePageIndex = -1
        private var restoreScrollY = 0
    }

    private val STATUS_TOP = 0
    private val STATUS_BOTTOM = 1
    private val STYLE_CARD = 0
    private val STYLE_STRIP = 1
    private val STYLE_BUTTON = 2
    private var statusPosition = STATUS_TOP
    private var statusStyle = STYLE_STRIP

    /** 0 监测 / 1 应用 / 2 设置 / 3 关于 / 4 使用说明 */
    private var currentPageIndex = 0

    // 应用列表偏好：是否隐藏系统应用；已添加应用在多选里的位置（0 保持 / 1 置顶 / 2 置底）
    private var hideSystemApps = false
    private var addedPosition = 0

    /** 本次启动新出现的应用（对比 known_apps），多选时标「新」并靠前 */
    private var newApps: Set<String> = emptySet()

    /** 预览里第一次点中的格子；再点另一个就交换 */
    private var swapIndex = -1

    /** 启动页：0 统计 / 1 图标 */
    private var startPage = 0

    private var currentPageView: View? = null
    private var rankExpanded = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        palette = AppTheme.current(this)
        val uiPrefs = getSharedPreferences("QuickPrefs", Context.MODE_PRIVATE)
        statusPosition = uiPrefs.getInt("status_position", STATUS_TOP)
        statusStyle = uiPrefs.getInt("status_style", STYLE_STRIP)
        hideSystemApps = uiPrefs.getBoolean("hide_system_apps", false)
        addedPosition = uiPrefs.getInt("added_position", 0)
        startPage = uiPrefs.getInt("start_page", 0)
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
        val homeBottomPad = if (statusPosition == STATUS_BOTTOM) 196 else 118
        homePage = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(40, 32, 40, dpToPx(homeBottomPad.toFloat())) }
        val pageBottomPad = dpToPx(if (statusPosition == STATUS_BOTTOM) 210f else 110f)
        appsPage = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(40, 32, 40, pageBottomPad); visibility = View.GONE }
        settingsPage = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(40, 32, 40, pageBottomPad); visibility = View.GONE }
        aboutPage = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(40, 32, 40, 110); visibility = View.GONE }
        guidePage = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(40, 32, 40, 110); visibility = View.GONE }
        pageHost.addView(homePage)
        pageHost.addView(appsPage)
        pageHost.addView(settingsPage)
        pageHost.addView(aboutPage)
        pageHost.addView(guidePage)
        rootScroll.addView(pageHost)

        val rootFrame = FrameLayout(this).apply { setBackgroundColor(palette.bg) }
        rootFrame.addView(
            rootScroll,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        )
        bottomNav = buildBottomNav()
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
        statusFloat = FrameLayout(this).apply { visibility = View.GONE }
        rootFrame.addView(
            statusFloat,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM
            ).apply {
                leftMargin = dpToPx(28f)
                rightMargin = dpToPx(28f)
                bottomMargin = dpToPx(82f)
            }
        )

        setContentView(rootFrame)
        // 必须放在 setContentView 之后：之前 DecorView 还没建出来，
        // 调 window.insetsController 会 NPE（1.3.0 的崩溃就是这么来的）
        applySystemBars()

        // 往下翻内容时导航自动收起，往上滑回来时再浮出来
        rootScroll.setOnScrollChangeListener { _, _, y, _, oldY ->
            if (!::bottomNav.isInitialized) return@setOnScrollChangeListener
            val dy = y - oldY
            if (dy > 8 && y > dpToPx(120f) && !navHidden) {
                navHidden = true
                val drop = (bottomNav.height + dpToPx(40f)).toFloat()
                bottomNav.animate().translationY(drop).setDuration(180).start()
                if (statusFloat.visibility == View.VISIBLE) {
                    statusFloat.animate().translationY(drop).setDuration(180).start()
                }
            } else if (dy < -8 && navHidden) {
                navHidden = false
                bottomNav.animate().translationY(0f).setDuration(180).start()
                if (statusFloat.visibility == View.VISIBLE) {
                    statusFloat.animate().translationY(0f).setDuration(180).start()
                }
            }
        }

        buildHomeDashboard()
        buildSettingsPage()

        appsPage.addView(TextView(this).apply {
            text = "📱 编辑应用与排序"
            textSize = 20f
            setTextColor(palette.textPrimary)
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, 10, 0, 8)
        })

        // 【P2】"防社死"图标伪装 UI 已移除（作者确认无效）。
        // 旧配置里的 icon_type 字段被忽略，不会导致崩溃或行为漂移。

        // 应用页也放一份预览：增删/排序时当场看到效果，不用切回监测页
        val appsPreviewCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpToPx(14f), dpToPx(12f), dpToPx(14f), dpToPx(10f))
            background = roundedBg(palette.card, 16f)
        }
        appsPreviewCard.addView(TextView(this).apply {
            text = "通知栏预览（改完立刻可见）"
            textSize = 11f
            setTextColor(palette.textSecondary)
        })
        appsPreviewContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dpToPx(8f), 0, dpToPx(2f))
            background = roundedBg(palette.cardSoft, 10f)
        }
        appsPreviewCard.addView(appsPreviewContainer)
        appsPage.addView(
            appsPreviewCard,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dpToPx(4f) }
        )

        appsPage.addView(TextView(this).apply { text = "⚙️ 每行显示几个图标？ (4 ~ 10个)"; textSize = 14f; setPadding(0, 24, 0, 10); setTextColor(palette.textSecondary) })
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

        val optParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dpToPx(8f) }
        appsPage.addView(appsOption("排序方式", sortModeLabel()) { showSortModeDialog() }, optParams)
        appsPage.addView(appsOption("翻页", pagingLabel()) { showPagingDialog() }, optParams)
        appsPage.addView(appsOption("点击震动", vibrateLabel()) { showVibrateDialog() }, optParams)
        appsPage.addView(appsOption("隐藏系统应用", if (hideSystemApps) "开" else "关") { toggleHideSystem() }, optParams)
        appsPage.addView(appsOption("已添加的位置", arrayOf("保持现状", "置顶", "置底")[addedPosition]) { showAddedPositionDialog() }, optParams)

        val btnHideApps = Button(this).apply {
            text = "🚫 把不想看到的 App 关进黑名单"
            setTextColor(palette.textSecondary)
            setBackgroundColor(palette.track)
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

        // 一周没怎么用的应用：日均 < 1 次（一周总数 < 7）
        idleAppsCard()?.let { appsPage.addView(it) }

        appsPage.addView(TextView(this).apply { text = "📝 已选队列（置顶 / 上移 / 下移 / 删除）："; textSize = 15f; setPadding(0, 30, 0, 10); setTextColor(palette.textSecondary) })

        selectedListContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 20, 0, 0) }
        appsPage.addView(selectedListContainer)

        updateStatus()
        refreshUI()

        // 若是主题/形态切换引起的重建，回到原来的页面并滚回原位置
        if (restorePageIndex > 0) {
            val target = when (restorePageIndex) {
                1 -> appsPage
                2 -> settingsPage
                3 -> aboutPage
                4 -> guidePage
                else -> homePage
            }
            showPage(target)
            val y = restoreScrollY
            rootScroll.post { rootScroll.scrollTo(0, y) }
            restorePageIndex = -1
            restoreScrollY = 0
        }

        newApps = detectNewApps()

        // 冷启动按用户选的启动页落脚（重建恢复优先，见上面的 restorePageIndex）
        if (restorePageIndex < 0 && startPage == 1) {
            showPage(appsPage)
        } else if (currentPageView == null) {
            // 默认停在统计页时不会走 showPage，这里补上，否则滑动找不到起点
            currentPageView = homePage
        }

        // 首次打开（或清数据后重装）先把使用说明摆到眼前，省得用户不知道要去放行自启
        val firstRunPrefs = getSharedPreferences("QuickPrefs", Context.MODE_PRIVATE)
        if (!firstRunPrefs.getBoolean("guide_shown", false)) {
            firstRunPrefs.edit().putBoolean("guide_shown", true).apply()
            showPage(guidePage)
        }
    }

    /** 重建 Activity 后回到原来的页面与滚动位置；不这么做，改个主题就被弹回主页 */
    private fun recreateKeepingPage() {
        restorePageIndex = currentPageIndex
        restoreScrollY = if (::rootScroll.isInitialized) rootScroll.scrollY else 0
        recreate()
    }

    private fun showPage(page: View) {
        currentPageIndex = when (page) {
            appsPage -> 1
            settingsPage -> 2
            aboutPage -> 3
            guidePage -> 4
            else -> 0
        }
        currentPageView = page
        val pages = listOf(homePage, appsPage, settingsPage, aboutPage, guidePage)
        for (p in pages) p.visibility = if (p === page) View.VISIBLE else View.GONE
        if (page === settingsPage) buildSettingsPage()   // 每次进入重建，右侧数值保持最新
        if (page === aboutPage) buildAboutPage()
        if (page === guidePage) buildGuidePage()
        if (::statusFloat.isInitialized) {
            val mainPages = page === homePage || page === appsPage || page === settingsPage
            statusFloat.visibility =
                if (mainPages && statusPosition == STATUS_BOTTOM) View.VISIBLE else View.GONE
        }
        if (::bottomNav.isInitialized) {
            // 换页时把导航重新露出来，别让下一页一进来就看不到导航
            navHidden = false
            bottomNav.animate().cancel()
            bottomNav.translationY = 0f
            if (::statusFloat.isInitialized) {
                statusFloat.animate().cancel()
                statusFloat.translationY = 0f
            }
        }
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
        tvStatus.setTextColor(if (running) palette.accent else palette.dangerText)
        tvStatusSub.text = "${selectedApps.size} 个应用 · 今日打开 ${todayOpenCount()} 次"
        btnPower.text = if (running) "Ⅱ" else "▶"
        btnPower.setTextColor(if (running) palette.accent else palette.dangerText)
        val powerRadius = when (statusStyle) {
            STYLE_STRIP -> 18f
            STYLE_BUTTON -> 28f
            else -> 23f
        }
        btnPower.background = roundedBg(
            if (running) palette.accentSoft else palette.dangerBg,
            powerRadius
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
    // 横向滑动的起点与时间，用于在抬手瞬间判定是「翻页」还是「点了一下」
    private var touchDownX = 0f
    private var touchDownY = 0f
    private var touchDownAt = 0L
    private var swipeConsumed = false

    /**
     * 左右滑切页。判定放在 ACTION_UP：位移够大就切页并把这次事件吞掉，
     * 否则子 View 仍会收到 UP 而误触发点击（之前用 GestureDetector 就踩了这个坑）。
     */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touchDownX = ev.x
                touchDownY = ev.y
                touchDownAt = ev.eventTime
                swipeConsumed = false
            }
            MotionEvent.ACTION_UP -> {
                if (!swipeConsumed) {
                    val dx = ev.x - touchDownX
                    val dy = ev.y - touchDownY
                    val horizontal = abs(dx) > dpToPx(56f) && abs(dx) > abs(dy) * 1.5f
                    if (horizontal && trySwipePage(if (dx < 0) 1 else -1)) {
                        swipeConsumed = true
                        return true   // 吞掉这次抬手，防止穿透成点击
                    }
                }
            }
            MotionEvent.ACTION_CANCEL -> swipeConsumed = false
        }
        return super.dispatchTouchEvent(ev)
    }

    /** dir: 1 = 往左滑（下一页），-1 = 往右滑（上一页） */
    private fun trySwipePage(dir: Int): Boolean {
        val pages = listOf(homePage, appsPage, settingsPage)
        val idx = pages.indexOfFirst { it === currentPageView }
        if (idx < 0) return false
        val target = idx + dir
        if (target !in pages.indices) return false
        showPage(pages[target])
        return true
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        // 系统深浅色切换会重建 Activity，这里把页面位置带上
        restorePageIndex = currentPageIndex
        restoreScrollY = if (::rootScroll.isInitialized) rootScroll.scrollY else 0
    }

    override fun onBackPressed() {
        when {
            ::aboutPage.isInitialized && aboutPage.visibility == View.VISIBLE -> showPage(settingsPage)
            ::guidePage.isInitialized && guidePage.visibility == View.VISIBLE -> showPage(settingsPage)
            ::appsPage.isInitialized && appsPage.visibility == View.VISIBLE -> showPage(settingsPage)
            ::settingsPage.isInitialized && settingsPage.visibility == View.VISIBLE -> showPage(homePage)
            else -> super.onBackPressed()
        }
    }

    /** 状态栏 / 导航栏跟着底色走，深色底下把图标刷亮，否则看不见 */
    private fun applySystemBars() {
        try {
            window.statusBarColor = palette.bg
            window.navigationBarColor = palette.bg
            val lightIcons = !palette.isDark
            if (Build.VERSION.SDK_INT >= 30) {
                window.insetsController?.setSystemBarsAppearance(
                    if (lightIcons) android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS else 0,
                    android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                )
            } else if (Build.VERSION.SDK_INT >= 23) {
                @Suppress("DEPRECATION")
                val flags = window.decorView.systemUiVisibility
                @Suppress("DEPRECATION")
                window.decorView.systemUiVisibility = if (lightIcons) {
                    flags or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                } else {
                    flags and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
                }
            }
        } catch (e: Exception) {
            // 状态栏只是外观装饰，任何机型差异都不该让应用打不开
        }
    }

    /** 给主题色套一个透明度（0~255），用于悬浮底栏这类需要半透的效果 */
    private fun alphaOf(color: Int, alpha: Int): Int =
        (color and 0x00FFFFFF) or ((alpha and 0xFF) shl 24)

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
            background = roundedBg(alphaOf(palette.card, 242), 28f, palette.divider)
        }
        navMonitor = navItem("统计") { showPage(homePage) }
        navApps = navItem("图标") { showPage(appsPage) }
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
        setTextColor(palette.textSecondary)
        setOnClickListener { onClick() }
    }

    // ---------- 设置页：分组条目 + 点击弹窗（每次进入重建，数值永远是最新的） ----------

    private var settingsGroupBox: LinearLayout? = null

    private fun buildSettingsPage() {
        settingsPage.removeAllViews()
        settingsGroupBox = null
        settingsPage.addView(TextView(this).apply {
            text = "设置"
            textSize = 20f
            setTextColor(palette.textPrimary)
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, 8, 0, 4)
        })

        settingsGroup("外观")
        settingsRow("背景", AppTheme.appearanceLabels[palette.appearance]) { showAppearanceDialog() }
        settingsRow("主题色", AppTheme.accentLabels[palette.accentId]) { showAccentDialog() }
        settingsRow("状态卡位置", if (statusPosition == STATUS_BOTTOM) "底部" else "顶部") { showStatusPositionDialog() }
        settingsRow("状态卡形态", when (statusStyle) {
            STYLE_STRIP -> "细条"
            STYLE_BUTTON -> "仅按钮"
            else -> "完整卡片"
        }) { showStatusStyleDialog() }
        settingsRow("应用图标", AppIcon.labels[AppIcon.current(this)]) { showIconDialog() }
        settingsRow("跟随系统夜间", if (AppTheme.isFollowingNight(this)) "开" else "关") { toggleFollowNight() }
        if (AppTheme.isFollowingNight(this)) {
            settingsRow(
                "夜间背景",
                if (AppTheme.readNightAppearance(this) == AppTheme.APPEARANCE_DARK) "夜间激进" else "夜间温和"
            ) { showNightAppearanceDialog() }
        }

        settingsGroup("运行")
        settingsRow("掉线自检", watchdogLabel()) { showWatchdogDialog() }
        settingsRow("编辑应用与排序", "${selectedApps.size} 个应用") { showPage(appsPage) }

        settingsGroup("启动页")
        settingsRow("打开时进入", if (startPage == 1) "图标" else "统计") { showStartPageDialog() }

        settingsGroup("数据")
        settingsRow("打开记录", recordLimitLabel()) { showOpenRecordsDialog() }
        settingsRow("生成使用海报", "周报 / 月报，可保存分享") { showReportDialog() }
        settingsRow("导出使用记录", usageSummary()) { showExportUsageDialog() }
        settingsRow("导入使用记录", "合并或覆盖") { startImportUsage() }
        settingsRow("导出配置", "文件 + 剪贴板") { exportConfig() }
        settingsRow("导入配置", "选文件或粘贴") { showImportDialog() }
        settingsRow("清理已卸载应用", "") { pruneNow() }

        settingsGroup("其他")
        settingsRow("使用说明", "自启 / 锁任务 / 白名单") { showPage(guidePage) }
        settingsRow("桌面插件", "横版 4×2 / 竖版 2×4") { showWidgetTip() }
        settingsRow("关于", "v${appVersion()} · 更新记录") { showPage(aboutPage) }
    }

    private fun showReportDialog() {
        showChoiceDialog("生成使用海报", arrayOf("最近 7 天（周报）", "最近 30 天（月报）"), 0) { which ->
            val days = if (which == 0) 7 else 30
            val bmp = try {
                buildReportBitmap(days)
            } catch (e: Exception) {
                Toast.makeText(this, "生成失败：" + e.message, Toast.LENGTH_LONG).show()
                return@showChoiceDialog
            }
            val iv = ImageView(this).apply {
                setImageBitmap(bmp)
                adjustViewBounds = true
                scaleType = ImageView.ScaleType.FIT_CENTER
                setPadding(dpToPx(12f), dpToPx(8f), dpToPx(12f), dpToPx(8f))
            }
            newDialog()
                .setTitle(if (days == 7) "本周使用海报" else "本月使用海报")
                .setView(iv)
                .setPositiveButton("保存到相册") { _, _ -> saveReport(bmp, days) }
                .setNeutralButton("直接分享") { _, _ -> shareReport(bmp, days) }
                .setNegativeButton("关闭", null)
                .show()
        }
    }

    /** 纯本地画一张海报：底色跟随主题，不联网、不上传 */
    private fun buildReportBitmap(days: Int): android.graphics.Bitmap {
        val w = 720
        val h = 1000
        val bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(bmp)
        c.drawColor(palette.bg)

        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        val pm = packageManager
        val since = System.currentTimeMillis() - days * 86_400_000L
        val counts = NotifyCore.countsByPackageSince(this, since).toMap()
        val total = counts.values.sum()
        val top = counts.entries.sortedByDescending { it.value }.take(5)

        paint.color = palette.textPrimary
        paint.textSize = 46f
        paint.typeface = android.graphics.Typeface.create(
            android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD
        )
        c.drawText(if (days == 7) "这周我打开了" else "这月我打开了", 52f, 120f, paint)

        paint.color = palette.accent
        paint.textSize = 128f
        val numText = total.toString()
        c.drawText(numText, 52f, 268f, paint)
        val numWidth = paint.measureText(numText)

        paint.color = palette.textPrimary
        paint.textSize = 40f
        c.drawText(" 次", 52f + numWidth, 268f, paint)

        paint.textSize = 26f
        paint.typeface = android.graphics.Typeface.DEFAULT
        paint.color = palette.textSecondary
        val fmt = java.text.SimpleDateFormat("M月d日", java.util.Locale.getDefault())
        val from = fmt.format(java.util.Date(since))
        val to = fmt.format(java.util.Date())
        c.drawText("$from - $to · 共 ${counts.size} 个应用", 52f, 320f, paint)

        var y = 400f
        paint.color = palette.textPrimary
        paint.textSize = 30f
        paint.typeface = android.graphics.Typeface.create(
            android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD
        )
        c.drawText("打开最多的几个", 52f, y, paint)
        y += 46f

        val maxCount = top.firstOrNull()?.value?.coerceAtLeast(1) ?: 1
        paint.typeface = android.graphics.Typeface.DEFAULT
        for ((pkg, n) in top) {
            val label = try {
                pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
            } catch (e: Exception) {
                pkg
            }
            paint.textSize = 26f
            paint.color = palette.textPrimary
            c.drawText(label, 52f, y, paint)
            paint.textAlign = android.graphics.Paint.Align.RIGHT
            paint.color = palette.accent
            c.drawText(n.toString(), w - 52f, y, paint)
            paint.textAlign = android.graphics.Paint.Align.LEFT

            val barY = y + 14f
            paint.color = palette.track
            c.drawRoundRect(52f, barY, w - 52f, barY + 10f, 5f, 5f, paint)
            paint.color = palette.accent
            val barW = 52f + (w - 104f) * (n.toFloat() / maxCount)
            c.drawRoundRect(52f, barY, barW, barY + 10f, 5f, 5f, paint)
            y += 64f
        }

        paint.textSize = 22f
        paint.color = palette.textTertiary
        paint.textAlign = android.graphics.Paint.Align.CENTER
        c.drawText("快捷通知 · 数据只存在这台手机里", w / 2f, h - 56f, paint)
        paint.textAlign = android.graphics.Paint.Align.LEFT

        return bmp
    }

    /** 存进系统相册（Pictures/快捷通知），不需要存储权限 */
    private fun saveReport(bmp: android.graphics.Bitmap, days: Int): android.net.Uri? {
        val kind = if (days == 7) "周报" else "月报"
        val name = "快捷通知-$kind-${System.currentTimeMillis()}.png"
        return try {
            val values = android.content.ContentValues().apply {
                put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, name)
                put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/png")
                if (Build.VERSION.SDK_INT >= 29) {
                    put(
                        android.provider.MediaStore.Images.Media.RELATIVE_PATH,
                        "Pictures/快捷通知"
                    )
                }
            }
            val uri = contentResolver.insert(
                android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values
            ) ?: return null
            contentResolver.openOutputStream(uri)?.use {
                bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            Toast.makeText(this, "已存到相册：Pictures/快捷通知", Toast.LENGTH_LONG).show()
            uri
        } catch (e: Exception) {
            Toast.makeText(this, "保存失败：" + e.message, Toast.LENGTH_LONG).show()
            null
        }
    }

    private fun shareReport(bmp: android.graphics.Bitmap, days: Int) {
        val uri = saveReport(bmp, days) ?: return
        try {
            startActivity(
                Intent(Intent.ACTION_SEND).apply {
                    type = "image/png"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            )
        } catch (e: Exception) {
            Toast.makeText(this, "没有可分享的应用，图片已保存到相册", Toast.LENGTH_LONG).show()
        }
    }

    private fun showStartPageDialog() {
        showChoiceDialog("打开时进入", arrayOf("统计（数据面板）", "图标（应用列表）"), startPage) { which ->
            startPage = which
            getSharedPreferences("QuickPrefs", Context.MODE_PRIVATE).edit()
                .putInt("start_page", which).apply()
            recreateKeepingPage()
        }
    }

    private fun showWidgetTip() {
        newDialog()
            .setTitle("添加桌面插件")
            .setMessage(
                "长按桌面空白处 → 小组件 / 插件 → 找到「快捷通知」→ 选横版或竖版拖到桌面。\n\n" +
                    "横版 4×2 放 16 个图标，竖版 2×4 放 8 个，都比桌面图标一行 4 个密。\n\n" +
                    "插件内容与通知栏同源：改了列表、换了应用，插件会自动跟着变。"
            )
            .setPositiveButton("知道了", null)
            .show()
    }

    private fun usageSummary(): String {
        val total = NotifyCore.countSince(this, 0L)
        val apps = NotifyCore.countsByPackageSince(this, 0L).size
        return "$total 次 · $apps 个应用"
    }

    private fun appVersion(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: ""
    } catch (e: Exception) {
        ""
    }

    private fun settingsGroup(title: String) {
        settingsPage.addView(TextView(this).apply {
            text = title
            textSize = 11f
            setTextColor(palette.textSecondary)
            setPadding(dpToPx(4f), dpToPx(16f), 0, dpToPx(6f))
        })
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBg(palette.card, 16f)
            setPadding(dpToPx(14f), dpToPx(2f), dpToPx(14f), dpToPx(2f))
        }
        settingsPage.addView(
            box,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        )
        settingsGroupBox = box
    }

    private fun settingsRow(title: String, value: String, onClick: () -> Unit) {
        val box = settingsGroupBox ?: return
        if (box.childCount > 0) {
            box.addView(View(this).apply { setBackgroundColor(palette.divider) },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1))
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dpToPx(14f), 0, dpToPx(14f))
            setOnClickListener { onClick() }
        }
        row.addView(TextView(this).apply {
            text = title
            textSize = 13f
            setTextColor(palette.textPrimary)
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        if (value.isNotEmpty()) {
            row.addView(TextView(this).apply {
                text = value
                textSize = 12f
                setTextColor(palette.textSecondary)
                setPadding(0, 0, dpToPx(8f), 0)
            })
        }
        row.addView(TextView(this).apply {
            text = "›"
            textSize = 15f
            setTextColor(palette.textTertiary)
        })
        box.addView(row)
    }

    private fun sortModeLabel(): String = when (NotifyCore.getSortMode(this)) {
        1 -> "全部按频率"
        2 -> "第一行定死"
        3 -> "置顶优先"
        else -> "手动顺序"
    }

    /** 应用页里的配置行：和设置页同一套观感，但挂在应用页下 */
    private fun appsOption(title: String, value: String, onClick: () -> Unit): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dpToPx(14f), dpToPx(12f), dpToPx(14f), dpToPx(12f))
            background = roundedBg(palette.card, 12f)
            setOnClickListener { onClick() }
        }
        row.addView(TextView(this).apply {
            text = title
            textSize = 13f
            setTextColor(palette.textPrimary)
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(TextView(this).apply {
            text = value
            textSize = 12f
            setTextColor(palette.textSecondary)
        })
        return row
    }

    private fun pagingLabel(): String = when {
        NotifyCore.isPagingEnabled(this) && NotifyCore.getPagingMode(this) == NotifyCore.PAGE_EXPAND -> "展开全部"
        !NotifyCore.isPagingEnabled(this) -> "关闭"
        NotifyCore.getPagingMode(this) == NotifyCore.PAGE_ROW -> "只翻第二行"
        else -> "整页翻"
    }

    private fun vibrateLabel(): String = when (NotifyCore.getVibrateMode(this)) {
        NotifyCore.VIBRATE_OFF -> "关闭"
        NotifyCore.VIBRATE_STRONG -> "强"
        else -> "弱"
    }

    private fun watchdogLabel(): String {
        val m = NotifyCore.getWatchdogIntervalMin(this)
        return if (m == NotifyCore.WATCHDOG_OFF) "关闭自愈" else "$m 分钟"
    }

    private fun recordLimitLabel(): String = when (val l = NotifyCore.getRecordLimit(this)) {
        NotifyCore.RECORD_OFF -> "关闭"
        NotifyCore.RECORD_UNLIMITED -> "无限制"
        else -> "$l 条"
    }

    private fun showChoiceDialog(title: String, options: Array<String>, current: Int, onPick: (Int) -> Unit) {
        newDialog()
            .setTitle(title)
            .setSingleChoiceItems(options, current) { dialog, which ->
                dialog.dismiss()
                onPick(which)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showSortModeDialog() {
        val opts = arrayOf(
            "全部自己排（手动队列顺序）",
            "全部按打开频率",
            "第一行定死 + 其余按频率",
            "置顶优先（队列里点 ☆ 指定）"
        )
        showChoiceDialog("排序方式", opts, NotifyCore.getSortMode(this)) { which ->
            getSharedPreferences("QuickPrefs", Context.MODE_PRIVATE).edit()
                .putInt("sort_mode", which).apply()
            NotifyCore.scheduleRefresh(this)
            recreateKeepingPage()
        }
    }

    private fun showPagingDialog() {
        val opts = arrayOf(
            "关闭（20 格全用）",
            "整页翻（每页 19 个）",
            "只翻第二行（第一行 10 个不动）",
            "展开全部（最多 4 行 40 个）"
        )
        val cur = when {
            !NotifyCore.isPagingEnabled(this) -> 0
            NotifyCore.getPagingMode(this) == NotifyCore.PAGE_ROW -> 2
            NotifyCore.getPagingMode(this) == NotifyCore.PAGE_EXPAND -> 3
            else -> 1
        }
        showChoiceDialog("翻页", opts, cur) { which ->
            val mode = when (which) {
                2 -> NotifyCore.PAGE_ROW
                3 -> NotifyCore.PAGE_EXPAND
                else -> NotifyCore.PAGE_WHOLE
            }
            getSharedPreferences("QuickPrefs", Context.MODE_PRIVATE).edit()
                .putBoolean("paging_enabled", which != 0)
                .putInt("paging_mode", mode)
                .putInt("current_page", 0)
                .apply()
            NotifyCore.scheduleRefresh(this)
            recreateKeepingPage()
        }
    }

    private fun showVibrateDialog() {
        showChoiceDialog("点击震动", arrayOf("关闭", "弱（推荐）", "强"), NotifyCore.getVibrateMode(this)) { which ->
            getSharedPreferences("QuickPrefs", Context.MODE_PRIVATE).edit()
                .putInt("vibrate_mode", which).apply()
            recreateKeepingPage()
        }
    }

    private fun showWatchdogDialog() {
        val values = intArrayOf(1, 3, 5, 10, 30, NotifyCore.WATCHDOG_OFF)
        val labels = arrayOf("1 分钟（默认）", "3 分钟", "5 分钟", "10 分钟", "30 分钟", "关闭自愈（只保留开机自启）")
        val cur = values.indexOf(NotifyCore.getWatchdogIntervalMin(this)).coerceAtLeast(0)
        showChoiceDialog("掉线自检间隔", labels, cur) { which ->
            NotifyCore.setWatchdogIntervalMin(this, values[which])
            Toast.makeText(
                this,
                if (values[which] == NotifyCore.WATCHDOG_OFF) "自愈已关闭（重启后仍会自启）"
                else "自检间隔：${values[which]} 分钟",
                Toast.LENGTH_SHORT
            ).show()
            buildSettingsPage()
        }
    }

    /** 背景档位与强调色各自独立：5 × 14 种组合，随用户搭 */
    private fun showAppearanceDialog() {
        val order = AppTheme.appearanceOrder
        val options = order.map { AppTheme.appearanceLabels[it] }.toTypedArray()
        val current = order.indexOf(palette.appearance).coerceAtLeast(0)
        showChoiceDialog("背景", options, current) { which ->
            val id = order[which]
            if (id != palette.appearance) {
                AppTheme.save(this, id, palette.accentId)
                recreateKeepingPage()   // 颜色在 onCreate 里取，重建最省事；但要留在原页
            }
        }
    }

    private fun showAccentDialog() {
        showChoiceDialog("主题色", AppTheme.accentLabels, palette.accentId) { which ->
            if (which != palette.accentId) {
                AppTheme.save(this, palette.appearance, which)
                recreateKeepingPage()
            }
        }
    }

    // ---------- 使用记录导出 / 导入（系统文件选择器，零权限零 root） ----------

    private var pendingExportText: String? = null

    private fun showExportUsageDialog() {
        val labels = arrayOf("最近 7 天", "最近 30 天（推荐）", "全部记录")
        val days = intArrayOf(7, 30, 0)
        showChoiceDialog("导出使用记录", labels, 1) { which ->
            pendingExportText = NotifyCore.exportUsageText(this, days[which])
            val name = "快捷通知-使用记录-${NotifyCore.dayKey(System.currentTimeMillis())}.txt"
            try {
                startActivityForResult(
                    Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TITLE, name)
                    },
                    REQ_EXPORT_USAGE
                )
            } catch (e: Exception) {
                pendingExportText = null
                Toast.makeText(this, "没找到可用的文件管理器", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun startImportUsage() {
        try {
            startActivityForResult(
                Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "text/plain"
                },
                REQ_IMPORT_USAGE
            )
        } catch (e: Exception) {
            Toast.makeText(this, "没找到可用的文件管理器", Toast.LENGTH_LONG).show()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val uri = data?.data ?: return

        when (requestCode) {
            REQ_EXPORT_USAGE -> {
                val text = pendingExportText
                pendingExportText = null
                if (text == null) return
                try {
                    contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray(Charsets.UTF_8)) }
                    Toast.makeText(this, "已导出，可在这里改名或转发", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(this, "写入失败：${e.message}", Toast.LENGTH_LONG).show()
                }
            }
            REQ_IMPORT_USAGE -> {
                val text = try {
                    contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
                } catch (e: Exception) {
                    null
                }
                if (text.isNullOrEmpty()) {
                    Toast.makeText(this, "读取失败，或文件是空的", Toast.LENGTH_LONG).show()
                    return
                }
                newDialog()
                    .setTitle("导入使用记录")
                    .setMessage("合并：同一天取较大值，明细按时间戳去重，导两次不会翻倍。\n覆盖：先清空现有记录再写入。")
                    .setPositiveButton("合并") { _, _ -> doImportUsage(text, true) }
                    .setNegativeButton("覆盖") { _, _ -> doImportUsage(text, false) }
                    .setNeutralButton("取消", null)
                    .show()
            }
        }
    }

    private fun doImportUsage(text: String, merge: Boolean) {
        val result = NotifyCore.importUsageText(this, text, merge)
        Toast.makeText(this, result.message, Toast.LENGTH_LONG).show()
        if (result.ok) {
            NotifyCore.scheduleRefresh(this)   // 排序若为「按频率」，记录一变顺序就该跟着变
            buildSettingsPage()
            updateDashboard()
        }
    }

    // ---------- 关于页 ----------

    private fun pageBack(label: String, onClick: () -> Unit): TextView = TextView(this).apply {
        text = "‹ $label"
        textSize = 13f
        setTextColor(palette.accent)
        setPadding(0, dpToPx(4f), 0, dpToPx(14f))
        setOnClickListener { onClick() }
    }

    private fun buildAboutPage() {
        aboutPage.removeAllViews()
        aboutPage.addView(pageBack("设置") { showPage(settingsPage) })

        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dpToPx(14f))
        }
        head.addView(ImageView(this).apply {
            try {
                setImageDrawable(packageManager.getApplicationIcon(packageName))
            } catch (e: Exception) {
                // 图标读不到就不显示，不影响其它内容
            }
            scaleType = ImageView.ScaleType.FIT_CENTER
        }, LinearLayout.LayoutParams(dpToPx(44f), dpToPx(44f)))

        val info = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        info.addView(TextView(this).apply {
            text = AppInfo.APP_NAME
            textSize = 15f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(palette.textPrimary)
        })
        info.addView(TextView(this).apply {
            text = "v${appVersion()} · 本地运行，无网络权限"
            textSize = 11f
            setTextColor(palette.textSecondary)
            setPadding(0, dpToPx(3f), 0, 0)
        })
        head.addView(info, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            leftMargin = dpToPx(12f)
        })
        aboutPage.addView(head)

        aboutPage.addView(TextView(this).apply {
            text = "不申请 INTERNET 权限，不读取通知内容；打开记录只存包名和时间，可随时清空。"
            textSize = 11f
            setLineSpacing(dpToPx(3f).toFloat(), 1f)
            setTextColor(palette.textSecondary)
            setPadding(dpToPx(12f), dpToPx(10f), dpToPx(12f), dpToPx(10f))
            background = roundedBg(palette.cardSoft, 12f)
        })

        aboutPage.addView(TextView(this).apply {
            text = "更新记录"
            textSize = 12f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(palette.textPrimary)
            setPadding(0, dpToPx(20f), 0, dpToPx(8f))
        })

        for (entry in AppInfo.changelog) {
            val block = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dpToPx(12f), dpToPx(8f), 0, dpToPx(8f))
            }
            val titleRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            titleRow.addView(TextView(this).apply {
                text = entry.version
                textSize = 12f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(palette.textPrimary)
            })
            titleRow.addView(TextView(this).apply {
                text = entry.date
                textSize = 10f
                setTextColor(palette.textTertiary)
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                leftMargin = dpToPx(8f)
            })
            block.addView(titleRow)

            for (item in entry.items) {
                block.addView(TextView(this).apply {
                    text = item
                    textSize = 11f
                    setLineSpacing(dpToPx(3f).toFloat(), 1f)
                    setTextColor(palette.textSecondary)
                    setPadding(0, dpToPx(3f), 0, 0)
                })
            }
            aboutPage.addView(block)
        }

        aboutPage.addView(TextView(this).apply {
            text = "所有数据只在这台手机里"
            textSize = 10f
            gravity = Gravity.CENTER
            setTextColor(palette.textTertiary)
            setPadding(0, dpToPx(18f), 0, 0)
        })
    }

    // ---------- 使用说明页 ----------

    private fun buildGuidePage() {
        guidePage.removeAllViews()
        guidePage.addView(pageBack("设置") { showPage(settingsPage) })

        guidePage.addView(TextView(this).apply {
            text = "使用说明"
            textSize = 15f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(palette.textPrimary)
        })
        guidePage.addView(TextView(this).apply {
            text = "通知栏想常驻，需要放行这几处。做完一次，以后升级不用重做。"
            textSize = 11f
            setLineSpacing(dpToPx(3f).toFloat(), 1f)
            setTextColor(palette.textSecondary)
            setPadding(0, dpToPx(5f), 0, dpToPx(14f))
        })

        guideStep(1, "允许自启动", "设置 → 应用 → 自启动管理，把「快捷通知」打开。不开的话重启手机后通知不会自己回来。") {
            openStartupSettings()
        }
        guideStep(2, "不受电池限制", "把本应用设为「不限制」或加进电池优化白名单。省电策略会在息屏后掐掉后台。") {
            openBatterySettings()
        }
        guideStep(3, "锁定最近任务", "从底部上滑打开最近任务，长按「快捷通知」卡片下拉锁定。之后一键清理也带不走它。", null)
        guideStep(4, "允许通知", "别把通知设成「静默」或折叠，否则图标会被收进次要通知里。") {
            openNotificationSettings()
        }

        guidePage.addView(TextView(this).apply {
            text = "两个容易混淆的概念"
            textSize = 11f
            setTextColor(palette.textTertiary)
            setPadding(0, dpToPx(16f), 0, dpToPx(6f))
        })
        guidePage.addView(TextView(this).apply {
            text = "已选队列：要显示在通知栏的那 20 个应用。\n黑名单：挑应用时不想看见的，只影响选择列表，不影响通知栏。"
            textSize = 11f
            setLineSpacing(dpToPx(4f).toFloat(), 1f)
            setTextColor(palette.textSecondary)
            setPadding(dpToPx(12f), dpToPx(10f), dpToPx(12f), dpToPx(10f))
            background = roundedBg(palette.card, 12f, palette.divider)
        })

        guidePage.addView(TextView(this).apply {
            text = "掉线了怎么办：回到「监测」点一下右上角按钮，会重新挂上并排好自检。"
            textSize = 11f
            setLineSpacing(dpToPx(3f).toFloat(), 1f)
            setTextColor(palette.successText)
            setPadding(dpToPx(12f), dpToPx(9f), dpToPx(12f), dpToPx(9f))
            background = roundedBg(palette.successBg, 10f)
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dpToPx(12f)
        })
    }

    private fun guideStep(index: Int, title: String, desc: String, action: (() -> Unit)?) {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpToPx(12f), dpToPx(11f), dpToPx(12f), dpToPx(11f))
            background = roundedBg(palette.cardSoft, 12f)
        }
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        head.addView(TextView(this).apply {
            text = index.toString()
            textSize = 10f
            gravity = Gravity.CENTER
            setTextColor(palette.card)
            background = roundedBg(palette.accent, 9f)
        }, LinearLayout.LayoutParams(dpToPx(18f), dpToPx(18f)))

        head.addView(TextView(this).apply {
            text = title
            textSize = 12f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(palette.textPrimary)
            setPadding(dpToPx(8f), 0, 0, 0)
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        if (action != null) {
            head.addView(TextView(this).apply {
                text = "去设置"
                textSize = 10f
                setTextColor(palette.accent)
                setPadding(dpToPx(12f), dpToPx(4f), dpToPx(12f), dpToPx(4f))
                background = roundedBg(palette.accentSoft, 8f)
                setOnClickListener { action() }
            })
        }
        card.addView(head)
        card.addView(TextView(this).apply {
            text = desc
            textSize = 11f
            setLineSpacing(dpToPx(3f).toFloat(), 1f)
            setTextColor(palette.textSecondary)
            setPadding(dpToPx(26f), dpToPx(6f), 0, 0)
        })
        guidePage.addView(card, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dpToPx(8f) })
    }

    private fun openStartupSettings() {
        try {
            startActivity(Intent().setComponent(ComponentName(
                "com.oplus.battery", "com.oplus.startupapp.view.StartupAppListActivity"
            )))
        } catch (e: Exception) {
            Toast.makeText(this, "没找到自启动页，请到 设置 → 应用 → 自启动管理 手动打开", Toast.LENGTH_LONG).show()
        }
    }

    private fun openBatterySettings() {
        try {
            startActivity(Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        } catch (e: Exception) {
            Toast.makeText(this, "请到 设置 → 电池 → 应用耗电管理 里设为「不限制」", Toast.LENGTH_LONG).show()
        }
    }

    private fun openNotificationSettings() {
        try {
            startActivity(
                Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, packageName)
            )
        } catch (e: Exception) {
            Toast.makeText(this, "请到 设置 → 通知 里允许本应用通知", Toast.LENGTH_LONG).show()
        }
    }

    /** 跟随系统夜间：切换后重建，颜色由 AppTheme.current 按系统状态决定 */
    private fun showNightAppearanceDialog() {
        val opts = arrayOf("夜间温和", "夜间激进")
        val cur = if (AppTheme.readNightAppearance(this) == AppTheme.APPEARANCE_DARK) 1 else 0
        showChoiceDialog("夜间背景", opts, cur) { which ->
            AppTheme.saveNightAppearance(
                this,
                if (which == 1) AppTheme.APPEARANCE_DARK else AppTheme.APPEARANCE_DIM
            )
            recreateKeepingPage()
        }
    }

    private fun toggleFollowNight() {
        val follow = !AppTheme.isFollowingNight(this)
        AppTheme.setFollowingNight(this, follow)
        recreateKeepingPage()
        Toast.makeText(
            this,
            if (follow) "已跟随系统：系统深色时自动用夜间温和" else "已关闭跟随，使用你选的背景",
            Toast.LENGTH_SHORT
        ).show()
    }

    /** 夜间模式下弹窗走深色主题，否则白底在暗环境里太刺眼 */
    private fun newDialog(): android.app.AlertDialog.Builder =
        if (palette.isDark) {
            android.app.AlertDialog.Builder(this, R.style.AppDialogDark)
        } else {
            android.app.AlertDialog.Builder(this, R.style.AppDialogLight)
        }

    private fun showIconDialog() {
        val current = AppIcon.current(this)
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpToPx(10f), dpToPx(6f), dpToPx(10f), dpToPx(6f))
        }
        val dialog = newDialog()
            .setTitle("应用图标")
            .setView(list)
            .setNegativeButton("取消", null)
            .create()

        for (i in AppIcon.labels.indices) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dpToPx(10f), dpToPx(9f), dpToPx(10f), dpToPx(9f))
                background = if (i == current) roundedBg(palette.accentSoft, 12f, palette.accent) else null
                setOnClickListener {
                    dialog.dismiss()
                    if (i != current) {
                        AppIcon.apply(this@MainActivity, i)
                        Toast.makeText(
                            this@MainActivity,
                            "已切换。桌面图标可能延迟两三秒才变；若原图标变成空白，从应用列表重新拖一次即可。",
                            Toast.LENGTH_LONG
                        ).show()
                        buildSettingsPage()
                    }
                }
            }
            row.addView(ImageView(this).apply {
                setImageResource(AppIcon.previewRes(i))
                scaleType = ImageView.ScaleType.FIT_CENTER
            }, LinearLayout.LayoutParams(dpToPx(38f), dpToPx(38f)))

            row.addView(TextView(this).apply {
                text = AppIcon.labels[i]
                textSize = 13f
                setTextColor(if (i == current) palette.accent else palette.textPrimary)
                setPadding(dpToPx(12f), 0, 0, 0)
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

            if (i == current) {
                row.addView(TextView(this).apply {
                    text = "使用中"
                    textSize = 10f
                    setTextColor(palette.textPrimary)
                    setTypeface(null, android.graphics.Typeface.BOLD)
                })
            }
            list.addView(row)
        }
        dialog.show()
    }

    private fun showStatusPositionDialog() {
        val labels = arrayOf("顶部", "底部（导航上方）")
        val ids = intArrayOf(STATUS_TOP, STATUS_BOTTOM)
        val current = if (statusPosition == STATUS_BOTTOM) 1 else 0
        showChoiceDialog("状态卡位置", labels, current) { which ->
            if (ids[which] != statusPosition) {
                getSharedPreferences("QuickPrefs", Context.MODE_PRIVATE).edit()
                    .putInt("status_position", ids[which]).apply()
                recreateKeepingPage()
            }
        }
    }

    private fun showStatusStyleDialog() {
        showChoiceDialog("状态卡形态", arrayOf("完整卡片", "细条", "仅按钮"), statusStyle) { which ->
            if (which != statusStyle) {
                getSharedPreferences("QuickPrefs", Context.MODE_PRIVATE).edit()
                    .putInt("status_style", which).apply()
                recreateKeepingPage()
            }
        }
    }

    // ---------- 曲线时间刻度 ----------

    private fun dayLabel(daysAgo: Int): String {
        if (daysAgo <= 0) return "今天"
        val cal = java.util.Calendar.getInstance()
        cal.timeInMillis = System.currentTimeMillis() - daysAgo * 86_400_000L
        return String.format(
            java.util.Locale.getDefault(), "%02d/%02d",
            cal.get(java.util.Calendar.MONTH) + 1, cal.get(java.util.Calendar.DAY_OF_MONTH)
        )
    }

    private fun hourLabel(hoursAgo: Int): String {
        if (hoursAgo <= 0) return "现在"
        val cal = java.util.Calendar.getInstance()
        cal.add(java.util.Calendar.HOUR_OF_DAY, -hoursAgo)
        return "${cal.get(java.util.Calendar.HOUR_OF_DAY)}时"
    }

    private fun monthLabel(monthsAgo: Int): String {
        if (monthsAgo <= 0) return "本月"
        val cal = java.util.Calendar.getInstance()
        cal.add(java.util.Calendar.MONTH, -monthsAgo)
        return "${cal.get(java.util.Calendar.MONTH) + 1}月"
    }

    /** 最近 days 天的刻度：首尾 + 三个等分点 */
    private fun axisLabelsForDays(days: Int): List<String> {
        val last = (days - 1).coerceAtLeast(1)
        return listOf(
            dayLabel(last),
            dayLabel(last * 3 / 4),
            dayLabel(last / 2),
            dayLabel(last / 4),
            dayLabel(0)
        )
    }

    /** 按时间档给刻度：今日按小时、7/30 天按日期、全部按月 */
    private fun axisLabelsFor(range: Int): List<String> = when (range) {
        0 -> listOf(hourLabel(11), hourLabel(8), hourLabel(6), hourLabel(3), hourLabel(0))
        1 -> axisLabelsForDays(7)
        2 -> axisLabelsForDays(30)
        else -> listOf(monthLabel(11), monthLabel(8), monthLabel(5), monthLabel(2), monthLabel(0))
    }

    /** 状态卡三种形态：完整卡片 / 细条 / 仅圆形按钮；位置由调用方决定 */
    private fun buildStatusView(): View {
        tvStatus = TextView(this).apply {
            text = "● 已停止"
            textSize = if (statusStyle == STYLE_STRIP) 12f else 15f
            setTypeface(null, android.graphics.Typeface.BOLD)
        }
        tvStatusSub = TextView(this).apply {
            text = "0 个应用 · 今日打开 0 次"
            textSize = 11f
            setTextColor(palette.textSecondary)
            setPadding(0, if (statusStyle == STYLE_STRIP) dpToPx(2f) else dpToPx(5f), 0, 0)
        }
        btnPower = TextView(this).apply {
            text = "▶"
            gravity = Gravity.CENTER
            setTextColor(palette.accent)
            setOnClickListener { toggleService() }
        }

        when (statusStyle) {
            STYLE_STRIP -> {
                val strip = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dpToPx(14f), dpToPx(8f), dpToPx(8f), dpToPx(8f))
                    background = roundedBg(palette.card, 26f)
                }
                val text = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                text.addView(tvStatus)
                text.addView(tvStatusSub)
                strip.addView(text, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                btnPower.textSize = 14f
                btnPower.background = roundedBg(palette.accentSoft, 18f)
                strip.addView(btnPower, LinearLayout.LayoutParams(dpToPx(36f), dpToPx(36f)))
                return strip
            }
            STYLE_BUTTON -> {
                val host = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.END or Gravity.CENTER_VERTICAL
                }
                // 状态文字保留但不显示，否则 updateStatus() 里的空判会直接返回
                tvStatus.visibility = View.GONE
                tvStatusSub.visibility = View.GONE
                host.addView(tvStatus)
                host.addView(tvStatusSub)
                btnPower.textSize = 18f
                btnPower.background = roundedBg(palette.accent, 28f)
                btnPower.elevation = dpToPx(4f).toFloat()
                host.addView(btnPower, LinearLayout.LayoutParams(dpToPx(52f), dpToPx(52f)))
                return host
            }
            else -> {
                val card = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dpToPx(18f), dpToPx(16f), dpToPx(14f), dpToPx(16f))
                    background = roundedBg(palette.card, 20f)
                }
                val text = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                text.addView(tvStatus)
                text.addView(tvStatusSub)
                card.addView(text, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                btnPower.textSize = 20f
                btnPower.background = roundedBg(palette.accentSoft, 23f)
                card.addView(btnPower, LinearLayout.LayoutParams(dpToPx(46f), dpToPx(46f)))
                return card
            }
        }
    }

    private fun buildHomeDashboard() {
        homePage.removeAllViews()

        // 先把控件建出来（updateStatus 依赖 tvStatus 等），真正插入放到最后
        val statusView = buildStatusView()

        val seg = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dpToPx(4f), dpToPx(4f), dpToPx(4f), dpToPx(4f))
            background = roundedBg(palette.track, 18f)
        }
        segToday = segmentItem("今日", 0)
        segSeven = segmentItem("7 天", 1)
        segMonth = segmentItem("一个月", 2)
        segAll = segmentItem("全部", 3)
        seg.addView(segToday, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
        seg.addView(segSeven, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
        seg.addView(segMonth, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
        seg.addView(segAll, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
        val chartCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpToPx(18f), dpToPx(16f), dpToPx(18f), dpToPx(8f))
            background = roundedBg(palette.card, 20f)
        }
        chartCard.addView(TextView(this).apply {
            text = "打开次数"
            textSize = 11f
            setTextColor(palette.textSecondary)
        })
        val numRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.BOTTOM }
        tvChartNumber = TextView(this).apply {
            text = "0"
            textSize = 24f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(palette.textPrimary)
        }
        tvChartDelta = TextView(this).apply {
            text = "较昨日 +0"
            textSize = 11f
            setTextColor(palette.accent)
            setPadding(dpToPx(8f), 0, 0, dpToPx(4f))
        }
        numRow.addView(tvChartNumber)
        numRow.addView(tvChartDelta)
        chartCard.addView(numRow)
        chartView = UsageChartView(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(100f))
            setColors(palette.accent, palette.accentSoft, palette.textTertiary)
        }
        chartCard.addView(chartView)
        homePage.addView(
            chartCard,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { bottomMargin = dpToPx(10f) }
        )

        // 时间档位放在曲线下面：换档时手不用往上够，眼睛也不用往回跳
        homePage.addView(
            seg,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(36f))
                .apply { bottomMargin = dpToPx(12f) }
        )

        val rankCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpToPx(18f), dpToPx(14f), dpToPx(18f), dpToPx(8f))
            background = roundedBg(palette.card, 20f)
        }
        val rankHead = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dpToPx(4f))
        }
        tvRankTitle = TextView(this).apply {
            text = "按应用查看"
            textSize = 12f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(palette.textPrimary)
        }
        rankHead.addView(tvRankTitle, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        rankHead.addView(TextView(this).apply {
            text = "点行看单曲线"
            textSize = 10f
            setTextColor(palette.textTertiary)
        })
        rankCard.addView(rankHead)
        rankContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        rankCard.addView(rankContainer)
        homePage.addView(
            rankCard,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { bottomMargin = dpToPx(12f) }
        )

        val previewCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpToPx(18f), dpToPx(14f), dpToPx(18f), dpToPx(12f))
            background = roundedBg(palette.card, 20f)
            setOnClickListener { showPage(appsPage) }
        }
        previewCard.addView(TextView(this).apply {
            text = "通知栏预览"
            textSize = 11f
            setTextColor(palette.textSecondary)
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
            setTextColor(palette.textSecondary)
        })
        homePage.addView(
            previewCard,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        )

        // 状态卡最后处理：顶部放进第 0 位；底部回到悬浮层（用户更喜欢钉在导航上方那种）
        val statusParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dpToPx(4f) }
        if (statusPosition == STATUS_TOP) {
            homePage.addView(statusView, 0, statusParams)
        } else {
            statusFloat.removeAllViews()
            statusFloat.addView(
                statusView,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
                )
            )
            statusFloat.visibility = View.VISIBLE
        }

        updateSegments()
        updateStatus()
        updateDashboard()
        updateNav(homePage)
    }

    private fun segmentItem(label: String, range: Int): TextView = TextView(this).apply {
        text = label
        textSize = 12f
        gravity = Gravity.CENTER
        setTextColor(palette.textSecondary)
        setOnClickListener {
            chartRange = range
            rankExpanded = false
            updateSegments()
            updateDashboard()
        }
    }

    private fun updateSegments() {
        if (!::segToday.isInitialized) return
        val items = listOf(segToday to 0, segSeven to 1, segMonth to 2, segAll to 3)
        for ((view, range) in items) {
            val active = chartRange == range
            view.background = if (active) roundedBg(palette.card, 12f) else null
            view.setTextColor(if (active) palette.textPrimary else palette.textSecondary)
            view.setTypeface(null, if (active) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
        }
    }

    private fun updateNav(page: View) {
        if (!::navMonitor.isInitialized) return
        val active = when (page) {
            homePage -> 0
            appsPage -> 1
            settingsPage, aboutPage, guidePage -> 2
            else -> 0
        }
        val items = listOf(navMonitor to 0, navApps to 1, navSettings to 2)
        for ((view, index) in items) {
            val on = active == index
            view.background = if (on) roundedBg(palette.accentSoft, 24f) else null
            view.setTextColor(if (on) palette.accent else palette.textSecondary)
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

    private fun todayOpenCount(): Int =
        NotifyCore.countSince(this, NotifyCore.dayStart(System.currentTimeMillis()))

    private fun yesterdayOpenCount(): Int {
        val start = NotifyCore.dayStart(System.currentTimeMillis())
        return NotifyCore.countSince(this, start - 86_400_000L) - NotifyCore.countSince(this, start)
    }

    /** 当前时间档对应的起点：0=今日 1=7天 2=30天 3=全部（0 表示不限） */
    private fun rangeStart(): Long {
        val start = NotifyCore.dayStart(System.currentTimeMillis())
        return when (chartRange) {
            0 -> start
            1 -> start - 6 * 86_400_000L
            2 -> start - 29 * 86_400_000L
            else -> 0L
        }
    }

    private fun rangeLabel(): String = when (chartRange) {
        0 -> "今日"
        1 -> "7 天"
        2 -> "30 天"
        else -> "全部"
    }

    private fun updateDashboard() {
        if (!::chartView.isInitialized) return
        val now = System.currentTimeMillis()
        val start = NotifyCore.dayStart(now)

        val values: List<Int>
        val total: Int
        when (chartRange) {
            0 -> {
                total = NotifyCore.countSince(this, start)
                values = NotifyCore.seriesHourly(this, 12)
            }
            1 -> {
                total = NotifyCore.countSince(this, start - 6 * 86_400_000L)
                values = NotifyCore.seriesDaily(this, 7)
            }
            2 -> {
                total = NotifyCore.countSince(this, start - 29 * 86_400_000L)
                values = NotifyCore.seriesDaily(this, 30)
            }
            else -> {
                total = NotifyCore.countSince(this, 0L)
                values = NotifyCore.seriesMonthly(this, 12)
            }
        }
        tvChartNumber.text = total.toString()
        tvChartDelta.text = when (chartRange) {
            0 -> {
                val diff = todayOpenCount() - yesterdayOpenCount()
                if (diff >= 0) "较昨日 +$diff" else "较昨日 $diff"
            }
            1 -> "近 7 天"
            2 -> "近 30 天"
            else -> "累计"
        }
        if (::tvRankTitle.isInitialized) tvRankTitle.text = "按应用查看 · ${rangeLabel()}"
        chartView.setData(values, axisLabelsFor(chartRange))
        updateRank()
    }

    /** 曲线下方的「按应用查看」排行：每个 App 各自的次数，点行看单条曲线 */
    private fun updateRank() {
        if (!::rankContainer.isInitialized) return
        rankContainer.removeAllViews()

        val list = NotifyCore.countsByPackageSince(this, rangeStart())
        if (list.isEmpty()) {
            rankContainer.addView(TextView(this).apply {
                text = "这段还没有记录，点一下通知栏图标就记着了"
                textSize = 12f
                setTextColor(palette.textTertiary)
                setPadding(0, dpToPx(12f), 0, dpToPx(10f))
            })
            return
        }

        val max = list.first().second.coerceAtLeast(1)
        val limit = if (rankExpanded) list.size else minOf(RANK_COLLAPSED, list.size)
        for (item in list.take(limit)) {
            rankContainer.addView(rankRow(item.first, item.second, max))
        }
        if (list.size > RANK_COLLAPSED) {
            rankContainer.addView(TextView(this).apply {
                text = if (rankExpanded) "收起" else "展开全部 ${list.size} 个"
                textSize = 11f
                gravity = Gravity.CENTER
                setTextColor(palette.accent)
                setPadding(0, dpToPx(9f), 0, dpToPx(3f))
                setOnClickListener {
                    rankExpanded = !rankExpanded
                    updateRank()
                }
            })
        }
    }

    private fun rankRow(pkg: String, count: Int, max: Int): LinearLayout {
        val pm = packageManager
        val label = try {
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        } catch (e: Exception) { pkg }
        val icon = try { pm.getApplicationIcon(pkg) } catch (e: Exception) { null }

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dpToPx(9f), 0, dpToPx(9f))
            setOnClickListener { showAppUsageDialog(pkg, label) }
        }
        row.addView(ImageView(this).apply {
            setImageDrawable(icon)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }, LinearLayout.LayoutParams(dpToPx(20f), dpToPx(20f)))

        row.addView(TextView(this).apply {
            text = label
            textSize = 12f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setTextColor(palette.textPrimary)
            setPadding(dpToPx(8f), 0, dpToPx(6f), 0)
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        val ratio = (count.toFloat() / max).coerceIn(0.06f, 1f)
        val barWrap = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = roundedBg(palette.cardSoft, 2f)
        }
        barWrap.addView(View(this).apply { setBackgroundColor(palette.accent) },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, ratio))
        barWrap.addView(View(this),
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f - ratio))
        row.addView(barWrap, LinearLayout.LayoutParams(dpToPx(54f), dpToPx(4f)))

        row.addView(TextView(this).apply {
            text = count.toString()
            textSize = 12f
            setTypeface(null, android.graphics.Typeface.BOLD)
            gravity = Gravity.END
            setTextColor(palette.textPrimary)
        }, LinearLayout.LayoutParams(dpToPx(40f), LinearLayout.LayoutParams.WRAP_CONTENT))

        return row
    }

    /** 单个 App 的打开次数详情：今日/7天/30天/累计 + 最近 14 天曲线 */
    private fun showAppUsageDialog(pkg: String, label: String) {
        val base = NotifyCore.dayStart(System.currentTimeMillis())
        val today = NotifyCore.countSince(this, base, pkg)
        val week = NotifyCore.countSince(this, base - 6 * 86_400_000L, pkg)
        val month = NotifyCore.countSince(this, base - 29 * 86_400_000L, pkg)
        val all = NotifyCore.countSince(this, 0L, pkg)

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpToPx(20f), dpToPx(6f), dpToPx(20f), 0)
        }
        box.addView(TextView(this).apply {
            text = "今日 $today 次 · 7 天 $week 次"
            textSize = 13f
            setTextColor(palette.textPrimary)
        })
        box.addView(TextView(this).apply {
            text = "一个月 $month 次 · 累计 $all 次"
            textSize = 13f
            setTextColor(palette.textSecondary)
            setPadding(0, dpToPx(4f), 0, dpToPx(10f))
        })
        box.addView(UsageChartView(this).apply {
            setColors(palette.accent, palette.accentSoft, palette.textTertiary)
            setData(NotifyCore.seriesDaily(this@MainActivity, 14, pkg), axisLabelsForDays(14))
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(100f)
        ))

        newDialog()
            .setTitle(label)
            .setView(box)
            .setPositiveButton("知道了", null)
            .show()
    }

    /**
     * 找出新安装的应用：拿当前可启动列表跟 known_apps 做差集。
     * 第一次运行（known_apps 为空）时只登记、不标记，避免把现有几十个应用全标成新的。
     */
    private fun detectNewApps(): Set<String> {
        val prefs = getSharedPreferences("QuickPrefs", Context.MODE_PRIVATE)
        val stored = (prefs.getString("known_apps", "") ?: "")
            .split(",").filter { it.isNotEmpty() }.toSet()
        val current = allApps.map { it.activityInfo.packageName }.toSet()

        if (stored.isEmpty()) {
            prefs.edit().putString("known_apps", current.joinToString(",")).apply()
            return emptySet()
        }
        val fresh = current - stored
        if (fresh.isNotEmpty()) {
            prefs.edit().putString("known_apps", (stored + fresh).joinToString(",")).apply()
        }
        return fresh
    }

    /** 按设置过滤：黑名单 + 可选的系统应用 */
    private fun visibleApps(): List<ResolveInfo> {
        return allApps.filter { app ->
            val pkg = app.activityInfo.packageName
            if (hiddenApps.contains(pkg)) return@filter false
            if (hideSystemApps) {
                val flags = app.activityInfo.applicationInfo.flags
                val system = flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM != 0
                val updated = flags and android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP != 0
                // 被用户更新过的系统应用（如升级过的浏览器）算普通应用，保留
                if (system && !updated) return@filter false
            }
            true
        }
    }

    /** 多选列表排序：已添加的可选择置顶 / 置底 / 保持字母序 */
    private fun sortForPicker(apps: List<ResolveInfo>, selected: Set<String>): List<ResolveInfo> {
        val pm = packageManager
        fun label(r: ResolveInfo) = r.loadLabel(pm).toString()
        return when (addedPosition) {
            1 -> apps.sortedWith(
                compareByDescending<ResolveInfo> { selected.contains(it.activityInfo.packageName) }
                    .thenBy { label(it) }
            )
            2 -> apps.sortedWith(
                compareBy<ResolveInfo> { selected.contains(it.activityInfo.packageName) }
                    .thenBy { label(it) }
            )
            else -> apps.sortedWith(
                compareByDescending<ResolveInfo> { newApps.contains(it.activityInfo.packageName) }
                    .thenBy { label(it) }
            )
        }
    }

    private fun showGridAddDialog() {
        val pm = packageManager
        val tempSelectedPkgs = mutableSetOf<String>().apply { addAll(selectedApps) }
        val displayApps = sortForPicker(visibleApps(), tempSelectedPkgs)

        val tvCounter = TextView(this).apply {
            text = buildString {
                append("✨ 连点选择 · 长按可卸载：已选 ${tempSelectedPkgs.size} / ${displayApps.size} 个")
                val freshCount = displayApps.count { newApps.contains(it.activityInfo.packageName) }
                if (freshCount > 0) append(" · 🆕 $freshCount 个新安装")
            }
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(0, 30, 0, 20)
            setTextColor(palette.successText)
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
            tvCounter.text = "✨ 连点选择 · 长按可卸载：已选 ${tempSelectedPkgs.size} / ${displayApps.size} 个"
        }

        gridView.setOnItemLongClickListener { _, _, position, _ ->
            val app = displayApps[position]
            val pkg = app.activityInfo.packageName
            val name = app.loadLabel(pm).toString()
            newDialog()
                .setTitle("卸载「$name」？")
                .setMessage("会打开系统的卸载确认界面，确认后才会真正删除。\n\n$pkg")
                .setPositiveButton("去卸载") { _, _ ->
                    try {
                        startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:$pkg")))
                    } catch (e: Exception) {
                        Toast.makeText(this, "系统不允许从这里卸载，请到设置里操作", Toast.LENGTH_LONG).show()
                    }
                }
                .setNegativeButton("取消", null)
                .show()
            true
        }

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(tvCounter)
            addView(gridView)
        }

        newDialog()
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

    /** 一周内总打开次数 < 7（≈ 日均 < 1）的已选应用 */
    private fun idleAppsCard(): View? {
        if (selectedApps.isEmpty()) return null
        val weekAgo = System.currentTimeMillis() - 7L * 86_400_000L
        val counts = NotifyCore.countsByPackageSince(this, weekAgo).toMap()
        val idle = selectedApps.filter { (counts[it] ?: 0) < 7 }
        if (idle.isEmpty()) return null

        val pm = packageManager
        val names = idle.take(4).mapNotNull { pkg ->
            try {
                pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
            } catch (e: Exception) {
                null
            }
        }
        if (names.isEmpty()) return null

        val msg = buildString {
            append("💤 一周没怎么用（日均不到 1 次）：")
            append(names.joinToString("、"))
            if (idle.size > names.size) append(" 等 ${idle.size} 个")
            append("\n想让通知栏清爽点，可以在多选里取消勾选。")
        }
        return TextView(this).apply {
            text = msg
            textSize = 11f
            setLineSpacing(dpToPx(3f).toFloat(), 1f)
            setTextColor(palette.textSecondary)
            setPadding(dpToPx(12f), dpToPx(10f), dpToPx(12f), dpToPx(10f))
            background = roundedBg(palette.accentSoft, 12f)
        }
    }

    private fun toggleHideSystem() {
        hideSystemApps = !hideSystemApps
        getSharedPreferences("QuickPrefs", Context.MODE_PRIVATE).edit()
            .putBoolean("hide_system_apps", hideSystemApps).apply()
        buildSettingsPage()
        Toast.makeText(
            this,
            if (hideSystemApps) "多选列表已隐藏系统应用" else "已显示全部应用",
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun showAddedPositionDialog() {
        showChoiceDialog("已添加应用的位置", arrayOf("保持现状（字母序）", "置顶", "置底"), addedPosition) { which ->
            addedPosition = which
            getSharedPreferences("QuickPrefs", Context.MODE_PRIVATE).edit()
                .putInt("added_position", which).apply()
            buildSettingsPage()
        }
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
            setTextColor(palette.dangerText) 
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

        gridView.setOnItemLongClickListener { _, _, position, _ ->
            val app = displayApps[position]
            val pkg = app.activityInfo.packageName
            val name = app.loadLabel(pm).toString()
            newDialog()
                .setTitle("卸载「$name」？")
                .setMessage("会打开系统的卸载确认界面，确认后才会真正删除。\n\n$pkg")
                .setPositiveButton("去卸载") { _, _ ->
                    try {
                        startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:$pkg")))
                    } catch (e: Exception) {
                        Toast.makeText(this, "系统不允许从这里卸载，请到设置里操作", Toast.LENGTH_LONG).show()
                    }
                }
                .setNegativeButton("取消", null)
                .show()
            true
        }

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(tvCounter)
            addView(gridView)
        }

        newDialog()
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

    /** 把已选队列画成预览（统计页和图标页共用）。按行构建，行高固定，避免第二行被压塌 */
    private fun fillPreview(container: LinearLayout) {
        val pm = packageManager
        container.removeAllViews()
        val previewApps = selectedApps.take(20)
        if (previewApps.isEmpty()) return

        val cols = columnsPerRow.coerceIn(1, 10)
        val rows = (previewApps.size + cols - 1) / cols
        val rowHeight = dpToPx(40f)
        val gap = dpToPx(3f)

        for (r in 0 until rows) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                weightSum = cols.toFloat()
            }
            for (c in 0 until cols) {
                val idx = r * cols + c
                val cell = FrameLayout(this)
                row.addView(
                    cell,
                    LinearLayout.LayoutParams(0, rowHeight, 1f)
                )
                if (idx >= previewApps.size) continue

                val app = allApps.find { it.activityInfo.packageName == previewApps[idx] }
                cell.addView(ImageView(this).apply {
                    layoutParams = FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                    ).apply { setMargins(gap, gap, gap, gap) }
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    background = android.graphics.drawable.GradientDrawable().apply {
                        cornerRadius = dpToPx(8f).toFloat()
                        setColor(palette.cardSoft)
                        if (idx == swapIndex) setStroke(dpToPx(2f), palette.accent)
                    }
                    clipToOutline = true
                    app?.let { setImageDrawable(it.loadIcon(pm)) }
                    setOnClickListener { onPreviewTap(idx) }
                })
            }
            container.addView(
                row,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    rowHeight
                )
            )
        }
    }

    /** 点第一下选中，点第二下和它换位置 */
    private fun onPreviewTap(index: Int) {
        if (swapIndex < 0) {
            swapIndex = index
            fillPreview(previewContainer)
            if (::appsPreviewContainer.isInitialized) fillPreview(appsPreviewContainer)
            Toast.makeText(this, "再点另一个图标，两者交换位置", Toast.LENGTH_SHORT).show()
            return
        }
        if (swapIndex == index) {
            swapIndex = -1
            if (::previewContainer.isInitialized) fillPreview(previewContainer)
            if (::appsPreviewContainer.isInitialized) fillPreview(appsPreviewContainer)
            return
        }
        if (swapIndex >= selectedApps.size || index >= selectedApps.size) {
            swapIndex = -1
            return
        }
        val tmp = selectedApps[swapIndex]
        selectedApps[swapIndex] = selectedApps[index]
        selectedApps[index] = tmp
        swapIndex = -1
        saveData()
        refreshUI()
        Toast.makeText(this, "已交换位置", Toast.LENGTH_SHORT).show()
    }

    private fun refreshUI() {
        val pm = packageManager
        if (::previewContainer.isInitialized) fillPreview(previewContainer)
        if (::appsPreviewContainer.isInitialized) fillPreview(appsPreviewContainer)

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
                setTextColor(if (isPinned) palette.accent else palette.textSecondary)
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            })
            row.addView(TextView(this).apply {
                text = "${openCounts[selectedApps[i]] ?: 0} 次"
                textSize = 12f
                setTextColor(palette.textSecondary)
                setPadding(8, 0, 8, 0)
            })
            // 【新增】置顶开关：切 pinned_apps，不改手动队列；若当前不是置顶档则自动切过去
            row.addView(Button(this).apply {
                text = if (isPinned) "📌" else "☆"
                setTextColor(if (isPinned) palette.accent else palette.textSecondary)
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
        // 插件内容与通知栏同源，列表一变就跟着换
        WidgetUpdater.refreshAll(this)
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
                setTextColor(palette.textSecondary)
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
                    NotifyCore.RECORD_OFF -> "已关闭记录，历史保留"
                    NotifyCore.RECORD_UNLIMITED -> "上限：无限制（数据会持续增长）"
                    else -> "记录上限：$value 条"
                }
                Toast.makeText(this@MainActivity, msg, Toast.LENGTH_SHORT).show()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        val builder = newDialog()
            .setTitle("应用打开记录（仅本地）")
            .setView(container)
            .setPositiveButton("关闭", null)

        if (NotifyCore.getRecordLimit(this) != NotifyCore.RECORD_OFF) {
            builder.setNeutralButton("清空记录") { _, _ ->
                // 二次确认：这一步不可撤销，不能一点就没
                newDialog()
                    .setTitle("清空全部打开记录？")
                    .setMessage("会清掉所有明细、按天统计和累计次数，清完无法恢复。\n\n只是想停止记录的话，把上面的上限改成「关闭」即可，历史会留着。")
                    .setPositiveButton("确认清空") { _, _ ->
                        NotifyCore.clearOpenRecords(this@MainActivity)
                        renderRecords()
                        Toast.makeText(this@MainActivity, "记录已清空", Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton("取消", null)
                    .show()
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
        newDialog()
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
        newDialog()
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
        newDialog()
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
