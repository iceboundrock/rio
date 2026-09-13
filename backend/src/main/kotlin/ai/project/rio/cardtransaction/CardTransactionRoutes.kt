package ai.project.rio.cardtransaction

import ai.project.rio.http.receiveJson
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route

/**
 * GET  /api/card-transactions
 * GET  /api/card-transactions/{id}
 * POST /api/card-transactions
 *
 * Routes only translate HTTP <-> DTO <-> service call. Errors are mapped in http/ErrorHandling.kt.
 */
fun Route.cardTransactionRoutes(service: CardTransactionService) {
    route("/api/card-transactions") {

        get {
            val items = service.list().map { it.toDto() }
            call.respond(CardTransactionListResponse(items))
        }

        get("/{id}") {
            val id = call.parameters["id"]!!
            call.respond(service.get(id).toDto())
        }

        post {
            val request = call.receiveJson<CreateCardTransactionRequest>()
            val created = service.create(
                description = request.description,
                amount = request.amount.toMoney(),
                type = parseCardTransactionType(request.type),
            )
            call.respond(HttpStatusCode.Created, created.toDto())
        }
    }
}
