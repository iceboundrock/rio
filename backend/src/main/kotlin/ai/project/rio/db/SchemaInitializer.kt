package ai.project.rio.db

import ai.project.rio.money.Currency
import ai.project.rio.money.Money
import ai.project.rio.cardtransaction.CardTransaction
import ai.project.rio.cardtransaction.CardTransactionRepository
import ai.project.rio.cardtransaction.CardTransactionStatus
import ai.project.rio.cardtransaction.CardTransactionType
import java.nio.file.Path
import java.time.Instant

/**
 * Creates the tables on a fresh database and refuses to serve any other file whose stored DDL does not
 * match. There is no migration tool: a file that predates a table is not completed, it is reset (edit
 * the DDL and delete the DB file).
 */
object SchemaInitializer {

    private val CREATE_CARD_TRANSACTIONS = """
        CREATE TABLE card_transactions (
            id TEXT PRIMARY KEY,
            description TEXT NOT NULL CHECK (length(trim(description)) > 0),
            amount_minor INTEGER NOT NULL CHECK (amount_minor > 0),
            currency TEXT NOT NULL CHECK (currency IN ('BRL', 'CAD', 'CNY', 'EUR', 'JPY', 'USD')),
            type TEXT NOT NULL CHECK (type IN ('CREDIT', 'DEBIT')),
            status TEXT NOT NULL CHECK (status IN ('PENDING', 'COMPLETED', 'DECLINED')),
            created_at TEXT NOT NULL
        )
    """.trimIndent()

    // One row per Idempotency-Key ever committed for POST /api/card-transactions. The primary key is
    // what decides ownership of a key between concurrent requests (CardTransactionService). Keys never
    // expire: they live as long as this database file.
    private val CREATE_CARD_TRANSACTION_IDEMPOTENCY = """
        CREATE TABLE card_transaction_idempotency (
            idempotency_key TEXT PRIMARY KEY,
            request_fingerprint TEXT NOT NULL,
            request_shape TEXT NOT NULL CHECK (request_shape IN ('ONE', 'MANY')),
            created_at TEXT NOT NULL
        )
    """.trimIndent()

    // The ordered card transactions a key created, so a replay can rebuild the original response.
    private val CREATE_CARD_TRANSACTION_IDEMPOTENCY_ITEMS = """
        CREATE TABLE card_transaction_idempotency_items (
            idempotency_key TEXT NOT NULL REFERENCES card_transaction_idempotency(idempotency_key) ON DELETE CASCADE,
            item_index INTEGER NOT NULL CHECK (item_index >= 0),
            card_transaction_id TEXT NOT NULL REFERENCES card_transactions(id),
            PRIMARY KEY (idempotency_key, item_index)
        )
    """.trimIndent()

    /** Creation order matters: the item table references both others. */
    private val TABLES = listOf(
        "card_transactions" to CREATE_CARD_TRANSACTIONS,
        "card_transaction_idempotency" to CREATE_CARD_TRANSACTION_IDEMPOTENCY,
        "card_transaction_idempotency_items" to CREATE_CARD_TRANSACTION_IDEMPOTENCY_ITEMS,
    )

    /** Names the exact file to delete: the default is relative to the working directory, and RIO_DB_PATH overrides it. */
    private fun resetInstructions(dbPath: Path): String =
        "Stop the backend and delete ${dbPath.toAbsolutePath()} (the file configured by RIO_DB_PATH, default data/rio.db), " +
            "then restart. This resets local card transactions to demo data; back up data you need first."

    fun initialize(jdbc: JdbcTemplate, dbPath: Path) {
        val resetInstructions = resetInstructions(dbPath)
        // Decide fresh vs existing before running any DDL: creating a missing table in a file that already
        // has the others would be a silent migration of a pre-existing database.
        if (storedDefinitions(jdbc).isEmpty()) {
            jdbc.withTransaction { tx -> TABLES.forEach { (_, ddl) -> tx.execute(ddl) } }
        }
        val stored = storedDefinitions(jdbc)
        val missing = TABLES.map { it.first }.filter { it !in stored }
        check(missing.isEmpty()) {
            "Database is missing table(s) ${missing.joinToString()}: it predates the current schema and is " +
                "not migrated. $resetInstructions"
        }
        for ((table, ddl) in TABLES) {
            val actual = stored.getValue(table)
            check(actual != null && canonicalDdl(actual) == canonicalDdl(ddl)) {
                "Stored $table schema does not match the current definition. $resetInstructions"
            }
        }
    }

