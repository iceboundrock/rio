package ai.project.rio.money

/**
 * Supported currencies. `precision` is the number of decimal places in the
 * major unit, i.e. how many digits of a minor-unit amount sit after the point.
 *
 * USD 1250 minor units => 12.50; JPY 1250 minor units => 1250.
 */
enum class Currency(val code: String, val precision: Int) {
    BRL("BRL", 2),
    CAD("CAD", 2),
    CNY("CNY", 2),
    EUR("EUR", 2),
    JPY("JPY", 0),
    USD("USD", 2);

    companion object {
        /** Returns null for unknown or differently-cased codes. */
        fun fromCode(code: String): Currency? = entries.firstOrNull { it.code == code }
    }
}
