package ai.project.rio.cardtransaction

import ai.project.rio.db.Database
import ai.project.rio.db.JdbcTemplate
import ai.project.rio.db.SchemaInitializer
import ai.project.rio.http.IdempotencyConflictException
import ai.project.rio.http.NotFoundException
import ai.project.rio.http.ValidationException
import ai.project.rio.money.Currency
import ai.project.rio.money.Money
import java.nio.file.Files
import java.nio.file.Path
import java.sql.SQLException
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Service-focused integration tests: the concrete repository against a temporary SQLite file,
 * with a fixed clock so business rules (normalization, defaults, timestamps) are observable.
 */
class CardTransactionServiceTest {

    private val fixedInstant = Instant.parse("2026-09-13T12:34:56.789Z")

    private lateinit var dbFile: Path
    private lateinit var jdbc: JdbcTemplate
    private lateinit var repository: CardTransactionRepository
    private lateinit var service: CardTransactionService

    @BeforeTest
    fun setUp() {
        dbFile = Files.createTempFile("card-transaction-service-test", ".db")
        jdbc = Database.open(dbFile)
        SchemaInitializer.initialize(jdbc)
        repository = CardTransactionRepository(jdbc)
        service = CardTransactionService(jdbc, Clock.fixed(fixedInstant, ZoneOffset.UTC))
    }

    @AfterTest
    fun tearDown() {
        Files.deleteIfExists(dbFile)
    }

    @Test
    fun `create trims description, defaults status to COMPLETED, and stamps the fixed clock instant`() {
        val created = service.create("key-1", "  Lunch  ", Money(1800, Currency.USD), CardTransactionType.DEBIT)

        assertEquals("Lunch", created.description)
        assertEquals(CardTransactionStatus.COMPLETED, created.status)
        assertEquals(fixedInstant, created.createdAt)
        assertEquals(Money(1800, Currency.USD), created.amount)
        assertEquals(CardTransactionType.DEBIT, created.type)
    }

    @Test
    fun `create persists exactly the card transaction it returns`() {
        val created = service.create("key-1", "Salary", Money(500_000, Currency.JPY), CardTransactionType.CREDIT)

        assertEquals(created, repository.findById(created.id))
        assertEquals(listOf(created), repository.findAll())
    }

    @Test
    fun `create rejects a blank description without inserting`() {
        assertFailsWith<ValidationException> {
            service.create("key-1", "   ", Money(1800, Currency.USD), CardTransactionType.DEBIT)
        }
        assertEquals(emptyList(), repository.findAll())
    }

    @Test
    fun `create rejects zero and negative amounts without inserting`() {
        assertFailsWith<ValidationException> {
            service.create("key-1", "Lunch", Money(0, Currency.USD), CardTransactionType.DEBIT)
        }
        assertFailsWith<ValidationException> {
            service.create("key-2", "Lunch", Money(-5, Currency.USD), CardTransactionType.DEBIT)
        }
        assertEquals(emptyList(), repository.findAll())
    }

    @Test
    fun `get throws NotFoundException for an unknown id`() {
        assertFailsWith<NotFoundException> { service.get("does-not-exist") }
    }

    private val lunch = NewCardTransaction("Lunch", Money(1800, Currency.USD), CardTransactionType.DEBIT)
    private val salary = NewCardTransaction("  Salary  ", Money(500_000, Currency.JPY), CardTransactionType.CREDIT)

    @Test
    fun `createAll inserts every item in order and stamps the same instant`() {
        val created = service.createAll("key-1", listOf(lunch, salary))

        assertEquals(listOf("Lunch", "Salary"), created.map { it.description })
        assertEquals(listOf(fixedInstant, fixedInstant), created.map { it.createdAt })
        assertEquals(created.sortedByDescending { it.id }, repository.findAll())
    }

    @Test
    fun `createAll rejects an empty list`() {
        assertFailsWith<ValidationException> { service.createAll("key-1", emptyList()) }
    }

    @Test
    fun `createAll rolls back every insert when a later item is invalid`() {
        assertFailsWith<ValidationException> {
            service.createAll("key-1", listOf(lunch, salary.copy(description = "   ")))
        }
        assertEquals(emptyList(), repository.findAll())
    }

    @Test
    fun `createAll reports the index of the invalid item`() {
        val blank = assertFailsWith<ValidationException> {
            service.createAll("key-1", listOf(lunch, salary.copy(description = "   ")))
        }
        assertEquals("[1]: description must not be blank", blank.message)

        val zero = assertFailsWith<ValidationException> {
            service.createAll("key-2", listOf(lunch.copy(amount = Money(0, Currency.USD)), salary))
        }
        assertEquals("[0]: amount must be positive", zero.message)
    }

    @Test
    fun `create reports no item index`() {
        val blank = assertFailsWith<ValidationException> {
            service.create("key-1", "   ", Money(1800, Currency.USD), CardTransactionType.DEBIT)
        }
        assertEquals("description must not be blank", blank.message)

        val zero = assertFailsWith<ValidationException> {
            service.create("key-2", NewCardTransaction("Lunch", Money(0, Currency.USD), CardTransactionType.DEBIT))
        }
        assertEquals("amount must be positive", zero.message)
    }

    // ---- Idempotency-Key ----

    private val idempotency get() = CardTransactionIdempotencyRepository(jdbc)

