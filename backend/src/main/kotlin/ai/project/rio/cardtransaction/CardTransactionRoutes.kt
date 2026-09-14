package ai.project.rio.cardtransaction

import ai.project.rio.http.atItemIndex
import ai.project.rio.http.methodNotAllowed
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
 * Any other method answers 405 (OPTIONS: 204) with Allow listing the methods routed for that path plus OPTIONS.
 * Routes only translate HTTP <-> DTO <-> service call. Errors are mapped in http/ErrorHandling.kt.
 */
fun Route.cardTransactionRoutes(service: CardTransactionService) {
    route("/api/card-transactions") {

        get {
            val items = service.list().map { it.toDto() }
            call.respond(CardTransactionListResponse(items))
        }

        route("/{id}") {
            get {
                val id = call.parameters["id"]!!
                call.respond(service.get(id).toDto())
            }

            methodNotAllowed()
        }

        post {
            when (val body = call.receiveJson<CreateCardTransactionsBody>()) {
                is CreateCardTransactionsBody.One -> {
                    val created = service.create(body.request.toNewCardTransaction())
                    call.respond(HttpStatusCode.Created, created.toDto())
                }
                is CreateCardTransactionsBody.Many -> {
                    // Wire-to-domain failures name the item, as the service's own rules do.
                    val items = body.requests.mapIndexed { index, request -> atItemIndex(index) { request.toNewCardTransaction() } }
                    val created = service.createAll(items)
                    call.respond(HttpStatusCode.Created, CardTransactionListResponse(created.map { it.toDto() }))
                }
            }
        }

        methodNotAllowed()
    }
}
