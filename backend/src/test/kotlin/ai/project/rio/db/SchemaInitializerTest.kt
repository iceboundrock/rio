package ai.project.rio.db

import ai.project.rio.transaction.TransactionRepository
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

    private fun storedDdl(): String = jdbc.queryForObject(
        "SELECT sql FROM sqlite_master WHERE type = ? AND name = ?",
        listOf("table", "transactions"),
    ) { it.getString("sql") }

    @Test
    fun `fresh schema can be reopened without losing transactions`() {
        SchemaInitializer.initialize(jdbc)
        SchemaInitializer.seedIfEmpty(jdbc)
        val before = TransactionRepository(jdbc).findAll()

        SchemaInitializer.initialize(Database.open(dbFile))

        assertEquals(SchemaInitializer.SEED.size, before.size)
        assertEquals(before, TransactionRepository(jdbc).findAll())
    }

    @Test
    fun `repository DDL formatting survives SQLite storage normalization`() {
        SchemaInitializer.initialize(jdbc)
        val body = storedDdl().substringAfter("CREATE TABLE ")
        for (prefix in listOf("CREATE TABLE ", "create table if not exists ", "CREATE  TABLE\nIF  NOT\tEXISTS ")) {
            for (suffix in listOf("", ";", " \n", " \n; \n")) {
                val ddl = prefix + body + suffix
                jdbc.execute("DROP TABLE transactions")
                jdbc.execute(ddl)

                assertEquals(SchemaInitializer.canonicalDdl(ddl), SchemaInitializer.canonicalDdl(storedDdl()))
                SchemaInitializer.initialize(jdbc)
            }
        }
    }

    @Test
    fun `same-name view fails with reset instructions and remains intact`() {
        jdbc.execute("CREATE VIEW transactions AS SELECT 1 AS id")

        val error = assertFailsWith<IllegalStateException> { SchemaInitializer.initialize(jdbc) }

        assertTrue(error.message!!.contains("backend/data/rio.db"))
        assertTrue(error.message!!.contains("RIO_DB_PATH"))
        assertTrue(error.message!!.contains("restart"))
        assertEquals(1, jdbc.queryForObject("SELECT id FROM transactions") { it.getInt("id") })
    }

    @Test
    fun `changes to literal case and whitespace are not normalized away`() {
        SchemaInitializer.initialize(jdbc)
        val ddl = storedDdl()
        for (literal in listOf("'usd'", "' USD '")) {
            jdbc.execute("DROP TABLE transactions")
            val changedDdl = ddl.replace("'USD'", literal)
            jdbc.execute(changedDdl)

            assertFailsWith<IllegalStateException> { SchemaInitializer.initialize(jdbc) }
            assertEquals(changedDdl, storedDdl())
        }
    }

    @Test
    fun `old currency constraint fails at startup with reset instructions and preserves data`() {
        SchemaInitializer.initialize(jdbc)
        val oldDdl = storedDdl().replace("'BRL', 'CAD', 'CNY', 'EUR', 'JPY', 'USD'", "'USD', 'EUR', 'JPY'")
        jdbc.execute("DROP TABLE transactions")
        jdbc.execute(oldDdl)
        SchemaInitializer.seedIfEmpty(jdbc)
        val before = TransactionRepository(jdbc).findAll()

        val error = assertFailsWith<IllegalStateException> { SchemaInitializer.initialize(jdbc) }

        assertTrue(error.message!!.contains("schema"))
        assertTrue(error.message!!.contains("backend/data/rio.db"))
        assertTrue(error.message!!.contains("RIO_DB_PATH"))
        assertTrue(error.message!!.contains("restart"))
        assertEquals(oldDdl, storedDdl())
        assertEquals(before, TransactionRepository(jdbc).findAll())
    }

    @Test
    fun `non-currency DDL drift is also rejected`() {
        SchemaInitializer.initialize(jdbc)
        val changedDdl = storedDdl().replace("amount_minor > 0", "amount_minor >= 0")
        jdbc.execute("DROP TABLE transactions")
        jdbc.execute(changedDdl)

        assertFailsWith<IllegalStateException> { SchemaInitializer.initialize(jdbc) }
        assertEquals(changedDdl, storedDdl())
    }
}
