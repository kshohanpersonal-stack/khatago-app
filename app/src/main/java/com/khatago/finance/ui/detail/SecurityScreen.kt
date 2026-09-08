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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.khatago.finance.AppContainer
import com.khatago.finance.core.security.PinHasher
import com.khatago.finance.data.repo.PinResult
import com.khatago.finance.ui.canUseBiometric
import com.khatago.finance.ui.components.DetailTopBar
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
 * App lock.
 *
 * The honest framing matters more here than anywhere else in the app. This PIN **gates the UI**; it is
 * not disk encryption, and saying otherwise would be a lie about security that could get someone hurt
 * (they might hand an unlocked phone to a shopkeeper, believing their whole ledger is hidden). So:
 *
 *  - The screen states in plain words what the lock does and does not do.
 *  - A wrong PIN is throttled in the repository (5 attempts, then a cool-down) — a 4-digit PIN is
 *    10,000 guesses, and only the delay makes that a real barrier.
 *  - Turning the lock **off** requires the current PIN. A lock that a passing finger could disable
 *    would not be a lock.
 *  - Biometric is an *extra*, never the only way in: the PIN always remains the fallback, because
 *    fingerprint sensors fail with wet hands, and a lock that locks you out of your own ledger is a
 *    worse failure than a slow one.
 */
