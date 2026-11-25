package com.my.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 【P0】看门狗 —— 治"莫名其妙被杀"的核心
 *
 * 工作原理：
 * 1. 按设置间隔被 AlarmManager 唤醒（默认每 60 秒，链式 exact alarm，进程死了也能唤起）
 * 2. 检查通知还在不在；不在且用户没主动关 → 拉起 QuickService 重建
 * 3. 无论这次是否触发自愈，都排下一次 tick，形成闭环
 *
 * 关键点：alarm 注册在系统里（AlarmManager），App 进程被杀后依然会响，
 * 这正是 START_STICKY 不可靠时最稳的兜底。
 */
class WatchdogReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        when (intent.action) {
            NotifyCore.ACTION_WATCHDOG -> {
                // 【新增】顺手清理已卸载应用（内部限流每小时一次，开销可忽略）
                NotifyCore.pruneUninstalledApps(app)
                NotifyCore.ensureRunning(app)
                // 链式：每次收到 tick 都重新排下一次（间隔可在设置里改）
                NotifyCore.scheduleWatchdog(app)
            }
            NotifyCore.ACTION_BOOT_RETRY -> {
                // 开机广播里排的一次性兜底：即使启动瞬间进程被杀，这里也会再拉一次
                NotifyCore.ensureRunning(app)
                NotifyCore.scheduleWatchdog(app)
            }
            NotifyCore.ACTION_REFRESH -> {
                // 配置变更后从 MainActivity 发来，立即重建通知
                NotifyCore.refreshNotification(app)
            }
        }
    }
}
