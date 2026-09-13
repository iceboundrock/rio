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
 * POST /api/card-transactions   one request object -> CardTransaction; array -> { items } (all-or-nothing)
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
            when (val body = call.receiveJson<CreateCardTransactionsBody>()) {
                is CreateCardTransactionsBody.One -> {
                    val created = service.createAll(listOf(body.request.toNewCardTransaction())).single()
                    call.respond(HttpStatusCode.Created, created.toDto())
                }
                is CreateCardTransactionsBody.Many -> {
                    val created = service.createAll(body.requests.map { it.toNewCardTransaction() })
                    call.respond(HttpStatusCode.Created, CardTransactionListResponse(created.map { it.toDto() }))
                }
            }
        }
    }
}
