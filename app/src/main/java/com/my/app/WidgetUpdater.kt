package com.my.app

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import android.widget.RemoteViews

/**
 * 桌面插件的渲染器。
 *
 * 插件不是普通界面：只能用 RemoteViews 那盒积木，曲线得先画成 Bitmap 再贴。
 * 图标一律 64px —— 传 Bitmap 走 Binder，总大小碰 1MB 就会整块失效。
 */
object WidgetUpdater {

    private const val WIDE_SLOTS = 16   // 8 列 × 2 行
    private const val TALL_SLOTS = 8    // 2 列 × 4 行

    /** 主应用里改了列表 / 颜色后调用，刷新所有已放置的插件 */
    fun refreshAll(context: Context) {
        val mgr = AppWidgetManager.getInstance(context)
        val pairs = listOf(
            QuickWidgetWideProvider::class.java to true,
            QuickWidgetTallProvider::class.java to false
        )
        for ((cls, wide) in pairs) {
            val ids = mgr.getAppWidgetIds(ComponentName(context, cls))
            if (ids.isEmpty()) continue
            val rv = runCatching { build(context, wide) }.getOrNull() ?: continue
            for (id in ids) {
                runCatching { mgr.updateAppWidget(id, rv) }
            }
        }
    }

    fun build(context: Context, wide: Boolean): RemoteViews {
        val layoutRes = if (wide) R.layout.widget_quick_wide else R.layout.widget_quick_tall
        val rv = RemoteViews(context.packageName, layoutRes)
        val slots = if (wide) WIDE_SLOTS else TALL_SLOTS
        val prefix = if (wide) "wc" else "wt"
        val res = context.resources
        val pkgName = context.packageName
        val pm = context.packageManager

        val apps = selectedApps(context).take(slots)

        for (i in 0 until slots) {
            val id = res.getIdentifier("$prefix$i", "id", pkgName)
            if (id != 0) rv.setViewVisibility(id, View.GONE)
        }

        for (i in apps.indices) {
            val id = res.getIdentifier("$prefix$i", "id", pkgName)
            if (id == 0) continue
            val pkg = apps[i]
            val bmp = iconBitmap(context, pkg) ?: continue
            rv.setImageViewBitmap(id, bmp)
            rv.setViewVisibility(id, View.VISIBLE)

            val launch = pm.getLaunchIntentForPackage(pkg)
            if (launch != null) {
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                val pi = PendingIntent.getActivity(
                    context,
                    pkg.hashCode(),
                    launch,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                rv.setOnClickPendingIntent(id, pi)
            }
        }

        val curveId = if (wide) R.id.wc_curve else R.id.wt_curve
        rv.setImageViewBitmap(curveId, curveBitmap(context, if (wide) 600 else 260, if (wide) 76 else 96))

        return rv
    }

    private fun selectedApps(context: Context): List<String> =
        (context.getSharedPreferences("QuickPrefs", Context.MODE_PRIVATE)
            .getString("selected_apps", "") ?: "")
            .split(",").filter { it.isNotEmpty() }

    private fun iconBitmap(context: Context, pkg: String): Bitmap? = try {
        val drawable = context.packageManager.getApplicationIcon(pkg)
        val size = 64
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        drawable.setBounds(0, 0, size, size)
        drawable.draw(canvas)
        bmp
    } catch (e: Exception) {
        null
    }

    /** 近 7 天折线：插件里画不了交互图表，先渲染成图贴上 */
    private fun curveBitmap(context: Context, w: Int, h: Int): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)

        val data = runCatching { NotifyCore.seriesDaily(context, 7) }.getOrDefault(emptyList())
        if (data.isEmpty()) return bmp

        val accent = Color.parseColor("#6C5CE7")
        val soft = Color.parseColor("#33FFFFFF")
        val guide = Color.parseColor("#1F000000")

        val padTop = h * 0.12f
        val padBottom = h * 0.10f
        val usable = h - padTop - padBottom
        val maxValue = maxOf(1, data.maxOrNull() ?: 1)
        val step = w.toFloat() / (data.size - 1).coerceAtLeast(1)

        // 基线
        val basePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = guide
            strokeWidth = 1f
        }
        canvas.drawLine(0f, h - padBottom, w.toFloat(), h - padBottom, basePaint)

        val path = Path()
        val area = Path()
        for (i in data.indices) {
            val x = step * i
            val y = padTop + usable * (1f - data[i].toFloat() / maxValue)
            if (i == 0) {
                path.moveTo(x, y)
                area.moveTo(x, h - padBottom)
                area.lineTo(x, y)
            } else {
                path.lineTo(x, y)
                area.lineTo(x, y)
            }
        }
        area.lineTo(step * (data.size - 1), h - padBottom)
        area.close()

        canvas.drawPath(area, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = soft
            style = Paint.Style.FILL
        })
        canvas.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = accent
            style = Paint.Style.STROKE
            strokeWidth = 3f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        })

        return bmp
    }
}
