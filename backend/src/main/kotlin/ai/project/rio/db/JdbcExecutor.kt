package ai.project.rio.db

import java.sql.ResultSet

/** Maps the current row of a positioned [ResultSet]. Must not call `next()`. */
typealias RowMapper<T> = (ResultSet) -> T

/** Consumes a whole [ResultSet] (iterating it itself) and produces one value. */
typealias ResultSetExtractor<T> = (ResultSet) -> T

/**
 * The small set of JDBC operations repositories use, modelled on the shape of Spring's
 * `JdbcTemplate` (query / queryForObject / update / batchUpdate / execute) without the framework.
 * SQL is always written by the caller; runtime values are always bound as PreparedStatement
 * parameters.
 *
 * Implemented by [JdbcTemplate] (one connection per call) and by the executor handed to a
 * [JdbcTemplate.withTransaction] block (one connection for the whole block).
 */
interface JdbcExecutor {

    /** Maps every row. Returns an empty list when nothing matches. */
    fun <T> query(sql: String, params: List<Any?> = emptyList(), mapper: RowMapper<T>): List<T>

    /** Returns the first row or null. Throws [IncorrectResultSizeException] if more than one row matches. */
    fun <T> queryOne(sql: String, params: List<Any?> = emptyList(), mapper: RowMapper<T>): T?

    /** Returns exactly one row. Throws [IncorrectResultSizeException] on zero or several rows. */
    fun <T> queryForObject(sql: String, params: List<Any?> = emptyList(), mapper: RowMapper<T>): T

    /**
     * Hands the open [ResultSet] to [extractor], which iterates it itself (aggregation, streaming,
     * multi-row grouping). The ResultSet is closed when the extractor returns.
     */
    fun <T> extract(sql: String, params: List<Any?> = emptyList(), extractor: ResultSetExtractor<T>): T

    /** Executes INSERT/UPDATE/DELETE/DDL. Returns the affected row count. */
    fun update(sql: String, params: List<Any?> = emptyList()): Int

    /**
     * Executes one statement once per parameter list using JDBC batching. Returns the affected
     * row count per parameter list, in order. An empty [batchParams] executes nothing.
     */
    fun batchUpdate(sql: String, batchParams: List<List<Any?>>): IntArray

    /** Executes a parameterless statement with no result, typically DDL. */
    fun execute(sql: String)
}
