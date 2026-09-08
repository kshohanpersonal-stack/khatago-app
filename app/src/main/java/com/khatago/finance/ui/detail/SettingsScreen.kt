package com.khatago.finance.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.khatago.finance.AppContainer
import com.khatago.finance.BuildConfig
import com.khatago.finance.core.money.CurrencySpec
import com.khatago.finance.data.db.entity.AppSettingEntity
import com.khatago.finance.data.db.entity.CategoryEntity
import com.khatago.finance.data.db.entity.PaymentMethodEntity
import com.khatago.finance.data.db.entity.ProfileEntity
import com.khatago.finance.data.repo.SaveResult
import com.khatago.finance.data.repo.isSaved
import com.khatago.finance.ui.Routes
import com.khatago.finance.ui.components.DetailTopBar
import com.khatago.finance.ui.components.DropdownField
import com.khatago.finance.ui.components.KhataGoCard
import com.khatago.finance.ui.components.KhataGoIcons
import com.khatago.finance.ui.components.PrimaryButton
import com.khatago.finance.ui.components.SectionHeader
import com.khatago.finance.ui.components.SwitchRow
import com.khatago.finance.ui.components.TextFieldLine
import com.khatago.finance.ui.theme.KhataGoColors
import com.khatago.finance.ui.theme.KhataGoRadii
import com.khatago.finance.ui.theme.KhataGoSpacing
import kotlinx.coroutines.launch

/**
 * Settings: profile, currency, reminders, and the two managers (categories, payment methods).
 *
 * Every switch here is wired to the behaviour it names — the reminder hour changes the scheduled work,
 * "hide dashboard widgets" is read by the dashboard, "hide built-in categories" is respected by the
 * pickers — because a setting that changes nothing is worse than no setting at all: it teaches the
 * user that this app's switches are decoration.
 */
