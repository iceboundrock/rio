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
import io.ktor.server.response.respond
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.MissingFieldException
import kotlinx.serialization.json.Json

/**
 * Central exception -> HTTP mapping.
 *
 *   ValidationException, malformed JSON  -> 400 VALIDATION_ERROR
 *   malformed Accept header              -> 400 VALIDATION_ERROR, before routing (ValidateAccept below)
 *   unsupported request content type     -> 415 VALIDATION_ERROR
 *   NotFoundException, unmatched route   -> 404 NOT_FOUND
 *   unacceptable Accept header           -> 406 VALIDATION_ERROR, before routing (ValidateAccept below)
 *   method not routed                    -> 405 VALIDATION_ERROR (framework-generated status)
 *   anything else                        -> 500 INTERNAL_ERROR (logged, message not exposed)
 *
 * This also installs request-side Accept validation, which can turn a request that would have
 * succeeded into a 400 or a 406. Response shaping and that rejection share this file so they cannot
 * drift: every response, success or error, is application/json, so whether a request can be answered
 * at all is decided from the Accept header alone, before any route runs (see acceptsProducedType).
 */
@OptIn(ExperimentalSerializationApi::class)
fun Application.configureErrorHandling() {
    install(StatusPages) {
        exception<ValidationException> { call, e ->
            call.respondApiError(HttpStatusCode.BadRequest, ApiError(ApiError.VALIDATION_ERROR, e.message ?: "invalid request"))
        }
        exception<NotAcceptableException> { call, e ->
            call.respondApiError(HttpStatusCode.NotAcceptable, ApiError(ApiError.VALIDATION_ERROR, e.message ?: NO_ACCEPTABLE_RESPONSE))
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
                missing != null -> "missing required fields: ${missing.describeFields()}"
                else -> "malformed request body"
            }
            call.respondApiError(HttpStatusCode.BadRequest, ApiError(ApiError.VALIDATION_ERROR, message))
        }
        exception<UnsupportedMediaTypeException> { call, _ ->
            call.respondUnsupportedContentType()
        }
        exception<CannotTransformContentToTypeException> { call, e ->
            // ApplicationRequest.contentType() throws on a malformed header. Thrown here, inside a
            // StatusPages handler, it would escape as the engine's default 500 echoing the raw header.
            val declared = call.request.headers[HttpHeaders.ContentType]?.takeIf { it.isNotBlank() }
            val actual = declared?.let { runCatching { ContentType.parse(it) }.getOrNull() }
            val expected = call.attributes.getOrNull(ExpectedRequestContentType)
            when {
                declared != null && actual == null ->
                    call.respondApiError(HttpStatusCode.BadRequest, ApiError(ApiError.VALIDATION_ERROR, "malformed Content-Type header"))
                expected != null && actual?.withoutParameters()?.match(expected) == true -> {
                    call.application.log.warn("Request content transformation failed; check receive type and ContentNegotiation configuration", e)
                    call.respondApiError(HttpStatusCode.InternalServerError, ApiError(ApiError.INTERNAL_ERROR, "internal server error"))
                }
                else -> {
                    call.application.log.debug("Unsupported request content type")
                    call.respondUnsupportedContentType()
                }
            }
        }
        exception<Throwable> { call, e ->
            call.application.log.error("Unhandled exception for ${call.request.local.method.value} ${call.request.local.uri}", e)
            call.respondApiError(HttpStatusCode.InternalServerError, ApiError(ApiError.INTERNAL_ERROR, "internal server error"))
        }
        status(HttpStatusCode.NotFound) { call, status ->
            call.respondApiError(status, ApiError(ApiError.NOT_FOUND, "no matching route"))
        }
        // Routing and ContentNegotiation produce these without an exception, so they would otherwise
        // answer with an empty body and break the "every error is an ApiError" contract.
        status(HttpStatusCode.MethodNotAllowed) { call, status ->
            call.respondApiError(status, ApiError(ApiError.VALIDATION_ERROR, "method not allowed"))
        }
        status(HttpStatusCode.NotAcceptable) { call, status ->
            // Unreachable while ValidateAccept rejects the same requests first; kept so a response type
            // this file does not know about still answers with the shared shape instead of an empty body.
            call.respondApiError(status, ApiError(ApiError.VALIDATION_ERROR, NO_ACCEPTABLE_RESPONSE))
        }
    }
    // Validate before route side effects, using the same syntax parser as ContentNegotiation. A route
    // that cannot be answered must not run: it would write, then fail negotiation, and the caller would
    // read an error for a transaction that exists.
    install(createApplicationPlugin("ValidateAccept") {
        onCall { call ->
            val ranges = try {
                parseHeaderValue(call.request.headers[HttpHeaders.Accept])
                    .map { ContentType.parse(it.value).withoutParameters() to it.quality }
            } catch (_: BadContentTypeFormatException) {
                throw ValidationException("malformed Accept header")
            }
            if (!acceptsProducedType(ranges)) throw NotAcceptableException(NO_ACCEPTABLE_RESPONSE)
        }
    })
}

private const val NO_ACCEPTABLE_RESPONSE = "no acceptable response media type"

/** The only media type this API produces, for successful responses and for errors alike. */
private val PRODUCED_TYPE = ContentType.Application.Json

/**
 * Whether these Accept media ranges, each paired with its quality, permit [PRODUCED_TYPE],
 * per RFC 9110 12.5.1. Parsing happens in the caller so this cannot throw on a malformed header.
 *
 * An absent or empty Accept expresses no preference and accepts anything. Otherwise the most specific
 * matching range decides - an exact `application/json`, then a subtype wildcard, then the catch-all -
 * and within one specificity the highest quality wins: `application/json;q=0` followed by the catch-all
 * is a rejection, while `application/json` followed by a catch-all at `q=0` is not. A quality of zero
 * excludes. Range parameters other than `q` are dropped by the caller rather than matched, because the
 * produced type carries none a range could select between: `application/json;charset=utf-16` is JSON.
 */
private fun acceptsProducedType(ranges: List<Pair<ContentType, Double>>): Boolean {
    if (ranges.isEmpty()) return true
    var specificity = -1
    var quality = 0.0
    for ((range, rangeQuality) in ranges) {
        if (!PRODUCED_TYPE.match(range)) continue
        val rank = when {
            range.contentType == "*" -> 0
            range.contentSubtype == "*" -> 1
            else -> 2
        }
        if (rank > specificity || (rank == specificity && rangeQuality > quality)) {
            specificity = rank
            quality = rangeQuality
        }
    }
    return quality > 0.0
}

// Bare field names are ambiguous when a nested object reuses one: MoneyDto.amount and
// CreateTransactionRequest.amount both report "amount". kotlinx appends the JSON path of the failing
// object to the message, so qualify the names with it. A path is a schema fact, not a request value;
// anything outside the schema-shaped character set is dropped rather than echoed.
private val MISSING_FIELD_PATH = Regex("""missing at path: \$([A-Za-z0-9_.\[\]]*)$""")

@OptIn(ExperimentalSerializationApi::class)
private fun MissingFieldException.describeFields(): String {
    val path = MISSING_FIELD_PATH.find(message.orEmpty())?.groupValues?.get(1).orEmpty().removePrefix(".")
    val prefix = if (path.isEmpty()) "" else "$path."
    return missingFields.joinToString { prefix + it }
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
