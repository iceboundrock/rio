package ai.project.rio.transaction

import ai.project.rio.db.JdbcExecutor
import ai.project.rio.money.Currency
import ai.project.rio.money.Money
import java.sql.ResultSet
import java.time.Instant

/**
 * All transaction SQL lives here. The repository owns the mapping between the
 * SQLite row shape (amount_minor INTEGER + currency TEXT) and the domain [Money] value.
 *
 * Pass a [ai.project.rio.db.JdbcTemplate] for standalone calls, or the executor from
 * `jdbc.transaction { tx -> TransactionRepository(tx) }` to take part in a transaction.
 */
class TransactionRepository(private val jdbc: JdbcExecutor) {

    private val columns = "id, description, amount_minor, currency, type, status, created_at"

    fun findAll(): List<Transaction> =
        jdbc.query("SELECT $columns FROM transactions ORDER BY created_at DESC, id DESC", mapper = ::mapTransaction)

    fun findById(id: String): Transaction? =
        jdbc.queryOne("SELECT $columns FROM transactions WHERE id = ?", listOf(id), ::mapTransaction)

    fun insert(transaction: Transaction) {
        jdbc.update(
            """
            INSERT INTO transactions (id, description, amount_minor, currency, type, status, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            listOf(
                transaction.id,
                transaction.description,
                transaction.amount.amount,
                transaction.amount.currency.code,
                transaction.type.name,
                transaction.status.name,
                transaction.createdAt.toString(),
            ),
        )
    }

    private fun mapTransaction(rs: ResultSet): Transaction = Transaction(
        id = rs.getString("id"),
        description = rs.getString("description"),
        amount = Money(
            amount = rs.getLong("amount_minor"),
            currency = Currency.valueOf(rs.getString("currency")),
        ),
        type = TransactionType.valueOf(rs.getString("type")),
        status = TransactionStatus.valueOf(rs.getString("status")),
        createdAt = Instant.parse(rs.getString("created_at")),
    )
}
