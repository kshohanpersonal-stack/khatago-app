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

    // --- failed-attempt throttling -------------------------------------------
    //
    // A 4-digit PIN is 10,000 guesses. Without a cool-down, a person holding an unlocked-for-
    // a-moment phone can brute force it in minutes; with it, the cost of guessing rises to hours
    // while the legitimate user pays nothing. The counter is *deliberately* not in the Room
    // database: it must survive "delete all data" (so a wipe cannot reset a lockout) yet never be
    // part of a backup (so a lockout cannot be imported onto another device).

    /** Attempts remaining before the next cool-down starts. */
    val remainingAttempts: Int
        get() = (MAX_ATTEMPTS - failureCount()).coerceAtLeast(0)

    fun failureCount(): Int = prefs.getInt(FAILURES, 0)

    /** Whole minutes still to wait before another attempt is allowed (0 when not locked out). */
    fun remainingLockoutMinutes(now: Long = System.currentTimeMillis()): Int {
        val until = prefs.getLong(LOCKED_UNTIL, 0L)
        if (until <= now) return 0
        return (((until - now) + 59_999L) / 60_000L).toInt()
    }

    /**
     * Records a wrong PIN. Returns the running failure count so the UI can say "attempt 3 of 5"
     * rather than inventing its own counter that could drift from the real one.
     */
    fun registerFailure(now: Long = System.currentTimeMillis()): Int {
        val count = failureCount() + 1
        prefs.edit().apply {
            putInt(FAILURES, count)
            if (count >= MAX_ATTEMPTS) {
                putLong(LOCKED_UNTIL, now + LOCKOUT_MILLIS)
                putInt(FAILURES, 0)
            }
        }.apply()
        return if (count >= MAX_ATTEMPTS) MAX_ATTEMPTS else count
    }

    fun clearFailures() {
        prefs.edit().remove(FAILURES).remove(LOCKED_UNTIL).apply()
    }

    /** True while attempts are blocked. Checked by the keypad, not by the caller's patience. */
    fun isLockedOut(now: Long = System.currentTimeMillis()): Boolean =
        prefs.getLong(LOCKED_UNTIL, 0L) > now


    private companion object {
        const val FILE = "khatago_security"
        const val PIN_HASH = "pin_hash"
        const val BIOMETRIC = "biometric_enabled"
        const val SET_AT = "pin_set_at"
        const val FAILURES = "pin_failures"
        const val LOCKED_UNTIL = "locked_until"

        /** Tuned so a legitimate "I forgot, let me try three times" is never punished. */
        const val MAX_ATTEMPTS = 5
        const val LOCKOUT_MILLIS = 5L * 60L * 1000L
    }
}

sealed interface PinResult {
    data object Ok : PinResult
    data class Invalid(val message: String) : PinResult
    data class Wrong(val message: String) : PinResult
}
