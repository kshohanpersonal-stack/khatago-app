package com.khatago.finance.core.money

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * The money primitives. These tests exist because everything else in the app trusts them: a rounding
 * rule that changes here silently changes every balance, report and export without any other file
 * looking wrong.
 *
 * `MoneyMinor` is Long-based and *refuses* to represent a negative or absurd value — that is the whole
 * design (`docs/ARCHITECTURE.md`, "Money"), so the refusals are asserted as loudly as the arithmetic.
 */
class MoneyCoreTest {

    private val bdt = CurrencySpec.BDT
    private val idr = CurrencySpec.IDR

    // --- parsing typed amounts ------------------------------------------------

    @Test
    fun `major units become minor units exactly`() {
        val parsed = MoneyParseResult.parse("1,234.50", bdt)
        assertTrue(parsed is MoneyParseResult.Success)
        assertEquals(123_450L, (parsed as MoneyParseResult.Success).amount.minor)
    }

    @Test
    fun `currency symbol and spaces are tolerated because users type them`() {
        // The symbol is decoration and is stripped wherever the user put it: this currency writes it in
        // front, but people paste back whatever was on screen and phones autocomplete it at the end too.
        assertEquals(1000L, parse(" ৳10.00 "))
        assertEquals(1000L, parse("10.00৳"))
        assertEquals(1000L, parse("৳10.00৳"))
        // A space is a thousands separator here, never a decimal mark: "10 00" is one thousand, not ten.
        // Reading it the other way would rewrite what the user typed, which no parser in this app may do.
        assertEquals(100_000L, parse("10 00"))
        assertEquals(100_050L, parse("10 00.50"))
    }

    @Test
    fun `sub-cent precision is rejected rather than rounded`() {
        val parsed = MoneyParseResult.parse("10.005", bdt)
        assertTrue(parsed is MoneyParseResult.Invalid)
        assertEquals(MoneyParseResult.Kind.TOO_PRECISE, (parsed as MoneyParseResult.Invalid).kind)
    }

    @Test
    fun `a currency without subunits rejects any decimal part`() {
        // IDR has decimals = 0: "50000.50" is not expressible, and rounding it would invent money.
        assertTrue(MoneyParseResult.parse("50000.50", idr) is MoneyParseResult.Invalid)
        assertEquals(50_000L, (MoneyParseResult.parse("50,000", idr) as MoneyParseResult.Success).amount.minor)
    }

    @Test
    fun `zero is rejected by default and accepted when the caller allows it`() {
        assertTrue(MoneyParseResult.parse("0.00", bdt) is MoneyParseResult.Invalid)
        assertTrue(
            MoneyParseResult.parse("0.00", bdt, allowZero = true) is MoneyParseResult.Success,
        )
    }

    @Test
    fun `negative input is rejected with an explanation instead of being abs-ed`() {
        val parsed = MoneyParseResult.parse("-50", bdt)
        assertTrue(parsed is MoneyParseResult.Invalid)
        assertEquals(MoneyParseResult.Kind.NEGATIVE, (parsed as MoneyParseResult.Invalid).kind)
    }

    @Test
    fun `text that is not a number is rejected`() {
        assertEquals(MoneyParseResult.Kind.NOT_A_NUMBER, kindOf("abc"))
        assertEquals(MoneyParseResult.Kind.NOT_A_NUMBER, kindOf("10.5.2"))
        assertEquals(MoneyParseResult.Kind.EMPTY, kindOf("   "))
    }

    @Test
    fun `absurd magnitudes are rejected before they can wrap a Long`() {
        val huge = "1".repeat(40)
        val parsed = MoneyParseResult.parse(huge, bdt)
        assertTrue(
            "A 40-digit amount must not be accepted: ${(parsed as? MoneyParseResult.Success)?.amount?.minor}",
            parsed is MoneyParseResult.Invalid,
        )
    }

    // --- MoneyMinor invariants ------------------------------------------------

    @Test
    fun `money can never be negative`() {
        expectIllegalArgument { MoneyMinor(-1L) }
    }

    @Test
    fun `the supported maximum is enforced at construction`() {
        MoneyMinor(MoneyMinor.MAX_MINOR) // exactly at the limit: fine
        expectIllegalArgument { MoneyMinor(MoneyMinor.MAX_MINOR + 1L) }
    }

    @Test
    fun `addition refuses to overflow rather than wrapping`() {
        expectIllegalArgument { MoneyMinor.ofMinor(1L) + MoneyMinor(MoneyMinor.MAX_MINOR) }
    }

    @Test
    fun `remaining is clamped at zero so a credit note cannot appear as a balance`() {
        assertEquals(0L, MoneyMinor.ofMinor(100L).subtractSaturating(MoneyMinor.ofMinor(250L)).minor)
        assertEquals(150L, MoneyMinor.ofMinor(250L).subtractSaturating(MoneyMinor.ofMinor(100L)).minor)
    }

    @Test
    fun `subtraction below zero is refused outright`() {
        expectIllegalArgument { MoneyMinor.ofMinor(10L) - MoneyMinor.ofMinor(20L) }
    }

