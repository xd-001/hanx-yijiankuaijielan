package com.my.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 【P0】开机 / 升级 / 解锁 自启
 *
 * 解决"重启后通知不回来"：Manifest 里原本没有任何 receiver，
 * 手机一重启 QuickService 就永远不会自己起来。
 *
 * 注意：BOOT_COMPLETED 时直接 startForegroundService 在部分 ROM 上会被拦，
 * 所以这里同时安排看门狗 alarm 做二次兜底——即使这次启动失败，
 * 按设置里的自检间隔，看门狗还会再试一次。
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // 用户显式关闭过就尊重用户选择，不自启
        if (NotifyCore.isStoppedByUser(context)) return

        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            Intent.ACTION_USER_INITIALIZE,
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                // 开机瞬间系统还不稳，稍等片刻再拉起，成功率更高
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    NotifyCore.ensureRunning(context.applicationContext)
                    NotifyCore.scheduleWatchdog(context.applicationContext)
                }, 3_000)
            }
        }
    }
}
