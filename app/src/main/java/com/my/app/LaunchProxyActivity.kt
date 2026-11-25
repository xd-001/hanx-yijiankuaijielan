package com.my.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.widget.Toast

/**
 * 【P1】点击反馈 + 打开记录
 *
 * 透明 Activity：点击通知图标时先走这里，做三件事后立刻 finish：
 * 1. 轻量震动反馈（可调档，不弹 Toast）
 * 2. 记录打开（包名 + 时间戳，仅本地）
 * 3. 启动目标 App
 *
 * 为什么用 Activity 而不是直接在 PendingIntent 里启动目标：
 * 目标 App 直接启动时我们拿不到执行时机，没法插入反馈和记录。
 * 透明主题 + excludeFromRecents 让它对用户完全不可见。
 */
class LaunchProxyActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val pkg = intent.getStringExtra("pkg")
        if (pkg.isNullOrEmpty()) {
            finish()
            return
        }

        // 1. 点击反馈：只保留震动，不再弹“打开 XX”的 Toast
        try {
            // 震动三档：0=关闭 1=弱(30ms/轻) 2=强(100ms/满幅)，默认弱
            when (NotifyCore.getVibrateMode(this)) {
                NotifyCore.VIBRATE_OFF -> { /* 关闭：不震动，也不打扰 */ }
                NotifyCore.VIBRATE_WEAK -> {
                    val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        vibrator?.vibrate(VibrationEffect.createOneShot(30, 60))
                    } else {
                        @Suppress("DEPRECATION")
                        vibrator?.vibrate(30)
                    }
                }
                NotifyCore.VIBRATE_STRONG -> {
                    val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        vibrator?.vibrate(VibrationEffect.createOneShot(100, VibrationEffect.DEFAULT_AMPLITUDE))
                    } else {
                        @Suppress("DEPRECATION")
                        vibrator?.vibrate(100)
                    }
                }
            }
        } catch (e: Exception) {
            // 反馈失败不影响主流程
        }

        // 2. 打开记录（隐私最小化）
        NotifyCore.recordOpen(this, pkg)

        // 3. 启动目标 App
        val launchIntent = packageManager.getLaunchIntentForPackage(pkg)
        if (launchIntent != null) {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            try {
                startActivity(launchIntent)
            } catch (e: Exception) {
                Toast.makeText(this, "应用启动失败", Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(this, "应用已卸载", Toast.LENGTH_SHORT).show()
        }

        finish()
        overridePendingTransition(0, 0)
    }
}
