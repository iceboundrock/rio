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
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing

/**
 * Entry point. Wiring is done by hand right here; there is no DI container.
 *
 *   DB_URL       PostgreSQL JDBC URL (default jdbc:postgresql://localhost:5432/rio, the ./start.sh container)
 *   DB_USER      role to connect as  (default rio)
 *   DB_PASSWORD  its password        (default rio)
 *   PORT         HTTP port           (default 8080)
 */
fun main() {
    val url = System.getenv("DB_URL") ?: "jdbc:postgresql://localhost:5432/rio"
    val user = System.getenv("DB_USER") ?: "rio"
    val password = System.getenv("DB_PASSWORD") ?: "rio"
    val port = System.getenv("PORT")?.toInt() ?: 8080

    val database = Database.open(url, user, password)
    SchemaInitializer.initialize(database.jdbc, database.address)
    SchemaInitializer.seedIfEmpty(database.jdbc)

    embeddedServer(Netty, port = port) {
        monitor.subscribe(ApplicationStopped) { database.close() }
        module(database.jdbc)
    }.start(wait = true)
}

/** Ktor module. Tests call this directly with an empty test database. */
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
