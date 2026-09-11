package ai.project.rio.db

import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import org.sqlite.SQLiteConfig

/**
 * The one place SQLite connections are configured. Every connection gets:
 * - foreign keys enforced (unused by the starter schema, but on from day one);
 * - a busy timeout so concurrent writers wait instead of failing immediately.
 */
object Database {

    fun open(path: Path): JdbcTemplate = JdbcTemplate { connect(path) }

    private fun connect(path: Path): Connection {
        val config = SQLiteConfig().apply {
            enforceForeignKeys(true)
            setBusyTimeout(5_000)
        }
        return DriverManager.getConnection("jdbc:sqlite:${path.toAbsolutePath()}", config.toProperties())
    }
}
