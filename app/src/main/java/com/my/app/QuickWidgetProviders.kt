package com.my.app

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context

/** 横版插件：4 格宽 × 2 格高，8 列 × 2 行 */
class QuickWidgetWideProvider : AppWidgetProvider() {
    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        val views = runCatching { WidgetUpdater.build(context, wide = true) }.getOrNull() ?: return
        for (id in appWidgetIds) {
            runCatching { appWidgetManager.updateAppWidget(id, views) }
        }
    }
}

/** 竖版插件：2 格宽 × 4 格高，2 列 × 4 行 */
class QuickWidgetTallProvider : AppWidgetProvider() {
    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        val views = runCatching { WidgetUpdater.build(context, wide = false) }.getOrNull() ?: return
        for (id in appWidgetIds) {
            runCatching { appWidgetManager.updateAppWidget(id, views) }
        }
    }
}
