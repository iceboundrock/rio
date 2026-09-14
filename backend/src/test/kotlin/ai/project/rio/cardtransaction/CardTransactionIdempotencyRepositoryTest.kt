package ai.project.rio.cardtransaction

import ai.project.rio.db.Database
import ai.project.rio.db.JdbcTemplate
import ai.project.rio.db.SchemaInitializer
import ai.project.rio.money.Currency
import ai.project.rio.money.Money
import java.nio.file.Files
import java.nio.file.Path
import java.sql.SQLException
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CardTransactionIdempotencyRepositoryTest {

    private lateinit var dbFile: Path
    private lateinit var jdbc: JdbcTemplate
    private lateinit var repository: CardTransactionIdempotencyRepository

    private val now = Instant.parse("2026-09-14T10:00:00Z")

    @BeforeTest
    fun setUp() {
        dbFile = Files.createTempFile("card-transaction-idempotency-repository-test", ".db")
        jdbc = Database.open(dbFile)
        SchemaInitializer.initialize(jdbc, dbFile)
        repository = CardTransactionIdempotencyRepository(jdbc)
    }

    @AfterTest
    fun tearDown() {
        Files.deleteIfExists(dbFile)
    }

    private fun cardTransaction(id: String) = CardTransaction(
        id = id,
        description = "Lunch",
        amount = Money(1800, Currency.USD),
        type = CardTransactionType.DEBIT,
        status = CardTransactionStatus.COMPLETED,
        createdAt = now,
    )

    @Test
    fun `claim inserts once and reports the second attempt`() {
        assertTrue(repository.claim("k", "fp-1", RequestShape.ONE, now))

        assertFalse(repository.claim("k", "fp-2", RequestShape.MANY, now))
        assertEquals(IdempotencyRecord("k", "fp-1", RequestShape.ONE), repository.find("k"))
    }

    @Test
    fun `find returns null for an unknown key`() {
        assertNull(repository.find("missing"))
    }

    @Test
    fun `keys are case-sensitive and opaque`() {
        assertTrue(repository.claim("Key", "fp-1", RequestShape.ONE, now))
        assertTrue(repository.claim("key", "fp-2", RequestShape.MANY, now))
        assertTrue(repository.claim("a, b", "fp-3", RequestShape.ONE, now))

        assertEquals(IdempotencyRecord("Key", "fp-1", RequestShape.ONE), repository.find("Key"))
        assertEquals(IdempotencyRecord("key", "fp-2", RequestShape.MANY), repository.find("key"))
        assertEquals(IdempotencyRecord("a, b", "fp-3", RequestShape.ONE), repository.find("a, b"))
        assertNull(repository.find("a"))
    }

    @Test
    fun `items are returned in index order`() {
        val transactions = CardTransactionRepository(jdbc)
        val a = cardTransaction("tx-a")
        val b = cardTransaction("tx-b")
        transactions.insert(a)
        transactions.insert(b)
        repository.claim("k", "fp", RequestShape.MANY, now)

        repository.insertItems("k", listOf(b.id, a.id))

        assertEquals(listOf("tx-b", "tx-a"), repository.findCardTransactionIds("k"))
        assertEquals(emptyList(), repository.findCardTransactionIds("other"))
    }

    @Test
    fun `items require an existing key and card transaction`() {
        CardTransactionRepository(jdbc).insert(cardTransaction("tx-a"))
        repository.claim("k", "fp", RequestShape.ONE, now)

        assertFailsWith<SQLException> { repository.insertItems("missing", listOf("tx-a")) }
        assertFailsWith<SQLException> { repository.insertItems("k", listOf("nope")) }
        assertEquals(emptyList(), repository.findCardTransactionIds("k"))
    }

    @Test
    fun `a claim inside a rolled back transaction is gone`() {
        assertFailsWith<IllegalStateException> {
            jdbc.withTransaction { tx ->
                assertTrue(CardTransactionIdempotencyRepository(tx).claim("k", "fp", RequestShape.ONE, now))
                error("boom")
            }
        }

        assertNull(repository.find("k"))
        assertTrue(repository.claim("k", "fp", RequestShape.ONE, now))
    }
}
