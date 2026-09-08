package com.khatago.finance.data.repo

import android.content.Context
import androidx.core.content.edit
import com.khatago.finance.core.security.PinHasher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The optional app lock.
 *
 * What it does: gates opening KhataGo behind a PIN (4-8 digits) and, where the device supports it,
 * a biometric. The PIN is stored only as a salted PBKDF2 digest in the app's private preferences —
 * never in the Room database, because the database is exported by backup and a backup file is a
 * plain-text document.
 *
 * What it does not do, stated plainly in the Settings screen: it is not disk encryption. On a
 * powered-off, rooted device this app relies on the operating system's own file encryption, which
 * modern Android enables by default. Pretending otherwise would be the kind of overclaim that
 * makes people trust the wrong thing with their money.
 */
class SecurityRepository(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    private val _isUnlocked = MutableStateFlow(true)

    /** Only read while composing; the AppLock gate flips this on background/resume. */
    val isUnlocked: StateFlow<Boolean> = _isUnlocked.asStateFlow()

    val isLockEnabled: Boolean get() = prefs.getString(PIN_HASH, null) != null

    val isBiometricEnabled: Boolean
        get() = prefs.getBoolean(BIOMETRIC, false) && isLockEnabled

    /** The user set this up on this device; nothing is sent anywhere, by construction. */
    suspend fun setPin(pin: String): PinResult {
        if (!PinHasher.isValidPin(pin)) return PinResult.Invalid("Use 4 to 8 digits.")
        prefs.edit { putString(PIN_HASH, PinHasher.encode(pin)); putLong(SET_AT, System.currentTimeMillis()) }
        return PinResult.Ok
    }

    suspend fun changePin(oldPin: String, newPin: String): PinResult {
        if (!verify(oldPin)) return PinResult.Wrong("That is not your current PIN.")
        return setPin(newPin)
    }

    fun verify(pin: String): Boolean = prefs.getString(PIN_HASH, null)?.let { PinHasher.matches(pin, it) } == true

    suspend fun clearPin(currentPin: String): PinResult {
        if (!verify(currentPin)) return PinResult.Wrong("Enter your current PIN to turn the lock off.")
        prefs.edit { remove(PIN_HASH); remove(BIOMETRIC) }
        return PinResult.Ok
    }

    fun setBiometricEnabled(enabled: Boolean) {
        prefs.edit { putBoolean(BIOMETRIC, enabled) }
    }

    fun lockNow() {
        if (isLockEnabled) _isUnlocked.value = false
    }

    fun unlock() {
        _isUnlocked.value = true
    }

    /** The PIN is required again whenever the app leaves the foreground, if a lock exists. */
    fun onBackground() = lockNow()

    fun setPinSync(pin: String): PinResult = setPin(pin).let { it }

    private companion object {
        const val FILE = "khatago_security"
        const val PIN_HASH = "pin_hash"
        const val BIOMETRIC = "biometric_enabled"
        const val SET_AT = "pin_set_at"
    }
}

sealed interface PinResult {
    data object Ok : PinResult
    data class Invalid(val message: String) : PinResult
    data class Wrong(val message: String) : PinResult
}
