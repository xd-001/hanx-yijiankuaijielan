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
        const val APPEARANCE_GREEN = 4

        const val ACCENT_PURPLE = 0
        const val ACCENT_BLUE = 1
        const val ACCENT_PINK = 2
        const val ACCENT_GREEN = 3
        const val ACCENT_ORANGE = 4
        const val ACCENT_IOS_PURPLE = 5

        private const val PREFS = "QuickPrefs"
        private const val KEY_APPEARANCE = "appearance_id"
        private const val KEY_ACCENT = "accent_id"
        private const val KEY_FOLLOW_NIGHT = "follow_system_night"

        /** 设置页展示顺序：浅色三档在前，深色两档在后。ID 保持不变，旧设置不会错位 */
        val appearanceOrder = intArrayOf(
            APPEARANCE_LIGHT, APPEARANCE_WARM, APPEARANCE_GREEN, APPEARANCE_DIM, APPEARANCE_DARK
        )
        /** 按 ID 顺序排（0 白天 / 1 护眼暖纸 / 2 夜间温和 / 3 夜间激进 / 4 护眼绿）。
         *  界面展示顺序另用 appearanceOrder 映射，两者别混用。 */
        val appearanceLabels = arrayOf("白天", "护眼暖纸", "夜间温和", "夜间激进", "护眼绿")
        val accentLabels = arrayOf(
            "紫（默认）", "苹果蓝", "苹果粉", "苹果绿", "苹果橙", "苹果紫",
            "护眼暖纸", "简洁白", "少女粉", "高能红", "咸蛋黄", "草苗绿", "宝石蓝", "罗兰紫"
        )

        /** 强调色：(亮色系, 暗色系) */
        private val ACCENTS = arrayOf(
            intArrayOf(Color.parseColor("#6C5CE7"), Color.parseColor("#9B8CFF")),
            intArrayOf(Color.parseColor("#007AFF"), Color.parseColor("#4DA3FF")),
            intArrayOf(Color.parseColor("#E8457E"), Color.parseColor("#FF7BA6")),
            intArrayOf(Color.parseColor("#34C759"), Color.parseColor("#5FD97C")),
            intArrayOf(Color.parseColor("#F08A00"), Color.parseColor("#FFB340")),
            intArrayOf(Color.parseColor("#AF52DE"), Color.parseColor("#C983F0")),
            intArrayOf(Color.parseColor("#8A6D3B"), Color.parseColor("#C9A66B")),
            intArrayOf(Color.parseColor("#5A5A66"), Color.parseColor("#D8D8E0")),
            intArrayOf(Color.parseColor("#FF8FB1"), Color.parseColor("#FFB3C9")),
            intArrayOf(Color.parseColor("#E53935"), Color.parseColor("#FF6B6B")),
            intArrayOf(Color.parseColor("#F2B705"), Color.parseColor("#FFD24A")),
            intArrayOf(Color.parseColor("#7CB342"), Color.parseColor("#A5D66A")),
            intArrayOf(Color.parseColor("#1565C0"), Color.parseColor("#5B9BE8")),
            intArrayOf(Color.parseColor("#7B1FA2"), Color.parseColor("#C77BE8"))
        )

        /** 强调色浅底：(亮色系, 暗色系) */
        private val ACCENT_SOFTS = arrayOf(
            intArrayOf(Color.parseColor("#EFECFE"), Color.parseColor("#2E2A47")),
            intArrayOf(Color.parseColor("#E5F1FF"), Color.parseColor("#122A45")),
            intArrayOf(Color.parseColor("#FDECF2"), Color.parseColor("#3A1E2A")),
            intArrayOf(Color.parseColor("#E8F8EC"), Color.parseColor("#14301C")),
            intArrayOf(Color.parseColor("#FFF3E0"), Color.parseColor("#3A2A10")),
            intArrayOf(Color.parseColor("#F6EBFC"), Color.parseColor("#2E1A3A")),
            intArrayOf(Color.parseColor("#F3EADB"), Color.parseColor("#3A2E1C")),
            intArrayOf(Color.parseColor("#EFEFF2"), Color.parseColor("#2A2A32")),
            intArrayOf(Color.parseColor("#FFF0F5"), Color.parseColor("#3A1F2A")),
            intArrayOf(Color.parseColor("#FDEAEA"), Color.parseColor("#3A1A1A")),
            intArrayOf(Color.parseColor("#FFF6DA"), Color.parseColor("#3A3010")),
            intArrayOf(Color.parseColor("#F0F7E6"), Color.parseColor("#24300F")),
            intArrayOf(Color.parseColor("#E7F0FB"), Color.parseColor("#12273D")),
            intArrayOf(Color.parseColor("#F5E9FA"), Color.parseColor("#2E1535"))
        )

        fun readAppearance(context: Context): Int {
            val v = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getInt(KEY_APPEARANCE, APPEARANCE_LIGHT)
            return if (v in 0..APPEARANCE_GREEN) v else APPEARANCE_LIGHT
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

        /** 系统当前是否深色 */
        fun isSystemDark(context: Context): Boolean {
            val mode = context.resources.configuration.uiMode and
                android.content.res.Configuration.UI_MODE_NIGHT_MASK
            return mode == android.content.res.Configuration.UI_MODE_NIGHT_YES
        }

        fun isFollowingNight(context: Context): Boolean =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_FOLLOW_NIGHT, false)

        /** 跟随系统时，深色时段用哪一档（默认夜间温和） */
        fun readNightAppearance(context: Context): Int {
            val v = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getInt("night_appearance", APPEARANCE_DIM)
            return if (v == APPEARANCE_DARK) APPEARANCE_DARK else APPEARANCE_DIM
        }

        fun saveNightAppearance(context: Context, appearance: Int) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putInt("night_appearance", appearance).apply()
        }

        fun setFollowingNight(context: Context, follow: Boolean) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean(KEY_FOLLOW_NIGHT, follow).apply()
        }

        /**
         * 跟随系统时：系统深色 → 夜间温和；系统浅色 → 用用户选的浅色档
         * （用户若选的是夜间档，浅色时段退回白天，避免大白天一片黑）
         */
        fun current(context: Context): AppTheme {
            val accent = readAccent(context)
            if (!isFollowingNight(context)) return build(readAppearance(context), accent)

            if (isSystemDark(context)) return build(readNightAppearance(context), accent)
            val chosen = readAppearance(context)
            val light = if (chosen == APPEARANCE_DIM || chosen == APPEARANCE_DARK) APPEARANCE_LIGHT else chosen
            return build(light, accent)
        }

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
                APPEARANCE_GREEN -> AppTheme(
                    appearance = appearance,
                    accentId = idx,
                    bg = Color.parseColor("#E9F0E3"),
                    card = Color.parseColor("#F5FAF0"),
                    cardSoft = Color.parseColor("#DEE8D5"),
                    track = Color.parseColor("#D8E3CE"),
                    divider = Color.parseColor("#CFDCC4"),
                    textPrimary = Color.parseColor("#29331F"),
                    textSecondary = Color.parseColor("#6A7A5E"),
                    textTertiary = Color.parseColor("#A7B79C"),
                    accent = accent,
                    accentSoft = accentSoft,
                    successBg = Color.parseColor("#E3EED3"),
                    successText = Color.parseColor("#33471E"),
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
