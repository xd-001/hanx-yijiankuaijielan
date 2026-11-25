package com.my.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.LruCache
import android.widget.RemoteViews

/**
 * 公共核心：通知构建 / 自愈看门狗 / 打开记录
 * 抽出来是为了让 QuickService、BootReceiver、WatchdogReceiver 共用同一套逻辑，
 * 避免多处各写一份导致行为漂移。
 *
 * 隐私红线（不要动）：不申请 INTERNET，不引入任何第三方 SDK。
 */
object NotifyCore {

    const val CHANNEL_ID = "quick_top_v3"
    const val NOTIFICATION_ID = 3
    const val ACTION_WATCHDOG = "com.my.app.WATCHDOG_TICK"
    const val ACTION_BOOT_RETRY = "com.my.app.BOOT_RETRY"
    const val ACTION_REFRESH = "com.my.app.REFRESH_NOTIFICATION"
    const val ACTION_PAGE_NEXT = "com.my.app.PAGE_NEXT"

    // 震动档位：0=关闭，1=弱（默认），2=强
    const val VIBRATE_OFF = 0
    const val VIBRATE_WEAK = 1
    const val VIBRATE_STRONG = 2

    // 排序模式：0=全部手动 1=全部按频率 2=第一行手动定死+其余按频率 3=手动指定置顶优先
    const val SORT_MANUAL = 0
    const val SORT_FREQ_ALL = 1
    const val SORT_HYBRID = 2
    const val SORT_PINNED = 3

    // 翻页方式：0=整页翻（每页19） 1=只翻第二行（第一行10个雷打不动）
    const val PAGE_WHOLE = 0
    const val PAGE_ROW = 1

    private const val PAGE_SIZE = 19        // 整页翻：20 格留 1 格给页码键
    private const val ROW_FIRST_COUNT = 10  // 只翻第二行：第一行固定 10 个
    private const val ROW_SECOND_COUNT = 9  // 第二行 9 个 + 页码键占第 20 格
    private const val PAGE_REQUEST_CODE = 2002

    private const val PREFS = "QuickPrefs"
    private const val PREFS_LOG = "OpenLog"
    private const val KEY_RECORDS = "records"
    private const val KEY_OPEN_COUNTS = "open_counts"
    private const val KEY_RECORD_LIMIT = "record_limit"

    // 按天 / 按小时聚合：明细会被上限裁剪，长周期统计必须靠这两份独立计数
    // 格式：pkg|yyyyMMdd|次数 ; pkg|yyyyMMddHH|次数
    private const val KEY_DAILY_COUNTS = "daily_counts"
    private const val KEY_HOURLY_COUNTS = "hourly_counts"
    private const val HOURLY_KEEP_HOURS = 48

    // 记录上限：-1 = 关闭（完全不写），0 = 无限制，>0 = 具体条数
    // 默认 100，与上一版行为一致，老用户升级后不会被偷偷改成"无限制"
    const val RECORD_OFF = -1
    const val RECORD_UNLIMITED = 0
    const val RECORD_DEFAULT = 100

    // 看门狗间隔（分钟）：1/3/5/10/30，0=关闭自愈（只保留开机自启）
    const val WATCHDOG_OFF = 0
    private const val KEY_WATCHDOG_INTERVAL = "watchdog_interval_min"
    private const val WATCHDOG_DEFAULT_MIN = 1
    private const val ALARM_REQUEST_CODE = 1001
    private const val BOOT_RETRY_REQUEST_CODE = 1002

    // 卸载清理：每小时最多扫一次，20 个包的 getLaunchIntentForPackage 开销可忽略
    private const val KEY_LAST_PRUNE = "last_prune_ms"
    private const val PRUNE_INTERVAL_MS = 3_600_000L

    // 备份配置：纯本地文本，不加 INTERNET、不加存储权限
    private const val CONFIG_HEADER = "# QuickNotify-Config-v1"
    private val INT_CONFIG_KEYS = setOf(
        "columns", "sort_mode", "paging_mode", "current_page",
        "vibrate_mode", "record_limit", KEY_WATCHDOG_INTERVAL
    )
    private val BOOL_CONFIG_KEYS = setOf("paging_enabled", "user_stopped")
    private val STR_CONFIG_KEYS = setOf("selected_apps", "hidden_apps", "pinned_apps")
    private val CONFIG_KEYS = INT_CONFIG_KEYS + BOOL_CONFIG_KEYS + STR_CONFIG_KEYS

    private val refreshHandler = Handler(Looper.getMainLooper())
    private var refreshPending = false

    /**
     * 图标 LruCache：容量按字节算，上限 1MB。
     * 20 个 80×80 ARGB_8888 图标约 512KB，翻页/自愈重建通知时不再重复解码。
     * 上限 1MB 是硬约束——即使以后 App 数翻倍，内存也不会失控。
     */
    private val iconCache = object : LruCache<String, Bitmap>(1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }

    /** 取图标：命中缓存直接返回，未命中解码一次后缓存。异常由调用方 try-catch 兜底。 */
    private fun appIconBitmap(context: Context, pkg: String): Bitmap {
        iconCache[pkg]?.let { return it }
        val pm = context.packageManager
        val bitmap = drawableToBitmap(pm.getApplicationInfo(pkg, 0).loadIcon(pm))
        iconCache.put(pkg, bitmap)
        return bitmap
    }

    /** 一次构建的分页结果 */
    private data class PageView(
        val items: List<String>,
        val totalPages: Int,
        val page: Int,
        val showPager: Boolean
    )

