package com.my.app

import android.content.Context
import android.graphics.Color

/**
 * 外观令牌：所有界面颜色都从这里取，别再往布局里塞十六进制。
 *
 * 两个维度互不干扰：
 *   - 外观 appearance：白天 / 护眼 / 夜间温和 / 夜间激进（决定底色与文字深浅）
 *   - 强调色 accentId：紫 / 蓝 / 粉 / 绿 / 橙 / 苹果紫（决定曲线、进度条、选中态）
 *
 * 改动成本提示：界面里真正随主题变的是「强调色 + 浅底」和「底色 + 三级文字」，
 * 其余中性灰已并入 textSecondary / textTertiary / divider，不要再新增硬编码。
 */
data class AppTheme(
    val appearance: Int,
    val accentId: Int,
    val bg: Int,
    val card: Int,
    val cardSoft: Int,
    val track: Int,
    val divider: Int,
    val textPrimary: Int,
    val textSecondary: Int,
    val textTertiary: Int,
    val accent: Int,
    val accentSoft: Int,
    val successBg: Int,
    val successText: Int,
    val dangerBg: Int,
    val dangerText: Int
) {
    val isDark: Boolean get() = appearance == APPEARANCE_DIM || appearance == APPEARANCE_DARK

    companion object {
        const val APPEARANCE_LIGHT = 0
        const val APPEARANCE_WARM = 1
        const val APPEARANCE_DIM = 2
        const val APPEARANCE_DARK = 3

        const val ACCENT_PURPLE = 0
        const val ACCENT_BLUE = 1
        const val ACCENT_PINK = 2
        const val ACCENT_GREEN = 3
        const val ACCENT_ORANGE = 4
        const val ACCENT_IOS_PURPLE = 5

        private const val PREFS = "QuickPrefs"
        private const val KEY_APPEARANCE = "appearance_id"
        private const val KEY_ACCENT = "accent_id"

        val appearanceLabels = arrayOf("白天", "护眼（暖纸）", "夜间温和", "夜间激进")
        val accentLabels = arrayOf("紫（默认）", "苹果蓝", "苹果粉", "苹果绿", "苹果橙", "苹果紫")

        /** 强调色：(亮色系, 暗色系) */
        private val ACCENTS = arrayOf(
            intArrayOf(Color.parseColor("#6C5CE7"), Color.parseColor("#9B8CFF")),
            intArrayOf(Color.parseColor("#007AFF"), Color.parseColor("#4DA3FF")),
            intArrayOf(Color.parseColor("#E8457E"), Color.parseColor("#FF7BA6")),
            intArrayOf(Color.parseColor("#34C759"), Color.parseColor("#5FD97C")),
            intArrayOf(Color.parseColor("#F08A00"), Color.parseColor("#FFB340")),
            intArrayOf(Color.parseColor("#AF52DE"), Color.parseColor("#C983F0"))
        )

        /** 强调色浅底：(亮色系, 暗色系) */
        private val ACCENT_SOFTS = arrayOf(
            intArrayOf(Color.parseColor("#EFECFE"), Color.parseColor("#2E2A47")),
            intArrayOf(Color.parseColor("#E5F1FF"), Color.parseColor("#122A45")),
            intArrayOf(Color.parseColor("#FDECF2"), Color.parseColor("#3A1E2A")),
            intArrayOf(Color.parseColor("#E8F8EC"), Color.parseColor("#14301C")),
            intArrayOf(Color.parseColor("#FFF3E0"), Color.parseColor("#3A2A10")),
            intArrayOf(Color.parseColor("#F6EBFC"), Color.parseColor("#2E1A3A"))
        )

        fun readAppearance(context: Context): Int {
            val v = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getInt(KEY_APPEARANCE, APPEARANCE_LIGHT)
            return if (v in 0..3) v else APPEARANCE_LIGHT
        }

        fun readAccent(context: Context): Int {
            val v = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getInt(KEY_ACCENT, ACCENT_PURPLE)
            return if (v in ACCENTS.indices) v else ACCENT_PURPLE
        }

        fun save(context: Context, appearance: Int, accent: Int) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putInt(KEY_APPEARANCE, appearance)
                .putInt(KEY_ACCENT, accent)
                .apply()
        }

        fun current(context: Context): AppTheme =
            build(readAppearance(context), readAccent(context))

        fun build(appearance: Int, accentId: Int): AppTheme {
            val dark = appearance == APPEARANCE_DIM || appearance == APPEARANCE_DARK
            val idx = if (accentId in ACCENTS.indices) accentId else 0
            val accent = ACCENTS[idx][if (dark) 1 else 0]
            val accentSoft = ACCENT_SOFTS[idx][if (dark) 1 else 0]

            return when (appearance) {
                APPEARANCE_WARM -> AppTheme(
                    appearance = appearance,
                    accentId = idx,
                    bg = Color.parseColor("#F4EFE4"),
                    card = Color.parseColor("#FBF7EF"),
                    cardSoft = Color.parseColor("#EFE8DA"),
                    track = Color.parseColor("#E6DECE"),
                    divider = Color.parseColor("#DFD6C4"),
                    textPrimary = Color.parseColor("#2E2A23"),
                    textSecondary = Color.parseColor("#7A7367"),
                    textTertiary = Color.parseColor("#B5AC9C"),
                    accent = accent,
                    accentSoft = accentSoft,
                    successBg = Color.parseColor("#E8EDD8"),
                    successText = Color.parseColor("#3E4A1C"),
                    dangerBg = Color.parseColor("#F7E3DC"),
                    dangerText = Color.parseColor("#8E3B24")
                )
                APPEARANCE_DIM -> AppTheme(
                    appearance = appearance,
                    accentId = idx,
                    bg = Color.parseColor("#2A2A2E"),
                    card = Color.parseColor("#35353A"),
                    cardSoft = Color.parseColor("#3F3F45"),
                    track = Color.parseColor("#1F1F23"),
                    divider = Color.parseColor("#45454C"),
                    textPrimary = Color.parseColor("#EDEDF0"),
                    textSecondary = Color.parseColor("#A5A5AE"),
                    textTertiary = Color.parseColor("#6E6E77"),
                    accent = accent,
                    accentSoft = accentSoft,
                    successBg = Color.parseColor("#2F3A24"),
                    successText = Color.parseColor("#B9D79A"),
                    dangerBg = Color.parseColor("#3A2426"),
                    dangerText = Color.parseColor("#F0A0A0")
                )
                APPEARANCE_DARK -> AppTheme(
                    appearance = appearance,
                    accentId = idx,
                    bg = Color.parseColor("#0B0B0D"),
                    card = Color.parseColor("#151517"),
                    cardSoft = Color.parseColor("#1E1E22"),
                    track = Color.parseColor("#08080A"),
                    divider = Color.parseColor("#26262B"),
                    textPrimary = Color.parseColor("#F2F2F5"),
                    textSecondary = Color.parseColor("#9A9AA2"),
                    textTertiary = Color.parseColor("#5E5E66"),
                    accent = accent,
                    accentSoft = accentSoft,
                    successBg = Color.parseColor("#16210F"),
                    successText = Color.parseColor("#8FBF5A"),
                    dangerBg = Color.parseColor("#2A1518"),
                    dangerText = Color.parseColor("#E88080")
                )
                else -> AppTheme(
                    appearance = APPEARANCE_LIGHT,
                    accentId = idx,
                    bg = Color.parseColor("#F5F5F7"),
                    card = Color.WHITE,
                    cardSoft = Color.parseColor("#F1EFE8"),
                    track = Color.parseColor("#ECECEF"),
                    divider = Color.parseColor("#E5E5EA"),
                    textPrimary = Color.parseColor("#1C1C1E"),
                    textSecondary = Color.parseColor("#8E8E93"),
                    textTertiary = Color.parseColor("#C7C7CC"),
                    accent = accent,
                    accentSoft = accentSoft,
                    successBg = Color.parseColor("#EAF3DE"),
                    successText = Color.parseColor("#27500A"),
                    dangerBg = Color.parseColor("#FCEBEB"),
                    dangerText = Color.parseColor("#A32D2D")
                )
            }
        }
    }
}
