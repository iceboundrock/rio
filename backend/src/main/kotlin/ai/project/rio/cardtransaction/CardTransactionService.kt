package ai.project.rio.cardtransaction

import ai.project.rio.db.JdbcTemplate
import ai.project.rio.db.TransactionalService
import ai.project.rio.http.NotFoundException
import ai.project.rio.http.ValidationException
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
        createAll(listOf(NewCardTransaction(description, amount, type))).single()

    /**
     * Creates every item or none: all inserts share one transaction, and an invalid later item rolls
     * back the earlier ones. Items are validated inside the transaction so that guarantee needs no
     * separate pre-pass. Every item gets the same `createdAt`.
     */
    fun createAll(items: List<NewCardTransaction>): List<CardTransaction> {
        if (items.isEmpty()) throw ValidationException("items must not be empty")
        val createdAt = Instant.now(clock)
        return transactional { tx ->
            val scoped = CardTransactionRepository(tx)
            items.map { item -> validated(item, createdAt).also(scoped::insert) }
        }
    }

    private fun validated(item: NewCardTransaction, createdAt: Instant): CardTransaction {
        if (item.description.isBlank()) throw ValidationException("description must not be blank")
        if (!item.amount.isPositive) throw ValidationException("amount must be positive")
        return CardTransaction(
            id = UUID.randomUUID().toString(),
            description = item.description.trim(),
            amount = item.amount,
            type = item.type,
            status = CardTransactionStatus.COMPLETED,
            createdAt = createdAt,
        )
    }
}
