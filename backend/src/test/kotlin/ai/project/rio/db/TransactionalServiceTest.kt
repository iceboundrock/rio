package ai.project.rio.db

import java.sql.SQLException
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TransactionalServiceTest {

    private lateinit var db: PostgresTestDatabase
    private lateinit var jdbc: JdbcTemplate
    private lateinit var service: ItemService

    /** A minimal service owning a two-statement write; the shape every real service follows. */
    private class ItemService(jdbc: JdbcTemplate) : TransactionalService(jdbc) {
        fun insertBoth(first: Int, second: Int): Int = transactional { tx ->
            tx.update("INSERT INTO items (id) VALUES (?)", listOf(first)) +
                tx.update("INSERT INTO items (id) VALUES (?)", listOf(second))
        }
    }

    @BeforeTest
    fun setUp() {
        db = PostgresTestDatabase.create()
        jdbc = db.open()
        jdbc.update("CREATE TABLE items (id INTEGER PRIMARY KEY)")
        service = ItemService(jdbc)
    }

    @AfterTest
    fun tearDown() {
        db.close()
    }

    private fun ids(): List<Int> = jdbc.query("SELECT id FROM items ORDER BY id") { it.getInt("id") }

    @Test
    fun `transactional commits every statement in the block`() {
        assertEquals(2, service.insertBoth(1, 2))
        assertEquals(listOf(1, 2), ids())
    }

    @Test
    fun `transactional rolls back the whole block when a later statement fails`() {
        assertFailsWith<SQLException> { service.insertBoth(1, 1) }
        assertEquals(emptyList(), ids())
    }
}
