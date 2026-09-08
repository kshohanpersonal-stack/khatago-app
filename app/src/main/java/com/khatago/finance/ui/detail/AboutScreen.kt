package com.khatago.finance.ui.detail

import androidx.compose.foundation.background
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.khatago.finance.BuildConfig
import com.khatago.finance.AppContainer
import com.khatago.finance.data.db.KHATAGO_DB_VERSION
import com.khatago.finance.data.repo.BackupStats
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.khatago.finance.ui.components.DetailTopBar
import com.khatago.finance.ui.components.KhataGoCard
import com.khatago.finance.ui.components.KhataGoIcons
import com.khatago.finance.ui.theme.KhataGoColors
import com.khatago.finance.ui.theme.KhataGoRadii
import com.khatago.finance.ui.theme.KhataGoSpacing

/**
 * About — the page where KhataGo states, in writing, what it will never do.
 *
 * This is not decoration. An app that holds a person's debts has to be answerable about its limits,
 * and a reviewer (or a user, six months from now) should be able to check every claim here against
 * the manifest and the build file. The permissions listed are exactly the ones declared, and the
 * absence of INTERNET is verifiable with `aapt dump permissions`.
 */
@Composable
fun AboutRoute(container: AppContainer, onBack: () -> Unit) {
    var stats by remember { mutableStateOf<BackupStats?>(null) }
    LaunchedEffect(Unit) { stats = container.backupRepository.statsSnapshot() }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        DetailTopBar(title = "About", onBack = onBack)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = KhataGoSpacing.screen, vertical = KhataGoSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(KhataGoSpacing.lg),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    modifier = Modifier
                        .size(72.dp)
                        .background(KhataGoColors.Emerald700, RoundedCornerShape(22.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = KhataGoIcons.Records,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(34.dp),
                    )
                }
                Spacer(Modifier.height(KhataGoSpacing.md))
                Text(text = "KhataGo", style = MaterialTheme.typography.headlineMedium)
                Text(
                    text = "Version ${BuildConfig.VERSION_NAME} · " +
                        (if (BuildConfig.DEBUG) "debug build" else "release build"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "Ledger database v$KHATAGO_DB_VERSION · offline only",
                    style = MaterialTheme.typography.labelSmall,
                    color = KhataGoColors.Ink400,
                )
            }

            stats?.let { counts ->
                AboutBlock(
                    title = "Currently stored",
                    lines = listOf(
                        "${counts.total} record(s) across ${counts.shops} shop(s), " +
                            "${counts.people} person(s), ${counts.loans} loan(s) and ${counts.emis} EMI plan(s).",
                        "${counts.payments} payment(s), ${counts.incomes} income and " +
                            "${counts.expenses} expense entries, ${counts.attachments} image(s).",
                    ),
                )
            }

            AboutBlock(
                title = "What this app is",
                body = "A personal money ledger for shops, people, loans and EMIs — the " +
                    "*khata* a Bangladeshi household keeps, on a phone instead of a notebook. " +
                    "Money you owe and money owed to you are always separate: no netting, no " +
                    "invented balance.",
            )

            AboutBlock(
                title = "Privacy, stated as facts",
                lines = listOf(
                    "There is no account, no sign-up, no server and no analytics SDK.",
                    "INTERNET is not in the manifest, so nothing can be uploaded even by accident.",
                    "No ad or tracking library is compiled into the app.",
                    "Permissions: POST_NOTIFICATIONS (daily due reminders) and USE_BIOMETRIC " +
                        "(optional app unlock). That is the complete list.",
                    "Export uses the system file picker — the app holds no storage permission.",
                    "A receipt photo is cropped and recompressed, then saved in this app's private " +
                        "directory. It never enters the shared gallery.",
                    "Your PIN is stored only as a salted PBKDF2 digest, outside the database, so a " +
                        "backup file can never carry it.",
                ),
            )

            AboutBlock(
                title = "Limits you should know",
                lines = listOf(
                    "One device. There is no sync: two phones mean two ledgers. Carry a JSON backup " +
                        "between them.",
                    "One currency for the whole ledger, and never any conversion — an estimated rate " +
                        "would make totals unverifiable.",
                    "Deleting a record cannot be undone from inside the app; a backup file is the " +
                        "only recovery path.",
                    "The app lock is a gate on the interface, not disk encryption. Android's own " +
                        "file encryption is what protects data at rest.",
                    "Amounts are stored in exact minor units. Rounding is display-only; nothing is " +
                        "ever stored rounded.",
                ),
            )

            AboutBlock(
                title = "Licence",
                lines = listOf(
                    "KhataGo is licensed under the Apache License, Version 2.0.",
                    "The full text ships in LICENSE, with third-party notices in NOTICE.",
                    "You can inspect, rebuild and self-sign it: the build has no network steps that " +
                        "change what runs on your phone.",
                ),
            )

            AboutBlock(
                title = "Built with",
                lines = listOf(
                    "Kotlin · Jetpack Compose (Material 3) · Room · WorkManager",
                    "WorkManager for reminders · kotlinx.serialization for the backup format",
                    "No backend, no cloud, no paid service.",
                ),
            )

            Text(
                text = "KhataGo keeps your accounts in one place, on one phone, in your hands.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun AboutBlock(title: String, body: String? = null, lines: List<String> = emptyList()) {
    KhataGoCard {
        Text(text = title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(KhataGoSpacing.sm))
        if (body != null) {
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        lines.forEach { line ->
            Row(modifier = Modifier.padding(vertical = 3.dp)) {
                Text(
                    text = "—",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(KhataGoSpacing.sm))
                Text(
                    text = line,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Suppress("unused")
private val shape = RoundedCornerShape(KhataGoRadii.card)
