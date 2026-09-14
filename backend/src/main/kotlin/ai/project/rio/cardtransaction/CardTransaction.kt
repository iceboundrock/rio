package ai.project.rio.cardtransaction

import ai.project.rio.money.Money
import java.time.Instant

enum class CardTransactionType { CREDIT, DEBIT }

enum class CardTransactionStatus { PENDING, COMPLETED, DECLINED }

/**
 * `amount` is a magnitude and is always > 0 for persisted card transactions.
 * Direction (money in vs. money out) comes from `type`, never from the sign of the amount.
 */
data class CardTransaction(
    val id: String,
    val description: String,
    val amount: Money,
    val type: CardTransactionType,
    val status: CardTransactionStatus,
    val createdAt: Instant,
)

/**
 * Whether a create request was one object or an array of them. The two answer with different
 * response shapes, so the shape is part of what an Idempotency-Key identifies.
 */
enum class RequestShape { ONE, MANY }

/** What a caller supplies to create a card transaction; the service assigns id, status, and createdAt. */
data class NewCardTransaction(
    val description: String,
    val amount: Money,
    val type: CardTransactionType,
)
