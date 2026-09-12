package ai.project.rio.http

import io.ktor.http.BadContentTypeFormatException
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.application.log
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.CannotTransformContentToTypeException
import io.ktor.server.plugins.UnsupportedMediaTypeException
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond

/**
 * Central exception -> HTTP mapping.
 *
 *   ValidationException, malformed JSON  -> 400 VALIDATION_ERROR
 *   unsupported request content type     -> 415 VALIDATION_ERROR
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
        exception<BadRequestException> { call, e ->
            // ContentNegotiation wraps header parsing failures in BadRequestException.
            // Parser diagnostics can contain request values, keys, or the entire input.
            val message = if (generateSequence<Throwable>(e) { it.cause }.any { it is BadContentTypeFormatException }) {
                "malformed Content-Type header"
            } else {
                "malformed request body"
            }
            call.respond(HttpStatusCode.BadRequest, ApiError(ApiError.VALIDATION_ERROR, message))
        }
        exception<UnsupportedMediaTypeException> { call, _ ->
            call.respondUnsupportedContentType()
        }
        exception<CannotTransformContentToTypeException> { call, e ->
            // This can also indicate missing/misconfigured receive converters, not just a bad media type.
            call.application.log.warn("Request content transformation failed; check receive type and ContentNegotiation configuration", e)
            call.respondUnsupportedContentType()
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

private suspend fun ApplicationCall.respondUnsupportedContentType() {
    // Report the supplied type, not a required type that varies by route. Keep the exception
    // mappings explicit: other ContentTransformationException subtypes may represent server failures.
    val type = request.headers[HttpHeaders.ContentType]?.takeIf { it.isNotBlank() }
    val message = if (type == null) "missing Content-Type" else "unsupported Content-Type: $type"
    respond(HttpStatusCode.UnsupportedMediaType, ApiError(ApiError.VALIDATION_ERROR, message))
}
