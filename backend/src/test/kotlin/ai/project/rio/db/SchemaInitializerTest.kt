package ai.project.rio.db

import ai.project.rio.cardtransaction.CardTransactionRepository
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SchemaInitializerTest {
    private lateinit var dbFile: Path
    private lateinit var jdbc: JdbcTemplate

    @BeforeTest
    fun setUp() {
        dbFile = Files.createTempFile("schema-initializer-test", ".db")
        jdbc = Database.open(dbFile)
    }

    @AfterTest
    fun tearDown() {
        Files.deleteIfExists(dbFile)
    }

    private fun tableNames(): List<String> =
        jdbc.query("SELECT name FROM sqlite_master WHERE type = ? ORDER BY name", listOf("table")) { it.getString("name") }

    private fun storedDdl(table: String = "card_transactions"): String = jdbc.queryForObject(
        "SELECT sql FROM sqlite_master WHERE type = ? AND name = ?",
        listOf("table", table),
    ) { it.getString("sql") }

    @Test
    fun `fresh schema creates the idempotency tables`() {
        SchemaInitializer.initialize(jdbc, dbFile)

        assertEquals(listOf("card_transaction_idempotency", "card_transaction_idempotency_items", "card_transactions"), tableNames())
    }

    @Test
    fun `drift in an idempotency table fails at startup and names the table`() {
        SchemaInitializer.initialize(jdbc, dbFile)
        val changedDdl = storedDdl("card_transaction_idempotency").replace("created_at TEXT NOT NULL", "created_at TEXT NOT NULL, extra TEXT")
        jdbc.execute("DROP TABLE card_transaction_idempotency_items")
        jdbc.execute("DROP TABLE card_transaction_idempotency")
        jdbc.execute(changedDdl)

        val error = assertFailsWith<IllegalStateException> { SchemaInitializer.initialize(jdbc, dbFile) }

        assertTrue(error.message!!.contains("card_transaction_idempotency"))
        assertTrue(error.message!!.contains("RIO_DB_PATH"))
        assertEquals(changedDdl, storedDdl("card_transaction_idempotency"))
    }

    @Test
    fun `database that predates the idempotency tables fails at startup without creating them`() {
        SchemaInitializer.initialize(jdbc, dbFile)
        jdbc.execute("DROP TABLE card_transaction_idempotency_items")
        jdbc.execute("DROP TABLE card_transaction_idempotency")
        CardTransactionRepository(jdbc).insert(SchemaInitializer.SEED.first())
        val before = CardTransactionRepository(jdbc).findAll()

        val error = assertFailsWith<IllegalStateException> { SchemaInitializer.initialize(Database.open(dbFile), dbFile) }

        assertTrue(error.message!!.contains("card_transaction_idempotency"))
        assertTrue(error.message!!.contains("card_transaction_idempotency_items"))
        assertTrue(error.message!!.contains(dbFile.toAbsolutePath().toString()))
        assertTrue(error.message!!.contains("RIO_DB_PATH"))
        assertTrue(error.message!!.contains("restart"))
        assertEquals(listOf("card_transactions"), tableNames())
        assertEquals(before, CardTransactionRepository(jdbc).findAll())
    }

    @Test
    fun `database missing only the items table is rejected, not completed`() {
        SchemaInitializer.initialize(jdbc, dbFile)
        jdbc.execute("DROP TABLE card_transaction_idempotency_items")

        val error = assertFailsWith<IllegalStateException> { SchemaInitializer.initialize(jdbc, dbFile) }

        assertTrue(error.message!!.contains("card_transaction_idempotency_items"))
        assertEquals(listOf("card_transaction_idempotency", "card_transactions"), tableNames())
    }

    @Test
    fun `fresh schema can be reopened without losing card transactions`() {
        SchemaInitializer.initialize(jdbc, dbFile)
        SchemaInitializer.seedIfEmpty(jdbc)
        val before = CardTransactionRepository(jdbc).findAll()

        SchemaInitializer.initialize(Database.open(dbFile), dbFile)

        assertEquals(SchemaInitializer.SEED.size, before.size)
        assertEquals(before, CardTransactionRepository(jdbc).findAll())
    }

    @Test
    fun `repository DDL formatting survives SQLite storage normalization`() {
        SchemaInitializer.initialize(jdbc, dbFile)
        val body = storedDdl().substringAfter("CREATE TABLE ")
        for (prefix in listOf("CREATE TABLE ", "create table if not exists ", "CREATE  TABLE\nIF  NOT\tEXISTS ")) {
            for (suffix in listOf("", ";", " \n", " \n; \n")) {
                val ddl = prefix + body + suffix
                jdbc.execute("DROP TABLE card_transactions")
                jdbc.execute(ddl)

                assertEquals(SchemaInitializer.canonicalDdl(ddl), SchemaInitializer.canonicalDdl(storedDdl()))
                SchemaInitializer.initialize(jdbc, dbFile)
            }
        }
    }

    @Test
    fun `same-name view fails with reset instructions and remains intact`() {
        jdbc.execute("CREATE VIEW card_transactions AS SELECT 1 AS id")

        val error = assertFailsWith<IllegalStateException> { SchemaInitializer.initialize(jdbc, dbFile) }

        assertTrue(error.message!!.contains(dbFile.toAbsolutePath().toString()))
        assertTrue(error.message!!.contains("RIO_DB_PATH"))
        assertTrue(error.message!!.contains("restart"))
        assertEquals(1, jdbc.queryForObject("SELECT id FROM card_transactions") { it.getInt("id") })
    }

    @Test
    fun `changes to literal case and whitespace are not normalized away`() {
        SchemaInitializer.initialize(jdbc, dbFile)
        val ddl = storedDdl()
        for (literal in listOf("'usd'", "' USD '")) {
            jdbc.execute("DROP TABLE card_transactions")
            val changedDdl = ddl.replace("'USD'", literal)
            jdbc.execute(changedDdl)

            assertFailsWith<IllegalStateException> { SchemaInitializer.initialize(jdbc, dbFile) }
            assertEquals(changedDdl, storedDdl())
        }
    }

    @Test
    fun `old currency constraint fails at startup with reset instructions and preserves data`() {
        SchemaInitializer.initialize(jdbc, dbFile)
        val oldDdl = storedDdl().replace("'BRL', 'CAD', 'CNY', 'EUR', 'JPY', 'USD'", "'USD', 'EUR', 'JPY'")
        jdbc.execute("DROP TABLE card_transactions")
        jdbc.execute(oldDdl)
        // Model existing data using a currency accepted by the old schema.
        CardTransactionRepository(jdbc).insert(SchemaInitializer.SEED.first())
        val before = CardTransactionRepository(jdbc).findAll()

        val error = assertFailsWith<IllegalStateException> { SchemaInitializer.initialize(jdbc, dbFile) }

        assertTrue(error.message!!.contains("schema"))
        assertTrue(error.message!!.contains(dbFile.toAbsolutePath().toString()))
        assertTrue(error.message!!.contains("RIO_DB_PATH"))
        assertTrue(error.message!!.contains("restart"))
        assertEquals(oldDdl, storedDdl())
        assertEquals(before, CardTransactionRepository(jdbc).findAll())
    }

    @Test
    fun `non-currency DDL drift is also rejected`() {
        SchemaInitializer.initialize(jdbc, dbFile)
        val changedDdl = storedDdl().replace("amount_minor > 0", "amount_minor >= 0")
        jdbc.execute("DROP TABLE card_transactions")
        jdbc.execute(changedDdl)

        assertFailsWith<IllegalStateException> { SchemaInitializer.initialize(jdbc, dbFile) }
        assertEquals(changedDdl, storedDdl())
    }
}