@Composable
fun SettingsRoute(
    container: AppContainer,
    onBack: () -> Unit,
    onOpen: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var profile by remember { mutableStateOf<ProfileEntity?>(null) }
    var notificationsOn by remember { mutableStateOf(true) }
    var reminderHour by remember { mutableStateOf(9) }
    var widgetsHidden by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        profile = container.catalogRepository.findProfile()
        notificationsOn = container.catalogRepository.boolSetting(AppSettingEntity.NOTIFICATIONS_ENABLED, true)
        reminderHour = container.catalogRepository.intSetting(AppSettingEntity.REMINDER_HOUR, 9)
        widgetsHidden = container.catalogRepository.boolSetting(AppSettingEntity.DASHBOARD_WIDGETS_HIDDEN, false)
    }

    val profileFlow by container.catalogRepository.observeProfile().collectAsStateWithLifecycle(initialValue = profile)
    val current = profileFlow ?: profile

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        DetailTopBar(title = "Settings", onBack = onBack)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = KhataGoSpacing.screen, vertical = KhataGoSpacing.md),
            verticalArrangement = Arrangement.spacedBy(KhataGoSpacing.lg),
        ) {
            SectionHeader(title = "Profile")
            KhataGoCard {
                TextFieldLine(
                    value = current?.displayName ?: "",
                    onValueChange = { name -> profile = (current ?: ProfileEntity()).copy(displayName = name) },
                    label = "Your name",
                    placeholder = "Shown on the dashboard",
                )
                Spacer(Modifier.height(KhataGoSpacing.md))
                CurrencyPicker(
                    selected = CurrencySpec.fromCode(current?.currencyCode),
                    onSelect = { spec ->
                        profile = (current ?: ProfileEntity()).copy(currencyCode = spec.code)
                    },
                )
                Spacer(Modifier.height(KhataGoSpacing.md))
                PrimaryButton(
                    text = "Save profile",
                    onClick = {
                        scope.launch {
                            val toSave = (profile ?: ProfileEntity()).copy(
                                onboardingComplete = true,
                            )
                            val result = container.catalogRepository.saveProfile(toSave)
                            notice = if (result.isSaved) {
                                "Saved."
                            } else {
                                (result as? SaveResult.Invalid)?.message ?: "Could not be saved."
                            }
                        }
                    },
                )
                CurrencyWarning(current = current)
            }

            SectionHeader(title = "Reminders")
            KhataGoCard {
                SwitchRow(
                    label = "Daily reminder",
                    sublabel = "One quiet notification listing what is due. Never sends anything anywhere.",
                    checked = notificationsOn,
                    onCheckedChange = { enabled ->
                        notificationsOn = enabled
                        scope.launch {
                            container.reminderScheduler.setEnabled(enabled, reminderHour)
                        }
                    },
                )
                Spacer(Modifier.height(KhataGoSpacing.sm))
                HourPicker(
                    hour = reminderHour,
                    onSelect = { hour ->
                        reminderHour = hour
                        scope.launch {
                            container.reminderScheduler.setEnabled(notificationsOn, hour)
                        }
                    },
                )
                Spacer(Modifier.height(KhataGoSpacing.sm))
                ReminderToggles(container = container, onNotice = { notice = it })
            }

            SectionHeader(title = "Interface")
            KhataGoCard {
                SwitchRow(
                    label = "Hide dashboard widgets",
                    sublabel = "Removes the recent-activity block. The numbers that matter stay on top.",
                    checked = widgetsHidden,
                    onCheckedChange = { hidden ->
                        widgetsHidden = hidden
                        scope.launch {
                            container.catalogRepository.putBool(
                                AppSettingEntity.DASHBOARD_WIDGETS_HIDDEN,
                                hidden,
                            )
                        }
                    },
                )
                SettingsLinkRow(
                    icon = KhataGoIcons.Lock,
                    title = "App lock (PIN and fingerprint)",
                    subtitle = if (container.securityRepository.isLockEnabled) "On" else "Off",
                    onClick = { onOpen(Routes.SETTINGS_SECURITY) },
                )
            }

            SectionHeader(title = "Lists")
            KhataGoCard {
                CategoryManager(container = container, kind = CategoryEntity.KIND_EXPENSE)
                Spacer(Modifier.height(KhataGoSpacing.md))
                CategoryManager(container = container, kind = CategoryEntity.KIND_INCOME)
                Spacer(Modifier.height(KhataGoSpacing.md))
                MethodManager(container = container)
            }

            SectionHeader(title = "Your data")
            KhataGoCard {
                SettingsLinkRow(
                    icon = KhataGoIcons.Share,
                    title = "Backup & restore",
                    subtitle = "A JSON file you keep; the ledger in one document",
                    onClick = { onOpen(Routes.BACKUP) },
                )
                SettingsLinkRow(
                    icon = KhataGoIcons.Receipt,
                    title = "Reports & CSV export",
                    subtitle = "Spreadsheets, printing, sharing with an accountant",
                    onClick = { onOpen(Routes.REPORTS) },
                )
                SettingsLinkRow(
                    icon = KhataGoIcons.Delete,
                    title = "Data management",
                    subtitle = "Sample data, attachment usage, delete everything",
                    onClick = { onOpen(Routes.SETTINGS_DATA) },
                )
                SettingsLinkRow(
                    icon = KhataGoIcons.Verified,
                    title = "About KhataGo",
                    subtitle = "Version, licence, privacy statement, what this app will never do",
                    onClick = { onOpen(Routes.ABOUT) },
                )
            }

            notice?.let { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun CurrencyPicker(selected: CurrencySpec, onSelect: (CurrencySpec) -> Unit) {
    DropdownField(
        options = CurrencySpec.ALL,
        selected = selected,
        onSelect = onSelect,
        labelOf = { "${it.code} — ${it.symbol}" },
        label = "Currency",
        helper = "One currency for the whole ledger. KhataGo never converts: an invented exchange rate " +
            "would make your totals wrong in a way you cannot audit.",
    )
}

@Composable
private fun CurrencyWarning(current: ProfileEntity?) {
    var show by remember { mutableStateOf(false) }
    if (current?.currencyCode == null) return
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = KhataGoSpacing.sm)
            .background(KhataGoColors.DueSoonBg, RoundedCornerShape(KhataGoRadii.field))
            .clickable { show = true }
            .padding(KhataGoSpacing.md),
    ) {
        Text(
            text = "Changing the currency relabels every existing amount — it does not convert them. " +
                "Tap to read why.",
            style = MaterialTheme.typography.bodySmall,
            color = KhataGoColors.DueSoon,
        )
    }
    if (show) {
        AlertDialog(
            onDismissRequest = { show = false },
            title = { Text("No conversion, by design") },
            text = {
                Text(
                    "A personal ledger must add up. If KhataGo converted amounts using an estimated or " +
                        "outdated rate, every total would silently become an approximation you cannot " +
                        "verify against your bank statement. So the app refuses: switching the currency " +
                        "only changes how numbers are *written*, and existing rows keep their stored " +
                        "minor units. If you are moving ledger, delete the data and start fresh, or " +
                        "restore a backup taken after the change.",
                )
            },
            confirmButton = { TextButton(onClick = { show = false }) { Text("Understood") } },
        )
    }
}

