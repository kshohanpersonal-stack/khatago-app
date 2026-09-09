package com.khatago.finance.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.khatago.finance.AppContainer
import com.khatago.finance.core.money.CurrencySpec
import com.khatago.finance.data.db.entity.AppSettingEntity
import com.khatago.finance.data.db.entity.ProfileEntity
import com.khatago.finance.data.repo.SampleResult
import com.khatago.finance.ui.components.DropdownField
import com.khatago.finance.ui.components.KhataGoIcons
import com.khatago.finance.ui.components.PrimaryButton
import com.khatago.finance.ui.components.SwitchRow
import com.khatago.finance.ui.components.TextFieldLine
import com.khatago.finance.ui.components.TonalButton
import com.khatago.finance.ui.theme.KhataGoColors
import com.khatago.finance.ui.theme.KhataGoRadii
import com.khatago.finance.ui.theme.KhataGoSpacing
import kotlinx.coroutines.launch

/**
 * First-run setup: three steps, all skippable.
 *
 * Onboarding in a money app has exactly one job — stop the user from building a wrong mental model
 * that costs them money later. So it teaches the two things that are genuinely unlike other apps and
 * skips the rest:
 *  1. **Direction is never netted.** "You owe ৳500" and "they owe you ৳500" are two records, not
 *     zero. Every number in KhataGo follows from that rule, and a user who expects a net balance
 *     misreads the dashboard on day one.
 *  2. **One currency, no conversion.** Chosen here because picking it later would relabel amounts
 *     that are already stored.
 *
 * Notifications and sample data are *offers*, never requirements, and finishing (or skipping) only
 * sets `onboardingComplete` — it creates no ledger rows, so a skipped setup really does start empty.
 */
