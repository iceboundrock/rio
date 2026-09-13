package ai.project.rio.cardtransaction

import ai.project.rio.http.NotFoundException
import ai.project.rio.http.ValidationException
import ai.project.rio.money.Money
import java.time.Clock
import java.time.Instant
import java.util.UUID

/** Business rules for card transactions. HTTP parsing happens before this layer; SQL happens after it. */
class CardTransactionService(
    private val repository: CardTransactionRepository,
    private val clock: Clock = Clock.systemUTC(),
) {
    fun list(): List<CardTransaction> = repository.findAll()

    fun get(id: String): CardTransaction =
        repository.findById(id) ?: throw NotFoundException("card transaction not found")

    fun create(description: String, amount: Money, type: CardTransactionType): CardTransaction {
        if (description.isBlank()) throw ValidationException("description must not be blank")
        if (!amount.isPositive) throw ValidationException("amount must be positive")

        val cardTransaction = CardTransaction(
            id = UUID.randomUUID().toString(),
            description = description.trim(),
            amount = amount,
            type = type,
            status = CardTransactionStatus.COMPLETED,
            createdAt = Instant.now(clock),
        )
        repository.insert(cardTransaction)
        return cardTransaction
    }
}
