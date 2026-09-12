package ai.project.rio.db

import ai.project.rio.money.Currency
import ai.project.rio.money.Money
import ai.project.rio.transaction.Transaction
import ai.project.rio.transaction.TransactionRepository
import ai.project.rio.transaction.TransactionStatus
import ai.project.rio.transaction.TransactionType
import java.time.Instant

/** Creates tables on startup. There is no migration tool; edit the DDL and delete the DB file. */
object SchemaInitializer {

    private val CREATE_TRANSACTIONS = """
        CREATE TABLE IF NOT EXISTS transactions (
            id TEXT PRIMARY KEY,
            description TEXT NOT NULL CHECK (length(trim(description)) > 0),
            amount_minor INTEGER NOT NULL CHECK (amount_minor > 0),
            currency TEXT NOT NULL CHECK (currency IN ('BRL', 'CAD', 'CNY', 'EUR', 'JPY', 'USD')),
            type TEXT NOT NULL CHECK (type IN ('CREDIT', 'DEBIT')),
            status TEXT NOT NULL CHECK (status IN ('PENDING', 'COMPLETED', 'DECLINED')),
            created_at TEXT NOT NULL
        )
    """.trimIndent()

    fun initialize(jdbc: JdbcTemplate) {
        jdbc.update(CREATE_TRANSACTIONS)
        val actual = jdbc.queryOne(
            "SELECT sql FROM sqlite_master WHERE type = ? AND name = ?",
            listOf("table", "transactions"),
        ) { it.getString("sql") }
        check(actual != null && canonicalDdl(actual) == canonicalDdl(CREATE_TRANSACTIONS)) {
            "Outdated or incompatible transactions schema. Stop the backend and delete " +
                "backend/data/rio.db (or the file configured by RIO_DB_PATH), then restart. " +
                "This resets local transactions to demo data; back up data you need first."
        }
    }

    // Normalize SQLite's CREATE prefix and statement terminator only. Preserve the body exactly,
    // especially quoted literals: this is a reset-only drift guard, not SQL semantic equivalence.
    internal fun canonicalDdl(ddl: String): String = ddl.trim().removeSuffix(";").trimEnd()
        .replaceFirst(
            Regex("^CREATE\\s+TABLE\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?", RegexOption.IGNORE_CASE),
            "CREATE TABLE ",
        )

    /** Inserts fixed demo rows if the table is empty. Deterministic so tests and demos match. */
    fun seedIfEmpty(jdbc: JdbcTemplate) {
        val repository = TransactionRepository(jdbc)
        if (repository.findAll().isNotEmpty()) return
        jdbc.transaction { tx -> SEED.forEach { TransactionRepository(tx).insert(it) } }
    }

    private fun seed(
        id: String, description: String, amount: Long, currency: Currency,
        type: TransactionType, status: TransactionStatus, createdAt: String,
    ) = Transaction(
        id = id,
        description = description,
        amount = Money(amount, currency),
        type = type,
        status = status,
        createdAt = Instant.parse(createdAt),
    )

    val SEED: List<Transaction> = listOf(
        seed("seed-0001", "Payroll deposit", 350_000, Currency.USD, TransactionType.CREDIT, TransactionStatus.COMPLETED, "2026-09-01T09:00:00Z"),
        seed("seed-0002", "Blue Bottle Coffee", 525, Currency.USD, TransactionType.DEBIT, TransactionStatus.COMPLETED, "2026-09-02T15:30:00Z"),
        seed("seed-0003", "Berlin office supplies", 8_990, Currency.EUR, TransactionType.DEBIT, TransactionStatus.COMPLETED, "2026-09-03T11:45:00Z"),
        seed("seed-0004", "Tokyo client dinner", 24_800, Currency.JPY, TransactionType.DEBIT, TransactionStatus.PENDING, "2026-09-04T12:15:00Z"),
        seed("seed-0005", "Refund: duplicate charge", 12_000, Currency.USD, TransactionType.CREDIT, TransactionStatus.PENDING, "2026-09-05T08:05:00Z"),
        seed("seed-0006", "Unrecognized vendor", 99_999, Currency.USD, TransactionType.DEBIT, TransactionStatus.DECLINED, "2026-09-06T22:10:00Z"),
        seed("seed-0007", "Cloud hosting", 145_000, Currency.USD, TransactionType.DEBIT, TransactionStatus.COMPLETED, "2026-09-07T00:00:00Z"),
        seed("seed-0008", "EUR invoice paid by client", 210_050, Currency.EUR, TransactionType.CREDIT, TransactionStatus.COMPLETED, "2026-09-08T16:20:00Z"),
    )
}
