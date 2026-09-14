package ai.project.rio.cardtransaction

import ai.project.rio.http.EcmaScript
import ai.project.rio.http.ValidationException
import ai.project.rio.http.atItemIndex
import ai.project.rio.http.methodNotAllowed
import ai.project.rio.http.receiveJson
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route

/**
 * GET  /api/card-transactions
 * GET  /api/card-transactions/{id}
 * POST /api/card-transactions   one request object -> CardTransaction; array -> { items } (all-or-nothing)
 *                               requires exactly one Idempotency-Key header; a replay answers 201 with
 *                               the original body, a different request under the same key 422
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
            val idempotencyKey = call.idempotencyKey()
            when (val body = call.receiveJson<CreateCardTransactionsBody>()) {
                is CreateCardTransactionsBody.One -> {
                    val created = service.create(idempotencyKey, body.request.toNewCardTransaction())
                    call.respond(HttpStatusCode.Created, created.toDto())
                }
                is CreateCardTransactionsBody.Many -> {
                    // Wire-to-domain failures name the item, as the service's own rules do.
                    val items = body.requests.mapIndexed { index, request -> atItemIndex(index) { request.toNewCardTransaction() } }
                    val created = service.createAll(idempotencyKey, items)
                    call.respond(HttpStatusCode.Created, CardTransactionListResponse(created.map { it.toDto() }))
                }
            }
        }

        methodNotAllowed()
    }
}

/**
 * The Idempotency-Key of a POST: exactly one field line, 1..255 characters, no control characters,
 * not blank. Read before the body so a client that forgot the key hears that first, whatever else is
 * wrong; nothing has been consumed at this point. The value is opaque: a comma inside it is not a
 * list separator, and case is significant. Blank is measured by the same whitespace set the contract
 * uses for descriptions; a whitespace-only field line arrives empty because field values exclude
 * their surrounding OWS (RFC 9110 5.5).
 */
private fun ApplicationCall.idempotencyKey(): String {
    val values = request.headers.getAll("Idempotency-Key") ?: emptyList()
    when {
        values.isEmpty() -> throw ValidationException("missing Idempotency-Key header")
        values.size > 1 -> throw ValidationException("multiple Idempotency-Key headers")
    }
    val key = values.single()
    if (key.isEmpty() || key.length > 255 || key.any { it.isISOControl() } || key.all(EcmaScript::isWhitespace)) {
        throw ValidationException("invalid Idempotency-Key header")
    }
    return key
}
