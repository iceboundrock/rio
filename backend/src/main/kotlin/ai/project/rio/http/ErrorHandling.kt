package ai.project.rio.http

import io.ktor.http.BadContentTypeFormatException
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.parseHeaderValue
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.install
import io.ktor.server.application.log
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.CannotTransformContentToTypeException
import io.ktor.server.plugins.UnsupportedMediaTypeException
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.contentType
import io.ktor.server.response.respond
import io.ktor.util.AttributeKey
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.MissingFieldException
import kotlinx.serialization.json.Json

// The route declares what it can receive, independently of converter installation.
val ExpectedRequestContentType = AttributeKey<ContentType>("ExpectedRequestContentType")

/**
 * Central exception -> HTTP mapping.
 *
 *   ValidationException, malformed JSON  -> 400 VALIDATION_ERROR
 *   unsupported request content type     -> 415 VALIDATION_ERROR
 *   NotFoundException, unmatched route   -> 404 NOT_FOUND
 *   anything else                        -> 500 INTERNAL_ERROR (logged, message not exposed)
 */
@OptIn(ExperimentalSerializationApi::class)
fun Application.configureErrorHandling() {
    install(StatusPages) {
        exception<ValidationException> { call, e ->
            call.respondApiError(HttpStatusCode.BadRequest, ApiError(ApiError.VALIDATION_ERROR, e.message ?: "invalid request"))
        }
        exception<NotFoundException> { call, e ->
            call.respondApiError(HttpStatusCode.NotFound, ApiError(ApiError.NOT_FOUND, e.message ?: "not found"))
        }
        exception<BadRequestException> { call, e ->
            // ContentNegotiation wraps header parsing failures in BadRequestException.
            // Parser diagnostics can contain request values, keys, or the entire input.
            val causes = generateSequence<Throwable>(e) { it.cause }.toList()
            val missing = causes.filterIsInstance<MissingFieldException>().firstOrNull()
            val message = when {
                causes.any { it is BadContentTypeFormatException } -> "malformed Content-Type header"
                missing != null -> "missing required fields: ${missing.missingFields.joinToString()}"
                else -> "malformed request body"
            }
            call.respondApiError(HttpStatusCode.BadRequest, ApiError(ApiError.VALIDATION_ERROR, message))
        }
        exception<UnsupportedMediaTypeException> { call, _ ->
            call.respondUnsupportedContentType()
        }
        exception<CannotTransformContentToTypeException> { call, e ->
            val expected = call.attributes.getOrNull(ExpectedRequestContentType)
            if (expected != null && call.request.contentType().withoutParameters().match(expected)) {
                call.application.log.warn("Request content transformation failed; check receive type and ContentNegotiation configuration", e)
                call.respondApiError(HttpStatusCode.InternalServerError, ApiError(ApiError.INTERNAL_ERROR, "internal server error"))
            } else {
                call.application.log.debug("Unsupported request content type")
                call.respondUnsupportedContentType()
            }
        }
        exception<Throwable> { call, e ->
            call.application.log.error("Unhandled exception for ${call.request.local.method.value} ${call.request.local.uri}", e)
            call.respondApiError(HttpStatusCode.InternalServerError, ApiError(ApiError.INTERNAL_ERROR, "internal server error"))
        }
        status(HttpStatusCode.NotFound) { call, status ->
            call.respondApiError(status, ApiError(ApiError.NOT_FOUND, "no matching route"))
        }
    }
    // Validate before route side effects, using the same syntax parser as ContentNegotiation.
    install(createApplicationPlugin("ValidateAccept") {
        onCall { call ->
            try {
                parseHeaderValue(call.request.headers[HttpHeaders.Accept]).forEach { ContentType.parse(it.value) }
            } catch (_: BadContentTypeFormatException) {
                throw ValidationException("malformed Accept header")
            }
        }
    })
}

private suspend fun ApplicationCall.respondApiError(status: HttpStatusCode, error: ApiError) {
    // OutgoingContent bypasses negotiation, including a failed or incompatible Accept header.
    respond(TextContent(Json.encodeToString(error), ContentType.Application.Json, status))
}

private suspend fun ApplicationCall.respondUnsupportedContentType() {
    // Do not reflect header values or claim a required type that varies by route. Keep the exception
    // mappings explicit: other ContentTransformationException subtypes may represent server failures.
    val type = request.headers[HttpHeaders.ContentType]?.takeIf { it.isNotBlank() }
    val message = if (type == null) "missing Content-Type" else "unsupported Content-Type"
    respondApiError(HttpStatusCode.UnsupportedMediaType, ApiError(ApiError.VALIDATION_ERROR, message))
}
