package ai.project.rio.transaction

import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route

/**
 * GET  /api/transactions
 * GET  /api/transactions/{id}
 * POST /api/transactions
 *
 * Routes only translate HTTP <-> DTO <-> service call. Errors are mapped in http/ErrorHandling.kt.
 */
fun Route.transactionRoutes(service: TransactionService) {
    route("/api/transactions") {

        get {
            val items = service.list().map { it.toDto() }
            call.respond(TransactionListResponse(items))
        }

        get("/{id}") {
            val id = call.parameters["id"]!!
            call.respond(service.get(id).toDto())
        }

        post {
            call.response.headers.append("Accept-Post", "application/json")
            val request = call.receive<CreateTransactionRequest>()
            val created = service.create(
                description = request.description,
                amount = request.amount.toMoney(),
                type = parseTransactionType(request.type),
            )
            call.respond(HttpStatusCode.Created, created.toDto())
        }
    }
}
