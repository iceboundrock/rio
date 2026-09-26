package ai.project.rio.cardtransaction

import ai.project.rio.db.JdbcTemplate
import ai.project.rio.db.PostgresTestDatabase
import ai.project.rio.db.SchemaInitializer
import ai.project.rio.http.IdempotencyConflictException
import ai.project.rio.http.NotFoundException
import ai.project.rio.http.ValidationException
import ai.project.rio.money.Currency
import ai.project.rio.money.Money
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
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Service-focused integration tests: the concrete repository against an empty PostgreSQL database,
 * with a fixed clock so business rules (normalization, defaults, timestamps) are observable.
 */
class CardTransactionServiceTest {

    private val fixedInstant = Instant.parse("2026-09-13T12:34:56.789Z")

    private lateinit var db: PostgresTestDatabase
    private lateinit var jdbc: JdbcTemplate
    private lateinit var repository: CardTransactionRepository
    private lateinit var service: CardTransactionService

    @BeforeTest
    fun setUp() {
        db = PostgresTestDatabase.create()
        jdbc = db.open()
        SchemaInitializer.initialize(jdbc, db.address)
        repository = CardTransactionRepository(jdbc)
        service = CardTransactionService(jdbc, Clock.fixed(fixedInstant, ZoneOffset.UTC))
    }

