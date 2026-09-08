package com.khatago.finance.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.khatago.finance.ui.components.KhataGoIcons
import com.khatago.finance.ui.theme.KhataGoColors
import com.khatago.finance.ui.theme.KhataGoMotion
import com.khatago.finance.ui.theme.KhataGoSpacing

/**
 * The app's five destinations.
 *
 * A custom bar rather than Material `NavigationBar`, for one practical reason: the centre action here
 * is *Quick add*, which is the single most-used thing in KhataGo and must be reachable with a thumb
 * from any tab. A stock bar would put "Record a payment" behind two taps from the wrong tab.
 *
 * The bar is also the *only* place the centre action lives — the dashboard has its own explicit
 * button, but the bar is what makes the affordance learnable once and reuse forever.
 */
@Composable
fun KhataGoBottomBar(
    selectedRoute: String?,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .navigationBarsPadding(),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(KhataGoColors.Ink100),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp)
                .padding(horizontal = KhataGoSpacing.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            TabItem(
                label = "Home",
                selected = selectedRoute == Routes.HOME,
                onClick = { onSelect(Routes.HOME) },
                modifier = Modifier.weight(1f),
            )
            TabItem(
                label = "Records",
                selected = selectedRoute == Routes.RECORDS,
                onClick = { onSelect(Routes.RECORDS) },
                modifier = Modifier.weight(1f),
            )
            CentreAction(onClick = { onSelect(Routes.QUICK_ADD) })
            TabItem(
                label = "Payments",
                selected = selectedRoute == Routes.PAYMENTS,
                onClick = { onSelect(Routes.PAYMENTS) },
                modifier = Modifier.weight(1f),
            )
            TabItem(
                label = "Insights",
                selected = selectedRoute == Routes.ANALYTICS || selectedRoute == Routes.INSIGHTS,
                onClick = { onSelect(Routes.ANALYTICS) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun TabItem(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scale by animateFloatAsState(
        targetValue = if (selected) 1.08f else 1f,
        animationSpec = androidx.compose.animation.core.tween(
            KhataGoMotion.Quick,
            easing = KhataGoMotion.easing,
        ),
        label = "tab-$label",
    )
    val icon = when (label) {
        "Home" -> KhataGoIcons.Home
        "Records" -> KhataGoIcons.Records
        "Payments" -> KhataGoIcons.Payments
        else -> KhataGoIcons.Analytics
    }
    Column(
        modifier = modifier
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(vertical = KhataGoSpacing.sm),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = if (selected) MaterialTheme.colorScheme.primary else KhataGoColors.Ink400,
            modifier = Modifier
                .size(22.dp)
                .scale(scale),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            color = if (selected) MaterialTheme.colorScheme.primary else KhataGoColors.Ink400,
        )
    }
}

@Composable
private fun CentreAction(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(52.dp)
            .background(
                KhataGoColors.Emerald700,
                RoundedCornerShape(18.dp),
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = KhataGoIcons.Add,
            contentDescription = "Quick add",
            tint = androidx.compose.ui.graphics.Color.White,
            modifier = Modifier.size(26.dp),
        )
    }
    Spacer(Modifier.size(0.dp))
}
