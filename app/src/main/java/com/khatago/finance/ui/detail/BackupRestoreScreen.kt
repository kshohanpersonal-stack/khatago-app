package com.khatago.finance.ui.detail

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.khatago.finance.AppContainer
import com.khatago.finance.data.backup.BackupDocument
import com.khatago.finance.data.repo.BackupStats
import com.khatago.finance.data.repo.ExportResult
import com.khatago.finance.data.repo.InspectResult
import com.khatago.finance.data.repo.RestoreMode
import com.khatago.finance.ui.components.DetailTopBar
import com.khatago.finance.ui.components.KhataGoCard
import com.khatago.finance.ui.components.KhataGoIcons
import com.khatago.finance.ui.components.PrimaryButton
import com.khatago.finance.ui.components.SectionHeader
import com.khatago.finance.ui.components.TonalButton
import com.khatago.finance.ui.theme.KhataGoColors
import com.khatago.finance.ui.theme.KhataGoRadii
import com.khatago.finance.ui.theme.KhataGoSpacing
import kotlinx.coroutines.launch

/**
 * Backup and restore.
 *
 * Why a bespoke JSON file instead of Android's auto-backup: auto-backup is cloud-backed, and a ledger
 * of who owes whom is precisely the data the product promises never to leave the device. So the
 * Android backup rules *exclude* everything, and this screen is the user-facing path — a file they
 * choose, they name, they keep.
 *
 * The restore flow is deliberately long, because it is the one destructive thing the app can do:
 *  1. read the file and **validate** it (version, amounts, dates, relation integrity);
 *  2. show what is inside — record counts, currency, creation date, warnings;
 *  3. state plainly whether existing records will be replaced or merged;
 *  4. only then write, inside one transaction, so a crash mid-import cannot leave half a ledger.
 *
 * Refusing a file is a normal outcome, and the screen says *why* (an unparseable or future-version
 * file) rather than showing a spinner that ends in an error toast.
 */
