package ai.project.rio.db

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger
import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger
import java.util.logging.SimpleFormatter
import javax.net.SocketFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** `DB_URL` checks in [Database.open]. No PostgreSQL server is involved, so none of these need Docker. */
class DatabaseTest {

    private val password = "db-password-must-not-appear"

    @Test
    fun `a URL without the jdbc postgresql prefix is refused`() {
        assertRefused("jdbc:mysql://h/rio", "DB_URL must start with jdbc:postgresql:")
        assertRefused("", "DB_URL must start with jdbc:postgresql:")
    }

    @Test
    fun `a URL the driver's parser rejects is refused without the driver printing it`() {
        assertRefused("jdbc:postgresql://localhost:54x/rio", "DB_URL is not a valid PostgreSQL JDBC URL")
        // Unsilenced, the driver logs this one whole as a JUL warning.
        assertRefused("jdbc:postgresql://localhost:5432", "DB_URL is not a valid PostgreSQL JDBC URL")
    }

    @Test
    fun `credentials in the URL are refused`() {
        for (query in listOf("user=x&password=y", "user=x", "password=y")) {
            assertRefused(
                "jdbc:postgresql://localhost:5432/rio?$query",
                "DB_URL must not carry credentials; use DB_USER and DB_PASSWORD",
            )
        }
    }

    @Test
    fun `the driver logger's level is restored after parsing`() {
        val logger = Logger.getLogger("org.postgresql.Driver")
        val before = logger.level
        logger.level = Level.WARNING
        try {
            assertRefused("jdbc:postgresql://localhost:5432", "DB_URL is not a valid PostgreSQL JDBC URL")
            assertEquals(Level.WARNING, logger.level)
        } finally {
            logger.level = before
        }
    }

    @Test
    fun `URLs a hand-written parser would misread pass validation as the driver reads them`() {
        assertEquals(DatabaseAddress("localhost:5432", "rio"), Database.address("jdbc:postgresql:rio"))
        assertEquals(
            DatabaseAddress("h1:5432,h2:5433", "rio"),
            Database.address("jdbc:postgresql://h1:5432,h2:5433/rio"),
        )
    }

    @Test
    fun `an unreachable database fails after one connection attempt, naming the database but not the URL`() {
        val url = "jdbc:postgresql://127.0.0.1:1/rio?socketFactory=${CountingSocketFactory::class.java.name}"
        CountingSocketFactory.created.set(0)

        val (e, output) = capture {
            assertFailsWith<IllegalStateException> { Database.open(url, "rio-user", password) }
        }

        assertEquals(1, CountingSocketFactory.created.get(), "TCP connections attempted")
        val message = e.message!!
        for (part in listOf("database rio on 127.0.0.1:1", "rio-user", "DB_URL", "DB_USER", "./start.sh")) {
            assertTrue(part in message, "'$part' missing from: $message")
        }
        assertNothingLeaks(url, e, output)
    }

    private fun assertRefused(url: String, expectedMessage: String) {
        val (e, output) = capture {
            assertFailsWith<IllegalArgumentException> { Database.open(url, "rio", password) }
        }
        assertEquals(expectedMessage, e.message)
        assertNothingLeaks(url, e, output)
    }

    /** Neither the exception, its causes included, nor anything printed meanwhile holds the URL or password. */
    private fun assertNothingLeaks(url: String, e: Throwable, output: String) {
        val trace = e.stackTraceToString()
        for (secret in listOfNotNull(url.ifEmpty { null }, password)) {
            assertFalse(secret in trace, "exception holds '$secret':\n$trace")
            assertFalse(secret in output, "output holds '$secret':\n$output")
        }
    }

    /**
     * Runs [block] and returns its result with everything printed meanwhile: stdout, stderr, and every
     * java.util.logging record that reaches the root logger. JUL's console handler writes to the stderr
     * stream it was created with, so replacing `System.err` alone would miss the driver's warnings.
     */
    private fun <T> capture(block: () -> T): Pair<T, String> {
        val printed = ByteArrayOutputStream()
        val logged = StringBuffer()
        val handler = object : Handler() {
            override fun publish(record: LogRecord) {
                logged.append(SimpleFormatter().format(record))
            }

            override fun flush() {}
            override fun close() {}
        }
        val root = Logger.getLogger("")
        val (out, err) = System.out to System.err
        PrintStream(printed, true, Charsets.UTF_8).use { stream ->
            System.setOut(stream)
            System.setErr(stream)
            root.addHandler(handler)
            try {
                val result = block()
                return result to printed.toString(Charsets.UTF_8) + logged
            } finally {
                root.removeHandler(handler)
                System.setOut(out)
                System.setErr(err)
            }
        }
    }
}

/**
 * Counts the sockets pgJDBC opens when a URL names it with `socketFactory=`. pgJDBC creates an
 * unconnected socket through [createSocket] and connects it itself; the other overloads are unused.
 */
class CountingSocketFactory : SocketFactory() {

    override fun createSocket(): Socket {
        created.incrementAndGet()
        return Socket()
    }

    override fun createSocket(host: String, port: Int): Socket = unused()
    override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket = unused()
    override fun createSocket(host: InetAddress, port: Int): Socket = unused()
    override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress, localPort: Int): Socket =
        unused()

    private fun unused(): Nothing = throw UnsupportedOperationException("pgJDBC opens sockets with createSocket()")

    companion object {
        val created = AtomicInteger()
    }
}
