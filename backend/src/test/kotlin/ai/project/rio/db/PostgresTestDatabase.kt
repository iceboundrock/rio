package ai.project.rio.db

import java.sql.DriverManager
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import org.testcontainers.postgresql.PostgreSQLContainer

/**
 * An empty PostgreSQL database for one test method: [create] it in `@BeforeTest` and [close] it in
 * `@AfterTest`, as the SQLite tests do with a temporary file.
 *
 * The databases live in one `postgres:17` container per test JVM, started by the first [create] and
 * shared by every test class; Testcontainers' reaper removes it when the JVM exits. Without a Docker
 * daemon, [create] fails with Testcontainers' "Could not find a valid Docker environment".
 */
class PostgresTestDatabase private constructor(private val name: String) : AutoCloseable {

    private val url = urlOf(name)
    private val pools = ConcurrentLinkedQueue<PooledDatabase>()

    /** A new pool on this database, built by [Database.open] as the application builds its own. */
    fun open(): JdbcTemplate = Database.open(url, server.username, server.password).also(pools::add).jdbc

    /** Closes every pool [open] built, then drops the database. */
    override fun close() {
        pools.forEach(PooledDatabase::close)
        admin.execute("DROP DATABASE $name WITH (FORCE)")
    }

    companion object {
        private val server by lazy { PostgreSQLContainer("postgres:17").apply { start() } }

        /** Connects to the container's own database to create and drop the per-test ones. */
        private val admin by lazy {
            JdbcTemplate { DriverManager.getConnection(urlOf(server.databaseName), server.username, server.password) }
        }

        private val databases = AtomicInteger()

        fun create(): PostgresTestDatabase {
            // Generated here, never taken from input, so it is safe to put into the DDL.
            val name = "rio_test_${databases.incrementAndGet()}"
            admin.execute("CREATE DATABASE $name")
            return PostgresTestDatabase(name)
        }

        private fun urlOf(database: String) =
            "jdbc:postgresql://${server.host}:${server.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT)}/$database"
    }
}
