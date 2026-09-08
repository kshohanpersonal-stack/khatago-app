package com.khatago.finance.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.khatago.finance.ui.theme.KhataGoColors
import com.khatago.finance.ui.theme.KhataGoRadii
import com.khatago.finance.ui.theme.KhataGoSpacing
import com.khatago.finance.ui.theme.KhataGoTypography
import kotlinx.coroutines.delay

/**
 * KhataGo's shared component kit.
 *
 * Everything here exists because the same block appears in more than two screens and must not drift:
 * a card that is 24dp round in one place and 20dp in another is how an app starts to look like
 * eleven different apps. Status pills additionally carry an icon *and* text, never colour alone —
 * "overdue" must survive a colour-blind user, a sunlight screen, and a printed report.
 */

@Composable
fun KhataGoCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(KhataGoSpacing.lg),
    filled: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        shape = RoundedCornerShape(KhataGoRadii.card),
        colors = CardDefaults.cardColors(
            containerColor = if (filled) MaterialTheme.colorScheme.surface else Color.Transparent,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
        border = if (filled) BorderStroke(1.dp, KhataGoColors.Ink100) else null,
        // Soft, single-layer elevation: premium cards here read as "lifted paper", not as neon slabs.
        elevation = CardDefaults.cardElevation(defaultElevation = if (filled) 1.dp else 0.dp),
    ) {
        Column(modifier = Modifier.padding(contentPadding), content = content)
    }
}

/**
 * The hero figure treatment: a small label, then a big number that animates from its previous value
 * when the ledger changes. The count-up is not decoration — when you record a payment, watching the
 * remaining amount fall to zero is the confirmation that the thing you did worked.
 */
