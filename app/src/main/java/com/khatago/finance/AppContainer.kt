package com.khatago.finance

import android.content.Context
import com.khatago.finance.data.db.KhataGoDatabase
import com.khatago.finance.data.repo.AttachmentRepository
import com.khatago.finance.data.repo.BackupRepository
import com.khatago.finance.data.repo.CatalogRepository
import com.khatago.finance.data.repo.CsvExportRepository
import com.khatago.finance.data.repo.DataRepository
import com.khatago.finance.data.repo.ObligationRepository
import com.khatago.finance.data.repo.PayableResolver
import com.khatago.finance.data.repo.PaymentRepository
import com.khatago.finance.data.repo.PersonRepository
import com.khatago.finance.data.repo.SearchRepository
import com.khatago.finance.data.repo.SecurityRepository
import com.khatago.finance.data.repo.ShopRepository
import com.khatago.finance.data.repo.StatsRepository
import com.khatago.finance.data.repo.TransactionRepository
import com.khatago.finance.notify.ReminderScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.plus

/**
 * The application's object graph, built once.
 *
 * Deliberately a hand-wired container rather than a DI framework. KhataGo has twelve repositories
 * with a single, static dependency graph and no variants; adding a code-generation framework to
 * that buys build complexity and a kapt/ksp dependency while buying nothing at all. One file that
 * can be read top-to-bottom is easier to audit than a graph assembled by annotations — and for an
 * app that holds someone's money ledger, "easy to audit" is a feature.
 */
class AppContainer(context: Context) {

    private val appContext: Context = context.applicationContext

    /** For fire-and-forget work that must survive a screen being destroyed (backup, exports). */
    val applicationScope = CoroutineScope(SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)

    val database: KhataGoDatabase by lazy { KhataGoDatabase.create(appContext) }

    val shopRepository: ShopRepository by lazy { ShopRepository(database) }
    val personRepository: PersonRepository by lazy { PersonRepository(database) }
    val obligationRepository: ObligationRepository by lazy { ObligationRepository(database) }
    val transactionRepository: TransactionRepository by lazy { TransactionRepository(database) }
    val catalogRepository: CatalogRepository by lazy { CatalogRepository(database) }
    val paymentRepository: PaymentRepository by lazy {
        PaymentRepository(database, PayableResolver(database))
    }
    val statsRepository: StatsRepository by lazy { StatsRepository(database, paymentRepository) }
    val searchRepository: SearchRepository by lazy { SearchRepository(database) }
    val attachmentRepository: AttachmentRepository by lazy { AttachmentRepository(appContext, database) }
    val securityRepository: SecurityRepository by lazy { SecurityRepository(appContext) }
    val backupRepository: BackupRepository by lazy {
        BackupRepository(appContext, database, BuildConfig.VERSION_NAME)
    }
    val csvExportRepository: CsvExportRepository by lazy { CsvExportRepository(appContext, database) }
    val dataRepository: DataRepository by lazy {
        DataRepository(appContext, database, attachmentRepository)
    }
    val reminderScheduler: ReminderScheduler by lazy { ReminderScheduler(appContext, this) }

    /**
     * Fallback currency only. Every screen resolves the real currency from the profile, so nothing in
     * the app hardcodes BDT into business logic — this value exists for the first frame before the
     * profile flow has emitted.
     */
    val defaultCurrency get() = com.khatago.finance.core.money.CurrencySpec.BDT
}

/**
 * Access from anywhere that has a [Context] (Composables included).
 *
 * The cast is safe in the app because [KhataGoApplication] owns the container. Compose previews and
 * `ui-test` harnesses run without it, so [previewContainer] installs a throwaway in-memory
 * container the first time one is needed — without that, a preview would crash the preview renderer
 * for no reason a reader could work out.
 */
val Context.container: AppContainer
    get() = when (val app = applicationContext) {
        is KhataGoApplication -> app.container
        else -> previewContainer(app)
    }

private var previewFallback: AppContainer? = null

private fun previewContainer(context: Context): AppContainer =
    previewFallback ?: AppContainer(context).also { previewFallback = it }
