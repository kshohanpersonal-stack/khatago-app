package com.khatago.finance.core.money

/**
 * A currency KhataGo can display and store amounts in.
 *
 * KhataGo is **single-active-currency per profile**: one currency is configured in Settings and
 * every stored amount is interpreted in it. There is deliberately no exchange-rate conversion —
 * a personal ledger with invented or stale FX rates produces wrong numbers, and a wrong number is
 * worse than a missing feature. Switching currency re-interprets existing amounts, which the UI
 * warns about explicitly.
 */
data class CurrencySpec(
    /** ISO-4217 code, e.g. `BDT`. Used as the persisted key. */
    val code: String,
    /** Display symbol, e.g. `৳`. */
    val symbol: String,
    /** Minor-unit exponent: 2 for ৳100.50, 0 for a currency with no subunit. */
    val decimals: Int,
    /** False for currencies that write the symbol after the number (e.g. `1.000,00 €`). */
    val symbolBefore: Boolean = true,
    /** Thousands grouping glyph. Kept explicit so formatting is deterministic across locales. */
    val grouping: Char = ',',
    /** Decimal separator glyph. */
    val decimalMark: Char = '.',
) {
    val isGrouped: Boolean get() = decimals > 0

    companion object {
        val BDT = CurrencySpec(code = "BDT", symbol = "৳", decimals = 2)
        val USD = CurrencySpec(code = "USD", symbol = "$", decimals = 2)
        val EUR = CurrencySpec(code = "EUR", symbol = "€", decimals = 2)
        val GBP = CurrencySpec(code = "GBP", symbol = "£", decimals = 2)
        val INR = CurrencySpec(code = "INR", symbol = "₹", decimals = 2)
        val SAR = CurrencySpec(code = "SAR", symbol = "﷼", decimals = 2)
        val AED = CurrencySpec(code = "AED", symbol = "د.إ", decimals = 2, symbolBefore = false)
        val PKR = CurrencySpec(code = "PKR", symbol = "₨", decimals = 2)
        val MYR = CurrencySpec(code = "MYR", symbol = "RM", decimals = 2)
        val IDR = CurrencySpec(code = "IDR", symbol = "Rp", decimals = 0)
        val NGN = CurrencySpec(code = "NGN", symbol = "₦", decimals = 2)
        val KES = CurrencySpec(code = "KES", symbol = "KSh", decimals = 2)

        /**
         * Supported catalogue. Order matters: it is the order shown in the currency picker,
         * with BDT first because KhataGo is built for a Bangladeshi market first.
         */
        val ALL: List<CurrencySpec> = listOf(
            BDT, USD, EUR, GBP, INR, SAR, AED, PKR, MYR, IDR, NGN, KES,
        )

        val DEFAULT: CurrencySpec = BDT

        fun fromCode(code: String?): CurrencySpec =
            ALL.firstOrNull { it.code.equals(code?.trim()?.uppercase(), ignoreCase = true) } ?: DEFAULT
    }
}
