package com.khatago.finance

import android.os.Build
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.WindowCompat
import com.khatago.finance.ui.KhataGoApp
import com.khatago.finance.ui.Routes
import com.khatago.finance.ui.theme.KhataGoTheme

/**
 * The single activity. Compose Navigation owns all screens; there are no other activities to leak
 * data through, and nothing in the manifest is exported except the launcher entry.
 *
 * Lifecycle rules that matter here:
 *  - `enableEdgeToEdge()` + a light system-bar style, because the brand surfaces are mint/white and
 *    default dark icons would be unreadable on them.
 *  - The app lock re-arms on `onStop`, not `onPause`: taking a photo of a receipt or choosing a
 *    backup file briefly pauses the activity, and locking there would make the app feel broken.
 */
class MainActivity : AppCompatActivity() {

    private var holdSplash = true

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)
        // Held until the first frame is composed, so the brand splash hands over without a white gap.
        splash.setKeepOnScreenCondition { holdSplash }
        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }
        enableEdgeToEdge()
        setContent {
            KhataGoTheme {
                Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
                    val container = remember { (applicationContext as KhataGoApplication).container }
                    val unlocked by container.securityRepository.isUnlocked.collectAsState()
                    // Read once, before the first frame, and held until the splash lifts: an onboarding
                    // check on a flow would render HOME for a frame and then jump, which reads as a bug
                    // and (worse) could be tapped during that frame.
                    var needsOnboarding by remember { mutableStateOf<Boolean?>(null) }
                    var visible by remember { mutableStateOf(false) }
                    LaunchedEffect(Unit) {
                        needsOnboarding = runCatching {
                            container.catalogRepository.findProfile()?.onboardingComplete != true
                        }.getOrDefault(true)
                        visible = true
                        holdSplash = false
                    }
                    AnimatedVisibility(
                        visible = visible && needsOnboarding != null,
                        enter = fadeIn(),
                        exit = fadeOut(),
                    ) {
                        Box {
                            KhataGoApp(
                                container = container,
                                lockEnabled = container.securityRepository.isLockEnabled,
                                unlocked = unlocked,
                                onUnlocked = { container.securityRepository.unlock() },
                                startRoute = if (needsOnboarding == true) Routes.ONBOARDING else null,
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onStop() {
        super.onStop()
        // Re-arm the app lock when the app truly leaves the screen.
        (application as? KhataGoApplication)?.takeIf { it.isContainerReady }
            ?.container?.securityRepository?.onBackground()
    }
}
