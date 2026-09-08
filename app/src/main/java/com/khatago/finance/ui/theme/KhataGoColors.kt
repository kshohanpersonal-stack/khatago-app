package com.khatago.finance.ui.theme

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/**
 * KhataGo's colour tokens — the single place a hex value is allowed to appear in the UI layer.
 *
 * The palette is light-only by product requirement, and it is built around one rule that keeps a
 * money app feeling calm rather than neon: **colour marks meaning, never decoration.** Emerald is
 * "settled/positive", amber is "due", a muted rose is "overdue", and slate is "information". Surfaces
 * stay white or mint-tinted white. That is why no screen here uses a random gradient and why dark
 * mode was not simply inverted — it does not exist.
 */
object KhataGoColors {
    // Brand
    val Emerald900 = Color(0xFF06382A)
    val Emerald800 = Color(0xFF0B5F45)
    val Emerald700 = Color(0xFF0B7A55)
    val Emerald600 = Color(0xFF16A37A)
    val Emerald500 = Color(0xFF2FBF94)
    val Emerald200 = Color(0xFFA7E7CE)
    val Emerald100 = Color(0xFFD3F3E5)
    val Emerald50 = Color(0xFFE9FAF1)

    // Neutrals — warm-tinted slate so text reads soft rather than clinical
    val Ink900 = Color(0xFF0E1C17)
    val Ink800 = Color(0xFF182721)
    val Ink600 = Color(0xFF3B4A44)
    val Ink500 = Color(0xFF5A6B64)
    val Ink400 = Color(0xFF7C8B85)
    val Ink200 = Color(0xFFDCE4E0)
    val Ink100 = Color(0xFFEAF0ED)
    val SurfaceWhite = Color(0xFFFFFFFF)
    val SurfaceCanvas = Color(0xFFF7FBF9)
    val SurfaceTinted = Color(0xFFF1FAF5)

    // Meaning
    val Settled = Color(0xFF0F8A5F)
    val SettledBg = Color(0xFFE1F6EC)
    val DueSoon = Color(0xFFA5670A)
    val DueSoonBg = Color(0xFFFDF1DC)
    val Overdue = Color(0xFFB3261E)
    val OverdueBg = Color(0xFFFCE9E7)
    val OwedToMe = Color(0xFF0E6F9C)
    val OwedToMeBg = Color(0xFFE2F2FA)
    val Info = Color(0xFF4A5A67)
    val InfoBg = Color(0xFFEDF1F4)

    /** Chart ramp: same family, distinguishable, colour-blind-safe when paired with labels. */
    val ChartRamp = listOf(
        Emerald700,
        Color(0xFF3FA9F5),
        Color(0xFFF2A33C),
        Color(0xFF8E6BD8),
        Emerald500,
        Color(0xFFD96A8A),
        Color(0xFF4FB0A5),
        Color(0xFFB08A5B),
    )

    /** The hero surface: a whisper of mint, never a billboard. */
    val HeroGradient = Brush.linearGradient(
        colors = listOf(Color(0xFFF3FBF7), Color(0xFFE3F6EC)),
    )

    val CardGradient = Brush.linearGradient(
        colors = listOf(Color(0xFFFFFFFF), Color(0xFFFAFEFC)),
    )
}
