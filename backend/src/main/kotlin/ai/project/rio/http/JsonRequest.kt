package ai.project.rio.http

import io.ktor.http.ContentType
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.util.AttributeKey

/**
 * What the route declared it can receive, independently of ContentNegotiation being configured for it.
 * ErrorHandling.kt reads this to tell a client sending the wrong media type (415) apart from a server
 * that cannot convert the media type the route does accept (500).
 */
val ExpectedRequestContentType = AttributeKey<ContentType>("ExpectedRequestContentType")

/**
 * Receive a JSON request body. Declaring the expected type, advertising it, and receiving are one
 * operation: a route that receives JSON without declaring it would downgrade a ContentNegotiation
 * misconfiguration from 500 to 415, blaming a correct client for a server fault.
 */
suspend inline fun <reified T : Any> ApplicationCall.receiveJson(): T {
    val json = ContentType.Application.Json
    attributes.put(ExpectedRequestContentType, json)
    // Ktor 3.5.2 has no HttpHeaders constant for Accept-Post (RFC 7231 registry entry).
    response.headers.append("Accept-Post", json.toString())
    return receive()
}
