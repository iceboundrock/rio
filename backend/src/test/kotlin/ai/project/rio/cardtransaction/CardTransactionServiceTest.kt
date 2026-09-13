package ai.project.rio.cardtransaction

import ai.project.rio.db.Database
import ai.project.rio.db.JdbcTemplate
import ai.project.rio.db.SchemaInitializer
import ai.project.rio.http.NotFoundException
import ai.project.rio.http.ValidationException
import ai.project.rio.money.Currency
import ai.project.rio.money.Money
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

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
        val created = service.create("  Lunch  ", Money(1800, Currency.USD), CardTransactionType.DEBIT)

        assertEquals("Lunch", created.description)
        assertEquals(CardTransactionStatus.COMPLETED, created.status)
        assertEquals(fixedInstant, created.createdAt)
        assertEquals(Money(1800, Currency.USD), created.amount)
        assertEquals(CardTransactionType.DEBIT, created.type)
    }

    @Test
    fun `create persists exactly the card transaction it returns`() {
        val created = service.create("Salary", Money(500_000, Currency.JPY), CardTransactionType.CREDIT)

        assertEquals(created, repository.findById(created.id))
        assertEquals(listOf(created), repository.findAll())
    }

    @Test
    fun `create rejects a blank description without inserting`() {
        assertFailsWith<ValidationException> {
            service.create("   ", Money(1800, Currency.USD), CardTransactionType.DEBIT)
        }
        assertEquals(emptyList(), repository.findAll())
    }

    @Test
    fun `create rejects zero and negative amounts without inserting`() {
        assertFailsWith<ValidationException> {
            service.create("Lunch", Money(0, Currency.USD), CardTransactionType.DEBIT)
        }
        assertFailsWith<ValidationException> {
            service.create("Lunch", Money(-5, Currency.USD), CardTransactionType.DEBIT)
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
        val created = service.createAll(listOf(lunch, salary))

        assertEquals(listOf("Lunch", "Salary"), created.map { it.description })
        assertEquals(listOf(fixedInstant, fixedInstant), created.map { it.createdAt })
        assertEquals(created.sortedByDescending { it.id }, repository.findAll())
    }

    @Test
    fun `createAll rejects an empty list`() {
        assertFailsWith<ValidationException> { service.createAll(emptyList()) }
    }

    @Test
    fun `createAll rolls back every insert when a later item is invalid`() {
        assertFailsWith<ValidationException> {
            service.createAll(listOf(lunch, salary.copy(description = "   ")))
        }
        assertEquals(emptyList(), repository.findAll())
    }

    @Test
    fun `createAll reports the index of the invalid item`() {
        val blank = assertFailsWith<ValidationException> {
            service.createAll(listOf(lunch, salary.copy(description = "   ")))
        }
        assertEquals("[1]: description must not be blank", blank.message)

        val zero = assertFailsWith<ValidationException> {
            service.createAll(listOf(lunch.copy(amount = Money(0, Currency.USD)), salary))
        }
        assertEquals("[0]: amount must be positive", zero.message)
    }

    @Test
    fun `create reports no item index`() {
        val blank = assertFailsWith<ValidationException> {
            service.create("   ", Money(1800, Currency.USD), CardTransactionType.DEBIT)
        }
        assertEquals("description must not be blank", blank.message)

        val zero = assertFailsWith<ValidationException> {
            service.create(NewCardTransaction("Lunch", Money(0, Currency.USD), CardTransactionType.DEBIT))
        }
        assertEquals("amount must be positive", zero.message)
    }
}