@Composable
fun SecurityRoute(container: AppContainer, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val security = container.securityRepository
    var lockOn by remember { mutableStateOf(security.isLockEnabled) }
    var biometricOn by remember { mutableStateOf(security.isBiometricEnabled) }
    var dialog by remember { mutableStateOf<SecurityDialog?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    val biometricAvailable = remember { canUseBiometric(context) }
    val lockoutMinutes = remember { security.remainingLockoutMinutes() }
    val attemptsLeft = remember { security.remainingAttempts }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        DetailTopBar(title = "App lock", onBack = onBack)
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
                        tint = if (lockOn) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            KhataGoColors.Ink400
                        },
                    )
                    Spacer(Modifier.width(KhataGoSpacing.md))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (lockOn) "KhataGo asks for your PIN" else "No PIN set",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            text = if (lockOn) {
                                "Required again every time the app leaves the foreground."
                            } else {
                                "Anyone holding your unlocked phone can open and read the ledger."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(Modifier.height(KhataGoSpacing.md))
                SwitchRow(
                    label = "Require a PIN to open",
                    checked = lockOn,
                    onCheckedChange = { want ->
                        dialog = if (want) SecurityDialog.Create else SecurityDialog.Remove
                    },
                )
                if (lockOn) {   // `isLockEnabled` *is* "a PIN exists"
                    Spacer(Modifier.height(KhataGoSpacing.sm))
                    SettingsActionRow(
                        icon = KhataGoIcons.Edit,
                        title = "Change PIN",
                        subtitle = "You will enter the current PIN first",
                        onClick = { dialog = SecurityDialog.Change },
                    )
                    SettingsActionRow(
                        icon = KhataGoIcons.Lock,
                        title = "Lock now",
                        subtitle = "Useful if you hand the phone over and want it shut immediately",
                        onClick = {
                            security.lockNow()
                            notice = "Locked. The PIN is required to get back in."
                        },
                    )
                }
            }

            SectionHeader(title = "Fingerprint / face unlock")
            KhataGoCard {
                if (!biometricAvailable) {
                    Text(
                        text = "This device has no enrolled fingerprint or face unlock that KhataGo can " +
                            "use (it needs a secured lock screen of its own). The PIN still works — " +
                            "nothing here is skipped silently.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    SwitchRow(
                        label = "Unlock with biometrics",
                        sublabel = "Offered first; the PIN is always the fallback if a read fails.",
                        checked = biometricOn && lockOn,
                        onCheckedChange = { enabled ->
                            if (!lockOn) {
                                notice = "Set a PIN first — biometrics only ever unlock *after* the PIN gate."
                                return@SwitchRow
                            }
                            security.setBiometricEnabled(enabled)
                            biometricOn = enabled
                        },
                    )
                }
            }

            SectionHeader(title = "What this lock is, and what it is not")
            KhataGoCard {
                Bullet(
                    "It hides your ledger from a phone that is already unlocked — a colleague, a " +
                        "borrower, a child.",
                )
                Bullet("It re-arms the moment KhataGo leaves the foreground.")
                Bullet(
                    "It is not encryption of the database. On a powered-off, rooted device KhataGo " +
                        "relies on Android's own file-based encryption, which modern devices enable by " +
                        "default. Anyone who tells you a PIN protects against that is selling you a " +
                        "false sense of safety.",
                )
                Bullet(
                    "It never uploads the PIN. What is stored is a salted PBKDF2 digest in the app's " +
                        "private preferences — deliberately outside the database, so a backup file can " +
                        "never carry your PIN anywhere.",
                )
                Bullet(
                    "If you forget it, there is no recovery: no server knows your PIN. The only way in " +
                        "is a backup file you kept, restored after clearing the app's data.",
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

    when (dialog) {
        SecurityDialog.Create -> PinEntrySheet(
            title = "Choose a PIN",
            subtitle = "4 to 8 digits. It stays on this device.",
            confirmLabel = "Turn on",
            onDismiss = { dialog = null },
            onSubmit = { first, second ->
                scope.launch {
                    if (first != second) {
                        notice = "The two PINs did not match."
                        dialog = null
                        return@launch
                    }
                    when (val result = security.setPin(first)) {
                        PinResult.Ok -> {
                            lockOn = true
                            notice = "App lock is on."
                            dialog = null
                        }

                        is PinResult.Invalid -> notice = result.message
                        is PinResult.Wrong -> notice = result.message
                    }
                }
            },
        )

        SecurityDialog.Change -> PinEntrySheet(
            title = "Change your PIN",
            subtitle = "Your current PIN, then the new one twice.",
            confirmLabel = "Change",
            asksCurrentFirst = true,
            onDismiss = { dialog = null },
            onSubmit = { currentPin, newPin ->
                scope.launch {
                    when (val result = security.changePin(currentPin, newPin)) {
                        PinResult.Ok -> {
                            notice = "PIN changed."
                            dialog = null
                        }

                        is PinResult.Wrong -> notice = result.message
                        is PinResult.Invalid -> notice = result.message
                    }
                }
            },
        )

        SecurityDialog.Remove -> PinEntrySheet(
            title = "Turn off the app lock",
            subtitle = "Enter your current PIN. Your ledger becomes readable to anyone holding the phone.",
            confirmLabel = "Turn off",
            destructive = true,
            onDismiss = { dialog = null },
            onSubmit = { currentPin, _ ->
                scope.launch {
                    when (val result = security.clearPin(currentPin)) {
                        PinResult.Ok -> {
                            lockOn = false
                            biometricOn = false
                            notice = "App lock is off."
                            dialog = null
                        }

                        is PinResult.Wrong -> notice = result.message
                        is PinResult.Invalid -> notice = result.message
                    }
                }
            },
        )

        null -> Unit
    }
}

private enum class SecurityDialog { Create, Change, Remove }

/**
 * A PIN dialog with an explicit confirm field.
 *
 * No "eye" toggle to reveal what you typed: a PIN entry that can be shown on screen is a PIN entry
 * that can be read over a shoulder. Masked input plus a confirm field is the honest trade.
 */
@Composable
private fun PinEntrySheet(
    title: String,
    subtitle: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onSubmit: (first: String, second: String) -> Unit,
    destructive: Boolean = false,
    asksCurrentFirst: Boolean = false,
) {
    var first by remember { mutableStateOf("") }
    var second by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val digits: (String) -> String = { raw -> raw.filter { it.isDigit() }.take(8) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(KhataGoSpacing.md)) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextFieldLine(
                    value = first,
                    onValueChange = { first = digits(it) },
                    label = if (asksCurrentFirst) "Current PIN" else "PIN",
                    keyboardType = KeyboardType.NumberPassword,
                    error = error,
                )
                TextFieldLine(
                    value = second,
                    onValueChange = { second = digits(it) },
                    label = if (asksCurrentFirst) "New PIN" else "Repeat PIN",
                    keyboardType = KeyboardType.NumberPassword,
                )
                if (asksCurrentFirst) {
                    var third by remember { mutableStateOf("") }
                    TextFieldLine(
                        value = third,
                        onValueChange = { third = digits(it) },
                        label = "Repeat new PIN",
                        keyboardType = KeyboardType.NumberPassword,
                    )
                    LaunchedEffect(third) {
                        if (third.isNotEmpty() && third != second) {
                            error = "The new PIN and its repeat do not match."
                        } else if (error == "The new PIN and its repeat do not match.") {
                            error = null
                        }
                    }
                    LaunchedEffect(third) {
                        if (third.isNotEmpty() && third == second) {
                            // Fold the verified repeat into the submitted pair; the caller only ever
                            // receives (current, new) so no branch can forget to compare.
                            second = third
                        }
                    }
                }
                Text(
                    text = "Digits only. There is no “show PIN” toggle on purpose: a code you can reveal " +
                        "on screen is a code someone behind you can read.",
                    style = MaterialTheme.typography.labelSmall,
                    color = KhataGoColors.Ink400,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (!PinHasher.isValidPin(first)) {
                        error = "Enter 4 to 8 digits."
                        return@TextButton
                    }
                    if (asksCurrentFirst && !PinHasher.isValidPin(second)) {
                        error = "The new PIN must be 4 to 8 digits."
                        return@TextButton
                    }
                    if (!asksCurrentFirst && first != second) {
                        error = "The two entries did not match."
                        return@TextButton
                    }
                    error = null
                    onSubmit(first, second)
                },
            ) {
                Text(
                    text = confirmLabel,
                    color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun SettingsActionRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(KhataGoColors.Ink100, RoundedCornerShape(KhataGoRadii.innerCard))
            .clickable(onClick = onClick)
            .padding(horizontal = KhataGoSpacing.md, vertical = KhataGoSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(imageVector = icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(KhataGoSpacing.md))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.titleSmall)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(
            imageVector = androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight,
            contentDescription = null,
            tint = KhataGoColors.Ink400,
        )
    }
}

@Composable
private fun Bullet(text: String) {
    Row(modifier = Modifier.padding(vertical = 3.dp)) {
        Text(
            text = "—",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.width(KhataGoSpacing.sm))
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
    }
}
