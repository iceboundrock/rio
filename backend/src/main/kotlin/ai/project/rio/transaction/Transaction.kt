package ai.project.rio.transaction

import ai.project.rio.money.Money
import java.time.Instant

enum class TransactionType { CREDIT, DEBIT }

enum class TransactionStatus { PENDING, COMPLETED, DECLINED }

/**
 * `amount` is a magnitude and is always > 0 for persisted transactions.
 * Direction (money in vs. money out) comes from `type`, never from the sign of the amount.
 */
data class Transaction(
    val id: String,
    val description: String,
    val amount: Money,
    val type: TransactionType,
    val status: TransactionStatus,
    val createdAt: Instant,
)
