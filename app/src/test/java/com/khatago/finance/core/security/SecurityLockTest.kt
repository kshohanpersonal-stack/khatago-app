package com.khatago.finance.core.security

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.khatago.finance.data.repo.PinResult
import com.khatago.finance.data.repo.SecurityRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.Locale

/**
 * The app lock: how a PIN is stored, and what happens when someone guesses.
 *
 * The properties that matter are the ones a reviewer cannot check by looking at a screenshot:
 *  - the PIN is never recoverable from what is stored, and never written in the clear;
 *  - the same PIN twice produces two different digests (per-record salt), so a leaked file does not
 *    reveal which users share a code;
 *  - wrong guesses are throttled, and the throttle is what makes 10,000 four-digit codes meaningful;
 *  - turning the lock off requires the current PIN, so the setting cannot be flipped by a thumb.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class SecurityLockTest {

    private lateinit var context: Context

    @Test
    fun `the stored triple is ascii hex and verifies whatever the device locale is`() {
        // The triple is written once and read back forever, so it must not depend on the phone's language.
        // `"%02x".format(it)` used the default locale, which on a device set to Arabic renders digits with
        // non-ASCII glyphs: the stored value would then fail its own hex parser and the PIN would lock the
        // user out of their own ledger. Formatting is pinned to Locale.US, and this is the proof.
        val saved = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("ar"))
            val encoded = PinHasher.encode("4321")
            assertTrue(
                "expected algorithm:iterations:32 hex:64 hex, got $encoded",
                Regex("^[A-Za-z0-9]+:\\d+:[0-9a-f]{32}:[0-9a-f]{64}$").matches(encoded),
            )
            assertTrue(PinHasher.matches("4321", encoded))
            assertFalse(PinHasher.matches("1234", encoded))
            assertNotEquals("a second encode must not reuse the salt", encoded, PinHasher.encode("4321"))
        } finally {
            Locale.setDefault(saved)
        }
    }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        // Robolectric gives each test class a fresh app data dir in practice, but be explicit:
        // a leak here would make the tests order-dependent.
        context.getSharedPreferences("khatago_security", Context.MODE_PRIVATE).edit().clear().apply()
    }

    private fun repo() = SecurityRepository(context)

    // --- the hasher -----------------------------------------------------------

    @Test
    fun `a stored PIN never contains the PIN itself`() {
        val encoded = PinHasher.encode("2468")
        assertFalse("digest must not contain the digits", encoded.contains("2468"))
        assertTrue(encoded.startsWith("PBKDF2WithHmacSHA256:"))
        assertEquals(4, encoded.split(":").size)
    }

    @Test
    fun `the same PIN encodes differently every time because the salt is random`() {
        assertNotEquals(PinHasher.encode("2468"), PinHasher.encode("2468"))
    }

    @Test
    fun `a stored digest still verifies its own PIN and rejects others`() {
        val encoded = PinHasher.encode("13579")
        assertTrue(PinHasher.matches("13579", encoded))
        assertFalse(PinHasher.matches("13578", encoded))
        assertFalse(PinHasher.matches("", encoded))
        assertFalse(PinHasher.matches("13579", null))
        assertFalse(PinHasher.matches("13579", "garbage"))
    }

    @Test
    fun `pin shape rules accept 4 to 8 digits only`() {
        assertTrue(PinHasher.isValidPin("1234"))
        assertTrue(PinHasher.isValidPin("12345678"))
        assertFalse(PinHasher.isValidPin("123"))
        assertFalse(PinHasher.isValidPin("123456789"))
        assertFalse(PinHasher.isValidPin("12a4"))
        assertFalse(PinHasher.isValidPin("1234 "))
    }

    @Test
    fun `encoding refuses a malformed pin instead of storing a weak one`() {
        assertThrows(IllegalArgumentException::class.java) { PinHasher.encode("12") }
    }

    // --- the repository -------------------------------------------------------

    @Test
    fun `set then verify then clear round-trips through preferences`() = runTest {
        val repo = repo()
        assertFalse(repo.isLockEnabled)
        assertEquals(PinResult.Ok, repo.setPin("2468"))
        assertTrue(repo.isLockEnabled)
        assertTrue(repo.verify("2468"))
        assertFalse(repo.verify("0000"))

        assertTrue(repo.clearPin("2468") is PinResult.Ok)
        assertFalse(repo.isLockEnabled)
    }

    @Test
    fun `a malformed pin is refused with a message and nothing is written`() = runTest {
        val repo = repo()
        val result = repo.setPin("12")
        assertTrue(result is PinResult.Invalid)
        assertFalse((result as PinResult.Invalid).message.isBlank())
        assertFalse(repo.isLockEnabled)
    }

    @Test
    fun `turning the lock off requires the current pin`() = runTest {
        val repo = repo()
        repo.setPin("1122")
        val result = repo.clearPin("9999")
        assertTrue(result is PinResult.Wrong)
        assertTrue(repo.isLockEnabled) // still locked: a wrong PIN must not disarm anything
    }

    @Test
    fun `changing a pin needs the old one and keeps verification working`() = runTest {
        val repo = repo()
        repo.setPin("1122")
        assertTrue(repo.changePin("0000", "3344") is PinResult.Wrong)
        assertTrue(repo.changePin("1122", "3344") is PinResult.Ok)
        assertTrue(repo.verify("3344"))
        assertFalse(repo.verify("1122"))
    }

    @Test
    fun `biometrics are only ever reported available while a pin exists`() = runTest {
        val repo = repo()
        repo.setBiometricEnabled(true)
        assertFalse(repo.isBiometricEnabled) // no PIN yet: biometrics alone cannot unlock this app
        repo.setPin("4321")
        assertTrue(repo.isBiometricEnabled)
        repo.setBiometricEnabled(false)
        assertFalse(repo.isBiometricEnabled)
    }

    @Test
    fun `backgrounding the app re-arms the lock and unlocking clears it`() = runTest {
        val repo = repo()
        assertTrue(repo.isUnlocked.value)
        repo.setPin("4321")
        repo.onBackground()
        assertFalse(repo.isUnlocked.value)
        repo.unlock()
        assertTrue(repo.isUnlocked.value)
    }

    @Test
    fun `an app without a lock is never reported as locked`() {
        val repo = repo()
        repo.onBackground()
        assertTrue(repo.isUnlocked.value)
    }

    @Test
    fun `five wrong attempts lock the keypad for five minutes`() {
        val repo = repo()
        repo.setPinBlocking("1234")
        val now = 1_700_000_000_000L
        assertEquals(5, repo.remainingAttempts)
        repeat(4) { assertEquals(it + 1, repo.registerFailure(now)) }
        assertEquals(1, repo.remainingAttempts)
        assertEquals(0, repo.remainingLockoutMinutes(now))

        assertEquals(5, repo.registerFailure(now))
        assertEquals(5, repo.remainingLockoutMinutes(now))
        assertTrue(repo.isLockedOut(now))
        assertFalse(repo.isLockedOut(now + 6 * 60_000L))
        assertEquals(5, repo.remainingAttempts) // the counter restarts after a cool-down
    }

    @Test
    fun `a successful attempt clears the failure counter`() {
        val repo = repo()
        val now = 1_700_000_000_000L
        repo.registerFailure(now)
        repo.registerFailure(now)
        assertEquals(2, repo.failureCount())
        repo.clearFailures()
        assertEquals(0, repo.failureCount())
        assertEquals(0, repo.remainingLockoutMinutes(now))
    }

    /** The suspend setter, called from a synchronous test without a coroutine builder. */
    private fun SecurityRepository.setPinBlocking(pin: String) {
        kotlinx.coroutines.runBlocking { setPin(pin) }
    }
}