@Composable
fun MoneyFigure(
    label: String,
    amountText: String,
    modifier: Modifier = Modifier,
    emphasis: MoneyEmphasis = MoneyEmphasis.Standard,
    supportingText: String? = null,
    tint: Color = MaterialTheme.colorScheme.onSurface,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(KhataGoSpacing.xs)) {
        Text(
            text = label.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = amountText,
            style = when (emphasis) {
                MoneyEmphasis.Hero -> KhataGoTypography.displayLarge
                MoneyEmphasis.Standard -> KhataGoTypography.headlineMoney
                MoneyEmphasis.Compact -> KhataGoTypography.sectionMoney
            },
            color = tint,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (supportingText != null) {
            Text(
                text = supportingText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

enum class MoneyEmphasis { Hero, Standard, Compact }

/** Animated amount. Falls back to the plain text when the user has disabled animations. */
@Composable
fun AnimatedMoney(
    formatted: (Long) -> String,
    amountMinor: Long,
    modifier: Modifier = Modifier,
    style: androidx.compose.ui.text.TextStyle = KhataGoTypography.headlineMoney,
    color: Color = MaterialTheme.colorScheme.onSurface,
) {
    // There is no AccessibilityManager.shouldReduceMotion(); the supported signal is the system's
    // own "remove animations" switch, i.e. the animation scales being zero. Reading a setting needs no
    // permission and no new dependency, and it honours what the user already told the device.
    val reduced = android.provider.Settings.Global.getFloat(
        androidx.compose.ui.platform.LocalContext.current.contentResolver,
        android.provider.Settings.Global.TRANSITION_ANIMATION_SCALE,
        1f,
    ) == 0f
    var target by remember { mutableFloatStateOf(amountMinor.toFloat()) }
    LaunchedEffect(amountMinor) {
        if (reduced) {
            target = amountMinor.toFloat()
        } else {
            // Manual easing keeps this dependency-free and honours the app's single curve.
            val from = target
            val steps = 18
            repeat(steps) { index ->
                val t = (index + 1) / steps.toFloat()
                target = from + (amountMinor - from) * (1f - (1f - t) * (1f - t))
                delay(16)
            }
            target = amountMinor.toFloat()
        }
    }
    Text(
        text = formatted(target.toLong().coerceAtLeast(0L)),
        style = style,
        color = color,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

@Composable
fun StatusPill(
    text: String,
    tone: StatusTone,
    modifier: Modifier = Modifier,
    withIcon: Boolean = true,
) {
    Row(
        modifier = modifier
            .background(tone.background, RoundedCornerShape(KhataGoRadii.chip))
            .padding(horizontal = KhataGoSpacing.md, vertical = KhataGoSpacing.xs + 2.dp)
            .semantics { contentDescription = "$text: ${tone.a11yDescription}" },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(KhataGoSpacing.sm),
    ) {
        if (withIcon) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(tone.dot, CircleShape),
            )
        }
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = tone.content,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/** Status tones carry a text description for accessibility: colour is never the only signal. */
data class StatusTone(
    val background: Color,
    val content: Color,
    val dot: Color,
    val a11yDescription: String,
) {
    companion object {
        val Settled = StatusTone(
            KhataGoColors.SettledBg, KhataGoColors.Settled, KhataGoColors.Settled, "fully paid",
        )
        val Active = StatusTone(
            KhataGoColors.InfoBg, KhataGoColors.Info, KhataGoColors.Info, "outstanding",
        )
        val DueSoon = StatusTone(
            KhataGoColors.DueSoonBg, KhataGoColors.DueSoon, KhataGoColors.DueSoon, "due soon",
        )
        val Overdue = StatusTone(
            KhataGoColors.OverdueBg, KhataGoColors.Overdue, KhataGoColors.Overdue, "overdue",
        )
        val OwedToMe = StatusTone(
            KhataGoColors.OwedToMeBg, KhataGoColors.OwedToMe, KhataGoColors.OwedToMe, "owed to you",
        )
        val Cancelled = StatusTone(
            KhataGoColors.Ink100, KhataGoColors.Ink500, KhataGoColors.Ink400, "cancelled",
        )
        val Info = StatusTone(
            KhataGoColors.InfoBg, KhataGoColors.Info, KhataGoColors.Info, "information",
        )
    }
}

@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
    subtitle: String? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        action?.invoke()
    }
}

@Composable
fun EmptyState(
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    brandMark: Boolean = true,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = KhataGoSpacing.xl, vertical = KhataGoSpacing.xxl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(KhataGoSpacing.md),
    ) {
        if (brandMark) {
            Icon(
                imageVector = Icons.Outlined.Description,
                contentDescription = null,
                tint = KhataGoColors.Emerald200,
                modifier = Modifier.size(46.dp),
            )
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Text(
            text = body,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(KhataGoSpacing.xs))
            PrimaryButton(text = actionLabel, onClick = onAction)
        }
    }
}

@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
) {
    androidx.compose.material3.Button(
        onClick = onClick,
        enabled = enabled && !loading,
        modifier = modifier
            .fillMaxWidth()
            .height(52.dp),
        shape = RoundedCornerShape(KhataGoRadii.button),
        colors = androidx.compose.material3.ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
        ),
    ) {
        if (loading) {
            androidx.compose.material3.CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                color = MaterialTheme.colorScheme.onPrimary,
                strokeWidth = 2.dp,
            )
            Spacer(Modifier.width(KhataGoSpacing.sm))
        }
        Text(text = text, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
fun TonalButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    androidx.compose.material3.FilledTonalButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(46.dp),
        shape = RoundedCornerShape(KhataGoRadii.button),
    ) {
        Text(text = text, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
fun TextActionButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier
            .clickable(onClick = onClick)
            .padding(vertical = KhataGoSpacing.sm, horizontal = KhataGoSpacing.xs)
            .semantics { contentDescription = text },
    )
}

/** Payoff bar. `fraction` is clamped here so a bad upstream value can never draw past the end. */
@Composable
fun PayoffBar(
    fraction: Float,
    modifier: Modifier = Modifier,
    color: Color = KhataGoColors.Emerald600,
    trackColor: Color = KhataGoColors.Ink100,
    height: androidx.compose.ui.unit.Dp = 8.dp,
    label: String? = null,
) {
    val animated by animateFloatAsState(
        targetValue = fraction.coerceIn(0f, 1f),
        animationSpec = androidx.compose.animation.core.tween(
            durationMillis = 420,
            easing = com.khatago.finance.ui.theme.KhataGoMotion.easing,
        ),
        label = "payoff",
    )
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(KhataGoSpacing.xs)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(height)
                .background(trackColor, RoundedCornerShape(50)),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(animated)
                    .height(height)
                    .background(color, RoundedCornerShape(50)),
            )
        }
        if (label != null) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailTopBar(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    actions: RowScopeContent = {},
) {
    TopAppBar(
        title = {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                )
            }
        },
        actions = { actions() },
        modifier = modifier,
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = Color.Transparent,
            titleContentColor = MaterialTheme.colorScheme.onBackground,
        ),
    )
}