@Composable
private fun HourPicker(hour: Int, onSelect: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(KhataGoSpacing.xs)) {
        Text(
            text = "REMIND ME AROUND",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(KhataGoSpacing.sm)) {
            listOf(7, 9, 12, 18, 20).forEach { option ->
                val selected = option == hour
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .background(
                            if (selected) MaterialTheme.colorScheme.primaryContainer else KhataGoColors.Ink100,
                            RoundedCornerShape(KhataGoRadii.chip),
                        )
                        .clickable { onSelect(option) }
                        .padding(vertical = KhataGoSpacing.sm),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = com.khatago.finance.core.time.AppDates.clockTime(
                            java.time.LocalTime.of(option, 0),
                        ),
                        style = MaterialTheme.typography.labelMedium,
                        color = if (selected) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
        }
        Text(
            text = "The schedule is a daily WorkManager job aligned to this hour; the exact minute can " +
                "shift by the OS to save battery, and reminders are deduplicated so a delay can never " +
                "produce two notifications for the same day.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ReminderToggles(container: AppContainer, onNotice: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var dueToday by remember { mutableStateOf(true) }
    var dueTomorrow by remember { mutableStateOf(true) }
    var overdue by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        dueToday = container.catalogRepository.boolSetting(AppSettingEntity.REMIND_DUE_TODAY, true)
        dueTomorrow = container.catalogRepository.boolSetting(AppSettingEntity.REMIND_DUE_TOMORROW, true)
        overdue = container.catalogRepository.boolSetting(AppSettingEntity.REMIND_OVERDUE, true)
    }

    fun persist(key: String, value: Boolean) {
        scope.launch {
            container.catalogRepository.putBool(key, value)
            onNotice("Saved.")
        }
    }

    Column {
        SwitchRow(
            label = "Due today",
            checked = dueToday,
            onCheckedChange = { dueToday = it; persist(AppSettingEntity.REMIND_DUE_TODAY, it) },
        )
        SwitchRow(
            label = "Due tomorrow",
            sublabel = "The useful one: you can still act before the date passes.",
            checked = dueTomorrow,
            onCheckedChange = { dueTomorrow = it; persist(AppSettingEntity.REMIND_DUE_TOMORROW, it) },
        )
        SwitchRow(
            label = "Overdue",
            checked = overdue,
            onCheckedChange = { overdue = it; persist(AppSettingEntity.REMIND_OVERDUE, it) },
        )
    }
}

@Composable
internal fun SettingsLinkRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = KhataGoSpacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(imageVector = icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(KhataGoSpacing.md))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.titleMedium)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * Category manager.
 *
 * Built-in categories can be **hidden, not deleted**: existing records reference them by name, and a
 * delete that orphans those rows would corrupt the very reports the categories exist to support.
 */