    @Test
    fun `overpayment protection compares against the remaining limit exactly`() {
        // `this` is what has already been paid, the first argument is the whole obligation and the second
        // is the payment being offered. The limit is INCLUSIVE: taking the paid total one paisa past it is
        // an overpayment, landing exactly on it settles the record and must stay allowed.
        val paid = MoneyMinor.ofMinor(700L)
        val limit = MoneyMinor.ofMinor(1_000L)
        assertTrue(paid.wouldExceed(limit, MoneyMinor.ofMinor(301L)))
        assertFalse(paid.wouldExceed(limit, MoneyMinor.ofMinor(300L)))
        assertFalse(paid.wouldExceed(limit, MoneyMinor.ZERO))
    }

    @Test
    fun `quantity times unit price is exact and overflow-guarded`() {
        assertEquals(3L * 199L, MoneyMinor.ofProduct(3L, 199L).minor)
        assertEquals(0L, MoneyMinor.ofProduct(0L, 5_000L).minor)
        expectIllegalArgument { MoneyMinor.ofProduct(1_000L, MoneyMinor.MAX_MINOR) }
    }

    @Test
    fun `multiplication overflow is refused`() {
        // MAX_MINOR is 10^15 and it is a hard ceiling, not a hint: above it a ledger amount is
        // meaningless (and nearly always a fat-finger entry). So the cap itself is the boundary — the
        // multiplication that lands exactly on it is fine, the one that steps past it throws.
        val half = MoneyMinor.ofMinor(500_000_000_000_000L)
        assertEquals(MoneyMinor.MAX_MINOR, half.times(2).minor)
        expectIllegalArgument { half.times(3) }
    }

    // --- formatting -----------------------------------------------------------

    @Test
    fun `display formatting groups thousands and places the symbol`() {
        assertEquals("৳1,234.50", MoneyFormat.format(123_450L, bdt))
        assertEquals("৳0.05", MoneyFormat.format(5L, bdt))
        assertEquals("৳10,000.00", MoneyFormat.format(1_000_000L, bdt))
    }

    @Test
    fun `zero decimals print no fractional part`() {
        assertEquals("Rp10,000", MoneyFormat.format(10_000L, idr))
    }

    @Test
    fun `negative amounts keep the sign outside the symbol`() {
        assertEquals("-৳5.00", MoneyFormat.format(-500L, bdt))
    }

    @Test
    fun `percent labels use the same decimal mark as the amounts beside them`() {
        // Money is rendered by hand and is always `.`-separated; a percentage that went through
        // java.util.Formatter with the device locale would print `66,7%` next to `৳1,000.50` and read as
        // two different conventions on one line. The locale is pinned, so the line cannot disagree.
        val saved = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("de"))
            assertEquals("66.7%", MoneyFormat.percentLabel(MoneyMinor.ofMinor(2L), MoneyMinor.ofMinor(3L)))
            assertEquals(66.66666, MoneyFormat.percentOf(MoneyMinor.ofMinor(2L), MoneyMinor.ofMinor(3L)), 1e-4)
        } finally {
            Locale.setDefault(saved)
        }
    }

    @Test
    fun `csv numbers carry no symbol and no grouping so a spreadsheet adds them`() {
        assertEquals("1234.50", MoneyFormat.toCsvNumber(123_450L, bdt))
        assertEquals("0.05", MoneyFormat.toCsvNumber(5L, bdt))
        assertEquals("50000", MoneyFormat.toCsvNumber(50_000L, idr))
    }

    @Test
    fun `a csv number round-trips back to the same minor units`() {
        val minor = 9_876_543_210L
        val text = MoneyFormat.toCsvNumber(minor, bdt)
        assertEquals(minor, MoneyMinor.parse(text, bdt.decimals, allowZero = true)?.minor)
    }

    // --- currency spec --------------------------------------------------------

    @Test
    fun `an unknown or blank currency code falls back to the default`() {
        assertEquals(CurrencySpec.DEFAULT, CurrencySpec.fromCode("XX"))
        assertEquals(CurrencySpec.DEFAULT, CurrencySpec.fromCode(null))
        assertEquals(CurrencySpec.DEFAULT, CurrencySpec.fromCode("   "))
    }

    @Test
    fun `currency codes are matched case-insensitively because they come from user text`() {
        assertEquals("BDT", CurrencySpec.fromCode("bdt").code)
        assertEquals("USD", CurrencySpec.fromCode(" USD ").code)
    }

    // --- helpers --------------------------------------------------------------

    private fun parse(raw: String): Long =
        (MoneyParseResult.parse(raw, bdt) as? MoneyParseResult.Success)?.amount?.minor ?: -1L

    private fun kindOf(raw: String): MoneyParseResult.Kind? =
        (MoneyParseResult.parse(raw, bdt) as? MoneyParseResult.Invalid)?.kind

    private inline fun expectIllegalArgument(block: () -> Unit) {
        val thrown = runCatching(block).exceptionOrNull()
        assertNotNull("expected an IllegalArgumentException, got none", thrown)
        assertTrue(
            "expected IllegalArgumentException, got ${thrown!!::class.simpleName}: ${thrown.message}",
            thrown is IllegalArgumentException,
        )
    }
}
