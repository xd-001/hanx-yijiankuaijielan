package com.my.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 【P0】开机 / 升级 / 解锁 自启
 *
 * 关键修复：不能在 onReceive 里 postDelayed 后直接返回——
 * 广播结束后进程可能被系统回收，3 秒后的 Runnable 永远不执行。
 *
 * 现在改为：
 * 1. 广播内立刻拉起前台服务（BOOT_COMPLETED 属于 FGS 启动限制的豁免场景）
 * 2. 立刻排看门狗
 * 3. 再排一个 5 秒后的一次性 alarm，即使进程这次被杀，系统也会把服务重新拉起
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext

        // 用户显式关闭过就尊重用户选择；解锁前读 prefs 失败时按“未关闭”处理
        val stopped = try {
            NotifyCore.isStoppedByUser(app)
        } catch (e: Exception) {
            false
        }
        if (stopped) return

        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            Intent.ACTION_USER_INITIALIZE,
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                // 先立即尝试，能起来就马上变成前台服务，进程不会再被随手回收
                try { NotifyCore.ensureRunning(app) } catch (e: Exception) {}
                // 再排看门狗，保证后续掉线仍能自愈
                try { NotifyCore.scheduleWatchdog(app) } catch (e: Exception) {}
                // 最后排一次 5 秒后的 alarm 兜底：它活在系统里，不怕当前进程被杀
                try { NotifyCore.scheduleBootRetry(app) } catch (e: Exception) {}
            }
        }
    }
}
