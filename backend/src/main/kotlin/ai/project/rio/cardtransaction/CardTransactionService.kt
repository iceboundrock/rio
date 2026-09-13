package ai.project.rio.cardtransaction

import ai.project.rio.db.JdbcTemplate
import ai.project.rio.db.TransactionalService
import ai.project.rio.http.NotFoundException
import ai.project.rio.http.EcmaScript
import ai.project.rio.http.ValidationException
import ai.project.rio.http.atItemIndex
import ai.project.rio.money.Money
import java.time.Clock
import java.time.Instant
import java.util.UUID

/** Business rules for card transactions. HTTP parsing happens before this layer; SQL happens after it. */
class CardTransactionService(
    jdbc: JdbcTemplate,
    private val clock: Clock = Clock.systemUTC(),
) : TransactionalService(jdbc) {

    /** Standalone reads; each call auto-commits on its own connection. */
    private val repository = CardTransactionRepository(jdbc)

    fun list(): List<CardTransaction> = repository.findAll()

    fun get(id: String): CardTransaction =
        repository.findById(id) ?: throw NotFoundException("card transaction not found")

    fun create(description: String, amount: Money, type: CardTransactionType): CardTransaction =
        create(NewCardTransaction(description, amount, type))

    /** Creates one card transaction. Validation failures carry no item index, unlike [createAll]. */
    fun create(item: NewCardTransaction): CardTransaction =
        transactional { tx -> validated(item, Instant.now(clock)).also(CardTransactionRepository(tx)::insert) }

    /**
     * Creates every item or none: all inserts share one transaction, and an invalid later item rolls
     * back the earlier ones. Items are validated inside the transaction so that guarantee needs no
     * separate pre-pass. Every item gets the same `createdAt`. A validation failure names the item
     * (`[1]: amount must be positive`), since the client cannot tell otherwise which row it was.
     */
    fun createAll(items: List<NewCardTransaction>): List<CardTransaction> {
        if (items.isEmpty()) throw ValidationException("items must not be empty")
        val createdAt = Instant.now(clock)
        return transactional { tx ->
            val scoped = CardTransactionRepository(tx)
            items.mapIndexed { index, item -> atItemIndex(index) { validated(item, createdAt) }.also(scoped::insert) }
        }
    }

    private fun validated(item: NewCardTransaction, createdAt: Instant): CardTransaction {
        // Blank means the contract's `pattern: "\S"` would fail, so whitespace is ECMAScript's set,
        // not Kotlin's; the same set is trimmed so what is stored is what the check looked at.
        val description = item.description.trim(EcmaScript::isWhitespace)
        if (description.isEmpty()) throw ValidationException("description must not be blank")
        if (!item.amount.isPositive) throw ValidationException("amount must be positive")
        return CardTransaction(
            id = UUID.randomUUID().toString(),
            description = description,
            amount = item.amount,
            type = item.type,
            status = CardTransactionStatus.COMPLETED,
            createdAt = createdAt,
        )
    }
}