    @AfterTest
    fun tearDown() {
        db.close()
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

    // ---- Timestamps ----

    private fun serviceAt(instant: String) = CardTransactionService(jdbc, Clock.fixed(Instant.parse(instant), ZoneOffset.UTC))

    @Test
    fun `a nanosecond clock is stamped at microseconds, so create and its replay are equal`() {
        val nanoseconds = serviceAt("2026-09-10T18:00:00.123456789Z")

        val created = nanoseconds.create("key-1", lunch)

        // The replay is read back from timestamptz, which holds microseconds.
        assertEquals(created, nanoseconds.create("key-1", lunch))
        assertEquals(Instant.parse("2026-09-10T18:00:00.123456Z"), created.createdAt)
        assertEquals(created, repository.findById(created.id))
    }

    /**
     * Both pairs are ones the TEXT column of `Instant.toString()` listed the wrong way round: within
     * one second the shorter rendering is a prefix of the longer, and `Z` sorts after `.` and every digit.
     */
    @Test
    fun `list orders same-second rows by time, not by their ISO-8601 text`() {
        val oldestFirst = listOf(
            "2026-09-10T18:00:00Z", "2026-09-10T18:00:00.123Z",
            "2026-09-10T19:00:00.500Z", "2026-09-10T19:00:00.500001Z",
        ).mapIndexed { i, instant -> serviceAt(instant).create("key-$i", lunch) }

        assertEquals(oldestFirst.reversed(), service.list())
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
    fun `a description containing U+0000 is rejected without consuming the key`() {
        // PostgreSQL text cannot store the character, so without this check the insert would fail with a 500.
        val nul = assertFailsWith<ValidationException> { service.create("key-1", lunch.copy(description = "a\u0000b")) }
        assertEquals("description must not contain U+0000", nul.message)
        val inBatch = assertFailsWith<ValidationException> {
            service.createAll("key-1", listOf(lunch, salary.copy(description = "\u0000")))
        }
        assertEquals("[1]: description must not contain U+0000", inBatch.message)

        assertNull(idempotency.find("key-1"))
        assertEquals(emptyList(), repository.findAll())
        assertEquals(listOf(service.create("key-1", lunch)), repository.findAll())
    }

    @Test
    fun `a rolled back batch leaves no idempotency record`() {
        // A real mid-transaction failure: the second insert aborts, so the claim and the first insert
        // must roll back with it. SchemaInitializer's drift guard does not compare triggers.
        jdbc.execute(
            "CREATE FUNCTION boom() RETURNS trigger LANGUAGE plpgsql AS " +
                "'BEGIN IF NEW.description = ''boom'' THEN RAISE EXCEPTION ''boom''; END IF; RETURN NEW; END'",
        )
        jdbc.execute("CREATE TRIGGER boom BEFORE INSERT ON card_transactions FOR EACH ROW EXECUTE FUNCTION boom()")
        assertFailsWith<SQLException> { service.createAll("key-1", listOf(lunch, lunch.copy(description = "boom"))) }

        assertNull(idempotency.find("key-1"))
        assertEquals(emptyList(), repository.findAll())

        jdbc.execute("DROP TRIGGER boom ON card_transactions")
        assertEquals(2, service.createAll("key-1", listOf(lunch, salary)).size)
    }

    @Test
    fun `replay survives reopening the database`() {
        val first = service.createAll("key-1", listOf(lunch, salary))

        val reopened = db.open()
        SchemaInitializer.initialize(reopened, db.address)
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

    /**
     * Makes every card transaction insert sleep, so racing transactions overlap. A new pool starts with
     * one connection and opens the rest in the background; without this, the callers can take turns on
     * that one connection and nothing races.
     */
    private fun slowCardTransactionInserts(seconds: Double) {
        jdbc.execute("CREATE FUNCTION slow_insert() RETURNS trigger LANGUAGE plpgsql AS 'BEGIN PERFORM pg_sleep($seconds); RETURN NEW; END'")
        jdbc.execute("CREATE TRIGGER slow_insert BEFORE INSERT ON card_transactions FOR EACH ROW EXECUTE FUNCTION slow_insert()")
    }

    @Test
    fun `parallel same key and payload create once`() {
        slowCardTransactionInserts(0.5)
        val outcomes = inParallel(8) { service.create("key-1", lunch) }

        val results = outcomes.map { it.getOrThrow() }
        assertEquals(1, results.toSet().size, "every caller must see the same card transaction")
        assertEquals(listOf(results.first()), repository.findAll())
        assertEquals(listOf(results.first().id), idempotency.findCardTransactionIds("key-1"))
    }

    @Test
    fun `parallel same key and different payloads keep only the winner`() {
        slowCardTransactionInserts(0.25)
        val outcomes = inParallel(8) { i -> service.createAll("key-1", listOf(lunch.copy(description = "item-$i"), salary)) }

        val winners = outcomes.filter { it.isSuccess }.map { it.getOrThrow() }
        val losers = outcomes.filter { it.isFailure }.map { it.exceptionOrNull()!! }
        assertEquals(1, winners.size, "exactly one payload may win: $outcomes")
        assertEquals(7, losers.size)
        assertTrue(losers.all { it is IdempotencyConflictException }, losers.toString())
        assertEquals(winners.single().sortedByDescending { it.id }, repository.findAll())
        assertEquals(winners.single().map { it.id }, idempotency.findCardTransactionIds("key-1"))
    }

    /** More callers than the pool's 10 connections, so some wait for a connection as well as for the key. */
    private val manyCallers = 32

    @Test
    fun `many callers racing one key insert once, and each other caller replays or conflicts by its payload`() {
        // The winner holds its uncommitted claim while it sleeps, so the callers that get a connection
        // meanwhile wait on the key; the rest find it committed.
        slowCardTransactionInserts(0.5)
        val payloads = listOf(lunch, lunch.copy(description = "Dinner"))

        val outcomes = inParallel(manyCallers) { i -> service.create("key-1", payloads[i % 2]) }

        val rows = repository.findAll()
        assertEquals(1, rows.size, "exactly one insert: $rows")
        val winner = rows.single()
        val winningPayload = payloads.indexOfFirst { it.description == winner.description }
        outcomes.forEachIndexed { i, outcome ->
            if (i % 2 == winningPayload) {
                assertEquals(winner, outcome.getOrThrow(), "caller $i sent the winning payload")
            } else {
                assertIs<IdempotencyConflictException>(outcome.exceptionOrNull(), "caller $i sent the other payload: $outcome")
            }
        }
        assertEquals(listOf(winner.id), idempotency.findCardTransactionIds("key-1"))
    }

    @Test
    fun `many callers with distinct keys all create`() {
        slowCardTransactionInserts(0.05)
        val outcomes = inParallel(manyCallers) { i -> service.createAll("key-$i", listOf(lunch, salary)) }

        // A serialization failure, a deadlock or a pool timeout would be a SQLException here, which the API answers as a 500.
        val created = outcomes.map { it.getOrThrow() }
        assertEquals(created.flatten().toSet(), repository.findAll().toSet())
        assertEquals(2 * manyCallers, repository.findAll().size)
        created.forEachIndexed { i, items -> assertEquals(items.map { it.id }, idempotency.findCardTransactionIds("key-$i")) }
    }
}
