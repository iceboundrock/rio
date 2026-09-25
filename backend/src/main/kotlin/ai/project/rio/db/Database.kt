package ai.project.rio.db

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import com.zaxxer.hikari.pool.HikariPool
import java.util.Properties
import java.util.logging.Level
import java.util.logging.Logger
import org.postgresql.Driver
import org.postgresql.PGProperty

/**
 * The one place database connections are configured. `DB_URL` is checked with pgJDBC's own URL
 * parser, then a HikariCP pool is built with HikariCP's defaults (10 connections), and connections
 * keep PostgreSQL's default READ COMMITTED isolation. Messages identify the database by its parsed
 * [DatabaseAddress], never by the URL, which can hold secrets.
 */
object Database {

    private const val POSTGRESQL_PREFIX = "jdbc:postgresql:"

    /**
     * Checks [url] (see [address]), then builds the pool. Building it opens one connection (HikariCP's
     * default `initializationFailTimeout`), so an unreachable server or rejected credentials fail here
     * after that single attempt, not on the first request.
     */
    fun open(url: String, user: String, password: String): PooledDatabase {
        val address = address(url)
        val config = HikariConfig().apply {
            jdbcUrl = url
            username = user
            this.password = password
        }
        val dataSource = try {
            HikariDataSource(config)
        } catch (e: HikariPool.PoolInitializationException) {
            throw IllegalStateException(
                "Cannot connect to database ${address.database} on ${address.servers} as user $user. " +
                    "Check that PostgreSQL is running there and that DB_URL, DB_USER and DB_PASSWORD are right; " +
                    "./start.sh starts the local PostgreSQL container.",
                e,
            )
        }
        return PooledDatabase(dataSource, address)
    }

    /**
     * Where `DB_URL` points, as pgJDBC reads it. [Driver.parseURL] is the only parser, so Rio and the
     * driver never disagree about a URL such as `jdbc:postgresql:rio` (localhost:5432) or one naming
     * several hosts. Credentials come only from `DB_USER` and `DB_PASSWORD`: a `password` parameter
     * would silently override the latter.
     */
    internal fun address(url: String): DatabaseAddress {
        require(url.startsWith(POSTGRESQL_PREFIX)) { "DB_URL must start with $POSTGRESQL_PREFIX" }
        val properties = requireNotNull(parseQuietly(url)) { "DB_URL is not a valid PostgreSQL JDBC URL" }
        require(!PGProperty.USER.isPresent(properties) && !PGProperty.PASSWORD.isPresent(properties)) {
            "DB_URL must not carry credentials; use DB_USER and DB_PASSWORD"
        }
        val hosts = PGProperty.PG_HOST.getOrDefault(properties).orEmpty().split(',')
        val ports = PGProperty.PG_PORT.getOrDefault(properties).orEmpty().split(',')
        return DatabaseAddress(
            servers = hosts.zip(ports) { host, port -> "$host:$port" }.joinToString(","),
            database = PGProperty.PG_DBNAME.getOrNull(properties).orEmpty(),
        )
    }

    /**
     * The parser reports a URL it rejects as a JUL warning, and for a missing or extra `/` that
     * warning holds the whole URL. The driver's logger is raised to SEVERE for the parse and restored
     * afterwards; it is held in a local meanwhile because JUL keeps loggers weakly, and a level set
     * on a logger nothing references is lost when it is collected.
     */
    private fun parseQuietly(url: String): Properties? {
        val logger = Logger.getLogger(Driver::class.java.name)
        val level = logger.level
        logger.level = Level.SEVERE
        try {
            return Driver.parseURL(url, null)
        } finally {
            logger.level = level
        }
    }
}

/**
 * The server(s) and database a valid `DB_URL` names, safe to print where the URL is not.
 * [servers] lists every `host:port`, comma-separated, in the URL's order.
 */
data class DatabaseAddress(val servers: String, val database: String)

/**
 * A PostgreSQL connection pool. [jdbc] borrows a connection per call, or one for a whole
 * `withTransaction` block, and returns it afterwards; [close] shuts the pool down. [address] is
 * where it points, for messages that must name the database without printing `DB_URL`.
 */
class PooledDatabase internal constructor(
    private val dataSource: HikariDataSource,
    val address: DatabaseAddress,
) : AutoCloseable {

    val jdbc = JdbcTemplate(openConnection = dataSource::getConnection)

    override fun close() = dataSource.close()
}
