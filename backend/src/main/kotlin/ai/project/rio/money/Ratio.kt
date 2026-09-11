package ai.project.rio.money

/**
 * An exact rational number used to scale Money without floating point.
 *
 * 8%    = Ratio(8, 100)
 * 8.25% = Ratio(825, 10000)
 */
data class Ratio(val numerator: Long, val denominator: Long) {
    init {
        require(denominator > 0) { "Ratio denominator must be > 0, was $denominator" }
    }

    companion object {
        /** Basis points: 1 bp = 0.01%. Ratio.basisPoints(825) == 8.25%. */
        fun basisPoints(bps: Long): Ratio = Ratio(bps, 10_000)

        fun percent(pct: Long): Ratio = Ratio(pct, 100)
    }
}
