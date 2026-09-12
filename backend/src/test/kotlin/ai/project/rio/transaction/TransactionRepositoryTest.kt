package ai.project.rio.transaction

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
import kotlin.test.assertNull

class TransactionRepositoryTest {

    private lateinit var dbFile: Path
    private lateinit var jdbc: JdbcTemplate
    private lateinit var repository: TransactionRepository

    @BeforeTest
    fun setUp() {
        dbFile = Files.createTempFile("transaction-repository-test", ".db")
        jdbc = Database.open(dbFile)
        SchemaInitializer.initialize(jdbc)
        repository = TransactionRepository(jdbc)
    }

    @AfterTest
    fun tearDown() {
        Files.deleteIfExists(dbFile)
    }

    private val lunch = Transaction(
        id = "tx-1",
        description = "Lunch",
        amount = Money(1800, Currency.USD),
        type = TransactionType.DEBIT,
        status = TransactionStatus.COMPLETED,
        createdAt = Instant.parse("2026-09-10T18:00:00Z"),
    )

    @Test
    fun `insert then findById reconstructs the same Transaction including Money`() {
        repository.insert(lunch)
        val found = repository.findById("tx-1")
        assertEquals(lunch, found)
        assertEquals(Money(1800, Currency.USD), found!!.amount)
    }

    @Test
    fun `currency and precision-free amount round-trip for JPY`() {
        val yen = lunch.copy(id = "tx-jpy", amount = Money(24_800, Currency.JPY), status = TransactionStatus.PENDING)
        repository.insert(yen)
        assertEquals(yen, repository.findById("tx-jpy"))
    }

    @Test
    fun `enums and timestamp round-trip`() {
        val declined = lunch.copy(
            id = "tx-2", type = TransactionType.CREDIT, status = TransactionStatus.DECLINED,
            createdAt = Instant.parse("2026-01-02T03:04:05.123Z"),
        )
        repository.insert(declined)
        val found = repository.findById("tx-2")!!
        assertEquals(TransactionType.CREDIT, found.type)
        assertEquals(TransactionStatus.DECLINED, found.status)
        assertEquals(Instant.parse("2026-01-02T03:04:05.123Z"), found.createdAt)
    }

    @Test
    fun `findAll lists newest first`() {
        repository.insert(lunch)
        repository.insert(lunch.copy(id = "tx-newer", createdAt = Instant.parse("2026-09-11T00:00:00Z")))
        repository.insert(lunch.copy(id = "tx-older", createdAt = Instant.parse("2026-09-01T00:00:00Z")))
        assertEquals(listOf("tx-newer", "tx-1", "tx-older"), repository.findAll().map { it.id })
    }

    @Test
    fun `findById returns null for a missing row`() {
        assertNull(repository.findById("does-not-exist"))
    }

    @Test
    fun `SQLite CHECK constraints back up application validation`() {
        assertFailsWith<SQLException> { repository.insert(lunch.copy(amount = Money(0, Currency.USD))) }
        assertFailsWith<SQLException> { repository.insert(lunch.copy(amount = Money(-5, Currency.USD))) }
        assertFailsWith<SQLException> { repository.insert(lunch.copy(description = "   ")) }
        assertFailsWith<SQLException> { repository.insert(lunch); repository.insert(lunch) } // duplicate id
    }

    @Test
    fun `seed is deterministic and inserted only once`() {
        SchemaInitializer.seedIfEmpty(jdbc)
        SchemaInitializer.seedIfEmpty(jdbc)
        assertEquals(SchemaInitializer.SEED.size, repository.findAll().size)
        assertEquals(SchemaInitializer.SEED.sortedByDescending { it.createdAt }, repository.findAll())
    }

    @Test
    fun `service joins a caller transaction and rolls back with other repository writes`() {
        assertFailsWith<SQLException> {
            jdbc.transaction { tx ->
                val scopedRepository = TransactionRepository(tx)
                val service = TransactionService(scopedRepository)
                val created = service.create("Lunch", lunch.amount, lunch.type)
                assertEquals(created, service.get(created.id))
                scopedRepository.insert(lunch)
                scopedRepository.insert(lunch) // Roll back the service write as well.
            }
        }
        assertEquals(emptyList(), repository.findAll())
    }

    @Test
    fun `repositories sharing an executor roll back together`() {
        assertFailsWith<SQLException> {
            jdbc.transaction { tx ->
                val first = TransactionRepository(tx)
                val second = TransactionRepository(tx)
                first.insert(lunch)
                assertEquals(lunch, second.findById(lunch.id))
                second.insert(lunch.copy(id = "tx-second"))
                second.insert(lunch) // Duplicate ID fails after both preceding writes.
            }
        }
        assertEquals(emptyList(), repository.findAll())
    }
}
