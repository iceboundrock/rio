package ai.project.rio.db

/**
 * Base class for services that own multi-statement writes. `transactional { tx -> ... }` runs the
 * block on one connection, commits when it returns, and rolls back and rethrows on any exception.
 * Construct every repository the block touches from `tx`; a repository built from [jdbc] would
 * auto-commit on its own connection and escape the rollback. Single-statement reads and writes can
 * use [jdbc] directly.
 *
 * Knows only JDBC. Business rules stay in the subclass; SQL stays in repositories.
 */
abstract class TransactionalService(protected val jdbc: JdbcTemplate) {

    protected fun <T> transactional(block: (JdbcExecutor) -> T): T = jdbc.withTransaction(block)
}