typealias RowScopeContent = @Composable () -> Unit

/**
 * A one-line list row with title, subtitle, amount and status. Used by every list in the app so a
 * credit row, a loan row and a payment row all read the same way.
 */
@Composable
fun RecordRow(
    title: String,
    subtitle: String,
    amountText: String,
    modifier: Modifier = Modifier,
    status: String? = null,
    statusTone: StatusTone? = null,
    dateText: String? = null,
    leading: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    secondaryAmountText: String? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = KhataGoSpacing.md, horizontal = KhataGoSpacing.lg),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            leading()
            Spacer(Modifier.width(KhataGoSpacing.md))
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (status != null && statusTone != null) {
                Spacer(Modifier.height(KhataGoSpacing.xs))
                StatusPill(text = status, tone = statusTone)
            }
        }
        Spacer(Modifier.width(KhataGoSpacing.md))
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = amountText,
                style = KhataGoTypography.figure,
                maxLines = 1,
            )
            if (secondaryAmountText != null) {
                Text(
                    text = secondaryAmountText,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (dateText != null) {
                Text(
                    text = dateText,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Segmented control used for the time-range picker and record filters. */
@Composable
fun <T> SegmentedControl(
    options: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    labelOf: (T) -> String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .background(KhataGoColors.Ink100, RoundedCornerShape(KhataGoRadii.field))
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        options.forEach { option ->
            val isSelected = option == selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .background(
                        if (isSelected) MaterialTheme.colorScheme.surface else Color.Transparent,
                        RoundedCornerShape(KhataGoRadii.field - 4.dp),
                    )
                    .clickable { onSelect(option) }
                    .padding(vertical = 9.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = labelOf(option),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (isSelected) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                )
            }
        }
    }
}

/** Horizontal scroll-safe variant for narrow screens with many options. */
@Composable
fun FilterChipRow(
    options: List<String>,
    selected: Set<String>,
    onToggle: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    androidx.compose.foundation.lazy.LazyRow(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = KhataGoSpacing.screen),
        horizontalArrangement = Arrangement.spacedBy(KhataGoSpacing.sm),
    ) {
        items(options) { option ->
            val isOn = option in selected
            Box(
                modifier = Modifier
                    .background(
                        if (isOn) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                        RoundedCornerShape(KhataGoRadii.chip),
                    )
                    .border(
                        1.dp,
                        if (isOn) Color.Transparent else KhataGoColors.Ink200,
                        RoundedCornerShape(KhataGoRadii.chip),
                    )
                    .clickable { onToggle(option) }
                    .padding(horizontal = KhataGoSpacing.md, vertical = KhataGoSpacing.sm),
            ) {
                Text(
                    text = option,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (isOn) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
    }
}

@Composable
fun InfoTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    hint: String? = null,
    valueColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    Column(
        modifier = modifier
            .background(KhataGoColors.SurfaceTinted, RoundedCornerShape(KhataGoRadii.innerCard))
            .padding(KhataGoSpacing.md),
        verticalArrangement = Arrangement.spacedBy(KhataGoSpacing.xxs),
    ) {
        Text(
            text = label.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(text = value, style = MaterialTheme.typography.titleMedium, color = valueColor)
        if (hint != null) {
            Text(
                text = hint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** The plus action used in empty states and quick-add surfaces; consistent size keeps it learnable. */
@Composable
fun QuickAddIcon(modifier: Modifier = Modifier, size: androidx.compose.ui.unit.Dp = 40.dp) {
    Box(
        modifier = modifier
            .size(size)
            .background(KhataGoColors.Emerald100, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Filled.Add,
            contentDescription = null,
            tint = KhataGoColors.Emerald700,
            modifier = Modifier.size(size / 2),
        )
    }
}

@Composable
fun FadeInContent(visible: Boolean, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    AnimatedVisibility(
        visible = visible,
        enter = androidx.compose.animation.fadeIn(animationSpec = androidx.compose.animation.core.tween(220)) +
            androidx.compose.animation.slideInVertically(
                animationSpec = androidx.compose.animation.core.tween(260),
            ) { it / 14 },
        exit = androidx.compose.animation.fadeOut(animationSpec = androidx.compose.animation.core.tween(120)),
        modifier = modifier,
    ) {
        content()
    }
}