@Composable
fun BackupRestoreRoute(container: AppContainer, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    var preview by remember { mutableStateOf<RestorePreview?>(null) }
    var mode by remember { mutableStateOf(RestoreMode.Replace) }
    var stats by remember { mutableStateOf<BackupStats?>(null) }

    LaunchedEffect(Unit) {
        stats = container.backupRepository.statsSnapshot()
    }

    val saveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null) {
            notice = "No file was chosen, so nothing was written."
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            busy = true
            when (val result = container.backupRepository.exportTo(uri)) {
                is ExportResult.Done -> notice =
                    "Backup written: ${result.recordCount} record(s), ${result.humanSize}."

                is ExportResult.Failed -> notice = "Backup failed: ${result.message}"
            }
            busy = false
        }
    }

    val openLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            busy = true
            when (val result = container.backupRepository.inspect(uri)) {
                is InspectResult.Rejected -> notice = result.issues.joinToString(" ") { it.message }

                is InspectResult.Ready -> preview = RestorePreview(
                    summary = result.document.summary(),
                    recordCount = result.recordCount,
                    warnings = result.warnings.map { it.message },
                    dropped = result.droppedRecords,
                    document = result.document,
                )
            }
            busy = false
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        DetailTopBar(title = "Backup & restore", onBack = onBack)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = KhataGoSpacing.screen, vertical = KhataGoSpacing.md),
            verticalArrangement = Arrangement.spacedBy(KhataGoSpacing.lg),
        ) {
            KhataGoCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = KhataGoIcons.Lock,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.size(KhataGoSpacing.md))
                    Text(
                        text = "A backup file is the only copy outside this app. Keep it somewhere you " +
                            "trust — it contains your whole ledger in plain text.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            SectionHeader(title = "What is in your ledger now")
            KhataGoCard {
                val rows = stats?.rows().orEmpty()
                if (rows.isEmpty()) {
                    Text(
                        text = "Counting records…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                rows.forEach { (label, value) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(text = value, style = MaterialTheme.typography.titleSmall)
                    }
                }
            }

            SectionHeader(title = "Create a backup")
            KhataGoCard {
                Text(
                    text = "Writes a versioned JSON file (backup format v${BackupDocument.CURRENT_VERSION}) " +
                        "containing every table: shops, credits, people, borrowings, lendings, loans, " +
                        "EMI plans, installments, payments, income, expenses, categories, payment " +
                        "methods, attachments and reminders.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(KhataGoSpacing.md))
                PrimaryButton(
                    text = "Choose where to save…",
                    loading = busy,
                    onClick = {
                        val name = "khatago-backup-${System.currentTimeMillis()}.json"
                        saveLauncher.launch(name)
                    },
                )
                Spacer(Modifier.height(KhataGoSpacing.sm))
                Text(
                    text = "Attachment *images* are listed in the file but the photos themselves stay on " +
                        "this device — a restored install will say “attachment unavailable” rather than " +
                        "pretend they transferred.",
                    style = MaterialTheme.typography.labelSmall,
                    color = KhataGoColors.Ink400,
                )
            }

            SectionHeader(title = "Restore from a file")
            KhataGoCard {
                Column(verticalArrangement = Arrangement.spacedBy(KhataGoSpacing.sm)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                if (mode == RestoreMode.Replace) KhataGoColors.OverdueBg else KhataGoColors.Ink100,
                                RoundedCornerShape(KhataGoRadii.field),
                            )
                            .clickable { mode = RestoreMode.Replace }
                            .padding(KhataGoSpacing.md),
                        verticalAlignment = Alignment.Top,
                    ) {
                        RadioDot(selected = mode == RestoreMode.Replace)
                        Spacer(Modifier.size(KhataGoSpacing.md))
                        Column {
                            Text(text = "Replace everything", style = MaterialTheme.typography.titleSmall)
                            Text(
                                text = "Deletes the current ledger, then writes the file's records. " +
                                    "The result is exactly what the backup contained.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                if (mode == RestoreMode.Merge) KhataGoColors.Emerald100 else KhataGoColors.Ink100,
                                RoundedCornerShape(KhataGoRadii.field),
                            )
                            .clickable { mode = RestoreMode.Merge }
                            .padding(KhataGoSpacing.md),
                        verticalAlignment = Alignment.Top,
                    ) {
                        RadioDot(selected = mode == RestoreMode.Merge)
                        Spacer(Modifier.size(KhataGoSpacing.md))
                        Column {
                            Text(text = "Merge with what is here", style = MaterialTheme.typography.titleSmall)
                            Text(
                                text = "Keeps existing records. Shops and people are matched by name so " +
                                    "you do not get duplicates; obligations and payments are added as new " +
                                    "rows. Nothing is deleted.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Spacer(Modifier.height(KhataGoSpacing.xxs))
                    TonalButton(
                        text = "Choose a backup file…",
                        onClick = {
                            notice = null
                            openLauncher.launch(arrayOf("application/json", "text/plain", "application/octet-stream"))
                        },
                    )
                }
            }

            notice?.let { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(KhataGoColors.SurfaceTinted, RoundedCornerShape(KhataGoRadii.field))
                        .padding(KhataGoSpacing.md),
                )
            }
        }
    }

    val current = preview
    if (current != null) {
        AlertDialog(
            onDismissRequest = { preview = null },
            title = { Text(if (mode == RestoreMode.Replace) "Replace your ledger?" else "Merge this backup?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(KhataGoSpacing.sm)) {
                    Text(text = current.summary, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        text = if (mode == RestoreMode.Replace) {
                            "Everything currently stored will be deleted first. This cannot be undone."
                        } else {
                            "Your current records are kept; ${current.recordCount} record(s) from the " +
                                "file will be added."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    if (current.warnings.isNotEmpty()) {
                        Text(
                            text = "Warnings: ${current.warnings.joinToString(" ")}",
                            style = MaterialTheme.typography.bodySmall,
                            color = KhataGoColors.DueSoon,
                        )
                    }
                    if (current.dropped > 0) {
                        Text(
                            text = "${current.dropped} row(s) could not be matched to a parent record " +
                                "and will be skipped rather than imported with a broken link.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        busy = true
                        val report = container.backupRepository.importDocument(current.document, mode)
                        busy = false
                        preview = null
                        notice = if (report.applied) {
                            "Restored ${report.recordsAdded} record(s)." +
                                if (report.droppedRecords > 0) " ${report.droppedRecords} skipped." else ""
                        } else {
                            "Restore failed: ${report.failureMessage ?: "the file could not be applied"}"
                        }
                        stats = container.backupRepository.statsSnapshot()
                    }
                }) {
                    Text(
                        text = if (mode == RestoreMode.Replace) "Delete and restore" else "Restore",
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = { TextButton(onClick = { preview = null }) { Text("Cancel") } },
        )
    }
}

private data class RestorePreview(
    val summary: String,
    val recordCount: Int,
    val warnings: List<String>,
    val dropped: Int,
    /** Kept so the confirm dialog can hand the *same* validated document to the importer. */
    val document: BackupDocument,
)

@Composable
private fun RadioDot(selected: Boolean) {
    Column(
        modifier = Modifier
            .size(18.dp)
            .background(
                if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
                RoundedCornerShape(9.dp),
            ),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (selected) {
            Text(text = "·", color = MaterialTheme.colorScheme.onPrimary)
        }
    }
}
