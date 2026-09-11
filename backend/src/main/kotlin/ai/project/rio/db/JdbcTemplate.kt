package ai.project.rio.db

import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.Statement

/**
 * Per-statement JDBC settings, applied to every statement a [JdbcTemplate] prepares. `null` leaves
 * the driver default in place (the same meaning as Spring JdbcTemplate's `-1`).
 *
 * - [queryTimeoutSeconds]: `Statement.setQueryTimeout`; `0` means no limit.
 * - [maxRows]: `Statement.setMaxRows`; `0` means unlimited.
 * - [fetchSize]: `Statement.setFetchSize`; a hint the driver may ignore.
 */
data class StatementSettings(
    val queryTimeoutSeconds: Int? = null,
    val maxRows: Int? = null,
    val fetchSize: Int? = null,
) {
    fun applyTo(stmt: Statement) {
        queryTimeoutSeconds?.let(stmt::setQueryTimeout)
        maxRows?.let(stmt::setMaxRows)
        fetchSize?.let(stmt::setFetchSize)
    }
}

/** A single-row query returned a different number of rows than the caller required. */
class IncorrectResultSizeException(val expectedSize: Int, val actualSize: Int, sql: String) :
    IllegalStateException("expected $expectedSize row(s) but got $actualSize: $sql")

/**
 * Minimal JDBC helper in the spirit of Spring's JdbcTemplate: opens a connection per call, binds
 * parameters, applies [StatementSettings], maps rows, and closes everything deterministically.
 * It knows nothing about the domain (no Money, no Transaction). SQLExceptions are not translated;
 * they propagate as thrown by the driver.
 *
 * `transaction { tx -> ... }` runs the block against one connection with auto-commit off,
 * commits on success and rolls back on any exception. Nested transactions are not supported.
 *
 * `batchUpdate` called directly on the template runs inside its own transaction so the batch is
 * atomic; inside a `transaction` block, `tx.batchUpdate` joins the surrounding transaction.
 */
class JdbcTemplate(
    private val settings: StatementSettings = StatementSettings(),
    private val openConnection: () -> Connection,
) : JdbcExecutor {

    override fun <T> query(sql: String, params: List<Any?>, mapper: RowMapper<T>): List<T> =
        withConnection { it.query(sql, params, mapper) }

    override fun <T> queryOne(sql: String, params: List<Any?>, mapper: RowMapper<T>): T? =
        withConnection { it.queryOne(sql, params, mapper) }

    override fun <T> queryForObject(sql: String, params: List<Any?>, mapper: RowMapper<T>): T =
        withConnection { it.queryForObject(sql, params, mapper) }

    override fun <T> extract(sql: String, params: List<Any?>, extractor: ResultSetExtractor<T>): T =
        withConnection { it.extract(sql, params, extractor) }

    override fun update(sql: String, params: List<Any?>): Int =
        withConnection { it.update(sql, params) }

    override fun batchUpdate(sql: String, batchParams: List<List<Any?>>): IntArray =
        transaction { it.batchUpdate(sql, batchParams) }

    override fun execute(sql: String) =
        withConnection { it.execute(sql) }

    fun <T> transaction(block: (JdbcExecutor) -> T): T =
        openConnection().use { conn ->
            conn.autoCommit = false
            try {
                val result = block(ConnectionExecutor(conn, settings))
                conn.commit()
                result
            } catch (e: Throwable) {
                try {
                    conn.rollback()
                } catch (rollbackFailure: Throwable) {
                    e.addSuppressed(rollbackFailure)
                }
                throw e
            }
        }

    private fun <T> withConnection(block: (JdbcExecutor) -> T): T =
        openConnection().use { conn -> block(ConnectionExecutor(conn, settings)) }
}

/** Executes statements on one already-open connection. Does not close the connection. */
private class ConnectionExecutor(
    private val conn: Connection,
    private val settings: StatementSettings,
) : JdbcExecutor {

    override fun <T> query(sql: String, params: List<Any?>, mapper: RowMapper<T>): List<T> =
        extract(sql, params) { rs ->
            val rows = ArrayList<T>()
            while (rs.next()) rows.add(mapper(rs))
            rows
        }

    override fun <T> queryOne(sql: String, params: List<Any?>, mapper: RowMapper<T>): T? {
        val rows = query(sql, params, mapper)
        if (rows.size > 1) throw IncorrectResultSizeException(1, rows.size, sql)
        return rows.firstOrNull()
    }

    override fun <T> queryForObject(sql: String, params: List<Any?>, mapper: RowMapper<T>): T {
        val rows = query(sql, params, mapper)
        if (rows.size != 1) throw IncorrectResultSizeException(1, rows.size, sql)
        return rows[0]
    }

    override fun <T> extract(sql: String, params: List<Any?>, extractor: ResultSetExtractor<T>): T =
        prepare(sql, params).use { stmt -> stmt.executeQuery().use(extractor) }

    override fun update(sql: String, params: List<Any?>): Int =
        prepare(sql, params).use { it.executeUpdate() }

    override fun batchUpdate(sql: String, batchParams: List<List<Any?>>): IntArray {
        if (batchParams.isEmpty()) return IntArray(0)
        return prepare(sql, emptyList()).use { stmt ->
            batchParams.forEach { params ->
                params.forEachIndexed { i, value -> bind(stmt, i + 1, value) }
                stmt.addBatch()
            }
            stmt.executeBatch()
        }
    }

    override fun execute(sql: String) {
        conn.createStatement().use { stmt ->
            settings.applyTo(stmt)
            stmt.execute(sql)
        }
    }

    private fun prepare(sql: String, params: List<Any?>): PreparedStatement {
        val stmt = conn.prepareStatement(sql)
        try {
            settings.applyTo(stmt)
            params.forEachIndexed { i, value -> bind(stmt, i + 1, value) }
        } catch (e: Throwable) {
            stmt.close()
            throw e
        }
        return stmt
    }

    private fun bind(stmt: PreparedStatement, index: Int, value: Any?) {
        when (value) {
            null -> stmt.setObject(index, null)
            is String -> stmt.setString(index, value)
            is Int -> stmt.setInt(index, value)
            is Long -> stmt.setLong(index, value)
            is Boolean -> stmt.setBoolean(index, value)
            else -> throw IllegalArgumentException(
                "Unsupported JDBC parameter type ${value::class.qualifiedName} at index $index. " +
                    "Convert it to String/Int/Long/Boolean in the repository."
            )
        }
    }
}
