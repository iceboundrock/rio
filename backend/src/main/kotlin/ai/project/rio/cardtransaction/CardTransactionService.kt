package ai.project.rio.cardtransaction

import ai.project.rio.db.JdbcTemplate
import ai.project.rio.db.TransactionalService
import ai.project.rio.http.IdempotencyConflictException
import ai.project.rio.http.NotFoundException
import ai.project.rio.http.EcmaScript
import ai.project.rio.http.ValidationException
import ai.project.rio.http.atItemIndex
import ai.project.rio.money.Money
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * Business rules for card transactions. HTTP parsing happens before this layer; SQL happens after it.
 *
 * Creates are idempotent per `Idempotency-Key`: the key names one logical create operation. Every
 * item is validated and normalized first, outside any transaction, so a rejected request never
 * touches the key. The fingerprint of the normalized request is then compared against what the key
 * committed before:
 *
 *   claim the key   (INSERT ... ON CONFLICT DO NOTHING; the primary key decides between concurrent callers)
 *   claimed         -> insert the card transactions and the ordered item mapping, commit, return them
 *   already taken   -> same fingerprint and shape: return the stored card transactions (a replay)
 *                      different: IdempotencyConflictException, nothing written
 *
 * The claim is deliberately the first statement of the transaction. SQLite lets a transaction that
 * has only read wait for the writer, but a transaction that read first and then wants to write can
 * fail with SQLITE_BUSY instead of waiting; claiming first means a concurrent caller blocks on the
 * winner's commit and then sees its row. A rollback for any reason takes the claim with it.
 */
class CardTransactionService(
    jdbc: JdbcTemplate,
    private val clock: Clock = Clock.systemUTC(),
) : TransactionalService(jdbc) {

    /** Standalone reads; each call auto-commits on its own connection. */
    private val repository = CardTransactionRepository(jdbc)

    fun list(): List<CardTransaction> = repository.findAll()

    fun get(id: String): CardTransaction =
        repository.findById(id) ?: throw NotFoundException("card transaction not found")

    fun create(idempotencyKey: String, description: String, amount: Money, type: CardTransactionType): CardTransaction =
        create(idempotencyKey, NewCardTransaction(description, amount, type))

    /** Creates one card transaction. Validation failures carry no item index, unlike [createAll]. */
    fun create(idempotencyKey: String, item: NewCardTransaction): CardTransaction =
        createIdempotent(idempotencyKey, RequestShape.ONE, listOf(normalized(item))).single()

    /**
     * Creates every item or none: all inserts share one transaction with the idempotency claim, so
     * a failing later insert rolls back the earlier ones and the claim. Every item gets the same
     * `createdAt`. A validation failure names the item (`[1]: amount must be positive`), since the
     * client cannot tell otherwise which row it was. An object body and a one-element array body are
     * different request shapes: they answer differently, so the same key may not serve both.
     */
    fun createAll(idempotencyKey: String, items: List<NewCardTransaction>): List<CardTransaction> {
        if (items.isEmpty()) throw ValidationException("items must not be empty")
        val normalized = items.mapIndexed { index, item -> atItemIndex(index) { normalized(item) } }
        return createIdempotent(idempotencyKey, RequestShape.MANY, normalized)
    }

    private fun createIdempotent(idempotencyKey: String, shape: RequestShape, items: List<NewCardTransaction>): List<CardTransaction> {
        val createdAt = Instant.now(clock)
        val fingerprint = CardTransactionRequestFingerprint.of(shape, items)
        return transactional { tx ->
            val idempotency = CardTransactionIdempotencyRepository(tx)
            val transactions = CardTransactionRepository(tx)
            if (idempotency.claim(idempotencyKey, fingerprint, shape, createdAt)) {
                val created = items.map { newCardTransaction(it, createdAt) }
                created.forEach(transactions::insert)
                idempotency.insertItems(idempotencyKey, created.map { it.id })
                created
            } else {
                val record = idempotency.find(idempotencyKey)
                    ?: error("Idempotency-Key is taken but has no record; the claim and the record are one row")
                if (record.requestFingerprint != fingerprint || record.requestShape != shape) {
                    throw IdempotencyConflictException("Idempotency-Key was already used with a different request")
                }
                // Card transactions are immutable, so the stored ids rebuild the original response.
                idempotency.findCardTransactionIds(idempotencyKey).map { id ->
                    transactions.findById(id) ?: error("card transaction $id recorded for an Idempotency-Key no longer exists")
                }
            }
        }
    }

    private fun normalized(item: NewCardTransaction): NewCardTransaction {
        // Blank means the contract's `pattern: "\S"` would fail, so whitespace is ECMAScript's set,
        // not Kotlin's; the same set is trimmed so what is stored is what the check looked at.
        val description = item.description.trim(EcmaScript::isWhitespace)
        if (description.isEmpty()) throw ValidationException("description must not be blank")
        if (!item.amount.isPositive) throw ValidationException("amount must be positive")
        return item.copy(description = description)
    }

    private fun newCardTransaction(item: NewCardTransaction, createdAt: Instant): CardTransaction = CardTransaction(
        id = UUID.randomUUID().toString(),
        description = item.description,
        amount = item.amount,
        type = item.type,
        status = CardTransactionStatus.COMPLETED,
        createdAt = createdAt,
    )
}
