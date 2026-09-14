package ai.project.rio.cardtransaction

import ai.project.rio.db.JdbcExecutor
import java.time.Instant

/** Whether the create request body was one object or an array; the two answer with different shapes. */
enum class RequestShape { ONE, MANY }

/** What was committed under an Idempotency-Key: enough to tell a replay from a different request. */
data class IdempotencyRecord(val idempotencyKey: String, val requestFingerprint: String, val requestShape: RequestShape)

/**
 * SQL for the idempotency state of POST /api/card-transactions (tables in SchemaInitializer). This
 * is the card-transaction create's own bookkeeping, not a generic idempotency store.
 *
 * Pass the executor from `transactional { tx -> }` so the claim, the card transaction inserts and the
 * item mapping commit or roll back together.
 */
class CardTransactionIdempotencyRepository(private val jdbc: JdbcExecutor) {

    /**
     * Atomically takes ownership of [idempotencyKey]. The primary key decides between concurrent
     * callers: exactly one sees `true`; the others see `false` once the winner has committed, and
     * must then read what it stored.
     */
    fun claim(idempotencyKey: String, requestFingerprint: String, requestShape: RequestShape, createdAt: Instant): Boolean =
        jdbc.update(
            """
            INSERT INTO card_transaction_idempotency (idempotency_key, request_fingerprint, request_shape, created_at)
            VALUES (?, ?, ?, ?)
            ON CONFLICT(idempotency_key) DO NOTHING
            """.trimIndent(),
            listOf(idempotencyKey, requestFingerprint, requestShape.name, createdAt.toString()),
        ) == 1

    fun find(idempotencyKey: String): IdempotencyRecord? =
        jdbc.queryOne(
            "SELECT idempotency_key, request_fingerprint, request_shape FROM card_transaction_idempotency WHERE idempotency_key = ?",
            listOf(idempotencyKey),
        ) { rs ->
            IdempotencyRecord(
                idempotencyKey = rs.getString("idempotency_key"),
                requestFingerprint = rs.getString("request_fingerprint"),
                requestShape = RequestShape.valueOf(rs.getString("request_shape")),
            )
        }

    /** Records the created card transactions in request order; `item_index` is the list position. */
    fun insertItems(idempotencyKey: String, cardTransactionIds: List<String>) {
        jdbc.batchUpdate(
            "INSERT INTO card_transaction_idempotency_items (idempotency_key, item_index, card_transaction_id) VALUES (?, ?, ?)",
            cardTransactionIds.mapIndexed { index, id -> listOf(idempotencyKey, index, id) },
        )
    }

    fun findCardTransactionIds(idempotencyKey: String): List<String> =
        jdbc.query(
            "SELECT card_transaction_id FROM card_transaction_idempotency_items WHERE idempotency_key = ? ORDER BY item_index",
            listOf(idempotencyKey),
        ) { it.getString("card_transaction_id") }
}
