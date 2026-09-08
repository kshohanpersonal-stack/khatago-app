package com.khatago.finance.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.khatago.finance.AppContainer
import com.khatago.finance.data.repo.BackupStats
import com.khatago.finance.data.repo.DeletionSummary
import com.khatago.finance.data.repo.SampleResult
import com.khatago.finance.ui.Routes
import com.khatago.finance.ui.components.DetailTopBar
import com.khatago.finance.ui.components.KhataGoCard
import com.khatago.finance.ui.components.KhataGoIcons
import com.khatago.finance.ui.components.SectionHeader
import com.khatago.finance.ui.components.TonalButton
import com.khatago.finance.ui.components.humanBytes
import com.khatago.finance.ui.theme.KhataGoColors
import com.khatago.finance.ui.theme.KhataGoRadii
import com.khatago.finance.ui.theme.KhataGoSpacing
import kotlinx.coroutines.launch

/**
 * Data management: the physical truth about what this app is holding.
 *
 * Two things are deliberately shown side by side here:
 *  - **record counts**, because "how much have I actually typed in?" is the first question a user has
 *    before trusting an export or a delete;
 *  - **attachment bytes**, because photos are the only part of a khata app that grows without the user
 *    noticing, and KhataGo stores them in its own private directory rather than in the shared gallery.
 *
 * "Delete everything" is the app's only irreversible action, so the confirmation counts down taps
 * (three) instead of a single "Are you sure?" — a design that has ended far too many accidents.
 */
@Composable
fun DataManagementRoute(
    container: AppContainer,
    onBack: () -> Unit,
    onOpen: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var stats by remember { mutableStateOf<BackupStats?>(null) }
    var attachmentFiles by remember { mutableIntStateOf(0) }
    var attachmentBytes by remember { mutableLongStateOf(0L) }
    var summary by remember { mutableStateOf<DeletionSummary?>(null) }
    var busy by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var taps by remember { mutableIntStateOf(0) }

    suspend fun refresh() {
        stats = container.backupRepository.statsSnapshot()
        attachmentFiles = container.attachmentRepository.fileCount()
        attachmentBytes = container.attachmentRepository.usedBytes()
        summary = container.dataRepository.deletionSummary()
    }

    LaunchedEffect(Unit) { refresh() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        DetailTopBar(title = "Data management", onBack = onBack)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = KhataGoSpacing.screen, vertical = KhataGoSpacing.md),
            verticalArrangement = Arrangement.spacedBy(KhataGoSpacing.lg),
        ) {
            SectionHeader(title = "Records on this device")
            KhataGoCard {
                val rows = stats?.rows().orEmpty()
                if (rows.isEmpty()) {
                    Text(
                        text = "Counting…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    rows.forEach { (label, value) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 3.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = label,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(text = value, style = MaterialTheme.typography.titleSmall)
                        }
                    }
                }
            }

            SectionHeader(title = "Photos and receipts")
            KhataGoCard {
                Text(
                    text = "$attachmentFiles image(s) using ${humanBytes(attachmentBytes)} " +
                        "inside KhataGo's own storage.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(KhataGoSpacing.xs))
                Text(
                    text = "Attachment files live in the app's private directory, so they are readable by " +
                        "no other app and are removed with it — but they are **not** inside the JSON " +
                        "backup. Copy anything you must keep out of the app before you reinstall or " +
                        "factory-reset; that is the only way a photo can be lost here.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            SectionHeader(title = "Sample ledger")
            KhataGoCard {
                Text(
                    text = "Load a small, realistic ledger — three shops, two loans, two EMI plans, " +
                        "borrowings and lendings, months of income and expense — to see how the screens " +
                        "behave before you enter your own data. It is refused when your ledger already " +
                        "has records, so it can never mix into real numbers.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(KhataGoSpacing.md))
                TonalButton(
                    text = if (busy) "Working…" else "Load sample data",
                    onClick = {
                        scope.launch {
                            busy = true
                            when (val result = container.dataRepository.loadSampleData()) {
                                is SampleResult.Loaded ->
                                    notice = "Loaded ${result.recordCount} record(s). Explore freely — " +
                                        "delete everything afterwards to start clean."

                                SampleResult.NotEmpty ->
                                    notice = "Your ledger already has records, so nothing was added. " +
                                        "Delete everything first if you want to try the sample."
                            }
                            busy = false
                            refresh()
                        }
                    },
                )
            }

            SectionHeader(title = "Move or copy your data")
            KhataGoCard {
                SettingsLinkRow(
                    icon = KhataGoIcons.Share,
                    title = "Backup & restore",
                    subtitle = "Write or apply the JSON file — the only full copy of your ledger",
                    onClick = { onOpen(Routes.BACKUP) },
                )
                SettingsLinkRow(
                    icon = KhataGoIcons.Receipt,
                    title = "Export CSV",
                    subtitle = "For spreadsheets and printing; not restorable",
                    onClick = { onOpen(Routes.REPORTS) },
                )
            }

            SectionHeader(title = "Delete everything")
            KhataGoCard {
                Text(
                    text = (summary?.describe() ?: "Counting…") +
                        "\n\nYour PIN (if set) and the failed-attempt counter survive this on purpose — " +
                        "otherwise a wipe would also be a way past the lock.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(KhataGoSpacing.md))
                Text(
                    text = "Delete all data…",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(KhataGoColors.OverdueBg, RoundedCornerShape(KhataGoRadii.field))
                        .clickable { showDeleteDialog = true; taps = 0 }
                        .padding(KhataGoSpacing.md),
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

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Delete your entire ledger?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(KhataGoSpacing.sm)) {
                    Text(text = summary?.describe() ?: "All records will be removed.", maxLines = 8)
                    Text(
                        text = "This cannot be undone from inside KhataGo. If you have a backup file you " +
                            "can restore it afterwards.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Text(
                        text = "Tap “Delete” three times to confirm.",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    taps += 1
                    when (taps) {
                        1 -> notice = "Tap “Delete” again to be sure."
                        2 -> notice = "One more tap and everything goes."
                        else -> {
                            showDeleteDialog = false
                            scope.launch {
                                busy = true
                                val result = container.dataRepository.deleteAllData()
                                busy = false
                                notice = "Deleted ${result.summary.totalRecords} record(s); " +
                                    "${result.attachmentFilesRemoved} attachment file(s) removed."
                                taps = 0
                                refresh()
                            }
                        }
                    }
                }) {
                    Text(
                        text = if (taps == 0) "Delete" else "Delete (${3 - taps} more)",
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false; taps = 0 }) { Text("Keep my data") }
            },
        )
    }
}