@Composable
private fun CategoryManager(container: AppContainer, kind: String) {
    val scope = rememberCoroutineScope()
    val categories by container.catalogRepository.observeCategories(kind)
        .collectAsStateWithLifecycle(initialValue = emptyList())
    var newName by remember(kind) { mutableStateOf("") }
    var error by remember(kind) { mutableStateOf<String?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(KhataGoSpacing.sm)) {
        Text(
            text = if (kind == CategoryEntity.KIND_INCOME) "Income categories" else "Expense categories",
            style = MaterialTheme.typography.titleMedium,
        )
        categories.forEach { category ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = category.name + if (category.builtIn) "  · built-in" else "",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = if (category.enabled) "Hide" else "Show",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .clickable {
                            scope.launch {
                                container.catalogRepository.setCategoryEnabled(category.id, !category.enabled)
                            }
                        }
                        .padding(KhataGoSpacing.sm),
                )
                if (!category.builtIn) {
                    Text(
                        text = "Delete",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier
                            .clickable {
                                scope.launch {
                                    val result = container.catalogRepository.deleteCategory(category.id)
                                    error = if (result.isSaved) {
                                        null
                                    } else {
                                        (result as? SaveResult.Invalid)?.message
                                    }
                                }
                            }
                            .padding(KhataGoSpacing.sm),
                    )
                }
            }
        }
        error?.let {
            Text(text = it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextFieldLine(
                value = newName,
                onValueChange = { newName = it },
                label = "New category",
                modifier = Modifier.weight(1f),
                keyboardType = KeyboardType.Text,
            )
            Spacer(Modifier.width(KhataGoSpacing.md))
            Text(
                text = "Add",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .clickable {
                        scope.launch {
                            val result = container.catalogRepository.addCategory(kind, newName.trim())
                            error = if (result.isSaved) {
                                newName = ""
                                null
                            } else {
                                (result as? SaveResult.Invalid)?.message
                            }
                        }
                    }
                    .padding(KhataGoSpacing.sm),
            )
        }
    }
}

@Composable
private fun MethodManager(container: AppContainer) {
    val scope = rememberCoroutineScope()
    val methods by container.catalogRepository.observePaymentMethods()
        .collectAsStateWithLifecycle(initialValue = emptyList())
    var newName by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(KhataGoSpacing.sm)) {
        Text(text = "Payment methods", style = MaterialTheme.typography.titleMedium)
        methods.forEach { method: PaymentMethodEntity ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = method.name + if (method.builtIn) "  · built-in" else "",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = if (method.enabled) "Hide" else "Show",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .clickable {
                            scope.launch {
                                container.catalogRepository.setPaymentMethodEnabled(method.id, !method.enabled)
                            }
                        }
                        .padding(KhataGoSpacing.sm),
                )
                if (!method.builtIn) {
                    Text(
                        text = "Delete",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier
                            .clickable {
                                scope.launch {
                                    val result = container.catalogRepository.deletePaymentMethod(method.id)
                                    error = if (result.isSaved) {
                                        null
                                    } else {
                                        (result as? SaveResult.Invalid)?.message
                                    }
                                }
                            }
                            .padding(KhataGoSpacing.sm),
                    )
                }
            }
        }
        error?.let {
            Text(text = it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextFieldLine(
                value = newName,
                onValueChange = { newName = it },
                label = "New method",
                modifier = Modifier.weight(1f),
                placeholder = "Nogod, Rocket, bank cash…",
            )
            Spacer(Modifier.width(KhataGoSpacing.md))
            Text(
                text = "Add",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .clickable {
                        scope.launch {
                            val result = container.catalogRepository.addPaymentMethod(newName.trim())
                            error = if (result.isSaved) {
                                newName = ""
                                null
                            } else {
                                (result as? SaveResult.Invalid)?.message
                            }
                        }
                    }
                    .padding(KhataGoSpacing.sm),
            )
        }
    }
}

@Suppress("unused")
private fun versionString(): String = BuildConfig.VERSION_NAME