@Composable
fun OnboardingRoute(container: AppContainer, onFinished: () -> Unit) {
    val scope = rememberCoroutineScope()
    var step by remember { mutableIntStateOf(0) }
    var name by remember { mutableStateOf("") }
    var currency by remember { mutableStateOf(CurrencySpec.DEFAULT) }
    var remindersOn by remember { mutableStateOf(true) }
    var reminderHour by remember { mutableIntStateOf(9) }
    var loadSample by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }

    fun finish(skipOptional: Boolean) {
        scope.launch {
            busy = true
            // The profile is saved first so `onboardingComplete` cannot be set while the chosen
            // currency is still pending: a half-finished setup would show ৳ amounts under another
            // symbol for one frame, and one frame is how "the app changed my currency" bug reports start.
            container.catalogRepository.saveProfile(
                (container.catalogRepository.findProfile() ?: ProfileEntity()).copy(
                    displayName = name.trim().takeIf { it.isNotEmpty() },
                    currencyCode = currency.code,
                    onboardingComplete = true,
                ),
            )
            if (!skipOptional) {
                container.catalogRepository.putBool(AppSettingEntity.NOTIFICATIONS_ENABLED, remindersOn)
                container.catalogRepository.putInt(AppSettingEntity.REMINDER_HOUR, reminderHour)
                container.reminderScheduler.setEnabled(remindersOn, reminderHour)
                if (loadSample) {
                    when (val result = container.dataRepository.loadSampleData()) {
                        is SampleResult.Loaded -> Unit
                        SampleResult.NotEmpty -> notice =
                            "Sample data was not loaded: the ledger already has records."
                    }
                }
            }
            busy = false
            onFinished()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = KhataGoSpacing.screen),
    ) {
        Spacer(Modifier.height(KhataGoSpacing.xxl))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(KhataGoColors.Emerald100, RoundedCornerShape(KhataGoRadii.chip))
                .padding(horizontal = KhataGoSpacing.md, vertical = KhataGoSpacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Step ${step + 1} of 3",
                style = MaterialTheme.typography.labelMedium,
                color = KhataGoColors.Emerald900,
            )
            Spacer(Modifier.width(KhataGoSpacing.md))
            LinearProgressIndicator(
                progress = { (step + 1) / 3f },
                modifier = Modifier
                    .weight(1f)
                    .height(6.dp),
                trackColor = MaterialTheme.colorScheme.surface,
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(top = KhataGoSpacing.xl),
            verticalArrangement = Arrangement.spacedBy(KhataGoSpacing.lg),
        ) {
            when (step) {
                0 -> Column(verticalArrangement = Arrangement.spacedBy(KhataGoSpacing.md)) {
                    Headline(
                        title = "Whose ledger is this?",
                        body = "Your name only appears on the dashboard. Nothing is sent anywhere: " +
                            "KhataGo has no account and no server, so there is nowhere to send it.",
                    )
                    TextFieldLine(
                        value = name,
                        onValueChange = { name = it },
                        label = "Your name",
                        placeholder = "e.g. Shohan",
                        maxLength = 40,
                    )
                    DropdownField(
                        options = CurrencySpec.ALL,
                        selected = currency,
                        onSelect = { currency = it },
                        labelOf = { "${it.code} — ${it.symbol}" },
                        label = "Currency",
                        helper = "Every amount in the app uses this one currency, and KhataGo will never " +
                            "convert between currencies — an invented rate would make your totals " +
                            "unverifiable.",
                    )
                    Hint(
                        text = "You can change both later in Settings. Changing the currency later " +
                            "relabels existing amounts; it does not convert them.",
                    )
                }

                1 -> Column(verticalArrangement = Arrangement.spacedBy(KhataGoSpacing.md)) {
                    Headline(
                        title = "Owing and being owed are never added together",
                        body = "This is the one rule that makes a khata different from a notes app. Two " +
                            "records do not cancel out, because they are two different promises with " +
                            "two different dates.",
                    )
                    DirectionCard(
                        icon = KhataGoIcons.Borrowed,
                        tint = KhataGoColors.OverdueBg,
                        title = "You owe ৳500 to Rahman",
                        body = "Shown as money going out, with its own due date. It never reduces what " +
                            "Karim owes you.",
                    )
                    DirectionCard(
                        icon = KhataGoIcons.Lent,
                        tint = KhataGoColors.OwedToMeBg,
                        title = "Karim owes you ৳500",
                        body = "Shown as money coming in. The dashboard prints both figures side by side — " +
                            "never one net number.",
                    )
                    DirectionCard(
                        icon = KhataGoIcons.Payments,
                        tint = KhataGoColors.Emerald100,
                        title = "A payment reduces one record",
                        body = "Paying ৳200 against Rahman's loan leaves ৳300 there and leaves Karim's " +
                            "debt untouched. Partial and early payments are normal, not exceptions.",
                    )
                    Hint(
                        text = "Every total in the app is derived from payments like that — nothing stores " +
                            "a running balance that could drift from the rows.",
                    )
                }

                else -> Column(verticalArrangement = Arrangement.spacedBy(KhataGoSpacing.md)) {
                    Headline(
                        title = "Two offers",
                        body = "Both optional, both changeable later. Nothing here is required to start.",
                    )
                    SwitchRow(
                        label = "Daily reminder for what is due",
                        sublabel = "One quiet notification. It never shows an amount on the lock screen " +
                            "and never uses the internet.",
                        checked = remindersOn,
                        onCheckedChange = { remindersOn = it },
                    )
                    if (remindersOn) {
                        Row(horizontalArrangement = Arrangement.spacedBy(KhataGoSpacing.sm)) {
                            listOf(7, 9, 12, 18, 20).forEach { hour ->
                                val selected = hour == reminderHour
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .background(
                                            if (selected) {
                                                MaterialTheme.colorScheme.primaryContainer
                                            } else {
                                                KhataGoColors.Ink100
                                            },
                                            RoundedCornerShape(KhataGoRadii.chip),
                                        )
                                        .clickable { reminderHour = hour }
                                        .padding(vertical = KhataGoSpacing.sm),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        text = "%02d:00".format(java.util.Locale.US, hour),
                                        style = MaterialTheme.typography.labelMedium,
                                    )
                                }
                            }
                        }
                    }
                    SwitchRow(
                        label = "Load a sample ledger so I can look around",
                        sublabel = "Three shops, two loans, two EMI plans, months of income and expense. " +
                            "Delete it any time from Settings → Data management.",
                        checked = loadSample,
                        onCheckedChange = { loadSample = it },
                    )
                    Hint(
                        text = "KhataGo will ask for the notification permission after this screen. " +
                            "Saying no is fine: the due lists still work, you just get no reminder.",
                    )
                }
            }

            notice?.let { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Spacer(Modifier.height(KhataGoSpacing.md))
            Row(horizontalArrangement = Arrangement.spacedBy(KhataGoSpacing.md)) {
                if (step < 2) {
                    TonalButton(text = "Skip setup", onClick = { finish(skipOptional = true) })
                }
                PrimaryButton(
                    text = if (step < 2) "Next" else "Start using KhataGo",
                    modifier = Modifier.weight(1f),
                    loading = busy,
                    onClick = { if (step < 2) step += 1 else finish(skipOptional = false) },
                )
            }
            if (step > 0) {
                Text(
                    text = "Back",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .padding(KhataGoSpacing.sm)
                        .clickable { step -= 1 },
                )
            }
            Spacer(Modifier.height(KhataGoSpacing.xl))
        }
    }
}

@Composable
private fun Headline(title: String, body: String) {
    Column(verticalArrangement = Arrangement.spacedBy(KhataGoSpacing.sm)) {
        Text(text = title, style = MaterialTheme.typography.headlineSmall)
        Text(
            text = body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun DirectionCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tint: androidx.compose.ui.graphics.Color,
    title: String,
    body: String,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(tint, RoundedCornerShape(KhataGoRadii.card))
            .padding(KhataGoSpacing.md),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(11.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.width(KhataGoSpacing.md))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.titleSmall)
            Text(
                text = body,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun Hint(text: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(KhataGoColors.SurfaceTinted, RoundedCornerShape(KhataGoRadii.field))
            .padding(KhataGoSpacing.md),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Start,
        )
    }
}