    // ---------- 通知渠道 ----------

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(CHANNEL_ID, "置顶快捷通知 v3", NotificationManager.IMPORTANCE_HIGH)
            channel.setShowBadge(false)
            channel.setSound(null, null)
            channel.enableVibration(false)
            manager.createNotificationChannel(channel)
        }
    }

    // ---------- 通知构建 ----------

    private fun drawableToBitmap(drawable: android.graphics.drawable.Drawable): Bitmap {
        val size = 80
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, size, size)
        drawable.draw(canvas)
        return bitmap
    }

    /** 把页码画成 bitmap（icon19 是 ImageView，RemoteViews 里不能换控件类型） */
    private fun pageNumberBitmap(text: String): Bitmap {
        val size = 80
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(android.graphics.Color.parseColor("#E8EAF6"))
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.parseColor("#333333")
            textAlign = android.graphics.Paint.Align.CENTER
            textSize = 30f
            isFakeBoldText = true
        }
        val fm = paint.fontMetrics
        val baseline = size / 2f - (fm.ascent + fm.descent) / 2f
        canvas.drawText(text, size / 2f, baseline, paint)
        return bitmap
    }

    /** 当前震动档位：0=关闭 1=弱 2=强 */
    fun getVibrateMode(context: Context): Int {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt("vibrate_mode", VIBRATE_WEAK)
    }

    /** 排序模式：0=全部手动 1=全部按频率 2=混合（第一行定死 + 其余按频率） */
    fun getSortMode(context: Context): Int {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        // 兼容上一版的 sort_by_freq 布尔键：只在新键不存在时迁移一次，迁移后删旧键
        if (!prefs.contains("sort_mode") && prefs.contains("sort_by_freq")) {
            val migrated = if (prefs.getBoolean("sort_by_freq", false)) SORT_FREQ_ALL else SORT_MANUAL
            prefs.edit().putInt("sort_mode", migrated).remove("sort_by_freq").apply()
            return migrated
        }
        return prefs.getInt("sort_mode", SORT_MANUAL)
    }

    /**
     * 手动置顶的包名集合（逗号分隔存 prefs）。
     * 只影响「置顶优先」档的显示顺序，绝不写回 selectedApps——取消置顶后原顺序原样回来。
     */
    fun getPinnedApps(context: Context): Set<String> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString("pinned_apps", "") ?: ""
        return raw.split(",").filter { it.isNotEmpty() }.toSet()
    }

    /** 切换某个 App 的置顶状态，返回切换后是否置顶 */
    fun togglePinned(context: Context, pkg: String): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val pinned = getPinnedApps(context).toMutableSet()
        val nowPinned = if (pkg in pinned) {
            pinned.remove(pkg)
            false
        } else {
            pinned.add(pkg)
            true
        }
        prefs.edit().putString("pinned_apps", pinned.joinToString(",")).apply()
        return nowPinned
    }

    /** 翻页方式：0=整页翻 1=只翻第二行 */
    fun getPagingMode(context: Context): Int {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt("paging_mode", PAGE_WHOLE)
    }

    /** 是否开启翻页模式（20 格变 19 格 + 右下角翻页键） */
    fun isPagingEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean("paging_enabled", false)
    }

    /**
     * 返回本次通知要显示的完整包名顺序（只影响显示，绝不写回 selectedApps）。
     * 混合模式：第一行 = 手动队列前 10 个定死；第二行起 = 剩余 App 按频率降序，同频保持手动原序。
     */
    private fun orderedPackages(context: Context, prefs: android.content.SharedPreferences): List<String> {
        val savedApps = prefs.getString("selected_apps", "") ?: ""
        val pkgs = if (savedApps.isNotEmpty()) savedApps.split(",") else emptyList()
        if (pkgs.isEmpty()) return pkgs

        return when (getSortMode(context)) {
            SORT_FREQ_ALL -> {
                val counts = getOpenCounts(context)
                pkgs.sortedByDescending { counts[it] ?: 0 }
            }
            SORT_HYBRID -> {
                val head = pkgs.take(ROW_FIRST_COUNT)              // 第一行定死
                val tail = pkgs.drop(ROW_FIRST_COUNT)              // 其余按频率
                if (tail.isEmpty()) head else {
                    val counts = getOpenCounts(context)
                    head + tail.sortedByDescending { counts[it] ?: 0 }
                }
            }
            SORT_PINNED -> {
                // 手动置顶优先：置顶项按手动队列相对顺序排前，其余保持原序
                val pinned = getPinnedApps(context)
                if (pinned.isEmpty()) pkgs else {
                    pkgs.filter { it in pinned } + pkgs.filterNot { it in pinned }
                }
            }
            else -> pkgs
        }
    }

    /** 当前页码（从 0 开始） */
    private fun currentPage(prefs: android.content.SharedPreferences): Int =
        prefs.getInt("current_page", 0).coerceAtLeast(0)

    /** 前进一页；页数由调用方在构建通知时 clamp，这里只做循环 +1 */
    fun nextPage(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit().putInt("current_page", currentPage(prefs) + 1).apply()
    }

    /**
     * 构建完整通知（含图标网格）。
     * 修复点：先把 20 个槽位全部 GONE，再逐个填，避免配置变更时残留旧图标。
     * 修复点：槽位映射固定按 10 列两行（layout 本来就是 icon0~icon19），
     *         原代码 row*10+col 在 columns<10 时会跳号/越界，这里改成线性 i。
     * 新增：频率排序（显示层）、分页（每页 19 个 + 翻页键）。
     */
    fun buildNotification(context: Context): Notification {
        ensureChannel(context)

        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val pkgs = orderedPackages(context, prefs)
        val paging = isPagingEnabled(context)

        // 分页结果：(本页显示的包, 总页数, 当前页, 是否显示页码键)
        // 整页翻 = 每页 19 个；只翻第二行 = 第一行固定 10 + 第二行翻 9
        val view: PageView = if (!paging || pkgs.isEmpty()) {
            PageView(pkgs.take(20), 1, 0, false)
        } else if (getPagingMode(context) == PAGE_ROW) {
            val head = pkgs.take(ROW_FIRST_COUNT)          // 第一行：定死，始终显示
            val tail = pkgs.drop(ROW_FIRST_COUNT)          // 第二行起：按分页翻
            val cap = ROW_SECOND_COUNT
            val totalPages = maxOf(1, (tail.size + cap - 1) / cap)
            val page = currentPage(prefs) % totalPages
            val sub = if (tail.isEmpty()) emptyList()
                      else tail.subList(page * cap, minOf((page + 1) * cap, tail.size))
            PageView(head + sub, totalPages, page, totalPages > 1)
        } else {
            val cap = PAGE_SIZE
            val totalPages = maxOf(1, (pkgs.size + cap - 1) / cap)
            val page = currentPage(prefs) % totalPages
            val sub = pkgs.subList(page * cap, minOf((page + 1) * cap, pkgs.size))
            PageView(sub, totalPages, page, totalPages > 1)
        }
        val pagePkgs = view.items
        val totalPages = view.totalPages
        val page = view.page

        // 小图标：作者确认"防社死"无效，固定用系统同步图标（与旧配置 icon_type=0 一致）
        val smallIcon = android.R.drawable.stat_notify_sync

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(context, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(context)
        }

        builder.setSmallIcon(smallIcon)
            .setContentTitle(" ")
            .setContentText(" ")
            .setShowWhen(false)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setSortKey("0000")

        val remoteViews = RemoteViews(context.packageName, R.layout.layout_notification)

        // 先清空两行 + 全部 20 个槽位，防止残留
        for (r in 0..1) {
            val rowId = context.resources.getIdentifier("row$r", "id", context.packageName)
            if (rowId != 0) remoteViews.setViewVisibility(rowId, android.view.View.GONE)
        }
        for (i in 0 until 20) {
            val id = context.resources.getIdentifier("icon$i", "id", context.packageName)
            if (id != 0) remoteViews.setViewVisibility(id, android.view.View.GONE)
        }

        val pm = context.packageManager
        for (i in pagePkgs.indices) {
            val pkg = pagePkgs[i]
            val launchIntent = pm.getLaunchIntentForPackage(pkg) ?: continue

            // 点击走透明代理：先反馈 + 记录，再启动目标 App
            val proxy = Intent(context, LaunchProxyActivity::class.java).apply {
                putExtra("pkg", pkg)
            }
            val pi = PendingIntent.getActivity(
                context, i, proxy,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            try {
                // 【新增】走 1MB 上限的 LruCache，翻页/自愈重建时不再重复解码
                val bitmap = appIconBitmap(context, pkg)
                val resId = context.resources.getIdentifier("icon$i", "id", context.packageName)
                val rowId = context.resources.getIdentifier("row${i / 10}", "id", context.packageName)
                if (resId != 0 && rowId != 0 && i / 10 <= 1) {
                    remoteViews.setViewVisibility(rowId, android.view.View.VISIBLE)
                    remoteViews.setImageViewBitmap(resId, bitmap)
                    remoteViews.setViewVisibility(resId, android.view.View.VISIBLE)
                    remoteViews.setOnClickPendingIntent(resId, pi)
                }
            } catch (e: Exception) {
                // 应用被卸载 / 图标加载失败：跳过这个槽位，不影响其他图标
            }
        }

        // 翻页键：只在确实有多页时显示，占 icon19
        if (view.showPager) {
            val pagerId = context.resources.getIdentifier("icon19", "id", context.packageName)
            if (pagerId != 0) {
                remoteViews.setViewVisibility(context.resources.getIdentifier("row1", "id", context.packageName), android.view.View.VISIBLE)
                remoteViews.setViewVisibility(pagerId, android.view.View.VISIBLE)
                remoteViews.setImageViewBitmap(pagerId, pageNumberBitmap("${page + 1}/$totalPages"))
                val pagePi = PendingIntent.getBroadcast(
                    context, PAGE_REQUEST_CODE,
                    Intent(ACTION_PAGE_NEXT).setPackage(context.packageName),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                remoteViews.setOnClickPendingIntent(pagerId, pagePi)
            }
        }

        builder.setCustomContentView(remoteViews)
            .setCustomBigContentView(remoteViews)
        return builder.build()
    }

    // ---------- 状态检查 ----------

    /** 通知是否还在（FGS 活着时这条通知一定在） */
    fun isNotificationAlive(context: Context): Boolean {
        return try {
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.activeNotifications.any { it.id == NOTIFICATION_ID }
        } catch (e: Exception) {
            false
        }
    }

    /** 用户是否显式关闭过（点了"一键关闭"按钮） */
    fun isStoppedByUser(context: Context): Boolean {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean("user_stopped", false)
    }

    fun setStoppedByUser(context: Context, stopped: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean("user_stopped", stopped).apply()
    }

    // ---------- 启动 / 刷新 ----------

    fun startService(context: Context) {
        val intent = Intent(context, QuickService::class.java)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                @Suppress("DEPRECATION")
                context.startService(intent)
            }
        } catch (e: Exception) {
            // Android 12+ 后台启动 FGS 限制：targetSdk 30 一般不受限，
            // 若将来升 targetSdk 需改 WorkManager 或引导用户手动打开
        }
    }

    /** 确保服务在跑：通知在就跳过（幂等），不在就拉起 */
    fun ensureRunning(context: Context) {
        if (isStoppedByUser(context)) return
        if (!isNotificationAlive(context)) startService(context)
    }

    /** 强制重建通知（配置变更后调用） */
    fun refreshNotification(context: Context) {
        if (isStoppedByUser(context)) return
        if (!isNotificationAlive(context)) {
            startService(context)
        } else {
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.notify(NOTIFICATION_ID, buildNotification(context))
        }
    }

    /** 带去抖的刷新，供 SeekBar 拖动等高频场景调用 */
    fun scheduleRefresh(context: Context) {
        if (refreshPending) return
        refreshPending = true
        refreshHandler.postDelayed({
            refreshPending = false
            refreshNotification(context.applicationContext)
        }, 400)
    }

    // ---------- 看门狗（自愈核心） ----------

    /** 看门狗间隔（分钟）：0=关闭自愈，默认 1 分钟 */
    fun getWatchdogIntervalMin(context: Context): Int {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_WATCHDOG_INTERVAL, WATCHDOG_DEFAULT_MIN)
    }

    /** 改间隔后立即重排；选 0 则彻底取消，只保留开机自启 */
    fun setWatchdogIntervalMin(context: Context, minutes: Int) {
        val safe = if (minutes in listOf(1, 3, 5, 10, 30)) minutes else WATCHDOG_OFF
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_WATCHDOG_INTERVAL, safe).apply()
        cancelWatchdog(context)
        if (safe != WATCHDOG_OFF && !isStoppedByUser(context)) scheduleWatchdog(context)
    }

    /**
     * 链式 exact alarm：每次 tick 后重新排下一次。
     * 为什么不用 setRepeating：国产 ROM 会把 repeating alarm 批量延迟/合并，
     * 链式 exact + setExactAndAllowWhileIdle 在 Doze 下也能触发（用户已在白名单）。
     * 间隔可在设置里调；注意 Doze 深度休眠下系统有约 9 分钟的最小唤醒间隔，
     * 设 1 分钟在非休眠时生效，休眠时会被系统merge到 ~9 分钟，这是系统限制。
     */
    fun scheduleWatchdog(context: Context) {
        val intervalMin = getWatchdogIntervalMin(context)
        if (intervalMin == WATCHDOG_OFF || isStoppedByUser(context)) return  // 用户关了自愈/通知，不排
        val intervalMs = intervalMin * 60_000L

        val alarm = context.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
        val pi = PendingIntent.getBroadcast(
            context, ALARM_REQUEST_CODE,
            Intent(ACTION_WATCHDOG).setPackage(context.packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val triggerAt = SystemClock.elapsedRealtime() + intervalMs
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarm.setExactAndAllowWhileIdle(android.app.AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi)
            } else {
                alarm.set(android.app.AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi)
            }
        } catch (e: Exception) {
            // 某些 ROM 禁止 exact alarm，退化为 inexact
            alarm.setInexactRepeating(
                android.app.AlarmManager.ELAPSED_REALTIME_WAKEUP,
                triggerAt, intervalMs, pi
            )
        }
    }

    /**
     * 开机广播的一次性兜底：5 秒后由系统唤醒 WatchdogReceiver 再拉一次服务。
     * 这个 alarm 注册在 AlarmManager 里，不依赖当前进程存活，专门对付
     * “onReceive 返回后进程被 ROM 立刻回收”的情况。
     */
    fun scheduleBootRetry(context: Context, delayMs: Long = 5_000L) {
        if (isStoppedByUser(context)) return
        val alarm = context.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
        val pi = PendingIntent.getBroadcast(
            context, BOOT_RETRY_REQUEST_CODE,
            Intent(context, WatchdogReceiver::class.java).setAction(ACTION_BOOT_RETRY),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val triggerAt = SystemClock.elapsedRealtime() + delayMs
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarm.setExactAndAllowWhileIdle(android.app.AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi)
            } else {
                alarm.set(android.app.AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi)
            }
        } catch (e: Exception) {
            alarm.set(android.app.AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi)
        }
    }

    fun cancelWatchdog(context: Context) {
        val alarm = context.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
        val pi = PendingIntent.getBroadcast(
            context, ALARM_REQUEST_CODE,
            Intent(ACTION_WATCHDOG).setPackage(context.packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        alarm.cancel(pi)
    }

    // ---------- 打开记录（隐私最小化：只存包名 + 时间戳） ----------

    /** 当前记录上限：-1=关闭，0=无限制，>0=条数 */
    fun getRecordLimit(context: Context): Int {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_RECORD_LIMIT, RECORD_DEFAULT)
    }

    /**
     * 设置记录上限。
     * 注意：选"关闭"时顺手清空已有记录——否则"关了却还留着历史"是最容易踩的隐私坑。
     */
    fun setRecordLimit(context: Context, limit: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_RECORD_LIMIT, limit).apply()
        if (limit == RECORD_OFF) clearOpenRecords(context)
    }

    private fun parseOpenCounts(raw: String): MutableMap<String, Int> {
        val out = mutableMapOf<String, Int>()
        raw.split(";").filter { it.isNotEmpty() }.forEach { entry ->
            val parts = entry.split("|")
            if (parts.size == 2) {
                val n = parts[1].toIntOrNull()
                if (n != null && n > 0) out[parts[0]] = n
            }
        }
        return out
    }

    private fun serializeOpenCounts(counts: Map<String, Int>): String =
        counts.entries.filter { it.value > 0 }
            .joinToString(";") { "${it.key}|${it.value}" }

    fun recordOpen(context: Context, pkg: String) {
        val limit = getRecordLimit(context)
        if (limit == RECORD_OFF) return   // 关闭：一个字节都不写

        val prefs = context.getSharedPreferences(PREFS_LOG, Context.MODE_PRIVATE)
        val list = prefs.getString(KEY_RECORDS, "") ?: ""
        val entries = list.split(";").filter { it.isNotEmpty() }.toMutableList()
        val now = System.currentTimeMillis()
        entries.add("$pkg|$now")
        if (limit > 0) {
            while (entries.size > limit) entries.removeAt(0)   // 只裁剪明细，不动累计次数
        }
        // limit == 0（无限制）：不裁剪

        // 累计次数单独存：即使明细只留最近 100 条，总次数也不会被洗掉
        val counts = getOpenCounts(context).toMutableMap()
        counts[pkg] = (counts[pkg] ?: 0) + 1

        // 按天 + 按小时聚合：长周期（一个月/全部）统计靠它，不受明细上限影响
        val dk = "$pkg|${dayKey(now)}"
        val daily = parseBuckets(prefs.getString(KEY_DAILY_COUNTS, "") ?: "")
        daily[dk] = (daily[dk] ?: 0) + 1

        val hk = "$pkg|${hourKey(now)}"
        val cutoffHour = hourKey(now - HOURLY_KEEP_HOURS * 3_600_000L)
        val hourly = parseBuckets(prefs.getString(KEY_HOURLY_COUNTS, "") ?: "")
            .filterKeys { it.substringAfterLast('|') >= cutoffHour }.toMutableMap()
        hourly[hk] = (hourly[hk] ?: 0) + 1

        prefs.edit()
            .putString(KEY_RECORDS, entries.joinToString(";"))
            .putString(KEY_OPEN_COUNTS, serializeOpenCounts(counts))
            .putString(KEY_DAILY_COUNTS, serializeBuckets(daily))
            .putString(KEY_HOURLY_COUNTS, serializeBuckets(hourly))
            .apply()
    }

    fun getOpenRecords(context: Context): List<Pair<String, Long>> {
        val raw = context.getSharedPreferences(PREFS_LOG, Context.MODE_PRIVATE)
            .getString(KEY_RECORDS, "") ?: ""
        return raw.split(";").filter { it.isNotEmpty() }.mapNotNull { entry ->
            val parts = entry.split("|")
            if (parts.size == 2) {
                val ts = parts[1].toLongOrNull()
                if (ts != null) parts[0] to ts else null
            } else null
        }.reversed()
    }

    /** 累计打开次数：pkg -> 次数。老版本没有计数时，用现有明细做一次迁移。 */
    fun getOpenCounts(context: Context): Map<String, Int> {
        val prefs = context.getSharedPreferences(PREFS_LOG, Context.MODE_PRIVATE)
        val stored = parseOpenCounts(prefs.getString(KEY_OPEN_COUNTS, "") ?: "")
        if (stored.isNotEmpty()) return stored

        // 兼容 1.09 及更早：从最近记录里汇总一次，并落盘
        val derived = getOpenRecords(context).groupingBy { it.first }.eachCount()
        if (derived.isNotEmpty()) {
            prefs.edit()
                .putString(KEY_OPEN_COUNTS, serializeOpenCounts(derived))
                .apply()
        }
        return derived
    }

    // ---------- 按天 / 按小时聚合（长周期统计，不受明细上限影响） ----------

    fun dayKey(ms: Long): String {
        val d = java.time.Instant.ofEpochMilli(ms)
            .atZone(java.time.ZoneId.systemDefault()).toLocalDate()
        return "%04d%02d%02d".format(d.year, d.monthValue, d.dayOfMonth)
    }

    fun hourKey(ms: Long): String {
        val t = java.time.Instant.ofEpochMilli(ms)
            .atZone(java.time.ZoneId.systemDefault())
        return "%04d%02d%02d%02d".format(t.year, t.monthValue, t.dayOfMonth, t.hour)
    }

    /** 当天 0 点的时间戳 */
    fun dayStart(ms: Long): Long =
        java.time.Instant.ofEpochMilli(ms)
            .atZone(java.time.ZoneId.systemDefault())
            .toLocalDate()
            .atStartOfDay(java.time.ZoneId.systemDefault())
            .toInstant().toEpochMilli()

    private fun parseBuckets(raw: String): MutableMap<String, Int> {
        val out = mutableMapOf<String, Int>()
        raw.split(";").filter { it.isNotEmpty() }.forEach { entry ->
            val p = entry.split("|")
            if (p.size == 3) {
                val n = p[2].toIntOrNull()
                if (n != null && n > 0) out["${p[0]}|${p[1]}"] = n
            }
        }
        return out
    }

    private fun serializeBuckets(m: Map<String, Int>): String =
        m.entries.filter { it.value > 0 }.joinToString(";") { "${it.key}|${it.value}" }

    /** 读按天聚合；老版本没有这份数据时，用现有明细尽力迁移一次 */
    private fun loadDaily(context: Context): MutableMap<String, Int> {
        val prefs = context.getSharedPreferences(PREFS_LOG, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_DAILY_COUNTS, null)
        if (raw != null) return parseBuckets(raw)
        val migrated = mutableMapOf<String, Int>()
        getOpenRecords(context).forEach { (pkg, ts) ->
            val k = "$pkg|${dayKey(ts)}"
            migrated[k] = (migrated[k] ?: 0) + 1
        }
        prefs.edit().putString(KEY_DAILY_COUNTS, serializeBuckets(migrated)).apply()
        return migrated
    }

    private fun loadHourly(context: Context): MutableMap<String, Int> {
        val prefs = context.getSharedPreferences(PREFS_LOG, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_HOURLY_COUNTS, null)
        if (raw != null) return parseBuckets(raw)
        val migrated = mutableMapOf<String, Int>()
        getOpenRecords(context).forEach { (pkg, ts) ->
            val k = "$pkg|${hourKey(ts)}"
            migrated[k] = (migrated[k] ?: 0) + 1
        }
        prefs.edit().putString(KEY_HOURLY_COUNTS, serializeBuckets(migrated)).apply()
        return migrated
    }

    /** 总打开次数。sinceMs <= 0 表示全部时间；pkg == null 表示所有应用 */
    fun countSince(context: Context, sinceMs: Long, pkg: String? = null): Int {
        val from = if (sinceMs <= 0L) "" else dayKey(sinceMs)
        return loadDaily(context).entries.sumOf { (k, v) ->
            val p = k.split("|")
            if (p.size == 2 && (pkg == null || p[0] == pkg) && (from.isEmpty() || p[1] >= from)) v else 0
        }
    }

    /** 按应用汇总次数，降序。sinceMs <= 0 表示全部时间 */
    fun countsByPackageSince(context: Context, sinceMs: Long): List<Pair<String, Int>> {
        val from = if (sinceMs <= 0L) "" else dayKey(sinceMs)
        val acc = mutableMapOf<String, Int>()
        loadDaily(context).forEach { (k, v) ->
            val p = k.split("|")
            if (p.size == 2 && (from.isEmpty() || p[1] >= from)) {
                acc[p[0]] = (acc[p[0]] ?: 0) + v
            }
        }
        return acc.entries.filter { it.value > 0 }
            .sortedByDescending { it.value }
            .map { it.key to it.value }
    }

    /** 最近 days 天的每日次数（含今天） */
    fun seriesDaily(context: Context, days: Int, pkg: String? = null): List<Int> {
        val map = loadDaily(context)
        val base = dayStart(System.currentTimeMillis())
        return (days - 1 downTo 0).map { d ->
            val key = dayKey(base - d * 86_400_000L)
            map.entries.sumOf { (k, v) ->
                val p = k.split("|")
                if (p.size == 2 && p[1] == key && (pkg == null || p[0] == pkg)) v else 0
            }
        }
    }

    /** 最近 hours 小时的每小时次数（含当前小时） */
    fun seriesHourly(context: Context, hours: Int, pkg: String? = null): List<Int> {
        val map = loadHourly(context)
        val now = System.currentTimeMillis()
        return (hours - 1 downTo 0).map { h ->
            val key = hourKey(now - h * 3_600_000L)
            map.entries.sumOf { (k, v) ->
                val p = k.split("|")
                if (p.size == 2 && p[1] == key && (pkg == null || p[0] == pkg)) v else 0
            }
        }
    }

    /** 最近 months 个月的每月次数（含本月） */
    fun seriesMonthly(context: Context, months: Int, pkg: String? = null): List<Int> {
        val map = loadDaily(context)
        val today = java.time.Instant.ofEpochMilli(System.currentTimeMillis())
            .atZone(java.time.ZoneId.systemDefault()).toLocalDate()
        return (months - 1 downTo 0).map { m ->
            val ym = today.minusMonths(m.toLong())
            val prefix = "%04d%02d".format(ym.year, ym.monthValue)
            map.entries.sumOf { (k, v) ->
                val p = k.split("|")
                if (p.size == 2 && p[1].startsWith(prefix) && (pkg == null || p[0] == pkg)) v else 0
            }
        }
    }

    fun clearOpenRecords(context: Context) {
        context.getSharedPreferences(PREFS_LOG, Context.MODE_PRIVATE).edit()
            .remove(KEY_RECORDS)
            .remove(KEY_OPEN_COUNTS)
            .remove(KEY_DAILY_COUNTS)
            .remove(KEY_HOURLY_COUNTS)
            .apply()
    }

    // ---------- 使用记录导出 / 导入（纯本地，走系统文件选择器，零权限） ----------

    private const val USAGE_HEADER = "# QuickNotify-Usage-v1"
    private const val USAGE_MAX_CHARS = 5_000_000
    private const val USAGE_MAX_RECORDS = 200_000

    /** 导出使用记录：days > 0 只导最近 N 天；days <= 0 导出全部 */
    fun exportUsageText(context: Context, days: Int): String {
        val logPrefs = context.getSharedPreferences(PREFS_LOG, Context.MODE_PRIVATE)
        val daily = parseBuckets(logPrefs.getString(KEY_DAILY_COUNTS, "") ?: "")
        val hourly = parseBuckets(logPrefs.getString(KEY_HOURLY_COUNTS, "") ?: "")
        val counts = parseOpenCounts(logPrefs.getString(KEY_OPEN_COUNTS, "") ?: "")

        val now = System.currentTimeMillis()
        val cutoffMs = if (days > 0) dayStart(now) - (days - 1) * 86_400_000L else 0L
        val cutoffDay = if (days > 0) dayKey(cutoffMs) else ""

        val sb = StringBuilder()
        sb.append(USAGE_HEADER).append('\n')
        sb.append("# exported=").append(now).append('\n')
        sb.append("# range=").append(if (days > 0) "${days}d" else "all").append('\n')

        sb.append("COUNT\n")
        counts.forEach { (pkg, n) -> if (n > 0) sb.append(pkg).append('|').append(n).append('\n') }

        sb.append("DAILY\n")
        daily.forEach { (k, v) ->
            val p = k.split("|")
            if (p.size == 2 && (cutoffDay.isEmpty() || p[1] >= cutoffDay)) {
                sb.append(p[0]).append('|').append(p[1]).append('|').append(v).append('\n')
            }
        }

        sb.append("HOURLY\n")
        hourly.forEach { (k, v) ->
            val p = k.split("|")
            if (p.size == 2) sb.append(p[0]).append('|').append(p[1]).append('|').append(v).append('\n')
        }

        sb.append("RECORD\n")
        getOpenRecords(context).asReversed().forEach { (pkg, ts) ->
            if (cutoffMs <= 0L || ts >= cutoffMs) sb.append(pkg).append('|').append(ts).append('\n')
        }
        return sb.toString()
    }

    /**
     * 导入使用记录。默认合并：同 key 取较大值（导两次不会翻倍），明细按「包名+时间戳」去重。
     * 累计次数用 DAILY 重算，而不是取 max —— 两台设备各自累计值取 max 会漏掉另一台。
     */
    fun importUsageText(context: Context, text: String, merge: Boolean): ImportResult {
        if (text.length > USAGE_MAX_CHARS) return ImportResult(false, "文件过大（超过 5MB），已拒绝")
        if (!text.lineSequence().any { it.trim() == USAGE_HEADER }) {
            return ImportResult(false, "这不是快捷通知的使用记录文件")
        }

        val inDaily = mutableMapOf<String, Int>()
        val inHourly = mutableMapOf<String, Int>()
        val inCounts = mutableMapOf<String, Int>()
        val inRecords = mutableListOf<String>()
        val pkgRe = Regex("^[A-Za-z0-9_.]+$")
        val now = System.currentTimeMillis()
        var section = ""

        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            if (line == "COUNT" || line == "DAILY" || line == "HOURLY" || line == "RECORD") {
                section = line
                continue
            }
            val p = line.split("|")
            when (section) {
                "COUNT" -> if (p.size == 2 && pkgRe.matches(p[0])) {
                    val n = p[1].toIntOrNull()
                    if (n != null && n > 0) inCounts[p[0]] = maxOf(inCounts[p[0]] ?: 0, n)
                }
                "DAILY" -> if (p.size == 3 && pkgRe.matches(p[0]) && p[1].length == 8 && p[1].all { it.isDigit() }) {
                    val n = p[2].toIntOrNull()
                    if (n != null && n > 0) {
                        val k = "${p[0]}|${p[1]}"
                        inDaily[k] = maxOf(inDaily[k] ?: 0, n)
                    }
                }
                "HOURLY" -> if (p.size == 3 && pkgRe.matches(p[0]) && p[1].length == 10 && p[1].all { it.isDigit() }) {
                    val n = p[2].toIntOrNull()
                    if (n != null && n > 0) {
                        val k = "${p[0]}|${p[1]}"
                        inHourly[k] = maxOf(inHourly[k] ?: 0, n)
                    }
                }
                "RECORD" -> if (p.size == 2 && pkgRe.matches(p[0])) {
                    val ts = p[1].toLongOrNull()
                    if (ts != null && ts > 0L && ts <= now + 86_400_000L) inRecords.add("${p[0]}|$ts")
                }
            }
        }

        if (inDaily.isEmpty() && inCounts.isEmpty() && inRecords.isEmpty()) {
            return ImportResult(false, "文件里没有可用的记录")
        }
        if (inRecords.size > USAGE_MAX_RECORDS) return ImportResult(false, "明细超过 20 万条，已拒绝")

        val logPrefs = context.getSharedPreferences(PREFS_LOG, Context.MODE_PRIVATE)
        if (!merge) logPrefs.edit().clear().apply()

        val daily = if (merge) parseBuckets(logPrefs.getString(KEY_DAILY_COUNTS, "") ?: "") else mutableMapOf()
        inDaily.forEach { (k, v) -> daily[k] = maxOf(daily[k] ?: 0, v) }

        val hourly = if (merge) parseBuckets(logPrefs.getString(KEY_HOURLY_COUNTS, "") ?: "") else mutableMapOf()
        inHourly.forEach { (k, v) -> hourly[k] = maxOf(hourly[k] ?: 0, v) }

        val mergedRecords = LinkedHashSet<String>()
        if (merge) getOpenRecords(context).forEach { mergedRecords.add("${it.first}|${it.second}") }
        mergedRecords.addAll(inRecords)
        val limit = getRecordLimit(context)
        val all = mergedRecords.toList()
        val kept = if (limit > 0) all.takeLast(limit) else all

        // 累计次数以按天数据为准重算；只导了 COUNT 的文件才退回文件里的值
        val recomputed = mutableMapOf<String, Int>()
        daily.forEach { (k, v) ->
            val pkg = k.substringBefore('|')
            recomputed[pkg] = (recomputed[pkg] ?: 0) + v
        }
        val finalCounts = if (recomputed.isNotEmpty()) recomputed else inCounts

        logPrefs.edit()
            .putString(KEY_DAILY_COUNTS, serializeBuckets(daily))
            .putString(KEY_HOURLY_COUNTS, serializeBuckets(hourly))
            .putString(KEY_OPEN_COUNTS, serializeOpenCounts(finalCounts))
            .putString(KEY_RECORDS, kept.joinToString(";"))
            .apply()

        val dayCount = daily.keys.map { it.substringAfter('|') }.toSet().size
        return ImportResult(true, "已导入 $dayCount 天记录、${inRecords.size} 条明细")
    }

    // ---------- 卸载清理 ----------

    /**
     * 清掉 selected_apps / hidden_apps 里已卸载的包名。
     * 看门狗 tick 里调用，默认每小时最多扫一次（force=true 时立即执行）。
     * 只判断 getLaunchIntentForPackage 是否为 null；禁用但未卸载的 App 不动。
     * @return 清掉的包名个数
     */
    fun pruneUninstalledApps(context: Context, force: Boolean = false): Int {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (!force && now - prefs.getLong(KEY_LAST_PRUNE, 0L) < PRUNE_INTERVAL_MS) return 0
        prefs.edit().putLong(KEY_LAST_PRUNE, now).apply()

        val pm = context.packageManager
        fun launchable(pkg: String): Boolean = try {
            pm.getLaunchIntentForPackage(pkg) != null
        } catch (e: Exception) { false }

        fun splitPref(key: String) = (prefs.getString(key, "") ?: "")
            .split(",").filter { it.isNotEmpty() }
        val sel = splitPref("selected_apps")
        val hid = splitPref("hidden_apps")
        val pin = splitPref("pinned_apps")
        val keptSel = sel.filter { launchable(it) }
        val keptHid = hid.filter { launchable(it) }
        val keptPin = pin.filter { it in keptSel && launchable(it) }  // 置顶必须还在已选且可用
        val removed = (sel.size - keptSel.size) + (hid.size - keptHid.size) + (pin.size - keptPin.size)
        if (removed > 0) {
            prefs.edit()
                .putString("selected_apps", keptSel.joinToString(","))
                .putString("hidden_apps", keptHid.joinToString(","))
                .putString("pinned_apps", keptPin.joinToString(","))
                .apply()
            iconCache.evictAll()   // 顺手丢掉旧图标，避免卸载后残留
        }
        return removed
    }

    // ---------- 配置导入 / 导出（纯本地，零权限） ----------

    data class ImportResult(val ok: Boolean, val message: String)

    /** 备份目录：App 专属外部目录，无需任何存储权限，root / 数据线可直接取 */
    fun backupDir(context: Context): java.io.File {
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        val dir = java.io.File(base, "backup")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /** 导出为纯文本：只含 QuickPrefs 的合法键，不含打开记录（行为日志不备份） */
    fun exportConfigText(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val sb = StringBuilder(CONFIG_HEADER).append('\n')
        for (key in CONFIG_KEYS) {
            sb.append(key).append('=').append(prefs.all[key]?.toString() ?: "").append('\n')
        }
        return sb.toString()
    }

    /**
     * 导入配置：白名单键 + 类型校验，非法内容全部拒绝，不写半个字。
     * 打开记录不导入（避免把别人的行为日志灌进来）。
     */
    fun importConfigText(context: Context, text: String): ImportResult {
        if (text.length > 100_000) return ImportResult(false, "文本过大，已拒绝")
        if (!text.lineSequence().any { it.trim() == CONFIG_HEADER }) {
            return ImportResult(false, "不是有效的快捷通知配置文本")
        }

        val parsed = mutableMapOf<String, Any>()
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val idx = line.indexOf('=')
            if (idx <= 0) continue
            val key = line.substring(0, idx).trim()
            val value = line.substring(idx + 1).trim()
            if (key !in CONFIG_KEYS) continue   // 未知键直接丢，不写进 prefs

            when (key) {
                in INT_CONFIG_KEYS -> {
                    val n = value.toIntOrNull() ?: continue
                    parsed[key] = when (key) {
                        "columns" -> n.coerceIn(4, 10)
                        "sort_mode" -> n.coerceIn(0, SORT_PINNED)
                        "paging_mode" -> n.coerceIn(0, 1)
                        "vibrate_mode" -> n.coerceIn(0, 2)
                        "current_page" -> n.coerceAtLeast(0)
                        else -> n   // record_limit 允许 -1/0/任意正数
                    }
                }
                in BOOL_CONFIG_KEYS -> parsed[key] = value.equals("true", ignoreCase = true)
                in STR_CONFIG_KEYS -> {
                    // 包名白名单字符，最多 200 个，拒绝注入垃圾数据
                    if (value.isNotEmpty() && !value.matches(Regex("[A-Za-z0-9_.]+(,[A-Za-z0-9_.]+)*"))) {
                        return ImportResult(false, "应用列表格式不合法，已拒绝导入")
                    }
                    parsed[key] = value.split(",").filter { it.isNotEmpty() }.take(200).joinToString(",")
                }
            }
        }
        if (parsed.isEmpty()) return ImportResult(false, "没读到有效配置项")

        // 置顶列表必须是已选列表的子集，否则会在置顶档留下永远填不满的空位
        val finalSelected = (parsed["selected_apps"] as? String)
            ?: (context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("selected_apps", "") ?: "")
        (parsed["pinned_apps"] as? String)?.let { raw ->
            val selected = finalSelected.split(",").filter { it.isNotEmpty() }.toSet()
            parsed["pinned_apps"] = raw.split(",").filter { it in selected }.joinToString(",")
        }

        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val editor = prefs.edit()
        for ((key, value) in parsed) {
            when (value) {
                is Int -> editor.putInt(key, value)
                is Boolean -> editor.putBoolean(key, value)
                is String -> editor.putString(key, value)
            }
        }
        editor.apply()
        iconCache.evictAll()   // 包名可能变了，旧缓存作废
        return ImportResult(true, "已导入 ${parsed.size} 项配置")
    }
}
