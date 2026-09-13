package ai.project.rio.db

/**
 * Base class for services that own multi-statement writes. `transactional { tx -> ... }` runs the
 * block on one connection, commits when it returns, and rolls back and rethrows on any exception.
 * Construct every repository the block touches from `tx`.
 *
 * The template is private on purpose: inside a member function only `tx` is reachable, so a
 * repository built from the template by mistake (which would auto-commit on its own connection and
 * escape the rollback) does not compile. Standalone single-statement repositories are built from
 * the subclass's own constructor parameter in a property initializer, as `CardTransactionService`
 * does for its reads.
 *
 * Knows only JDBC. Business rules stay in the subclass; SQL stays in repositories.
 */
abstract class TransactionalService(private val jdbc: JdbcTemplate) {

    protected fun <T> transactional(block: (JdbcExecutor) -> T): T = jdbc.withTransaction(block)
}