    /** Expected names present in sqlite_master, mapped to their DDL, or null when the name is not a table. */
    private fun storedDefinitions(jdbc: JdbcTemplate): Map<String, String?> = jdbc.query(
        "SELECT name, type, sql FROM sqlite_master WHERE name IN (${TABLES.joinToString { "?" }})",
        TABLES.map { it.first },
    ) { row -> row.getString("name") to row.getString("sql").takeIf { row.getString("type") == "table" } }.toMap()

    // Normalize SQLite's CREATE prefix and statement terminator only. Preserve the body exactly,
    // especially quoted literals: this is a reset-only drift guard, not SQL semantic equivalence.
    internal fun canonicalDdl(ddl: String): String = ddl.trim().removeSuffix(";").trimEnd()
        .replaceFirst(
            Regex("^CREATE\\s+TABLE\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?", RegexOption.IGNORE_CASE),
            "CREATE TABLE ",
        )

    /** Inserts fixed demo rows if the table is empty. Deterministic so tests and demos match. */
    fun seedIfEmpty(jdbc: JdbcTemplate) {
        val repository = CardTransactionRepository(jdbc)
        if (repository.findAll().isNotEmpty()) return
        jdbc.withTransaction { tx -> SEED.forEach { CardTransactionRepository(tx).insert(it) } }
    }

    private fun seed(
        id: String, description: String, amount: Long, currency: Currency,
        type: CardTransactionType, status: CardTransactionStatus, createdAt: String,
    ) = CardTransaction(
        id = id,
        description = description,
        amount = Money(amount, currency),
        type = type,
        status = status,
        createdAt = Instant.parse(createdAt),
    )

    val SEED: List<CardTransaction> = listOf(
        seed("seed-0001", "Payroll deposit", 350_000, Currency.USD, CardTransactionType.CREDIT, CardTransactionStatus.COMPLETED, "2026-09-01T09:00:00Z"),
        seed("seed-0002", "Blue Bottle Coffee", 525, Currency.USD, CardTransactionType.DEBIT, CardTransactionStatus.COMPLETED, "2026-09-02T15:30:00Z"),
        seed("seed-0003", "Berlin office supplies", 8_990, Currency.EUR, CardTransactionType.DEBIT, CardTransactionStatus.COMPLETED, "2026-09-03T11:45:00Z"),
        seed("seed-0004", "Tokyo client dinner", 24_800, Currency.JPY, CardTransactionType.DEBIT, CardTransactionStatus.PENDING, "2026-09-04T12:15:00Z"),
        seed("seed-0005", "Refund: duplicate charge", 12_000, Currency.USD, CardTransactionType.CREDIT, CardTransactionStatus.PENDING, "2026-09-05T08:05:00Z"),
        seed("seed-0006", "Unrecognized vendor", 99_999, Currency.USD, CardTransactionType.DEBIT, CardTransactionStatus.DECLINED, "2026-09-06T22:10:00Z"),
        seed("seed-0007", "Cloud hosting", 145_000, Currency.USD, CardTransactionType.DEBIT, CardTransactionStatus.COMPLETED, "2026-09-07T00:00:00Z"),
        seed("seed-0008", "EUR invoice paid by client", 210_050, Currency.EUR, CardTransactionType.CREDIT, CardTransactionStatus.COMPLETED, "2026-09-08T16:20:00Z"),
        seed("seed-0009", "São Paulo office supplies", 15_990, Currency.BRL, CardTransactionType.DEBIT, CardTransactionStatus.COMPLETED, "2026-09-09T14:00:00Z"),
    )
}
