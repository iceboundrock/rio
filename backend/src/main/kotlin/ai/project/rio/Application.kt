package ai.project.rio

import ai.project.rio.db.Database
import ai.project.rio.db.JdbcTemplate
import ai.project.rio.db.SchemaInitializer
import ai.project.rio.http.acceptRanges
import ai.project.rio.http.configureErrorHandling
import ai.project.rio.http.fastjson2
import ai.project.rio.cardtransaction.CardTransactionService
import ai.project.rio.cardtransaction.cardTransactionRoutes
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import java.nio.file.Path

/**
 * Entry point. Wiring is done by hand right here; there is no DI container.
 *
 *   RIO_DB_PATH  SQLite file (default ./data/rio.db)
 *   PORT          HTTP port  (default 8080)
 */
fun main() {
    val dbPath = Path.of(System.getenv("RIO_DB_PATH") ?: "data/rio.db")
    val port = System.getenv("PORT")?.toInt() ?: 8080

    dbPath.toAbsolutePath().parent?.toFile()?.mkdirs()
    val jdbc = Database.open(dbPath)
    SchemaInitializer.initialize(jdbc)
    SchemaInitializer.seedIfEmpty(jdbc)

    embeddedServer(Netty, port = port) { module(jdbc) }.start(wait = true)
}

/** Ktor module. Tests call this directly with a temporary database. */
fun Application.module(jdbc: JdbcTemplate) {
    install(ContentNegotiation) {
        fastjson2()
        // Negotiate the success response from the same reading of Accept that admitted the request
        // (see acceptRanges); the plugin's own reading would 406 a repeated Accept line after the write.
        accept { call, _ -> call.request.acceptRanges() }
    }
    install(CallLogging)
    configureErrorHandling()

    val cardTransactionService = CardTransactionService(jdbc)

    routing {
        cardTransactionRoutes(cardTransactionService)
    }
}
