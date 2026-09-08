package com.khatago.finance

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import com.khatago.finance.notify.Notifications
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * KhataGo's process entry point.
 *
 * Three things happen here, each for a reason that is not obvious from the code:
 *
 *  - **`MODE_NIGHT_NO`.** KhataGo is a light-only product. Setting the default night mode (alongside
 *    the `values-night` theme pin and `android:forceDarkAllowed=false` in the theme) is what keeps
 *    the app light when the whole device is dark — including framework dialogs and the status bar,
 *    not just our Compose surfaces.
 *  - **Notification channel creation.** Creating it eagerly means the very first reminder is
 *    possible without a race, and the channel description is what the user sees in system settings —
 *    so it has to state, in one line, that reminders are local.
 *  - **No analytics, no crash reporting, no SDK initialisation.** Nothing here talks to a network,
 *    because the app has no INTERNET permission; a dependency that wanted to would fail at install.
 */
class KhataGoApplication : Application() {

    lateinit var container: AppContainer
        private set

    /** `onStop` can fire before `onCreate` finished wiring in edge cases; the UI must never crash there. */
    val isContainerReady: Boolean get() = ::container.isInitialized

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
        super.onCreate()
        container = AppContainer(this)
        Notifications.ensureChannel(this)
        scope.launch { container.reminderScheduler.syncFromPreferences() }
    }

    override fun onTerminate() {
        // Only reached on emulators/robust test setups; closing keeps in-process handles tidy.
        if (::container.isInitialized) runCatching { container.database.close() }
        super.onTerminate()
    }
}
