package ai.project.rio.cardtransaction

import java.security.MessageDigest

/**
 * Deterministic identity of a validated create request, used to tell a replay of an Idempotency-Key
 * from its reuse for a different request. Computed from domain values only: ids and timestamps are
 * not inputs, and JSON member order or whitespace never reach it. Callers pass normalized items
 * (trimmed descriptions); this object does not normalize.
 *
 * Canonical text: the shape on the first line, then one line per item in request order,
 * `<description length>:<description>|<minor units>|<currency>|<type>`. The length prefix keeps a
 * description containing `|` or a newline from spelling another item. SHA-256 over the UTF-8 bytes.
 */
object CardTransactionRequestFingerprint {

    fun of(shape: RequestShape, items: List<NewCardTransaction>): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(canonical(shape, items).toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    internal fun canonical(shape: RequestShape, items: List<NewCardTransaction>): String = buildString {
        append(shape.name)
        for (item in items) {
            append('\n')
            append(item.description.length).append(':').append(item.description)
            append('|').append(item.amount.amount)
            append('|').append(item.amount.currency.code)
            append('|').append(item.type.name)
        }
    }
}
