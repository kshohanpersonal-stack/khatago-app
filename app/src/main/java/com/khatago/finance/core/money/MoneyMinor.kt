package com.khatago.finance.core.money

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * The complete money model of KhataGo.
 *
 * Money is never stored or calculated as [Float]/[Double]. Every amount is an exact count of
 * minor units: ৳100.50 is `10050`. A 64-bit integer is exact up to 9,223,372,036,854.775,807
 * minor units, far beyond any personal-finance figure, and every operation on it is deterministic
 * (unlike floating point, `0.1 + 0.2` never surprises us).
 *
 * Deliberately *not* an inline value class: Room and kotlinx.serialization both need a plain
 * field, and the compiler would then force the underlying [minor] through the persistence layer
 * anyway. Keeping it a normal class lets us guard construction and arithmetic in one place.
 */
data class MoneyMinor(val minor: Long) : Comparable<MoneyMinor> {

    init {
        require(minor >= 0L) { "Money cannot be negative: $minor minor units" }
        require(minor <= MAX_MINOR) { "Money exceeds the supported maximum: $minor minor units" }
    }

    val isZero: Boolean get() = minor == 0L
    val isPositive: Boolean get() = minor > 0L

    /** Never negative: a balance can be zero but not below zero (see [subtractSaturating]). */
    operator fun plus(other: MoneyMinor): MoneyMinor = MoneyMinor(addMinor(minor, other.minor))

    operator fun minus(other: MoneyMinor): MoneyMinor = MoneyMinor(subtractMinor(minor, other.minor))

    /** `this - other`, clamped at zero. Used for "remaining", where a negative balance is meaningless. */
    fun subtractSaturating(other: MoneyMinor): MoneyMinor =
        if (other.minor >= minor) ZERO else ofMinor(minor - other.minor)

    /** True when [payment] would take the amount past [limit] (used for overpayment protection). */
    fun wouldExceed(limit: MoneyMinor, payment: MoneyMinor): Boolean =
        addMinor(minor, payment.minor) > limit.minor

    fun times(count: Int): MoneyMinor {
        require(count >= 0) { "count must not be negative: $count" }
        val result = minor.toBigDecimal().multiply(count.toBigDecimal())
        require(result <= MAX_MINOR_VALUE) { "Multiplication overflow: $minor x $count" }
        return ofMinor(result.toLong())
    }

    override fun compareTo(other: MoneyMinor): Int = minor.compareTo(other.minor)

    /**
     * Major units (e.g. `100.50`) at this currency's exponent. Exact: BigDecimal, no doubles.
     */
    fun toMajor(decimals: Int): BigDecimal =
        BigDecimal.valueOf(minor)
            .movePointLeft(decimals)
            .setScale(decimals, RoundingMode.UNNECESSARY)

    /** Plain, locale-independent decimal string, e.g. `"100.50"`. Used by CSV/JSON export. */
    fun toPlainString(decimals: Int): String = toMajor(decimals).toPlainString()

    companion object {
        /**
         * Upper bound for a single amount. 10^15 minor units = 10 trillion in a 2-decimal
         * currency: still comfortably inside Long, and beyond that the number is meaningless
         * for a personal ledger (and likely a fat-finger mistake).
         */
        const val MAX_MINOR: Long = 1_000_000_000_000_000L

        val ZERO: MoneyMinor = MoneyMinor(0L)

        /** Exact upper bound as [BigDecimal]; named so instance code can reference it. */
        val MAX_MINOR_VALUE: BigDecimal = BigDecimal.valueOf(MAX_MINOR)

        /**
         * Wraps an already-validated minor-unit count (e.g. a value read from the database).
         * Throws on malformed data instead of silently clamping — silent clamping in a financial
         * app hides corruption.
         */
        fun ofMinor(minor: Long): MoneyMinor = MoneyMinor(minor)

        /**
         * Exact total from quantity x unit price. Guards against `Long` overflow so a
         * mistyped unit price can never roll the total around into a negative number.
         */
        fun ofProduct(quantity: Long, unitPriceMinor: Long): MoneyMinor {
            require(quantity >= 0L) { "quantity must not be negative: $quantity" }
            require(unitPriceMinor >= 0L) { "unitPriceMinor must not be negative: $unitPriceMinor" }
            if (quantity == 0L || unitPriceMinor == 0L) return ZERO
            val result = BigDecimal.valueOf(quantity).multiply(BigDecimal.valueOf(unitPriceMinor))
            require(result <= MAX_MINOR_VALUE) { "Total too large to store: $result minor units" }
            return MoneyMinor(result.toLong())
        }

        /**
         * Converts user-entered major units (e.g. `"1,234.50"`) into minor units using
         * [RoundingMode.HALF_UP], the convention Indian/South-Asian money markets use.
         *
         * Returns `null` when the input cannot be represented in this currency
         * (unparseable, negative, beyond the supported maximum, or more decimals than the
         * currency allows under a strict policy).
         */
        fun parse(
            raw: String,
            decimals: Int,
            allowZero: Boolean = false,
            strictExponent: Boolean = true,
        ): MoneyMinor? {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) return null
            val normalized = trimmed.replace(" ", "").replace(",", "")
            val decimal = try {
                BigDecimal(normalized)
            } catch (_: NumberFormatException) {
                return null
            }
            if (decimal.signum() < 0) return null
            if (!allowZero && decimal.signum() == 0) return null
            if (strictExponent && decimal.scale() > decimals) return null
            val scaled = try {
                decimal.movePointRight(decimals).setScale(0, RoundingMode.HALF_UP)
            } catch (_: ArithmeticException) {
                return null
            }
            if (scaled > MAX_MINOR_VALUE) return null
            return MoneyMinor(scaled.toLongExact())
        }

    }
}

/** Exact addition with an overflow/sanity guard, so a bad total can never wrap around. */
private fun addMinor(left: Long, right: Long): Long {
    val result = left + right
    require(result >= 0L && result <= MoneyMinor.MAX_MINOR) { "Money addition overflow: $left + $right" }
    return result
}

/** Exact subtraction that refuses to produce a negative amount. */
private fun subtractMinor(left: Long, right: Long): Long {
    val result = left - right
    require(result >= 0L) { "Money subtraction would go negative: $left - $right" }
    return result
}
