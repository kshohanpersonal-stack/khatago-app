package com.khatago.finance.core.money

import java.math.RoundingMode
import kotlin.math.abs

/**
 * Deterministic, locale-independent money display.
 *
 * Everything here is pure (no `Locale`, no `NumberFormat`, no Android types) so that formatting
 * is identical on every device and unit-testable on the JVM. This matters for a personal ledger:
 * a user must be able to compare a printed report against an on-screen number and see the same
 * digits.
 */
object MoneyFormat {

    /** e.g. `৳10,000` / `৳10,000.50` / `-৳25.00`. */
    fun format(amountMinor: Long, currency: CurrencySpec): String {
        val negative = amountMinor < 0
        val magnitude = abs(amountMinor)
        val decimals = currency.decimals

        val integerPart: String
        val fractionPart: String
        if (decimals <= 0) {
            integerPart = magnitude.toString()
            fractionPart = ""
        } else {
            val divisor = BigDecimalHelper.powerOfTen(decimals)
            integerPart = (magnitude / divisor).toString()
            val fraction = magnitude % divisor
            fractionPart = fraction.toString().padStart(decimals, '0')
        }

        val grouped = groupThousands(integerPart, currency.grouping)
        val digits = if (fractionPart.isEmpty()) grouped else "$grouped${currency.decimalMark}$fractionPart"
        // The sign goes outside the symbol ("-৳5.00", never "৳-5.00"): that is how a negative amount is
        // written on a paper khata, and inside the digits it reads as part of the number.
        val symbol = currency.symbol
        return when {
            currency.symbolBefore && negative -> "-$symbol$digits"
            currency.symbolBefore -> "$symbol$digits"
            negative -> "-$digits $symbol"
            else -> "$digits $symbol"
        }
    }

    /** Major-units string without a symbol, used by CSV cells so spreadsheets treat them as numbers. */
    fun toCsvNumber(amountMinor: Long, currency: CurrencySpec): String =
        java.math.BigDecimal.valueOf(amountMinor)
            .movePointLeft(currency.decimals)
            .setScale(currency.decimals, RoundingMode.HALF_UP)
            .toPlainString()

    /** Adds thousands separators: `1234567` -> `1,234,567`. */
    internal fun groupThousands(digits: String, separator: Char): String {
        if (digits.length <= 3) return digits
        val out = StringBuilder(digits.length + digits.length / 3)
        val firstGroup = digits.length % 3
        var index = 0
        if (firstGroup != 0) {
            out.append(digits, 0, firstGroup)
            index = firstGroup
        }
        while (index < digits.length) {
            if (out.isNotEmpty()) out.append(separator)
            out.append(digits, index, index + 3)
            index += 3
        }
        return out.toString()
    }

    /** Percentage with one decimal, always `0.0`-`100.0` clamped: used for payoff progress. */
    fun percent(numerator: Long, denominator: Long): Double {
        if (denominator <= 0L) return 0.0
        val raw = (numerator.toDouble() / denominator.toDouble()) * 100.0
        return when {
            raw.isNaN() -> 0.0
            raw < 0.0 -> 0.0
            raw > 100.0 -> 100.0
            else -> raw
        }
    }

    fun percentOf(part: MoneyMinor, whole: MoneyMinor): Double =
        percent(part.minor, whole.minor)

    /** `66.7%` style label; `—` when there is nothing to divide by. */
    fun percentLabel(part: MoneyMinor, whole: MoneyMinor): String =
        if (whole.isZero) "—" else "${"%.1f".format(percentOf(part, whole))}%"
}

/** Keeps the BigDecimal conversion in one tested place instead of repeating it. */
internal object BigDecimalHelper {
    fun powerOfTen(exponent: Int): Long {
        require(exponent in 0..15) { "Unsupported exponent: $exponent" }
        var result = 1L
        repeat(exponent) { result *= 10L }
        return result
    }
}

/** Result of parsing an amount the user typed. */
sealed interface MoneyParseResult {
    data class Success(val amount: MoneyMinor) : MoneyParseResult

    data class Invalid(val message: String, val kind: Kind = Kind.NOT_A_NUMBER) : MoneyParseResult

    enum class Kind { EMPTY, NOT_A_NUMBER, NEGATIVE, TOO_LARGE, TOO_PRECISE, ZERO_NOT_ALLOWED }

    companion object {
        /**
         * Single entry point for "user typed money". Never throws and never silently rounds:
         * the caller gets a human-readable message it can show in a form field.
         */
        fun parse(raw: String, currency: CurrencySpec, allowZero: Boolean = false): MoneyParseResult {
            val input = raw.trim()
            if (input.isEmpty()) {
                return Invalid("Enter an amount.", Kind.EMPTY)
            }
            val normalized = input.replace(" ", "").replace(currency.grouping.toString(), "")
                .removePrefix(currency.symbol)
                .let { if (!currency.symbolBefore) it.removeSuffix(currency.symbol) else it }
                .replace(currency.decimalMark, '.')
            if (normalized.startsWith("-")) {
                return Invalid("An amount cannot be negative here. Use a payment to reduce a balance.", Kind.NEGATIVE)
            }
            if (normalized.any { !it.isDigit() && it != '.' }) {
                return Invalid("\"$input\" is not a valid amount.", Kind.NOT_A_NUMBER)
            }
            if (normalized.count { it == '.' } > 1) {
                return Invalid("\"$input\" has more than one decimal point.", Kind.NOT_A_NUMBER)
            }
            val fractionDigits = normalized.substringAfter('.', "").length
            if (fractionDigits > currency.decimals) {
                return Invalid(
                    "This currency has at most ${currency.decimals} decimal digit(s).",
                    Kind.TOO_PRECISE,
                )
            }
            val amount = MoneyMinor.parse(normalized, currency.decimals, allowZero)
                ?: return Invalid("Enter an amount of at most ${MoneyMinor.MAX_MINOR} minor units.", Kind.TOO_LARGE)
            if (!allowZero && amount.isZero) {
                return Invalid("The amount must be greater than zero.", Kind.ZERO_NOT_ALLOWED)
            }
            return Success(amount)
        }
    }
}
