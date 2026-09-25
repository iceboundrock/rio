package ai.project.rio.cardtransaction

import ai.project.rio.db.JdbcExecutor
import ai.project.rio.money.Currency
import ai.project.rio.money.Money
import java.sql.ResultSet
import java.time.OffsetDateTime

/**
 * All card transaction SQL lives here. The repository owns the mapping between the
 * row shape (amount_minor BIGINT + currency TEXT) and the domain [Money] value.
 *
 * Pass a [ai.project.rio.db.JdbcTemplate] for standalone calls, or the executor from
 * `jdbc.withTransaction { tx -> CardTransactionRepository(tx) }` to take part in a transaction.
 */
class CardTransactionRepository(private val jdbc: JdbcExecutor) {

    private val columns = "id, description, amount_minor, currency, type, status, created_at"

    fun findAll(): List<CardTransaction> =
        jdbc.query("SELECT $columns FROM card_transactions ORDER BY created_at DESC, id DESC", mapper = ::mapCardTransaction)

    fun findById(id: String): CardTransaction? =
        jdbc.queryOne("SELECT $columns FROM card_transactions WHERE id = ?", listOf(id), ::mapCardTransaction)

    fun insert(cardTransaction: CardTransaction) {
        jdbc.update(
            """
            INSERT INTO card_transactions (id, description, amount_minor, currency, type, status, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            listOf(
                cardTransaction.id,
                cardTransaction.description,
                cardTransaction.amount.amount,
                cardTransaction.amount.currency.code,
                cardTransaction.type.name,
                cardTransaction.status.name,
                cardTransaction.createdAt,
            ),
        )
    }

    private fun mapCardTransaction(rs: ResultSet): CardTransaction = CardTransaction(
        id = rs.getString("id"),
        description = rs.getString("description"),
        amount = Money(
            amount = rs.getLong("amount_minor"),
            currency = Currency.valueOf(rs.getString("currency")),
        ),
        type = CardTransactionType.valueOf(rs.getString("type")),
        status = CardTransactionStatus.valueOf(rs.getString("status")),
        createdAt = rs.getObject("created_at", OffsetDateTime::class.java).toInstant(),
    )
}