    @Test
    fun `create replays the committed result for the same key`() {
        val first = service.create("key-1", lunch)

        val replay = service.create("key-1", lunch)

        assertEquals(first, replay)
        assertEquals(listOf(first), repository.findAll())
        assertEquals(listOf(first.id), idempotency.findCardTransactionIds("key-1"))
    }

    @Test
    fun `createAll replays the same ids in the same order`() {
        val first = service.createAll("key-1", listOf(lunch, salary))

        val replay = service.createAll("key-1", listOf(lunch, salary))

        assertEquals(first, replay)
        assertEquals(2, repository.findAll().size)
    }

    @Test
    fun `a different request with the same key is a conflict without a write`() {
        val first = service.create("key-1", lunch)

        val conflict = assertFailsWith<IdempotencyConflictException> { service.create("key-1", salary) }

        assertEquals("Idempotency-Key was already used with a different request", conflict.message)
        assertEquals(listOf(first), repository.findAll())
        assertFailsWith<IdempotencyConflictException> { service.createAll("key-1", listOf(lunch, salary)) }
        assertEquals(listOf(first), repository.findAll())
    }

    @Test
    fun `an object and a one-element array with the same key conflict`() {
        service.create("key-1", lunch)
        assertFailsWith<IdempotencyConflictException> { service.createAll("key-1", listOf(lunch)) }

        service.createAll("key-2", listOf(lunch))
        assertFailsWith<IdempotencyConflictException> { service.create("key-2", lunch) }

        assertEquals(2, repository.findAll().size)
    }

    @Test
    fun `identical requests with different keys create distinct rows`() {
        val first = service.create("key-1", lunch)
        val second = service.create("key-2", lunch)

        assertNotEquals(first.id, second.id)
        assertEquals(2, repository.findAll().size)
    }

    @Test
    fun `normalization feeds the fingerprint`() {
        val first = service.create("key-1", lunch.copy(description = "  Lunch\u3000"))

        assertEquals(first, service.create("key-1", lunch))
    }

    @Test
    fun `failed validation does not consume the key`() {
        assertFailsWith<ValidationException> { service.create("key-1", lunch.copy(description = "   ")) }
        assertNull(idempotency.find("key-1"))
        service.create("key-1", lunch)

        assertFailsWith<ValidationException> { service.createAll("key-2", listOf(lunch, salary.copy(amount = Money(0, Currency.JPY)))) }
        assertNull(idempotency.find("key-2"))
        service.createAll("key-2", listOf(lunch, salary))

        assertEquals(3, repository.findAll().size)
    }

    @Test
    fun `a rolled back batch leaves no idempotency record`() {
        // A real mid-transaction failure: the second insert aborts, so the claim and the first insert
        // must roll back with it. SchemaInitializer's drift guard only compares tables, not triggers.
        jdbc.execute(
            "CREATE TRIGGER boom BEFORE INSERT ON card_transactions WHEN NEW.description = 'boom' " +
                "BEGIN SELECT RAISE(ABORT, 'boom'); END",
        )
        assertFailsWith<SQLException> { service.createAll("key-1", listOf(lunch, lunch.copy(description = "boom"))) }

        assertNull(idempotency.find("key-1"))
        assertEquals(emptyList(), repository.findAll())

        jdbc.execute("DROP TRIGGER boom")
        assertEquals(2, service.createAll("key-1", listOf(lunch, salary)).size)
    }

    @Test
    fun `replay survives reopening the database`() {
        val first = service.createAll("key-1", listOf(lunch, salary))

        val reopened = Database.open(dbFile)
        SchemaInitializer.initialize(reopened)
        val replay = CardTransactionService(reopened, Clock.fixed(fixedInstant.plusSeconds(60), ZoneOffset.UTC)).createAll("key-1", listOf(lunch, salary))

        assertEquals(first, replay)
        assertEquals(2, CardTransactionRepository(reopened).findAll().size)
    }

    /** Runs [task] on [threads] threads released together; returns each outcome in thread order. */
    private fun <T> inParallel(threads: Int, task: (Int) -> T): List<Result<T>> {
        val pool = Executors.newFixedThreadPool(threads)
        val start = CountDownLatch(1)
        try {
            val futures = (0 until threads).map { i -> pool.submit<Result<T>> { start.await(); runCatching { task(i) } } }
            start.countDown()
            return futures.map { it.get(30, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `parallel same key and payload create once`() {
        val outcomes = inParallel(8) { service.create("key-1", lunch) }

        val results = outcomes.map { it.getOrThrow() }
        assertEquals(1, results.toSet().size, "every caller must see the same card transaction")
        assertEquals(listOf(results.first()), repository.findAll())
        assertEquals(listOf(results.first().id), idempotency.findCardTransactionIds("key-1"))
    }

    @Test
    fun `parallel same key and different payloads keep only the winner`() {
        val outcomes = inParallel(8) { i -> service.createAll("key-1", listOf(lunch.copy(description = "item-$i"), salary)) }

        val winners = outcomes.filter { it.isSuccess }.map { it.getOrThrow() }
        val losers = outcomes.filter { it.isFailure }.map { it.exceptionOrNull()!! }
        assertEquals(1, winners.size, "exactly one payload may win: $outcomes")
        assertEquals(7, losers.size)
        assertTrue(losers.all { it is IdempotencyConflictException }, losers.toString())
        assertEquals(winners.single().sortedByDescending { it.id }, repository.findAll())
        assertEquals(winners.single().map { it.id }, idempotency.findCardTransactionIds("key-1"))
    }
}
