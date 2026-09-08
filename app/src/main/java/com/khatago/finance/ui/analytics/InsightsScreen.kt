package com.khatago.finance.ui.analytics

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.khatago.finance.AppContainer
import com.khatago.finance.core.money.CurrencySpec
import com.khatago.finance.domain.calc.Insight
import com.khatago.finance.ui.components.DetailTopBar
import com.khatago.finance.ui.components.EmptyState
import com.khatago.finance.ui.components.KhataGoCard
import com.khatago.finance.ui.components.KhataGoIcons
import com.khatago.finance.core.money.MoneyFormat
import com.khatago.finance.ui.components.toneForLedger
import com.khatago.finance.ui.theme.KhataGoColors
import com.khatago.finance.ui.theme.KhataGoRadii
import com.khatago.finance.ui.theme.KhataGoSpacing
import com.khatago.finance.ui.theme.KhataGoTypography

/**
 * Insights, stated plainly.
 *
 * These are **rules over the user's own records**, not machine learning: each line corresponds to a
 * check a person could do themselves with a calculator, and the copy quotes the actual figure it is
 * talking about. That is the entire difference between a feature that helps and a feature that
 * patronises. When no rule fires, the screen says so and shows nothing — "no news" is an honest
 * answer, and inventing commentary to fill a card is how an app loses credibility in one sentence.
 */
@Composable
fun InsightsRoute(container: AppContainer, onBack: () -> Unit) {
    var insights by remember { mutableStateOf<List<Insight>>(emptyList()) }
    var currency by remember { mutableStateOf<CurrencySpec?>(null) }
    var snapshot by remember {
        mutableStateOf<com.khatago.finance.domain.calc.SnapshotResult?>(null)
    }
    var loaded by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val profile = container.catalogRepository.findProfile()
        val spec = CurrencySpec.fromCode(profile?.currencyCode)
        val today = com.khatago.finance.core.time.AppDates.today()
        currency = spec
        // `moneyText` is @Composable and this lambda runs in a coroutine, not in composition —
        // so the plain core formatter is used here, which is the exact function moneyText forwards to.
        snapshot = container.statsRepository.snapshot(today) { minor -> MoneyFormat.format(minor, spec) }
        insights = container.statsRepository.insights(today) { minor -> MoneyFormat.format(minor, spec) }
        loaded = true
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        DetailTopBar(title = "Insights", onBack = onBack)
        LazyColumn(
            contentPadding = PaddingValues(bottom = KhataGoSpacing.xxl),
            verticalArrangement = Arrangement.spacedBy(KhataGoSpacing.md),
            modifier = Modifier.fillMaxSize(),
        ) {
            snapshot?.let { card ->
                item {
                    KhataGoCard(modifier = Modifier.padding(horizontal = KhataGoSpacing.screen)) {
                        Column(verticalArrangement = Arrangement.spacedBy(KhataGoSpacing.sm)) {
                            Text(text = "Financial snapshot", style = MaterialTheme.typography.titleMedium)
                            Text(
                                text = card.bandLabel,
                                style = KhataGoTypography.headlineMoney,
                                color = KhataGoColors.Emerald900,
                            )
                            Text(
                                text = "Score $card.score/100 — computed from your records on this " +
                                    "device, and it is not a credit score.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            item {
                Text(
                    text = if (insights.isEmpty()) "What we can say from your records" else "Right now",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = KhataGoSpacing.screen),
                )
            }
            if (insights.isEmpty() && loaded) {
                item {
                    EmptyState(
                        title = "Nothing to point out",
                        body = "No pattern in your records crossed a threshold — no overdue items, no " +
                            "sudden jump in spending, no unusual quiet period. Add a couple more " +
                            "months of entries and observations will start appearing here.",
                        actionLabel = null,
                        onAction = null,
                    )
                }
            } else {
                items(insights) { insight ->
                    KhataGoCard(modifier = Modifier.padding(horizontal = KhataGoSpacing.screen)) {
                        InsightLine(insight = insight, currency = currency ?: CurrencySpec.DEFAULT)
                    }
                }
            }
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = KhataGoSpacing.screen)
                        .background(KhataGoColors.SurfaceTinted, RoundedCornerShape(KhataGoRadii.card))
                        .padding(KhataGoSpacing.lg),
                    verticalArrangement = Arrangement.spacedBy(KhataGoSpacing.xs),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = KhataGoIcons.Lock,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(KhataGoSpacing.sm))
                        Text(
                            text = "How these are made",
                            style = MaterialTheme.typography.titleSmall,
                        )
                    }
                    Text(
                        text = "Every line is produced by a fixed rule over your stored records (for " +
                            "example: this month's expenses more than 25% above last month's). No " +
                            "model, no upload, no profile of you — the rules are in " +
                            "domain/calc/Insights.kt and are unit-tested.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
