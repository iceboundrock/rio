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
