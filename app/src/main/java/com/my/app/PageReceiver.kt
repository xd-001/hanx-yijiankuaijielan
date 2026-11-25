package com.my.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 【翻页模式】通知右下角页码键的点击入口。
 *
 * 页码存在 SharedPreferences，Receiver 只做 +1，
 * 真正的边界处理（页数变化、取模回绕）在 NotifyCore.buildNotification 里完成，
 * 这样即使你中途增删 App、页数变了也不会越界。
 */
class PageReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != NotifyCore.ACTION_PAGE_NEXT) return
        // refreshNotification 内部会检查 isStoppedByUser，关闭状态下翻页广播自动失效
        NotifyCore.nextPage(context)
        NotifyCore.refreshNotification(context)
    }
}
