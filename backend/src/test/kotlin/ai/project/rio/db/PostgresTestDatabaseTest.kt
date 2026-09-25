package ai.project.rio.db

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

class PostgresTestDatabaseTest {

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

    @Test
    fun `SELECT 1 runs`() {
        assertEquals(1, jdbc.queryForObject("SELECT 1") { it.getInt(1) })
    }

    @Test
    fun `a row round-trips through JdbcTemplate`() {
        jdbc.execute("CREATE TABLE items (id INTEGER PRIMARY KEY, name TEXT NOT NULL)")
        assertEquals(1, jdbc.update("INSERT INTO items (id, name) VALUES (?, ?)", listOf(1, "a")))
        assertEquals("a", jdbc.queryForObject("SELECT name FROM items WHERE id = ?", listOf(1)) { it.getString("name") })
    }

    @Test
    fun `another pool on the same database sees committed rows`() {
        jdbc.execute("CREATE TABLE items (id INTEGER PRIMARY KEY)")
        jdbc.update("INSERT INTO items (id) VALUES (?)", listOf(1))
        assertEquals(listOf(1), db.open().query("SELECT id FROM items") { it.getInt("id") })
    }

    // The next two run in either order. Each creates the same table, which fails if the other's database
    // is visible, and each must see only its own row.

    @Test
    fun `a test starts on an empty database (first of two)`() = createTableAndSeeOnlyOwnRow("first")

    @Test
    fun `a test starts on an empty database (second of two)`() = createTableAndSeeOnlyOwnRow("second")

    private fun createTableAndSeeOnlyOwnRow(owner: String) {
        val relations = jdbc.query(
            "SELECT c.relname FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace WHERE n.nspname = 'public'"
        ) { it.getString("relname") }
        assertEquals(emptyList(), relations)
        jdbc.execute("CREATE TABLE shared (owner TEXT NOT NULL)")
        jdbc.update("INSERT INTO shared (owner) VALUES (?)", listOf(owner))
        assertEquals(listOf(owner), jdbc.query("SELECT owner FROM shared") { it.getString("owner") })
    }
}
