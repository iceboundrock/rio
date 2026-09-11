package ai.project.rio.money

/** Rounding policy for operations that can produce fractional minor units. Callers must choose one. */
enum class MoneyRounding {
    /** Toward negative infinity (floor). */
    DOWN,
    /** Toward positive infinity (ceiling). */
    UP,
    /** Nearest; ties go to the even neighbour (banker's rounding). */
    HALF_EVEN,
}
