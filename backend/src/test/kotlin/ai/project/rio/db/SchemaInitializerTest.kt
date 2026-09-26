package ai.project.rio.db

import ai.project.rio.cardtransaction.CardTransactionRepository
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SchemaInitializerTest {
    private lateinit var db: PostgresTestDatabase
    private lateinit var jdbc: JdbcTemplate

    @BeforeTest
    fun setUp() {
        db = PostgresTestDatabase.create()
        jdbc = db.open()
    }

    @AfterTest
    fun tearDown() {
        db.close()
    }

    private fun tableNames(): List<String> =
        jdbc.query("SELECT tablename FROM pg_tables WHERE schemaname = 'public' ORDER BY tablename") { it.getString("tablename") }

    /** Every relation in `public`, indexes included, so a start that created anything shows up. */
    private fun relations(): List<String> =
        jdbc.query("SELECT relname FROM pg_class WHERE relnamespace = 'public'::regnamespace ORDER BY relname") { it.getString("relname") }

    private fun columns(table: String): List<String> = jdbc.query(
        "SELECT column_name, data_type FROM information_schema.columns WHERE table_schema = 'public' AND table_name = ? ORDER BY ordinal_position",
        listOf(table),
    ) { "${it.getString("column_name")} ${it.getString("data_type")}" }

    private fun scratchSchemas(): List<String> =
        jdbc.query("SELECT nspname FROM pg_namespace WHERE nspname LIKE 'rio\\_schema\\_check\\_%'") { it.getString("nspname") }

    private fun ids(): List<String> = jdbc.query("SELECT id FROM card_transactions ORDER BY id") { it.getString("id") }

    /** Starts as the application does; afterwards no scratch schema may remain. */
    private fun initialize(on: JdbcTemplate = jdbc) {
        try {
            SchemaInitializer.initialize(on, db.address)
        } finally {
            assertEquals(emptyList(), scratchSchemas())
        }
    }

    /** Asserts the start is refused with the reset instructions and returns the message. */
    private fun assertRefused(vararg mentions: String, on: JdbcTemplate = jdbc): String {
        val message = assertFailsWith<IllegalStateException> { initialize(on) }.message!!
        mentions.forEach { assertContains(message, it) }
        assertContains(message, "recreate database ${db.address.database} on ${db.address.servers}")
        assertContains(message, "docker volume rm rio-postgres-data")
        return message
    }

    /** Swaps the CHECK on one column of `card_transactions`, which PostgreSQL named `card_transactions_<column>_check`. */
    private fun replaceCheck(column: String, check: String) {
        jdbc.execute("ALTER TABLE card_transactions DROP CONSTRAINT card_transactions_${column}_check")
        jdbc.execute("ALTER TABLE card_transactions ADD CONSTRAINT card_transactions_${column}_check CHECK ($check)")
    }

    @Test
    fun `fresh database creates every table with 64-bit amounts and timestamptz`() {
        initialize()

        assertEquals(listOf("card_transaction_idempotency", "card_transaction_idempotency_items", "card_transactions"), tableNames())
        assertEquals(
            listOf(
                "id text", "description text", "amount_minor bigint", "currency text", "type text", "status text",
                "created_at timestamp with time zone",
            ),
            columns("card_transactions"),
        )
        assertEquals("created_at timestamp with time zone", columns("card_transaction_idempotency").last())
        assertEquals(
            5,
            jdbc.queryForObject("SELECT count(*) FROM pg_constraint WHERE conrelid = 'card_transactions'::regclass AND contype = 'c'") { it.getInt(1) },
        )
    }

    @Test
    fun `tables created by a previous start are accepted and nothing is created`() {
        initialize()
        SchemaInitializer.seedIfEmpty(jdbc)
        val before = CardTransactionRepository(jdbc).findAll()
        val relationsBefore = relations()

        // A new pool, as a restarted backend has; the live side is read on its normal search_path.
        initialize(db.open())

        assertEquals(SchemaInitializer.SEED.size, before.size)
        assertEquals(before, CardTransactionRepository(jdbc).findAll())
        assertEquals(relationsBefore, relations())
    }

    @Test
    fun `a dropped column fails at startup and the table keeps its rows`() {
        initialize()
        SchemaInitializer.seedIfEmpty(jdbc)
        jdbc.execute("ALTER TABLE card_transactions DROP COLUMN status")
        val idsBefore = ids()
        val columnsBefore = columns("card_transactions")

        assertRefused("card_transactions", "does not match")

        assertEquals(idsBefore, ids())
        assertEquals(columnsBefore, columns("card_transactions"))
    }

    @Test
    fun `drift in an idempotency table fails at startup and names the table`() {
        initialize()
        jdbc.execute("ALTER TABLE card_transaction_idempotency ADD COLUMN extra TEXT")

        assertRefused("card_transaction_idempotency")

        assertEquals("extra text", columns("card_transaction_idempotency").last())
    }

    @Test
    fun `database that predates the idempotency tables fails at startup without creating them`() {
        initialize()
        jdbc.execute("DROP TABLE card_transaction_idempotency_items")
        jdbc.execute("DROP TABLE card_transaction_idempotency")
        CardTransactionRepository(jdbc).insert(SchemaInitializer.SEED.first())
        val before = CardTransactionRepository(jdbc).findAll()

        assertRefused("card_transaction_idempotency", "card_transaction_idempotency_items", "not migrated", on = db.open())

        assertEquals(listOf("card_transactions"), tableNames())
        assertEquals(before, CardTransactionRepository(jdbc).findAll())
    }

    @Test
    fun `database missing only the items table is rejected, not completed`() {
        initialize()
        jdbc.execute("DROP TABLE card_transaction_idempotency_items")

        assertRefused("card_transaction_idempotency_items")

        assertEquals(listOf("card_transaction_idempotency", "card_transactions"), tableNames())
    }

    /** Both sides are PostgreSQL's rendering, so how the DDL was written cannot cause a false mismatch. */
    @Test
    fun `DDL formatting, type spellings and constraint names do not cause a false mismatch`() {
        jdbc.execute(
            """
            create table if not exists card_transactions (
              id text constraint my_own_pk_name primary key,
              description   text not null check (length(TRIM(BOTH FROM description)) > 0),
              amount_minor int8 not null check (amount_minor > 0),
              currency text not null check (currency = any (array['BRL', 'CAD', 'CNY', 'EUR', 'JPY', 'USD'])),
              type text not null check (type in ('CREDIT','DEBIT')),
              status text not null check (status in ('PENDING', 'COMPLETED', 'DECLINED')),
              created_at timestamp with time zone not null
            );
            CREATE TABLE card_transaction_idempotency (idempotency_key TEXT PRIMARY KEY, request_fingerprint TEXT NOT NULL,
              request_shape TEXT NOT NULL CHECK (request_shape IN ('ONE', 'MANY')), created_at TIMESTAMPTZ NOT NULL);
            CREATE TABLE card_transaction_idempotency_items (
              idempotency_key TEXT NOT NULL REFERENCES public.card_transaction_idempotency ON DELETE CASCADE,
              item_index INT4 NOT NULL CHECK (item_index >= 0),
              card_transaction_id TEXT NOT NULL,
              PRIMARY KEY (idempotency_key, item_index),
              FOREIGN KEY (card_transaction_id) REFERENCES card_transactions (id)
            )
            """.trimIndent(),
        )

        initialize()

        assertEquals(listOf("card_transaction_idempotency", "card_transaction_idempotency_items", "card_transactions"), tableNames())
    }

    @Test
    fun `same-name view fails with reset instructions and remains intact`() {
        jdbc.execute("CREATE VIEW card_transactions AS SELECT 1 AS id")

        assertRefused("card_transactions")

        assertEquals(1, jdbc.queryForObject("SELECT id FROM card_transactions") { it.getInt("id") })
        assertEquals(emptyList(), tableNames())
    }

    @Test
    fun `changes to literal case and whitespace are not normalized away`() {
        initialize()
        for (literal in listOf("'usd'", "' USD '")) {
            replaceCheck("currency", "currency IN ('BRL', 'CAD', 'CNY', 'EUR', 'JPY', $literal)")

            assertRefused("card_transactions")

            // Put the table back as the DDL has it, so the next literal starts from a matching schema.
            replaceCheck("currency", "currency IN ('BRL', 'CAD', 'CNY', 'EUR', 'JPY', 'USD')")
            initialize()
        }
    }

    @Test
    fun `old currency constraint fails at startup with reset instructions and preserves data`() {
        initialize()
        // Model existing data using a currency accepted by the old schema.
        CardTransactionRepository(jdbc).insert(SchemaInitializer.SEED.first())
        replaceCheck("currency", "currency IN ('USD', 'EUR', 'JPY')")
        val before = CardTransactionRepository(jdbc).findAll()

        assertRefused("card_transactions", "schema")

        assertEquals(before, CardTransactionRepository(jdbc).findAll())
        assertContains(
            jdbc.queryForObject("SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname = 'card_transactions_currency_check'") { it.getString(1) },
            "'JPY'::text])",
        )
    }

    @Test
    fun `non-currency DDL drift is also rejected`() {
        initialize()
        replaceCheck("amount_minor", "amount_minor >= 0")

        assertRefused("card_transactions")
    }

    /** None of these changes what Rio reads or writes; tests and the CDC setup add some of them. */
    @Test
    fun `indexes, triggers and replica identity are not drift`() {
        initialize()
        jdbc.execute("CREATE INDEX card_transactions_currency_idx ON card_transactions (currency)")
        jdbc.execute("CREATE FUNCTION noop() RETURNS trigger LANGUAGE plpgsql AS 'BEGIN RETURN NEW; END'")
        jdbc.execute("CREATE TRIGGER noop BEFORE INSERT ON card_transactions FOR EACH ROW EXECUTE FUNCTION noop()")
        jdbc.execute("ALTER TABLE card_transactions REPLICA IDENTITY FULL")

        initialize(db.open())
    }
}
