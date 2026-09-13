package ai.project.rio.money

import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode

class CurrencyMismatchException(a: Currency, b: Currency) :
    IllegalArgumentException("Currency mismatch: ${a.code} vs ${b.code}")

/**
 * An amount of money: integer `amount` in the currency's minor units plus the currency.
 *
 * Money may be negative. Business rules (e.g. card transaction magnitude > 0) live in services.
 * All arithmetic is checked: overflowing a Long throws ArithmeticException instead of wrapping.
 */
data class Money(val amount: Long, val currency: Currency) : Comparable<Money> {

    operator fun plus(other: Money): Money {
        requireSameCurrency(other)
        return Money(Math.addExact(amount, other.amount), currency)
    }

    operator fun minus(other: Money): Money {
        requireSameCurrency(other)
        return Money(Math.subtractExact(amount, other.amount), currency)
    }

    operator fun unaryMinus(): Money = Money(Math.negateExact(amount), currency)

    override operator fun compareTo(other: Money): Int {
        requireSameCurrency(other)
        return amount.compareTo(other.amount)
    }

    val isPositive: Boolean get() = amount > 0L

    /**
     * Splits into `parts` pieces that sum exactly to this Money.
     * Earlier parts receive one extra minor unit each until the remainder is used up.
     *
     * USD 10000 split 3 => [3334, 3333, 3333]
     */
    fun split(parts: Int): List<Money> {
        require(parts > 0) { "parts must be > 0, was $parts" }
        return allocate(List(parts) { 1L })
    }

    /**
     * Allocates proportionally to `weights` using integer arithmetic. Each share is first
     * floored (toward negative infinity), then leftover minor units are handed out one at a time
     * from the first weight onward. The shares always sum exactly to this Money.
     */
    fun allocate(weights: List<Long>): List<Money> {
        require(weights.isNotEmpty()) { "weights must not be empty" }
        require(weights.all { it > 0 }) { "every weight must be > 0, was $weights" }
        val total = weights.fold(0L) { acc, w -> Math.addExact(acc, w) }

        val shares = LongArray(weights.size) { i ->
            // multiplyExact guards the intermediate product against Long overflow.
            Math.floorDiv(Math.multiplyExact(amount, weights[i]), total)
        }
        var remainder = amount - shares.sum() // always 0 <= remainder < weights.size
        var i = 0
        while (remainder > 0) {
            shares[i] = shares[i] + 1
            remainder--
            i++
        }
        return shares.map { Money(it, currency) }
    }

    /**
     * Multiplies by an exact ratio and rounds to a whole minor unit with the given policy.
     * Throws ArithmeticException if the result does not fit in a Long.
     */
    fun multiply(ratio: Ratio, rounding: MoneyRounding): Money {
        val exact = BigDecimal(BigInteger.valueOf(amount).multiply(BigInteger.valueOf(ratio.numerator)))
            .divide(BigDecimal.valueOf(ratio.denominator), 0, rounding.toRoundingMode())
        return Money(exact.toBigIntegerExact().longValueExact(), currency)
    }

    override fun toString(): String = "${currency.code} $amount"

    private fun requireSameCurrency(other: Money) {
        if (currency != other.currency) throw CurrencyMismatchException(currency, other.currency)
    }
}

private fun MoneyRounding.toRoundingMode(): RoundingMode = when (this) {
    MoneyRounding.DOWN -> RoundingMode.FLOOR
    MoneyRounding.UP -> RoundingMode.CEILING
    MoneyRounding.HALF_EVEN -> RoundingMode.HALF_EVEN
}

/** Sums a non-empty, single-currency collection. Rejects empty input and mixed currencies. */
fun Iterable<Money>.sumMoney(): Money {
    val iterator = iterator()
    require(iterator.hasNext()) { "cannot sum an empty collection of Money: the currency is unknown" }
    var total = iterator.next()
    while (iterator.hasNext()) total += iterator.next()
    return total
}

/** Sums per currency. Never mixes currencies. */
fun Iterable<Money>.sumByCurrency(): Map<Currency, Money> =
    groupBy { it.currency }.mapValues { (_, values) -> values.sumMoney() }
