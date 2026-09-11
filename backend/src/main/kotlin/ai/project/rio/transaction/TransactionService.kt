package ai.project.rio.transaction

import ai.project.rio.http.NotFoundException
import ai.project.rio.http.ValidationException
import ai.project.rio.money.Money
import java.time.Clock
import java.time.Instant
import java.util.UUID

/** Business rules for transactions. HTTP parsing happens before this layer; SQL happens after it. */
class TransactionService(
    private val repository: TransactionRepository,
    private val clock: Clock = Clock.systemUTC(),
) {

    fun list(): List<Transaction> = repository.findAll()

    fun get(id: String): Transaction =
        repository.findById(id) ?: throw NotFoundException("transaction $id not found")

    fun create(description: String, amount: Money, type: TransactionType): Transaction {
        if (description.isBlank()) throw ValidationException("description must not be blank")
        if (!amount.isPositive) throw ValidationException("amount must be positive")

        val transaction = Transaction(
            id = UUID.randomUUID().toString(),
            description = description.trim(),
            amount = amount,
            type = type,
            status = TransactionStatus.COMPLETED,
            createdAt = Instant.now(clock),
        )
        repository.insert(transaction)
        return transaction
    }
}
