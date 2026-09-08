package com.khatago.finance.ui

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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.khatago.finance.AppContainer
import com.khatago.finance.ui.components.KhataGoIcons
import com.khatago.finance.ui.theme.KhataGoColors
import com.khatago.finance.ui.theme.KhataGoSpacing
import com.khatago.finance.ui.theme.KhataGoTypography
import kotlinx.coroutines.launch

/**
 * The app lock.
 *
 * Rules that shaped it, stated because they are the difference between a lock and a toy:
 *  - **Nothing behind it renders while locked.** KhataGoApp shows this screen *instead of* the
 *    NavHost, not on top of it, so no balance is ever composed into the tree.
 *  - **Biometric is optional and PIN is always the fallback.** Fingerprint hardware fails, is wet,
 *    and is unavailable right after a reboot; a lock with no fallback is a lock that locks people out
 *    of their own ledger.
 *  - **No "remember me for 30 days".** `SecurityRepository.onBackground()` re-arms the lock whenever
 *    the app truly leaves the screen; that is the whole policy, and it is one line to audit.
 *  - **A wrong-PIN counter is persisted, and repeated guessing gets a cool-down.** Without that, a
 *    4-digit PIN is 10,000 guesses, which a determined person with the phone for ten minutes can do.
 *
 * Threat model, honestly: this defeats a casual grab of an unlocked phone. It does not defeat a
 * forensic extraction of a powered-off device — that is what the phone's own full-disk encryption is
 * for, and KhataGo relies on it rather than pretending to replace it.
 */
