package ai.project.rio.http

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.application.log
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import kotlinx.serialization.SerializationException

/**
 * Central exception -> HTTP mapping.
 *
 *   ValidationException, malformed JSON  -> 400 VALIDATION_ERROR
 *   NotFoundException, unmatched route   -> 404 NOT_FOUND
 *   anything else                        -> 500 INTERNAL_ERROR (logged, message not exposed)
 */
fun Application.configureErrorHandling() {
    install(StatusPages) {
        exception<ValidationException> { call, e ->
            call.respond(HttpStatusCode.BadRequest, ApiError(ApiError.VALIDATION_ERROR, e.message ?: "invalid request"))
        }
        exception<NotFoundException> { call, e ->
            call.respond(HttpStatusCode.NotFound, ApiError(ApiError.NOT_FOUND, e.message ?: "not found"))
        }
        // Ktor wraps JSON parse/shape failures (missing field, wrong type) in BadRequestException.
        exception<BadRequestException> { call, e ->
            call.respond(HttpStatusCode.BadRequest, ApiError(ApiError.VALIDATION_ERROR, describeBadRequest(e)))
        }
        exception<Throwable> { call, e ->
            call.application.log.error("Unhandled exception for ${call.request.local.method.value} ${call.request.local.uri}", e)
            call.respond(HttpStatusCode.InternalServerError, ApiError(ApiError.INTERNAL_ERROR, "internal server error"))
        }
        status(HttpStatusCode.NotFound) { call, status ->
            call.respond(status, ApiError(ApiError.NOT_FOUND, "no route for ${call.request.local.uri}"))
        }
    }
}

private fun describeBadRequest(e: BadRequestException): String {
    val cause = generateSequence<Throwable>(e) { it.cause }.firstOrNull { it is SerializationException }
    return "malformed request body: ${cause?.message ?: e.message ?: "cannot parse JSON"}"
}
