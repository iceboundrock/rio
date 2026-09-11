package ai.project.rio.db

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JdbcTemplateTest {

    private lateinit var dbFile: Path
    private lateinit var jdbc: JdbcTemplate

    @BeforeTest
    fun setUp() {
        dbFile = Files.createTempFile("jdbc-template-test", ".db")
        jdbc = Database.open(dbFile)
        jdbc.update("CREATE TABLE items (id INTEGER PRIMARY KEY, name TEXT, qty INTEGER, big INTEGER, active INTEGER, note TEXT)")
    }

    @AfterTest
    fun tearDown() {
        Files.deleteIfExists(dbFile)
    }

    private fun insert(id: Int, name: String?, qty: Int = 0, big: Long = 0, active: Boolean = false, note: String? = null) =
        jdbc.update(
            "INSERT INTO items (id, name, qty, big, active, note) VALUES (?, ?, ?, ?, ?, ?)",
            listOf(id, name, qty, big, active, note),
        )

    @Test
    fun `update returns affected rows and query maps every row`() {
        assertEquals(1, insert(1, "a"))
        assertEquals(1, insert(2, "b"))
        val names = jdbc.query("SELECT name FROM items ORDER BY id") { it.getString("name") }
        assertEquals(listOf("a", "b"), names)
    }

    @Test
    fun `queryOne returns the row or null`() {
        insert(1, "a")
        assertEquals("a", jdbc.queryOne("SELECT name FROM items WHERE id = ?", listOf(1)) { it.getString("name") })
        assertNull(jdbc.queryOne("SELECT name FROM items WHERE id = ?", listOf(99)) { it.getString("name") })
    }

    @Test
    fun `queryOne rejects multiple rows`() {
        insert(1, "a"); insert(2, "a")
        assertFailsWith<IllegalStateException> {
            jdbc.queryOne("SELECT id FROM items WHERE name = ?", listOf("a")) { it.getInt("id") }
        }
    }

    @Test
    fun `binds String Int Long Boolean and null parameters`() {
        insert(1, "name", qty = 7, big = Long.MAX_VALUE, active = true, note = null)
        val row = jdbc.queryOne("SELECT * FROM items WHERE id = ?", listOf(1)) {
            listOf(it.getString("name"), it.getInt("qty"), it.getLong("big"), it.getBoolean("active"), it.getString("note"))
        }
        assertEquals(listOf("name", 7, Long.MAX_VALUE, true, null), row)
        assertEquals(1, jdbc.queryOne("SELECT id FROM items WHERE note IS NULL") { it.getInt("id") })
    }

    @Test
    fun `unsupported parameter type fails clearly`() {
        val e = assertFailsWith<IllegalArgumentException> {
            jdbc.update("INSERT INTO items (id) VALUES (?)", listOf(java.time.Instant.EPOCH))
        }
        assertTrue(e.message!!.contains("Unsupported JDBC parameter type"))
    }

    @Test
    fun `transaction commits on success`() {
        val result = jdbc.transaction { tx ->
            tx.update("INSERT INTO items (id, name) VALUES (?, ?)", listOf(1, "a"))
            tx.update("INSERT INTO items (id, name) VALUES (?, ?)", listOf(2, "b"))
            tx.queryOne("SELECT count(*) AS n FROM items") { it.getInt("n") }
        }
        assertEquals(2, result)
        assertEquals(2, jdbc.queryOne("SELECT count(*) AS n FROM items") { it.getInt("n") })
    }

    @Test
    fun `transaction rolls back and rethrows on exception`() {
        insert(1, "before")
        val e = assertFailsWith<IllegalStateException> {
            jdbc.transaction { tx ->
                tx.update("INSERT INTO items (id, name) VALUES (?, ?)", listOf(2, "inside"))
                // The write above is visible to later statements in the same transaction...
                assertEquals(2, tx.queryOne("SELECT count(*) AS n FROM items") { it.getInt("n") })
                throw IllegalStateException("boom")
            }
        }
        assertEquals("boom", e.message)
        // ...but nothing inside the block survived.
        assertEquals(listOf("before"), jdbc.query("SELECT name FROM items") { it.getString("name") })
    }

    @Test
    fun `constraint violation inside a transaction rolls back earlier statements`() {
        assertFailsWith<java.sql.SQLException> {
            jdbc.transaction { tx ->
                tx.update("INSERT INTO items (id, name) VALUES (?, ?)", listOf(1, "first"))
                tx.update("INSERT INTO items (id, name) VALUES (?, ?)", listOf(1, "duplicate id"))
            }
        }
        assertEquals(0, jdbc.queryOne("SELECT count(*) AS n FROM items") { it.getInt("n") })
    }

    @Test
    fun `statements outside a transaction auto-commit`() {
        insert(1, "a")
        // A fresh connection (separate JdbcTemplate over the same file) sees the row.
        assertEquals(1, Database.open(dbFile).queryOne("SELECT count(*) AS n FROM items") { it.getInt("n") })
    }

    @Test
    fun `connections are closed after each call`() {
        var opened = 0
        val counting = JdbcTemplate {
            opened++
            java.sql.DriverManager.getConnection("jdbc:sqlite:${dbFile.toAbsolutePath()}")
        }
        counting.query("SELECT 1") { it.getInt(1) }
        counting.transaction { tx -> tx.update("INSERT INTO items (id) VALUES (?)", listOf(5)); tx.update("INSERT INTO items (id) VALUES (?)", listOf(6)) }
        assertEquals(2, opened, "one connection per call, one per transaction block")
        // If a connection leaked with an open write, SQLite would hold a lock and this would time out.
        assertEquals(2, jdbc.queryOne("SELECT count(*) AS n FROM items") { it.getInt("n") })
    }

    @Test
    fun `queryForObject returns the single matching row`() {
        insert(1, "a")
        assertEquals("a", jdbc.queryForObject("SELECT name FROM items WHERE id = ?", listOf(1)) { it.getString("name") })
    }

    @Test
    fun `queryForObject rejects an empty result`() {
        val e = assertFailsWith<IncorrectResultSizeException> {
            jdbc.queryForObject("SELECT name FROM items WHERE id = ?", listOf(99)) { it.getString("name") }
        }
        assertEquals(1, e.expectedSize)
        assertEquals(0, e.actualSize)
    }

    @Test
    fun `queryForObject rejects multiple rows`() {
        insert(1, "a"); insert(2, "a")
        val e = assertFailsWith<IncorrectResultSizeException> {
            jdbc.queryForObject("SELECT id FROM items WHERE name = ?", listOf("a")) { it.getInt("id") }
        }
        assertEquals(1, e.expectedSize)
        assertEquals(2, e.actualSize)
    }

    @Test
    fun `queryOne reports multiple rows as IncorrectResultSizeException`() {
        insert(1, "a"); insert(2, "a")
        val e = assertFailsWith<IncorrectResultSizeException> {
            jdbc.queryOne("SELECT id FROM items WHERE name = ?", listOf("a")) { it.getInt("id") }
        }
        assertEquals(2, e.actualSize)
    }

    @Test
    fun `extract hands the whole ResultSet to the caller`() {
        insert(1, "a", qty = 2); insert(2, "b", qty = 3)
        val total = jdbc.extract("SELECT qty FROM items WHERE qty > ?", listOf(0)) { rs ->
            var sum = 0
            while (rs.next()) sum += rs.getInt("qty")
            sum
        }
        assertEquals(5, total)
    }

    @Test
    fun `batchUpdate binds each parameter list and reports per-statement counts`() {
        val counts = jdbc.batchUpdate(
            "INSERT INTO items (id, name) VALUES (?, ?)",
            listOf(listOf(1, "a"), listOf(2, "b"), listOf(3, null)),
        )
        assertContentEquals(intArrayOf(1, 1, 1), counts)
        assertEquals(listOf("a", "b", null), jdbc.query("SELECT name FROM items ORDER BY id") { it.getString("name") })
    }

    @Test
    fun `batchUpdate with no parameter lists touches nothing`() {
        assertContentEquals(intArrayOf(), jdbc.batchUpdate("INSERT INTO items (id) VALUES (?)", emptyList()))
        assertEquals(0, jdbc.queryOne("SELECT count(*) AS n FROM items") { it.getInt("n") })
    }

    @Test
    fun `batchUpdate outside a transaction is atomic`() {
        assertFailsWith<java.sql.SQLException> {
            jdbc.batchUpdate(
                "INSERT INTO items (id, name) VALUES (?, ?)",
                listOf(listOf(1, "first"), listOf(1, "duplicate id")),
            )
        }
        assertEquals(0, jdbc.queryOne("SELECT count(*) AS n FROM items") { it.getInt("n") })
    }

    @Test
    fun `batchUpdate inside a transaction rolls back with the block`() {
        insert(9, "before")
        assertFailsWith<IllegalStateException> {
            jdbc.transaction { tx ->
                tx.batchUpdate("INSERT INTO items (id, name) VALUES (?, ?)", listOf(listOf(1, "a"), listOf(2, "b")))
                assertEquals(3, tx.queryOne("SELECT count(*) AS n FROM items") { it.getInt("n") })
                error("boom")
            }
        }
        assertEquals(listOf("before"), jdbc.query("SELECT name FROM items") { it.getString("name") })
    }

    @Test
    fun `execute runs parameterless DDL`() {
        jdbc.execute("CREATE TABLE extra (id INTEGER PRIMARY KEY)")
        jdbc.transaction { tx -> tx.execute("CREATE INDEX extra_idx ON extra (id)") }
        assertEquals(0, jdbc.queryOne("SELECT count(*) AS n FROM extra") { it.getInt("n") })
    }

    @Test
    fun `maxRows setting caps every query`() {
        insert(1, "a"); insert(2, "b"); insert(3, "c")
        val capped = JdbcTemplate(StatementSettings(maxRows = 2)) {
            java.sql.DriverManager.getConnection("jdbc:sqlite:${dbFile.toAbsolutePath()}")
        }
        assertEquals(listOf("a", "b"), capped.query("SELECT name FROM items ORDER BY id") { it.getString("name") })
        assertEquals(2, capped.transaction { tx -> tx.query("SELECT name FROM items") { it.getString("name") }.size })
    }

    @Test
    fun `statement settings are applied to each prepared statement`() {
        // sqlite-jdbc ignores fetchSize and does not echo it back, so record the setter calls instead.
        val applied = mutableMapOf<String, Any?>()
        val recording = JdbcTemplate(StatementSettings(queryTimeoutSeconds = 7, maxRows = 5, fetchSize = 3)) {
            recordStatementSetters(java.sql.DriverManager.getConnection("jdbc:sqlite:${dbFile.toAbsolutePath()}"), applied)
        }
        recording.query("SELECT 1") { rs -> rs.getInt(1) }
        assertEquals(mapOf<String, Any?>("setQueryTimeout" to 7, "setMaxRows" to 5, "setFetchSize" to 3), applied)
    }

    private fun recordStatementSetters(conn: java.sql.Connection, into: MutableMap<String, Any?>): java.sql.Connection {
        val loader = conn.javaClass.classLoader
        fun wrapStatement(stmt: java.sql.PreparedStatement): java.sql.PreparedStatement =
            java.lang.reflect.Proxy.newProxyInstance(loader, arrayOf(java.sql.PreparedStatement::class.java)) { _, m, args ->
                if (m.name in setOf("setQueryTimeout", "setMaxRows", "setFetchSize")) into[m.name] = args[0]
                m.invoke(stmt, *(args ?: emptyArray()))
            } as java.sql.PreparedStatement
        return java.lang.reflect.Proxy.newProxyInstance(loader, arrayOf(java.sql.Connection::class.java)) { _, m, args ->
            val result = m.invoke(conn, *(args ?: emptyArray()))
            if (m.name == "prepareStatement") wrapStatement(result as java.sql.PreparedStatement) else result
        } as java.sql.Connection
    }

    @Test
    fun `default statement settings leave driver defaults untouched`() {
        val seen = jdbc.extract("SELECT 1") { rs -> Triple(rs.statement.queryTimeout, rs.statement.maxRows, rs.statement.fetchSize) }
        assertEquals(Triple(0, 0, 0), seen)
    }
}
