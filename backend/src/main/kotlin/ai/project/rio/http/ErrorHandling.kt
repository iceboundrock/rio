package ai.project.rio.http

import com.alibaba.fastjson2.JSON
import io.ktor.http.BadContentTypeFormatException
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.install
import io.ktor.server.application.log
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.CannotTransformContentToTypeException
import io.ktor.server.plugins.UnsupportedMediaTypeException
import io.ktor.server.plugins.contentnegotiation.ContentTypeWithQuality
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.ApplicationRequest
import io.ktor.server.response.header
import io.ktor.server.response.respond

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
 * Because of that every response varies on Accept, and says so.
 */
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
            // ContentNegotiation wraps header parsing failures and every converter failure in
            // BadRequestException. Parser diagnostics can contain request values, keys, or the entire
            // input - fastjson2 names the offending key in "Unknown Property <key>" and the failing
            // constructor in "invoke constructor error" - so none of them is echoed.
            val malformedHeader = generateSequence<Throwable>(e) { it.cause }.any { it is BadContentTypeFormatException }
            val message = if (malformedHeader) "malformed Content-Type header" else "malformed request body"
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
    // Validate before route side effects. A route that cannot be answered must not run: it would
    // write, then fail negotiation, and the caller would read an error for a card transaction that exists.
    install(createApplicationPlugin("ValidateAccept") {
        onCall { call ->
            // Whether this call answers 200 or 406 is decided by Accept, so a cache must key on it
            // (RFC 9110 12.5.5) or it would serve a stored JSON body to a request the server rejects.
            call.response.header(HttpHeaders.Vary, HttpHeaders.Accept)
            if (!acceptsProducedType(call.request.acceptRanges())) throw NotAcceptableException(NO_ACCEPTABLE_RESPONSE)
        }
    })
}

private const val NO_ACCEPTABLE_RESPONSE = "no acceptable response media type"

/** The only media type this API produces, for successful responses and for errors alike. */
private val PRODUCED_TYPE = ContentType.Application.Json

// RFC 9110 grammar for Accept: `#( media-range [ weight ] )` where a media-range is `*/*`, `type/*`
// or `type/subtype` - `*` is a token character, so the wildcard type is excluded from the last form
// explicitly, or `*/json` would read as a media range - followed by
// `*( OWS ";" OWS [ token "=" ( token / quoted-string ) ] )` (5.6.2, 5.6.4, 5.6.6, 12.5.1)
// and a weight is the parameter `q` with a qvalue of `0` and up to three decimals or `1` and up to
// three zeros (12.4.2). Kotlin's `\s` is wider than OWS, which is only SP and HTAB.
private const val OWS = """[ \t]*"""
private const val TOKEN = """[!#$%&'*+\-.^_`|~0-9A-Za-z]+"""
private const val QUOTED_STRING = """"(?:[^"\\]|\\.)*""""
private const val MEDIA_RANGE = """\*/\*|(?!\*/)$TOKEN/$TOKEN"""
private val ACCEPT_ELEMENT = Regex("""($MEDIA_RANGE)((?:$OWS;$OWS(?:$TOKEN=(?:$TOKEN|$QUOTED_STRING))?)*)""")
private val ACCEPT_PARAMETER = Regex("""($TOKEN)=($TOKEN|$QUOTED_STRING)""")
private val QVALUE = Regex("""0(\.[0-9]{0,3})?|1(\.0{0,3})?""")

/**
 * The Accept media ranges of this request, each with its quality, read the way RFC 9110 requires:
 * repeated field lines combine in received order (5.2), empty list elements are ignored (5.6.1) and
 * the `q` parameter name is case-insensitive (12.4.2). Anything outside the grammar above is a
 * malformed Accept: the server does not guess at a preference it cannot read. Ktor's own reading is
 * looser on every point - `ContentNegotiation` sees the first line only, `ContentType.parse` turns a
 * bare `*` into the catch-all and tolerates spaces around the slash, `parseHeaderValue` strips the
 * quotes from a parameter value, and `HeaderValue.quality` recognises a lower-case `q` and silently
 * treats an unparseable value as 1 - so the response converter is given this list too (Application.kt);
 * otherwise a request this guard admits could still fail negotiation after the route has run. Range
 * parameters other than `q` are parsed but not matched, because the produced type carries none a range
 * could select between: `application/json;charset=utf-16` is JSON.
 */
fun ApplicationRequest.acceptRanges(): List<ContentTypeWithQuality> {
    val text = headers.getAll(HttpHeaders.Accept)?.joinToString(",") ?: return emptyList()
    val ranges = mutableListOf<ContentTypeWithQuality>()
    var position = 0
    while (position < text.length) {
        when (text[position]) {
            ' ', '\t', ',' -> position++ // OWS and empty list elements
            else -> {
                val element = ACCEPT_ELEMENT.matchAt(text, position) ?: throw ValidationException("malformed Accept header")
                val q = ACCEPT_PARAMETER.findAll(element.groupValues[2])
                    .firstOrNull { it.groupValues[1].equals("q", ignoreCase = true) }?.groupValues?.get(2)
                if (q != null && !QVALUE.matches(q)) throw ValidationException("malformed Accept header")
                ranges += ContentTypeWithQuality(ContentType.parse(element.groupValues[1]), q?.toDouble() ?: 1.0)
                position = element.range.last + 1
                while (position < text.length && (text[position] == ' ' || text[position] == '\t')) position++
                if (position < text.length && text[position] != ',') throw ValidationException("malformed Accept header")
            }
        }
    }
    return ranges
}

/**
 * Whether these Accept media ranges permit [PRODUCED_TYPE], per RFC 9110 12.5.1. Parsing happens in
 * [acceptRanges] so this cannot throw on a malformed header.
 *
 * An absent or empty Accept expresses no preference and accepts anything. Otherwise the most specific
 * matching range decides - an exact `application/json`, then a subtype wildcard, then the catch-all -
 * and within one specificity the highest quality wins: `application/json;q=0` followed by the catch-all
 * is a rejection, while `application/json` followed by a catch-all at `q=0` is not. A quality of zero
 * excludes.
 */
private fun acceptsProducedType(ranges: List<ContentTypeWithQuality>): Boolean {
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

private suspend fun ApplicationCall.respondApiError(status: HttpStatusCode, error: ApiError) {
    // OutgoingContent bypasses negotiation, including a failed or incompatible Accept header.
    respond(TextContent(JSON.toJSONString(error), ContentType.Application.Json, status))
}

private suspend fun ApplicationCall.respondUnsupportedContentType() {
    // Do not reflect header values or claim a required type that varies by route. Keep the exception
    // mappings explicit: other ContentTransformationException subtypes may represent server failures.
    val type = request.headers[HttpHeaders.ContentType]?.takeIf { it.isNotBlank() }
    val message = if (type == null) "missing Content-Type" else "unsupported Content-Type"
    respondApiError(HttpStatusCode.UnsupportedMediaType, ApiError(ApiError.VALIDATION_ERROR, message))
}
