package com.khatago.finance.ui.detail

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.khatago.finance.AppContainer
import com.khatago.finance.core.money.CurrencySpec
import com.khatago.finance.core.time.AppDates
import com.khatago.finance.data.repo.CsvExportRepository
import com.khatago.finance.ui.components.DetailTopBar
import com.khatago.finance.ui.components.KhataGoCard
import com.khatago.finance.ui.components.KhataGoIcons
import com.khatago.finance.ui.components.PrimaryButton
import com.khatago.finance.ui.components.SectionHeader
import com.khatago.finance.ui.components.SegmentedControl
import com.khatago.finance.ui.theme.KhataGoColors
import com.khatago.finance.ui.theme.KhataGoRadii
import com.khatago.finance.ui.theme.KhataGoSpacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Reports and CSV export.
 *
 * The export promise here is specific, because "export to CSV" means nothing until you say *what the
 * numbers are*:
 *  - amounts are **plain major-unit numbers** (`1400.00`) with no symbol, so a spreadsheet adds them;
 *  - the currency and the reporting range are printed in the file header, so a printed sheet is
 *    self-describing a year later;
 *  - balances are produced by the same derivation the screens show — an export that disagrees with the
 *    app would be worse than no export at all;
 *  - every text field is quoted per RFC 4180, which is what lets `Rahman's Store, Main Rd` survive.
 *
 * Writing uses the system's create-document picker. KhataGo has no storage permission and no INTERNET
 * permission: it can only put a file where the user explicitly pointed it.
 */
@Composable
fun ReportsRoute(container: AppContainer, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var currency by remember { mutableStateOf(CurrencySpec.DEFAULT) }
    var range by remember { mutableStateOf(ExportRange.AllTime) }
    var busy by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var pending by remember { mutableStateOf<CsvExportRepository.Report?>(null) }
    val today = remember { AppDates.today() }

    LaunchedEffect(Unit) {
        currency = CurrencySpec.fromCode(container.catalogRepository.findProfile()?.currencyCode)
    }

    val create = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        val report = pending
        pending = null
        if (uri == null || report == null) {
            notice = "Nothing was written — the file picker was closed."
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            busy = report.title
            val csv = container.csvExportRepository.buildCsv(
                report = report,
                currency = currency,
                range = range.rangeFor(today),
                todayEpochDay = today,
            )
            val ok = container.csvExportRepository.write(uri, csv)
            busy = null
            notice = if (ok) {
                "${report.title} exported. Open it in any spreadsheet app."
            } else {
                "The file could not be written. Check the storage location and try again."
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        DetailTopBar(title = "Reports & export", onBack = onBack)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = KhataGoSpacing.screen, vertical = KhataGoSpacing.md),
            verticalArrangement = Arrangement.spacedBy(KhataGoSpacing.lg),
        ) {
            SectionHeader(
                title = "Reporting period",
                subtitle = "Filters rows, not the live balances: outstanding figures are always 'right now'.",
            )
            SegmentedControl(
                options = ExportRange.entries.toList(),
                selected = range,
                onSelect = { range = it },
                labelOf = { it.label },
            )

            SectionHeader(title = "CSV files")
            CsvExportRepository.Report.entries.forEach { report ->
                KhataGoCard(
                    onClick = {
                        notice = null
                        pending = report
                        create.launch("${report.fileName.substringBeforeLast('.')}-${AppDates.monthKey(today)}.csv")
                    },
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .background(KhataGoColors.Emerald100, RoundedCornerShape(12.dp)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = KhataGoIcons.Documents,
                                contentDescription = null,
                                tint = KhataGoColors.Emerald700,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                        Spacer(Modifier.width(KhataGoSpacing.md))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(text = report.title, style = MaterialTheme.typography.titleMedium)
                            Text(
                                text = descriptionFor(report),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        if (busy == report.title) {
                            androidx.compose.material3.CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                            )
                        }
                    }
                }
            }

            SectionHeader(title = "Share a backup file")
            KhataGoCard {
                Text(
                    text = "The full JSON backup (Settings → Backup & restore) is the only file that " +
                        "can restore KhataGo. A CSV is for spreadsheets and printing — it is data, not " +
                        "a restore point.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(KhataGoSpacing.md))
                PrimaryButton(
                    text = "Share a CSV copy via any app",
                    onClick = {
                        scope.launch {
                            val report = CsvExportRepository.Report.Outstanding
                            val csv = container.csvExportRepository.buildCsv(
                                report = report,
                                currency = currency,
                                range = range.rangeFor(today),
                                todayEpochDay = today,
                            )
                            val file = withContext(Dispatchers.IO) {
                                java.io.File(context.cacheDir, report.fileName).apply {
                                    writeText(csv)
                                }
                            }
                            val uri = androidx.core.content.FileProvider.getUriForFile(
                                context,
                                "${context.packageName}.fileprovider",
                                file,
                            )
                            val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                type = "text/csv"
                                putExtra(android.content.Intent.EXTRA_STREAM, uri)
                                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            runCatching {
                                context.startActivity(
                                    android.content.Intent.createChooser(intent, "Send ${report.title}"),
                                )
                            }.onFailure { notice = "No app on this phone can receive a CSV file." }
                        }
                    },
                )
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

            Text(
                text = "Files are written where you choose in the system picker. KhataGo holds no " +
                    "storage permission of its own and uploads nothing.",
                style = MaterialTheme.typography.labelSmall,
                color = KhataGoColors.Ink400,
            )
        }
    }
}

enum class ExportRange(val label: String) {
    ThisMonth("This month"),
    LastThree("3 months"),
    ThisYear("Year"),
    AllTime("All time"),
    ;

    /** Inclusive epoch-day window; `null` means "no ceiling filter". */
    fun rangeFor(todayEpochDay: Long): LongRange? = when (this) {
        ThisMonth -> {
            val date = java.time.LocalDate.ofEpochDay(todayEpochDay)
            val start = date.withDayOfMonth(1).toEpochDay()
            start..todayEpochDay
        }

        LastThree -> {
            val date = java.time.LocalDate.ofEpochDay(todayEpochDay)
            val start = date.minusMonths(2).withDayOfMonth(1).toEpochDay()
            start..todayEpochDay
        }

        ThisYear -> {
            val date = java.time.LocalDate.ofEpochDay(todayEpochDay)
            date.withDayOfYear(1).toEpochDay()..todayEpochDay
        }

        AllTime -> null
    }
}

private fun descriptionFor(report: CsvExportRepository.Report): String = when (report) {
    CsvExportRepository.Report.Overall ->
        "Headline totals with the reconciliation line: app total vs. a fresh per-record sum"
    CsvExportRepository.Report.ShopCredits ->
        "Every credit record with quantity, total, paid, remaining and status"
    CsvExportRepository.Report.Loans ->
        "Loans with principal, total payable, installments settled and outstanding"
    CsvExportRepository.Report.Emis ->
        "EMI plans with cash price, total payable and remaining"
    CsvExportRepository.Report.Borrowings -> "Money you borrowed, per person, with due dates"
    CsvExportRepository.Report.Lendings -> "Money you lent, kept separate from borrowings"
    CsvExportRepository.Report.Payments ->
        "Every payment: date, method, reference, which record it settled"
    CsvExportRepository.Report.Income -> "Income entries by category and source"
    CsvExportRepository.Report.Expenses -> "Expense entries by category and merchant"
    CsvExportRepository.Report.Outstanding ->
        "Only unsettled records across all modules — the 'who owes what' sheet"
}