@Composable
fun AppLockScreen(
    container: AppContainer,
    onUnlocked: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val security = container.securityRepository
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var coolDownMinutes by remember { mutableStateOf(security.remainingLockoutMinutes()) }

    val biometricAvailable = remember {
        security.isBiometricEnabled && canUseBiometric(context)
    }

    fun submit(candidate: String) {
        if (candidate.length < 4) {
            error = "Enter your PIN (at least 4 digits)."
            return
        }
        scope.launch {
            when {
                security.verify(candidate) -> {
                    security.clearFailures()
                    onUnlocked()
                }
                else -> {
                    val failures = security.registerFailure()
                    coolDownMinutes = security.remainingLockoutMinutes()
                    error = if (coolDownMinutes > 0) {
                        "Too many attempts. Wait $coolDownMinutes minute(s) and try again."
                    } else {
                        "That PIN does not match. Attempt $failures of ${SecurityPolicy.MAX_ATTEMPTS}."
                    }
                    pin = ""
                }
            }
        }
    }

    // Offer biometrics straight away when it is enabled — a second tap to get in is a second tap too many.
    var prompted by remember { mutableStateOf(false) }
    if (biometricAvailable && !prompted && coolDownMinutes == 0) {
        DisposableEffect(Unit) {
            prompted = true
            showBiometric(
                activity = context as? FragmentActivity,
                onSuccess = onUnlocked,
                onFailed = { error = "Fingerprint did not match. Use your PIN." },
                onUnavailable = { error = "Biometrics are unavailable right now. Use your PIN." },
            )
            onDispose { }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(KhataGoColors.SurfaceCanvas),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = KhataGoSpacing.xxl),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(KhataGoSpacing.xl),
        ) {
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .background(KhataGoColors.Emerald100, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = KhataGoIcons.Lock,
                    contentDescription = null,
                    tint = KhataGoColors.Emerald700,
                    modifier = Modifier.size(28.dp),
                )
            }
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(KhataGoSpacing.xs),
            ) {
                Text(text = "KhataGo is locked", style = KhataGoTypography.headlineMoney)
                Text(
                    text = "Enter your PIN to open your ledger.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }

            // Dots instead of digits: a shoulder-surfer learns nothing, and a screenshot of this
            // screen is not a screenshot of a PIN.
            Row(horizontalArrangement = Arrangement.spacedBy(KhataGoSpacing.md)) {
                repeat(SecurityPolicy.MAX_PIN_LENGTH) { index ->
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .background(
                                if (index < pin.length) {
                                    KhataGoColors.Emerald700
                                } else {
                                    KhataGoColors.Ink200
                                },
                                CircleShape,
                            ),
                    )
                }
            }

            Text(
                text = error ?: if (coolDownMinutes > 0) {
                    "Locked for $coolDownMinutes minute(s)."
                } else {
                    ""
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (error != null) MaterialTheme.colorScheme.error else Color.Transparent,
                modifier = Modifier.height(18.dp),
            )

            Keypad(
                enabled = coolDownMinutes == 0,
                onDigit = { digit ->
                    error = null
                    if (pin.length < SecurityPolicy.MAX_PIN_LENGTH) pin += digit
                },
                onBackspace = { pin = pin.dropLast(1) },
                onSubmit = { submit(pin) },
            )

            if (biometricAvailable && coolDownMinutes == 0) {
                Row(
                    modifier = Modifier
                        .clickable {
                            showBiometric(
                                activity = context as? FragmentActivity,
                                onSuccess = onUnlocked,
                                onFailed = { error = "Fingerprint did not match. Use your PIN." },
                                onUnavailable = { error = "Biometrics are unavailable. Use your PIN." },
                            )
                        }
                        .padding(KhataGoSpacing.md),
                    horizontalArrangement = Arrangement.spacedBy(KhataGoSpacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = KhataGoIcons.Verified,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = "Use fingerprint",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}

private object SecurityPolicy {
    const val MAX_PIN_LENGTH = 8
    const val MAX_ATTEMPTS = 5
}

@Composable
private fun Keypad(
    enabled: Boolean,
    onDigit: (String) -> Unit,
    onBackspace: () -> Unit,
    onSubmit: () -> Unit,
) {
    val rows = listOf(
        listOf("1", "2", "3"),
        listOf("4", "5", "6"),
        listOf("7", "8", "9"),
        listOf("", "0", "ok"),
    )
    Column(verticalArrangement = Arrangement.spacedBy(KhataGoSpacing.md)) {
        rows.forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(KhataGoSpacing.md),
            ) {
                row.forEach { key ->
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(56.dp)
                            .background(
                                when (key) {
                                    "" -> Color.Transparent
                                    "ok" -> if (enabled) KhataGoColors.Emerald700 else KhataGoColors.Ink200
                                    else -> MaterialTheme.colorScheme.surface
                                },
                                RoundedCornerShape(16.dp),
                            )
                            .clickable(enabled = enabled && key.isNotEmpty()) {
                                when (key) {
                                    "ok" -> onSubmit()
                                    else -> onDigit(key)
                                }
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        when (key) {
                            "" -> Spacer(Modifier.size(1.dp))
                            "ok" -> Icon(
                                imageVector = KhataGoIcons.Check,
                                contentDescription = "Unlock",
                                tint = Color.White,
                            )
                            else -> Text(
                                text = key,
                                style = KhataGoTypography.sectionMoney,
                                fontWeight = FontWeight.Medium,
                            )
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(KhataGoSpacing.xs))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            Text(
                text = "Delete last digit",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .clickable(enabled = enabled, onClick = onBackspace)
                    .padding(KhataGoSpacing.md),
            )
        }
    }
}

internal fun canUseBiometric(context: android.content.Context): Boolean =
    BiometricManager.from(context)
        .canAuthenticate(BIOMETRIC_STRONG or DEVICE_CREDENTIAL) == BiometricManager.BIOMETRIC_SUCCESS

private fun showBiometric(
    activity: FragmentActivity?,
    onSuccess: () -> Unit,
    onFailed: () -> Unit,
    onUnavailable: () -> Unit,
) {
    if (activity == null) {
        onUnavailable()
        return
    }
    val prompt = BiometricPrompt(
        activity,
        ContextCompat.getMainExecutor(activity),
        object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onSuccess()
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                // A user-cancelled prompt is not a failure; only real unavailability says so.
                val cancelled = errorCode == BiometricPrompt.ERROR_USER_CANCELED ||
                    errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON ||
                    errorCode == BiometricPrompt.ERROR_CANCELED
                if (cancelled) Unit else onFailed()
            }

            override fun onAuthenticationFailed() = onFailed()
        },
    )
    prompt.authenticate(
        BiometricPrompt.PromptInfo.Builder()
            .setTitle("Unlock KhataGo")
            .setSubtitle("Use your fingerprint or device lock to open your ledger")
            .setAllowedAuthenticators(BIOMETRIC_STRONG or DEVICE_CREDENTIAL)
            .setConfirmationRequired(false)
            .build(),
    )
}
