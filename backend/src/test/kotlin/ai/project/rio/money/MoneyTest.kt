package ai.project.rio.money

import ai.project.rio.money.Currency.EUR
import ai.project.rio.money.Currency.JPY
import ai.project.rio.money.Currency.USD
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class MoneyTest {

    private fun usd(minor: Long) = Money(minor, USD)
    private fun eur(minor: Long) = Money(minor, EUR)

    // ---- currency metadata ----

    @Test
    fun `currency precision`() {
        assertEquals(2, USD.precision)
        assertEquals(2, EUR.precision)
        assertEquals(0, JPY.precision)
    }

    @Test
    fun `currency lookup is exact and case-sensitive`() {
        assertEquals(USD, Currency.fromCode("USD"))
        assertEquals(null, Currency.fromCode("usd"))
        assertEquals(null, Currency.fromCode("GBP"))
    }

    // ---- add / subtract / compare ----

    @Test
    fun `addition and subtraction`() {
        assertEquals(usd(300), usd(100) + usd(200))
        assertEquals(usd(200), usd(300) - usd(100))
        assertEquals(usd(-100), usd(100) - usd(200))
    }

    @Test
    fun `comparison`() {
        assertTrue(usd(100) < usd(200))
        assertTrue(usd(200) > usd(100))
        assertEquals(0, usd(100).compareTo(usd(100)))
    }

    @Test
    fun `currency mismatch is rejected`() {
        assertFailsWith<CurrencyMismatchException> { usd(100) + eur(100) }
        assertFailsWith<CurrencyMismatchException> { usd(100) - eur(100) }
        assertFailsWith<CurrencyMismatchException> { usd(100).compareTo(eur(100)) }
    }

    // ---- sum ----

    @Test
    fun `sumMoney adds a homogeneous list`() {
        assertEquals(usd(600), listOf(usd(100), usd(200), usd(300)).sumMoney())
    }

    @Test
    fun `sumMoney rejects mixed currencies and empty input`() {
        assertFailsWith<CurrencyMismatchException> { listOf(usd(100), eur(100)).sumMoney() }
        assertFailsWith<IllegalArgumentException> { emptyList<Money>().sumMoney() }
    }

    @Test
    fun `sumByCurrency groups without mixing`() {
        val totals = listOf(usd(100), eur(5), usd(1), Money(7, JPY)).sumByCurrency()
        assertEquals(mapOf(USD to usd(101), EUR to eur(5), JPY to Money(7, JPY)), totals)
    }

    // ---- split ----

    @Test
    fun `split 100 dollars three ways`() {
        val parts = usd(10_000).split(3)
        assertEquals(listOf(usd(3334), usd(3333), usd(3333)), parts)
        assertEquals(usd(10_000), parts.sumMoney())
    }

    @Test
    fun `split one cent three ways keeps the cent`() {
        val parts = usd(1).split(3)
        assertEquals(listOf(usd(1), usd(0), usd(0)), parts)
        assertEquals(usd(1), parts.sumMoney())
    }

    @Test
    fun `split negative amount preserves total`() {
        val parts = usd(-100).split(3)
        assertEquals(usd(-100), parts.sumMoney())
        assertEquals(listOf(usd(-33), usd(-33), usd(-34)), parts)
    }

    @Test
    fun `split exact`() {
        assertEquals(listOf(usd(50), usd(50)), usd(100).split(2))
        assertEquals(listOf(usd(7)), usd(7).split(1))
    }

    @Test
    fun `split rejects non-positive parts`() {
        assertFailsWith<IllegalArgumentException> { usd(100).split(0) }
        assertFailsWith<IllegalArgumentException> { usd(100).split(-1) }
    }

    // ---- allocate ----

    @Test
    fun `allocate by weights with remainder goes to earlier entries`() {
        val shares = usd(100).allocate(listOf(1, 1, 1))
        assertEquals(listOf(usd(34), usd(33), usd(33)), shares)
        assertEquals(usd(100), shares.sumMoney())
    }

    @Test
    fun `allocate uneven weights`() {
        // 1000 * 70/100 = 700, 1000 * 30/100 = 300
        assertEquals(listOf(usd(700), usd(300)), usd(1000).allocate(listOf(70, 30)))
        // 1001 * 3/7 = 429 (floor of 429.0), 1001 * 4/7 = 572 -> total 1001 exactly
        val shares = usd(1001).allocate(listOf(3, 4))
        assertEquals(usd(1001), shares.sumMoney())
        assertEquals(listOf(usd(429), usd(572)), shares)
    }

    @Test
    fun `allocate always preserves total across many cases`() {
        for (amount in -50L..250L) {
            for (weights in listOf(listOf(1L), listOf(1L, 2L), listOf(3L, 3L, 4L), listOf(1L, 1L, 1L, 1L, 1L, 1L, 1L))) {
                val shares = usd(amount).allocate(weights)
                assertEquals(usd(amount), shares.sumMoney(), "amount=$amount weights=$weights")
                assertEquals(weights.size, shares.size)
            }
        }
    }

    @Test
    fun `allocate rejects bad weights`() {
        assertFailsWith<IllegalArgumentException> { usd(100).allocate(emptyList()) }
        assertFailsWith<IllegalArgumentException> { usd(100).allocate(listOf(1, 0)) }
        assertFailsWith<IllegalArgumentException> { usd(100).allocate(listOf(1, -1)) }
    }

    // ---- ratio multiply ----

    @Test
    fun `ratio requires positive denominator`() {
        assertFailsWith<IllegalArgumentException> { Ratio(1, 0) }
        assertFailsWith<IllegalArgumentException> { Ratio(1, -100) }
    }

    @Test
    fun `multiply by ratio honours each rounding mode`() {
        // 1000 * 825 / 10000 = 82.5
        val bps825 = Ratio.basisPoints(825)
        assertEquals(usd(82), usd(1000).multiply(bps825, MoneyRounding.DOWN))
        assertEquals(usd(83), usd(1000).multiply(bps825, MoneyRounding.UP))
        assertEquals(usd(82), usd(1000).multiply(bps825, MoneyRounding.HALF_EVEN)) // tie -> even

        // 1010 * 825 / 10000 = 83.325
        assertEquals(usd(83), usd(1010).multiply(bps825, MoneyRounding.DOWN))
        assertEquals(usd(84), usd(1010).multiply(bps825, MoneyRounding.UP))
        assertEquals(usd(83), usd(1010).multiply(bps825, MoneyRounding.HALF_EVEN))

        // 1030 * 825 / 10000 = 84.975
        assertEquals(usd(85), usd(1030).multiply(bps825, MoneyRounding.HALF_EVEN))

        // exact: 8% of 1000 = 80, no rounding involved
        assertEquals(usd(80), usd(1000).multiply(Ratio.percent(8), MoneyRounding.HALF_EVEN))
    }

    @Test
    fun `multiply negative amount rounds toward the correct direction`() {
        // -1000 * 825 / 10000 = -82.5
        val bps825 = Ratio.basisPoints(825)
        assertEquals(usd(-83), usd(-1000).multiply(bps825, MoneyRounding.DOWN)) // floor
        assertEquals(usd(-82), usd(-1000).multiply(bps825, MoneyRounding.UP)) // ceiling
        assertEquals(usd(-82), usd(-1000).multiply(bps825, MoneyRounding.HALF_EVEN))
    }

    // ---- overflow ----

    @Test
    fun `checked arithmetic does not wrap`() {
        val max = usd(Long.MAX_VALUE)
        assertFailsWith<ArithmeticException> { max + usd(1) }
        assertFailsWith<ArithmeticException> { usd(Long.MIN_VALUE) - usd(1) }
        assertFailsWith<ArithmeticException> { -usd(Long.MIN_VALUE) }
        assertFailsWith<ArithmeticException> { max.allocate(listOf(2, 3)) } // amount * weight overflows
        assertFailsWith<ArithmeticException> { max.multiply(Ratio(2, 1), MoneyRounding.DOWN) }
    }
}
