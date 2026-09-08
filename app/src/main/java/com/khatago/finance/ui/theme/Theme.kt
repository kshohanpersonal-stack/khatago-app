package com.khatago.finance.ui.theme

import androidx.compose.animation.core.DurationBasedAnimationSpec
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/**
 * Motion and shape tokens.
 *
 * Animation in a finance app has one job: make a state change comprehensible. Numbers count up when
 * a balance changes, a sheet rises when it replaces the screen, a row collapses when it is deleted.
 * Anything that animates for its own sake is excluded — a 400 ms flourish on every tap is what makes
 * apps feel like a demo rather than a tool.
 */
object KhataGoMotion {
    /** Fast enough to feel instant, slow enough to be followed. */
    const val Quick = 140
    const val Standard = 220
    const val Entrances = 320
    const val Sheet = 260
    const val NumberRoll = 550

    /** Single easing across the app so transitions feel authored by one hand. */
    val easing = FastOutSlowInEasing

    fun <T> tween(durationMillis: Int = Standard): DurationBasedAnimationSpec<T> =
        androidx.compose.animation.core.tween(durationMillis, 0, easing)
}

object KhataGoSpacing {
    val xxs = 2.dp
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 20.dp
    val xxl = 28.dp
    val hero = 36.dp

    /** Screen gutter: 20dp keeps cards off the display curve on small phones and looks deliberate on large ones. */
    val screen = 20.dp
}

object KhataGoRadii {
    val card = 24.dp
    val innerCard = 18.dp
    val field = 16.dp
    val chip = 12.dp
    val button = 16.dp
    val sheet = 30.dp
}

@Composable
fun KhataGoTheme(
    /**
     * Ignored on purpose. The parameter exists so a caller (or a preview) cannot accidentally
     * request a dark KhataGo: the app is light-only, and that is a product requirement, not a
     * preference. `forceLight` documents the intent at the call site.
     */
    @Suppress("UNUSED_PARAMETER") forceLight: Boolean = true,
    content: @Composable () -> Unit,
) {
    // isSystemInDarkTheme() is read only to keep the compiler honest about the requirement being
    // explicit; it never changes the scheme below.
    @Suppress("UNUSED_EXPRESSION")
    isSystemInDarkTheme()

    val scheme = lightColorScheme(
        primary = KhataGoColors.Emerald700,
        onPrimary = Color.White,
        primaryContainer = KhataGoColors.Emerald100,
        onPrimaryContainer = KhataGoColors.Emerald900,
        secondary = KhataGoColors.Emerald600,
        onSecondary = Color.White,
        secondaryContainer = KhataGoColors.Emerald50,
        onSecondaryContainer = KhataGoColors.Emerald900,
        tertiary = KhataGoColors.OwedToMe,
        onTertiary = Color.White,
        tertiaryContainer = KhataGoColors.OwedToMeBg,
        background = KhataGoColors.SurfaceCanvas,
        onBackground = KhataGoColors.Ink900,
        surface = KhataGoColors.SurfaceWhite,
        onSurface = KhataGoColors.Ink900,
        surfaceVariant = KhataGoColors.SurfaceTinted,
        onSurfaceVariant = KhataGoColors.Ink600,
        surfaceContainer = KhataGoColors.SurfaceWhite,
        surfaceContainerHigh = KhataGoColors.SurfaceTinted,
        surfaceContainerHighest = KhataGoColors.Emerald50,
        outline = KhataGoColors.Ink200,
        outlineVariant = KhataGoColors.Ink100,
        error = KhataGoColors.Overdue,
        onError = Color.White,
        errorContainer = KhataGoColors.OverdueBg,
        onErrorContainer = Color(0xFF7A1C17),
        scrim = Color(0x660E1C17),
    )

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            // Light status + nav bar with dark glyphs: the app is never dark, so the system chrome
            // must never go light-on-dark to "match" a system theme the app does not follow.
            val window = (view.context as? android.app.Activity)?.window
            if (window != null) {
                WindowCompat.getInsetsController(window, view).apply {
                    isAppearanceLightStatusBars = true
                    isAppearanceLightNavigationBars = true
                }
            }
        }
    }

    MaterialTheme(
        colorScheme = scheme,
        typography = KhataGoTypography.build,
        shapes = Shapes(
            extraSmall = RoundedCornerShape(8.dp),
            small = RoundedCornerShape(KhataGoRadii.chip),
            medium = RoundedCornerShape(KhataGoRadii.innerCard),
            large = RoundedCornerShape(KhataGoRadii.card),
            extraLarge = RoundedCornerShape(KhataGoRadii.sheet),
        ),
        content = content,
    )
}
