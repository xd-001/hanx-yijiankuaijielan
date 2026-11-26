package com.my.app

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

/**
 * 桌面图标切换。
 *
 * Android 不允许运行时把图标换成任意图片，只能「预置多个图标，切换启用哪一个」，
 * 所以每种图标在 Manifest 里是一个 activity-alias，同一时间只启用一个。
 *
 * 顺序很关键：先启用目标、再禁用其余 —— 反过来会出现「一瞬间没有任何入口」的状态，
 * 部分桌面会把这个包记成已卸载。
 */
object AppIcon {

    val labels = arrayOf("原图（默认）", "紫色点阵", "深靛通知条", "近黑点阵")

    private val aliases = arrayOf(".IconDefault", ".IconPurple", ".IconBars", ".IconDots")

    /** 预览用图标资源，0 是原来那张二次元 JPG */
    fun previewRes(id: Int): Int = when (id) {
        1 -> R.mipmap.ic_style_purple
        2 -> R.mipmap.ic_style_bars
        3 -> R.mipmap.ic_style_dots
        else -> R.drawable.app_icon
    }

    private const val PREFS = "QuickPrefs"
    private const val KEY = "icon_id"

    fun current(context: Context): Int {
        val v = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY, 0)
        return if (v in aliases.indices) v else 0
    }

    fun apply(context: Context, id: Int) {
        if (id !in aliases.indices) return
        val pm = context.packageManager
        val pkg = context.packageName

        val target = ComponentName(pkg, pkg + aliases[id])
        runCatching {
            pm.setComponentEnabledSetting(
                target,
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP
            )
        }

        aliases.forEachIndexed { i, alias ->
            if (i != id) {
                runCatching {
                    pm.setComponentEnabledSetting(
                        ComponentName(pkg, pkg + alias),
                        PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                        PackageManager.DONT_KILL_APP
                    )
                }
            }
        }

        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY, id).apply()
    }
}
