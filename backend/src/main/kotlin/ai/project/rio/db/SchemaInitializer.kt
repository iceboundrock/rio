package ai.project.rio.db

import ai.project.rio.money.Currency
import ai.project.rio.money.Money
import ai.project.rio.cardtransaction.CardTransaction
import ai.project.rio.cardtransaction.CardTransactionRepository
import ai.project.rio.cardtransaction.CardTransactionStatus
import ai.project.rio.cardtransaction.CardTransactionType
import java.time.Instant
import java.util.UUID

/**
 * Owns the table DDL. Creates the tables on a fresh database and refuses to serve any other whose
 * tables do not match it. There is no migration tool: a database that predates a table is not
 * completed, it is reset (edit the DDL and recreate the database).
 */
object SchemaInitializer {

    private val CREATE_CARD_TRANSACTIONS = """
        CREATE TABLE card_transactions (
            id TEXT PRIMARY KEY,
            description TEXT NOT NULL CHECK (length(trim(description)) > 0),
            amount_minor BIGINT NOT NULL CHECK (amount_minor > 0),
            currency TEXT NOT NULL CHECK (currency IN ('BRL', 'CAD', 'CNY', 'EUR', 'JPY', 'USD')),
            type TEXT NOT NULL CHECK (type IN ('CREDIT', 'DEBIT')),
            status TEXT NOT NULL CHECK (status IN ('PENDING', 'COMPLETED', 'DECLINED')),
            created_at TIMESTAMPTZ NOT NULL
        )
    """.trimIndent()

    // One row per Idempotency-Key ever committed for POST /api/card-transactions. The primary key is
    // what decides ownership of a key between concurrent requests (CardTransactionService). Keys never
    // expire: they live as long as this database.
    private val CREATE_CARD_TRANSACTION_IDEMPOTENCY = """
        CREATE TABLE card_transaction_idempotency (
            idempotency_key TEXT PRIMARY KEY,
            request_fingerprint TEXT NOT NULL,
            request_shape TEXT NOT NULL CHECK (request_shape IN ('ONE', 'MANY')),
            created_at TIMESTAMPTZ NOT NULL
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

    /** Names the database as `DB_URL` was parsed, never the URL itself or the password. */
    private fun resetInstructions(address: DatabaseAddress): String =
        "Stop the backend and recreate database ${address.database} on ${address.servers} (for the ./start.sh container: " +
            "docker rm -f rio-postgres && docker volume rm rio-postgres-data, then ./start.sh). " +
            "This resets local card transactions to demo data; back up data you need first."

    fun initialize(jdbc: JdbcTemplate, address: DatabaseAddress) {
        val resetInstructions = resetInstructions(address)
        // Decide fresh vs existing before running any DDL: creating a missing table in a database that
        // already has the others would be a silent migration of it.
        if (describe(jdbc).isEmpty()) {
            jdbc.withTransaction { tx -> TABLES.forEach { (_, ddl) -> tx.execute(ddl) } }
        }
        val live = describe(jdbc)
        val expected = expectedDescriptions(jdbc)
        // What is there is checked first, so a same-name view is reported as itself rather than as the
        // tables that are missing beside it.
        for ((table, _) in TABLES) {
            val description = live[table] ?: continue
            check(description == expected.getValue(table)) {
                "Stored $table schema does not match the current definition. $resetInstructions"
            }
        }
        val missing = TABLES.map { it.first }.filter { it !in live }
        check(missing.isEmpty()) {
            "Database is missing table(s) ${missing.joinToString()}: it predates the current schema and is " +
                "not migrated. $resetInstructions"
        }
    }

    /**
     * What the guard compares for one relation. Indexes that back no constraint, triggers, grants,
     * replica identity and constraint names are left out on purpose: none of them changes what Rio
     * reads or writes, and tests and the CDC setup add some of them.
     */
    private data class TableDescription(val kind: String, val columns: List<ColumnDescription>, val constraints: List<String>)

    private data class ColumnDescription(val name: String, val type: String, val notNull: Boolean, val default: String?)

    /**
     * PostgreSQL's own rendering of [TABLES], so DDL formatting and PostgreSQL's normalization (`IN`
     * stored as `= ANY (ARRAY[...])`) can never cause a false mismatch. The DDL runs in a scratch
     * schema that is alone on the search_path, is described there, and is rolled back. The role Rio
     * connects as therefore needs `CREATE` on the database, as the local container's `rio` has.
     */
    private fun expectedDescriptions(jdbc: JdbcTemplate): Map<String, TableDescription> = jdbc.withRollback { tx ->
        // Generated here, never taken from input, so it is safe to put into the DDL.
        val scratch = "rio_schema_check_" + UUID.randomUUID().toString().replace("-", "")
        tx.execute("CREATE SCHEMA $scratch")
        tx.execute("SET LOCAL search_path TO $scratch")
        TABLES.forEach { (_, ddl) -> tx.execute(ddl) }
        describe(tx)
    }

    /**
     * The expected names present in the current schema, as relations of any kind, mapped to their
     * description. `pg_get_constraintdef` and `pg_get_expr` schema-qualify every name the current
     * search_path does not resolve, so each side must be read while bare names resolve to its own
     * tables: the live side on the connection's normal search_path, never inside the scratch
     * transaction, where its foreign keys would render as `REFERENCES public.card_transactions(id)`.
     */
    private fun describe(jdbc: JdbcExecutor): Map<String, TableDescription> {
        val names = TABLES.map { it.first }
        val ofExpectedNames = """
            JOIN pg_namespace n ON n.oid = c.relnamespace
            WHERE n.nspname = current_schema() AND c.relname IN (${names.joinToString { "?" }})
        """.trimIndent()
        val kinds = jdbc.query("SELECT c.relname, c.relkind FROM pg_class c $ofExpectedNames", names) {
            it.getString("relname") to it.getString("relkind")
        }.toMap()
        val columns = jdbc.query(
            """
            SELECT c.relname, a.attname, format_type(a.atttypid, a.atttypmod) AS type, a.attnotnull,
                   pg_get_expr(d.adbin, d.adrelid) AS default_expr
            FROM pg_attribute a
            LEFT JOIN pg_attrdef d ON d.adrelid = a.attrelid AND d.adnum = a.attnum
            JOIN pg_class c ON c.oid = a.attrelid
            $ofExpectedNames AND a.attnum > 0 AND NOT a.attisdropped
            ORDER BY c.relname, a.attnum
            """.trimIndent(),
            names,
        ) { rs ->
            rs.getString("relname") to ColumnDescription(
                name = rs.getString("attname"),
                type = rs.getString("type"),
                notNull = rs.getBoolean("attnotnull"),
                default = rs.getString("default_expr"),
            )
        }.groupBy({ it.first }, { it.second })
        val constraints = jdbc.query(
            "SELECT c.relname, pg_get_constraintdef(k.oid) AS definition FROM pg_constraint k JOIN pg_class c ON c.oid = k.conrelid $ofExpectedNames",
            names,
        ) { it.getString("relname") to it.getString("definition") }.groupBy({ it.first }, { it.second })
        return kinds.mapValues { (name, kind) ->
            TableDescription(kind, columns[name].orEmpty(), constraints[name].orEmpty().sorted())
        }
    }

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
