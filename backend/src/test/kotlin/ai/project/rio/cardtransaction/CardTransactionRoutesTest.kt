package ai.project.rio.cardtransaction

import ai.project.rio.contract.JsonSchemaAssertions.assertMatchesSchema
import ai.project.rio.contract.JsonSchemaAssertions.assertViolatesSchema
import ai.project.rio.db.Database
import ai.project.rio.db.SchemaInitializer
import ai.project.rio.http.IdempotencyConflictException
import ai.project.rio.http.configureErrorHandling
import ai.project.rio.http.fastjson2
import ai.project.rio.module
import ai.project.rio.money.Currency
import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.alibaba.fastjson2.JSON
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.contentType
import io.ktor.server.application.Application
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.install
import io.ktor.server.application.log
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.receiveMultipart
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * Exercises the real Ktor pipeline against a temporary SQLite file and validates every
 * response body against the shared JSON Schemas in contracts/schemas.
 */
class CardTransactionRoutesTest {

    private lateinit var dbFile: Path

    @BeforeTest
    fun setUp() {
        dbFile = Files.createTempFile("card-transaction-routes-test", ".db")
        val jdbc = Database.open(dbFile)
        SchemaInitializer.initialize(jdbc)
        SchemaInitializer.seedIfEmpty(jdbc)
    }

    @AfterTest
    fun tearDown() {
        Files.deleteIfExists(dbFile)
    }

    private fun withApp(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        application { module(Database.open(dbFile)) }
        block()
    }

    private suspend fun ApplicationTestBuilder.postJson(body: String): HttpResponse =
        client.post("/api/card-transactions") {
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    private val validRequest = """{"description":"Lunch","amount":{"amount":"1800","currency":"USD"},"type":"DEBIT"}"""

    /**
     * Raw sockets rather than the Ktor test client: the client parses Content-Type and Accept while
     * building the request and rejects malformed values before they ever reach the server.
     */
    private fun withRawServer(app: Application.() -> Unit = { module(Database.open(dbFile)) }, block: suspend (Int) -> Unit) = runBlocking {
        val server = embeddedServer(Netty, host = "127.0.0.1", port = 0, module = app)
        try {
            server.start(wait = false)
            block(server.engine.resolvedConnectors().single().port)
        } finally {
            server.stop(0, 5_000)
        }
    }

    // HTTP/1.0 + Connection: close avoids chunk framing and keeps the whole response readable to EOF.
    private fun rawRequest(port: Int, requestLine: String, headers: List<String> = emptyList(), body: String = ""): RawResponse {
        val text = Socket("127.0.0.1", port).use { socket ->
            socket.soTimeout = 10_000
            val request = buildString {
                append("$requestLine HTTP/1.0\r\nHost: localhost\r\nConnection: close\r\n")
                headers.forEach { append("$it\r\n") }
                append("Content-Length: ${body.toByteArray().size}\r\n\r\n")
                append(body)
            }
            socket.getOutputStream().write(request.toByteArray())
            socket.getOutputStream().flush()
            socket.getInputStream().bufferedReader().readText()
        }
        return RawResponse(text.substringBefore("\r\n\r\n"), text.substringAfter("\r\n\r\n"))
    }

    private data class RawResponse(val head: String, val body: String) {
        val status: Int get() = head.substringBefore("\r\n").split(" ")[1].toInt()
        val raw: String get() = "$head\r\n\r\n$body"
        fun hasHeader(line: String): Boolean = head.lineSequence().any { it.equals(line, ignoreCase = true) }
        fun message(): String = JSON.parseObject(body).getString("message")
    }

    @Test
    fun `raw HTTP media types preserve malformed blank and mixed case headers`() = withRawServer { port ->
        val cases = listOf(
            Triple(null, 415, "missing Content-Type"),
            Triple("", 415, "missing Content-Type"),
            Triple("   ", 415, "missing Content-Type"),
            Triple("not a mime type", 400, "malformed Content-Type header"),
            Triple("text/plain", 415, "unsupported Content-Type"),
            Triple("text/" + "x".repeat(2000), 415, "unsupported Content-Type"),
            Triple("text/plain; secret=" + "x".repeat(2000), 415, "unsupported Content-Type"),
            Triple("Application/JSON; charset=utf-8", 201, null),
            Triple("APPLICATION/JSON;CHARSET=UTF-8", 201, null),
            Triple("application/json;charset=", 201, null),
        )
        for ((type, status, message) in cases) {
            val headers = listOfNotNull(type?.let { "Content-Type: $it" })
            val response = rawRequest(port, "POST /api/card-transactions", headers, validRequest)
            assertEquals(status, response.status, "Content-Type: $type; ${response.raw}")
            assertTrue(response.hasHeader("Accept-Post: application/json"))
            assertMatchesSchema(response.body, if (status == 201) "card-transaction.schema.json" else "api-error.schema.json")
            if (message != null) {
                assertEquals("VALIDATION_ERROR", JSON.parseObject(response.body).getString("code"))
                assertEquals(message, response.message())
            }
        }
    }

    @Test
    fun `raw Accept headers cannot bypass error serialization`() = withRawServer({
        module(Database.open(dbFile))
        routing { get("/test-failure") { error("server-only-secret") } }
    }) { port ->
        val cases = listOf(
            Triple("GET /api/card-transactions", null, 200),
            Triple("GET /api/card-transactions/nope", null, 404),
            Triple("GET /api/no-route", null, 404),
            Triple("POST /api/card-transactions", "text/plain", 415),
            Triple("POST /api/card-transactions", "application/json", 400),
            Triple("POST /api/card-transactions", "application/json", 201),
            Triple("GET /test-failure", null, 500),
        )
        val repository = CardTransactionRepository(Database.open(dbFile))
        for (accept in listOf("**", "**secret-marker", "text/plain", "application/json")) {
            // Malformed and unacceptable Accept headers are both rejected before routing, so no target
            // reaches its handler: the status describes the header, not what the route would have done.
            val rejectedBeforeRouting = accept.startsWith("**") || accept == "text/plain"
            val countBefore = repository.findAll().size
            for ((target, type, normalStatus) in cases) {
                val body = if (normalStatus == 201) validRequest else ""
                val headers = listOfNotNull("Accept: $accept", type?.let { "Content-Type: $it" })
                val response = rawRequest(port, target, headers, body)
                val expectedStatus = when {
                    accept.startsWith("**") -> 400
                    accept == "text/plain" -> 406
                    else -> normalStatus
                }
                assertEquals(expectedStatus, response.status, response.raw)
                assertTrue(response.hasHeader("Content-Type: application/json"), response.raw)
                val schema = when (expectedStatus) {
                    200 -> "card-transaction-list-response.schema.json"
                    201 -> "card-transaction.schema.json"
                    else -> "api-error.schema.json"
                }
                assertMatchesSchema(response.body, schema)
                assertTrue(!response.body.contains("secret-marker") && !response.body.contains("server-only-secret"), response.body)
                when {
                    accept.startsWith("**") -> assertEquals("malformed Accept header", response.message())
                    expectedStatus == 406 -> assertEquals("no acceptable response media type", response.message())
                }
            }
            if (rejectedBeforeRouting) assertEquals(countBefore, repository.findAll().size)
        }
    }

    /**
     * Selected negotiation semantics (RFC 9110 12.5.1), enforced before routing: absent or empty
     * Accept means no preference; the most specific matching media range decides - an exact
     * `application/json`, then a subtype wildcard, then the catch-all - and within one specificity the
     * highest q wins; q=0 excludes; range parameters other than q are ignored. Every response this API
     * can produce is application/json, so a request excluding it must not reach a route at all.
     *
     * Each case is the list of Accept field lines sent: repeated lines combine in received order
     * (RFC 9110 5.2) and empty list elements are ignored (5.6.1). The parameter name `q` is
     * case-insensitive (12.4.2). Anything outside the Accept grammar - a bare `*`, a wildcard type
     * with a concrete subtype, a quoted qvalue, whitespace inside a media range - is a malformed
     * header (400), not a preference the server guesses at; a quoted-string parameter other than `q`
     * is grammatical and may contain `,` or `;`. Every answer varies on Accept and says so.
     */
    @Test
    fun `an unacceptable Accept is rejected before the route runs`() = withRawServer { port ->
        val unacceptable = listOf(
            listOf("application/json;q=0"),
            listOf("application/json;Q=0"),
            listOf("*/*;q=0"),
            listOf("*/*;Q=0"),
            listOf("application/*;q=0"),
            listOf("application/json;q=0, */*"),
            listOf("application/json;Q=0, */*"),
            listOf("text/plain"),
            listOf("text/plain, text/html"),
            listOf("text/plain, "),
            listOf("text/html"),
            listOf("text/*"),
            listOf("application/xml"),
            listOf("text/plain", "text/html"),
            listOf("application/json;q=0", "*/*"),
            listOf("text/plain", ""),
            listOf("application/json;charset=\"a,b\";q=0"),
            listOf("text/plain;note=\"a;q=1\""),
            listOf("application/json ;; q=0"),
        )
        val acceptable = listOf(
            null,
            listOf(""),
            listOf("application/json"),
            listOf("Application/JSON"),
            listOf("application/json; charset=utf-8"),
            listOf("application/json;Q=0.5"),
            listOf("*/*"),
            listOf("application/*"),
            listOf("text/plain, application/json"),
            listOf("text/plain;q=0.9, application/json;q=0.1"),
            listOf("text/plain;Q=0.9, application/json;Q=0.1"),
            listOf("text/html, */*;q=0.5"),
            listOf("application/json, */*;q=0"),
            listOf("application/json, */*;Q=0"),
            listOf("text/plain", "application/json"),
            listOf("application/json", "text/plain"),
            listOf("", "application/json"),
            listOf("text/html", "*/*;q=0.5"),
            listOf(","),
            listOf("application/json ; q=0.5"),
            listOf("application/json;q=0.5;ext=1"),
            listOf("application/json;charset=\"utf-8\""),
            listOf("application/json;"),
            listOf("*/*;q=0.001"),
        )
        val malformed = listOf(
            listOf("application/json;q=abc"),
            listOf("application/json;q="),
            listOf("application/json;q=2"),
            listOf("application/json;q=-1"),
            listOf("application/json;q=1.5"),
            listOf("application/json;q=.5"),
            listOf("application/json;q=0.1234"),
            listOf("application/json;Q=abc"),
            listOf("application/json", "text/plain;q=2"),
            listOf("*"),
            listOf("*;q=0.5"),
            listOf("text/plain, *"),
            listOf("application/json", "*"),
            listOf("*/json"),
            listOf("*/json;q=0"),
            listOf("*/JSON"),
            listOf("text/plain, */json"),
            listOf("application/json", "*/json"),
            listOf("application/json;q=\"0.5\""),
            listOf("application/json;q=\"0\""),
            listOf("application / json"),
            listOf("application"),
            listOf("/json"),
            listOf("application/"),
            listOf("application/json foo"),
            listOf("application/json;charset"),
            listOf("application/json;charset=\"open"),
            listOf("application/json;q = 0"),
        )
        val repository = CardTransactionRepository(Database.open(dbFile))
        for (accept in acceptable + unacceptable + malformed) {
            val expectedError = when (accept) {
                in unacceptable -> 406 to "no acceptable response media type"
                in malformed -> 400 to "malformed Accept header"
                else -> null
            }
            val headers = accept.orEmpty().map { "Accept: $it" }
            for ((target, success, schema) in listOf(
                Triple("GET /api/card-transactions", 200, "card-transaction-list-response.schema.json"),
                Triple("POST /api/card-transactions", 201, "card-transaction.schema.json"),
            )) {
                val post = target.startsWith("POST")
                val countBefore = repository.findAll().size
                val response = rawRequest(
                    port,
                    target,
                    if (post) headers + "Content-Type: application/json" else headers,
                    if (post) validRequest else "",
                )
                val context = "Accept: $accept; $target; ${response.raw}"
                assertEquals(expectedError?.first ?: success, response.status, context)
                assertTrue(response.hasHeader("Content-Type: application/json"), context)
                assertTrue(response.hasHeader("Vary: Accept"), context)
                assertMatchesSchema(response.body, if (expectedError != null) "api-error.schema.json" else schema)
                if (expectedError != null) {
                    assertEquals("VALIDATION_ERROR", JSON.parseObject(response.body).getString("code"))
                    assertEquals(expectedError.second, response.message())
                }
                // The write is the side effect a rejected request must not leave behind.
                assertEquals(countBefore + if (post && expectedError == null) 1 else 0, repository.findAll().size, context)
            }
        }
    }

    @Test
    fun `framework generated statuses carry the shared error shape`() = withRawServer { port ->
        // Routing produces this without throwing, so it never reaches an exception handler and used to
        // answer with an empty body. An unsatisfiable Accept no longer reaches the framework at all.
        // RFC 9110 15.5.6 requires the Allow header on every 405, listing the methods the target
        // supports (10.2.1): the ones it routes plus OPTIONS, which the fallback itself answers.
        val targets = listOf(
            "/api/card-transactions" to "GET, POST, OPTIONS",
            "/api/card-transactions/seed-0001" to "GET, OPTIONS",
        )
        for ((target, allow) in targets) {
            val unrouted = listOf("POST", "PUT", "DELETE", "PATCH", "HEAD").filter { it !in allow.split(", ") }
            for (method in unrouted) {
                val response = rawRequest(port, "$method $target")
                assertEquals(405, response.status, response.raw)
                assertTrue(response.hasHeader("Allow: $allow"), response.raw)
                assertTrue(response.hasHeader("Vary: Accept"), response.raw)
                if (method == "HEAD") continue // no body on HEAD, whatever the status
                assertMatchesSchema(response.body, "api-error.schema.json")
                assertEquals("method not allowed", response.message())
            }
        }
    }

    @Test
    fun `OPTIONS answers 204 with the target's Allow header`() = withRawServer { port ->
        // RFC 9110 9.3.7: the response to OPTIONS describes the target's communication options.
        for ((target, allow) in listOf("/api/card-transactions" to "GET, POST, OPTIONS", "/api/card-transactions/seed-0001" to "GET, OPTIONS")) {
            val response = rawRequest(port, "OPTIONS $target")
            assertEquals(204, response.status, response.raw)
            assertTrue(response.hasHeader("Allow: $allow"), response.raw)
            assertEquals("", response.body, response.raw)
        }
    }

    @Test
    fun `the method fallback does not claim paths the resource does not route`() = withRawServer { port ->
        // A bare handler under a route is a candidate only when the whole path is consumed, so an
        // unknown sub-path stays 404, without an Allow header, rather than becoming a 405 or 204.
        for (method in listOf("GET", "PUT", "OPTIONS")) {
            val response = rawRequest(port, "$method /api/card-transactions/seed-0001/extra")
            assertEquals(404, response.status, response.raw)
            assertTrue(response.head.lineSequence().none { it.startsWith("Allow:", ignoreCase = true) }, response.raw)
            assertEquals("no matching route", response.message())
        }
    }

    @Test
    fun `a malformed Content-Type cannot make the error handler itself fail`() = withRawServer({
        // No ContentNegotiation: receive() fails, and the handler for that failure must not re-parse
        // the header unguarded - a throw inside StatusPages escapes as a plain-text engine 500.
        configureErrorHandling()
        routing { cardTransactionRoutes(CardTransactionService(Database.open(dbFile))) }
    }) { port ->
        val malformed = rawRequest(port, "POST /api/card-transactions", listOf("Content-Type: not a mime type"), validRequest)
        assertEquals(400, malformed.status, malformed.raw)
        assertTrue(malformed.hasHeader("Content-Type: application/json"), malformed.raw)
        assertMatchesSchema(malformed.body, "api-error.schema.json")
        assertEquals("malformed Content-Type header", malformed.message())
        assertTrue(!malformed.body.contains("not a mime type"), malformed.body)

        // The genuine misconfiguration this handler exists for still reports a server fault.
        val declared = rawRequest(port, "POST /api/card-transactions", listOf("Content-Type: application/json"), validRequest)
        assertEquals(500, declared.status, declared.raw)
        assertMatchesSchema(declared.body, "api-error.schema.json")
    }

    @Test
    fun `ordinary unsupported media types do not log warnings`() = withApp {
        val events = ListAppender<ILoggingEvent>().apply { start() }
        var logger: Logger? = null
        application {
            logger = log as Logger
            logger.addAppender(events)
        }
        try {
            for (type in listOf(null, ContentType.Text.Plain, ContentType.parse("application/vnd.api+json"))) {
                val response = client.post("/api/card-transactions") {
                    setBody(object : OutgoingContent.ByteArrayContent() {
                        override val contentType: ContentType? = type
                        override fun bytes(): ByteArray = validRequest.toByteArray()
                    })
                }
                assertEquals(HttpStatusCode.UnsupportedMediaType, response.status)
                assertMatchesSchema(response.bodyAsText(), "api-error.schema.json")
            }
            assertTrue(events.list.none { it.level.isGreaterOrEqual(Level.WARN) })
        } finally {
            logger?.detachAppender(events)
            events.stop()
        }
    }

    @Test
    fun `missing ContentNegotiation is a server error with a JSON response`() = testApplication {
        application {
            configureErrorHandling()
            routing { cardTransactionRoutes(CardTransactionService(Database.open(dbFile))) }
        }
        val response = postJson(validRequest)
        assertEquals(HttpStatusCode.InternalServerError, response.status)
        val body = response.bodyAsText()
        assertMatchesSchema(body, "api-error.schema.json")
        assertEquals("INTERNAL_ERROR", JSON.parseObject(body).getString("code"))
    }

    @Test
    fun `misconfigured receive converter produces a server warning`() = testApplication {
        val events = ListAppender<ILoggingEvent>().apply { start() }
        var logger: Logger? = null
        application {
            logger = log as Logger
            logger.addAppender(events)
            install(ContentNegotiation) {
                fastjson2()
                // Valid JSON can no longer be converted to the request DTO; responses still serialize.
                ignoreType<CreateCardTransactionsBody>()
            }
            configureErrorHandling()
            routing { cardTransactionRoutes(CardTransactionService(Database.open(dbFile))) }
        }
        try {
            val response = postJson(validRequest)
            assertEquals(HttpStatusCode.InternalServerError, response.status)
            assertMatchesSchema(response.bodyAsText(), "api-error.schema.json")
            assertEquals("INTERNAL_ERROR", JSON.parseObject(response.bodyAsText()).getString("code"))
            assertTrue(events.list.any {
                it.level == Level.WARN && it.formattedMessage.contains("ContentNegotiation configuration") &&
                    it.throwableProxy?.className == "io.ktor.server.plugins.CannotTransformContentToTypeException"
            })
        } finally {
            logger?.detachAppender(events)
            events.stop()
        }
    }

    @Test
    fun `an idempotency conflict is 422 with the shared error shape`() = withApp {
        application {
            routing { get("/test-conflict") { throw IdempotencyConflictException("boom") } }
        }
        val response = client.get("/test-conflict")
        assertEquals(HttpStatusCode.UnprocessableEntity, response.status)
        assertEquals("application/json", response.headers[HttpHeaders.ContentType])
        val body = response.bodyAsText()
        assertMatchesSchema(body, "api-error.schema.json")
        assertEquals("IDEMPOTENCY_CONFLICT", JSON.parseObject(body).getString("code"))
        assertEquals("boom", JSON.parseObject(body).getString("message"))
    }

    // ---- Cross-layer currency and media-type contracts ----
    @Test
    fun `every supported currency persists and satisfies the contract`() = withApp {
        for (currency in Currency.entries) {
            val request = """{"description":"Currency test","amount":{"amount":"9223372036854775807","currency":"${currency.code}"},"type":"CREDIT"}"""
            assertMatchesSchema(request, "create-card-transaction-request.schema.json")
            val response = postJson(request)
            assertEquals(HttpStatusCode.Created, response.status)
            val body = response.bodyAsText()
            assertMatchesSchema(body, "card-transaction.schema.json")
            val json = JSON.parseObject(body)
            assertEquals(currency.code, json.getJSONObject("amount").getString("currency"))
            assertEquals("9223372036854775807", json.getJSONObject("amount").getString("amount"))
            val fetched = client.get("/api/card-transactions/${json.getString("id")}")
            assertEquals(HttpStatusCode.OK, fetched.status)
            assertEquals(body, fetched.bodyAsText())
        }
        assertMatchesSchema(client.get("/api/card-transactions").bodyAsText(), "card-transaction-list-response.schema.json")
    }

    @Test
    fun `missing and unsupported content types return 415`() = withApp {
        val receivedTypes = mutableListOf<String?>()
        application {
            install(createApplicationPlugin("CaptureContentType") {
                onCall { call -> receivedTypes.add(call.request.headers[HttpHeaders.ContentType]) }
            })
        }
        for (type in listOf(null, ContentType.Application.OctetStream, ContentType.Text.Plain, ContentType.Application.FormUrlEncoded, ContentType.parse("application/vnd.api+json"))) {
            val response = client.post("/api/card-transactions") {
                setBody(object : OutgoingContent.ByteArrayContent() {
                    override val contentType: ContentType? = type
                    override fun bytes(): ByteArray = validRequest.toByteArray()
                })
            }
            assertEquals(listOf(type?.toString()), receivedTypes.toList())
            receivedTypes.clear()
            assertEquals(HttpStatusCode.UnsupportedMediaType, response.status)
            assertEquals("application/json", response.headers["Accept-Post"])
            val body = response.bodyAsText()
            assertMatchesSchema(body, "api-error.schema.json")
            assertEquals("VALIDATION_ERROR", JSON.parseObject(body).getString("code"))
            val message = if (type == null) "missing Content-Type" else "unsupported Content-Type"
            assertEquals(message, JSON.parseObject(body).getString("message"))
        }
    }

    @Test
    fun `multipart receive rejects JSON without advising the caller to send JSON`() = withApp {
        application {
            routing {
                post("/test-multipart") { call.receiveMultipart() }
            }
        }
        val response = client.post("/test-multipart") {
            contentType(ContentType.Application.Json)
            setBody(validRequest)
        }
        assertEquals(HttpStatusCode.UnsupportedMediaType, response.status)
        assertEquals(null, response.headers["Accept-Post"])
        val body = response.bodyAsText()
        assertMatchesSchema(body, "api-error.schema.json")
        val error = JSON.parseObject(body)
        assertEquals("VALIDATION_ERROR", error.getString("code"))
        assertEquals("unsupported Content-Type", error.getString("message"))
    }

    // ---- GET list ----

    @Test
    fun `GET list returns seeded card transactions matching the schema`() = withApp {
        val response = client.get("/api/card-transactions")
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertMatchesSchema(body, "card-transaction-list-response.schema.json")
        assertEquals(SchemaInitializer.SEED.size, JSON.parseObject(body).getJSONArray("items").size)
    }

    // ---- GET one ----

    @Test
    fun `GET known id returns the card transaction`() = withApp {
        val response = client.get("/api/card-transactions/seed-0002")
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertMatchesSchema(body, "card-transaction.schema.json")
        val json = JSON.parseObject(body)
        assertEquals("Blue Bottle Coffee", json.getString("description"))
        assertEquals("525", json.getJSONObject("amount").getString("amount"))
        assertEquals("USD", json.getJSONObject("amount").getString("currency"))
        assertEquals("2026-09-02T15:30:00Z", json.getString("createdAt"))
    }

    @Test
    fun `GET unknown id returns 404 with the error shape`() = withApp {
        val response = client.get("/api/card-transactions/nope")
        assertEquals(HttpStatusCode.NotFound, response.status)
        val body = response.bodyAsText()
        assertMatchesSchema(body, "api-error.schema.json")
        assertEquals("NOT_FOUND", JSON.parseObject(body).getString("code"))
    }

    @Test
    fun `unmatched route returns 404 with the error shape`() = withApp {
        val response = client.get("/api/nothing-here")
        assertEquals(HttpStatusCode.NotFound, response.status)
        assertMatchesSchema(response.bodyAsText(), "api-error.schema.json")
    }

    // ---- POST ----

    @Test
    fun `POST valid card transaction returns 201 and persists it`() = withApp {
        assertMatchesSchema(validRequest, "create-card-transaction-request.schema.json")

        val response = postJson(validRequest)
        assertEquals(HttpStatusCode.Created, response.status)
        val body = response.bodyAsText()
        assertMatchesSchema(body, "card-transaction.schema.json")

        val created = JSON.parseObject(body)
        assertEquals("Lunch", created.getString("description"))
        assertEquals("1800", created.getJSONObject("amount").getString("amount"))
        assertEquals("DEBIT", created.getString("type"))
        assertEquals("COMPLETED", created.getString("status"))

        val id = created.getString("id")
        val fetched = client.get("/api/card-transactions/$id")
        assertEquals(HttpStatusCode.OK, fetched.status)
        assertEquals(body, fetched.bodyAsText())
    }

    @Test
    fun `POST JPY card transaction round-trips zero-precision money`() = withApp {
        val response = postJson("""{"description":"Ramen","amount":{"amount":"1200","currency":"JPY"},"type":"DEBIT"}""")
        assertEquals(HttpStatusCode.Created, response.status)
        val body = response.bodyAsText()
        assertMatchesSchema(body, "card-transaction.schema.json")
        assertEquals("JPY", JSON.parseObject(body).getJSONObject("amount").getString("currency"))
    }

    private suspend fun ApplicationTestBuilder.assertBadRequest(body: String, expectedMessagePart: String) {
        assertViolatesSchema(body, "create-card-transaction-request.schema.json")
        val response = postJson(body)
        assertEquals(HttpStatusCode.BadRequest, response.status, "body: $body -> ${response.bodyAsText()}")
        val text = response.bodyAsText()
        assertMatchesSchema(text, "api-error.schema.json")
        val error = JSON.parseObject(text)
        assertEquals("VALIDATION_ERROR", error.getString("code"))
        val message = error.getString("message")
        assertTrue(message.contains(expectedMessagePart), "expected '$expectedMessagePart' in: $message")
        assertNoParserDiagnostic(text)
    }

    /** fastjson2 messages name the unknown key, the failing constructor, or quote the input. */
    private fun assertNoParserDiagnostic(text: String) {
        for (marker in listOf("Unknown Property", "constructor", "fastjson", "offset")) {
            assertTrue(!text.contains(marker), "parser diagnostic leaked: $text")
        }
    }

    @Test
    fun `POST blank description is 400`() = withApp {
        assertBadRequest("""{"description":"   ","amount":{"amount":"100","currency":"USD"},"type":"DEBIT"}""", "description")
    }

    /**
     * Blank is what the contract's `pattern: "\S"` says it is, in ECMAScript terms: U+FEFF and the
     * Unicode space separators count, U+001C does not. Kotlin's isBlank disagrees on both counts, and
     * a description it accepted would come back to the browser as a response Ajv rejects.
     */
    @Test
    fun `POST description blank by the contract is 400 and trimmed by the same rule`() = withApp {
        for (blank in listOf("\uFEFF", "\u00A0", "\u2003\u3000", " \uFEFF\u2028 ")) {
            assertBadRequest(validRequest.replace("Lunch", blank), "description")
        }
        val response = postJson(validRequest.replace("Lunch", "\uFEFF \\u001CLunch\u3000"))
        assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
        val body = response.bodyAsText()
        assertMatchesSchema(body, "card-transaction.schema.json")
        assertEquals("\u001CLunch", JSON.parseObject(body).getString("description"))
    }

    @Test
    fun `POST zero amount is 400`() = withApp {
        assertBadRequest("""{"description":"x","amount":{"amount":"0","currency":"USD"},"type":"DEBIT"}""", "amount must be positive")
    }

    @Test
    fun `POST negative amount is 400`() = withApp {
        assertBadRequest("""{"description":"x","amount":{"amount":"-100","currency":"USD"},"type":"DEBIT"}""", "amount must be positive")
    }

    @Test
    fun `POST decimal amount is 400`() = withApp {
        assertBadRequest("""{"description":"x","amount":{"amount":"12.34","currency":"USD"},"type":"DEBIT"}""", "integer string")
    }

    @Test
    fun `POST exponent amount is 400`() = withApp {
        assertBadRequest("""{"description":"x","amount":{"amount":"1e3","currency":"USD"},"type":"DEBIT"}""", "integer string")
    }

    /**
     * fastjson2 would bind a JSON number, boolean, object or array into a String field by stringifying
     * it; the contract types every scalar as string, so the converter's String reader refuses them.
     * Each case would otherwise persist and answer 201.
     */
    @Test
    fun `POST non-string scalars are 400 for every string field`() = withApp {
        val fields = listOf("\"description\":\"Lunch\"" to "description", "\"amount\":\"1800\"" to "amount", "\"currency\":\"USD\"" to "currency", "\"type\":\"DEBIT\"" to "type")
        val kinds = listOf("1800", "1.5", "true", "null", "{\"secret-hunter2\":1}", "[\"Lunch\"]", "{}", "[]")
        for ((field, name) in fields) {
            for (kind in kinds) {
                val body = validRequest.replace(field, "\"$name\":$kind").also { check(it != validRequest) }
                assertViolatesSchema(body, "create-card-transaction-request.schema.json")
                assertMalformedBody(body)
                assertMalformedBody("[$body]")
            }
        }
    }

    /**
     * fastjson2 parses comments and trailing commas as if they were JSON; RFC 8259 has neither, and
     * the README promises 400 for malformed JSON, so the converter checks the grammar first.
     */
    @Test
    fun `POST non-RFC 8259 syntax is 400`() = withApp {
        for (body in listOf(
            validRequest.dropLast(1) + ",}",
            validRequest.replace("\"USD\"}", "\"USD\",}"),
            "[$validRequest,]",
            "$validRequest/* secret-hunter2 */",
            validRequest.replace("\"Lunch\",", "\"Lunch\",/* secret-hunter2 */"),
            "$validRequest// secret-hunter2",
            "// secret-hunter2\n$validRequest",
            validRequest.replace("\"Lunch\",", "\"Lunch\",// secret-hunter2\n"),
            "\uFEFF$validRequest",
            "$validRequest$validRequest",
        )) {
            assertMalformedBody(body)
        }
    }

    private suspend fun ApplicationTestBuilder.assertMalformedBody(body: String) {
        val before = listIds()
        val response = postJson(body)
        assertEquals(HttpStatusCode.BadRequest, response.status, "body: $body -> ${response.bodyAsText()}")
        val text = response.bodyAsText()
        assertMatchesSchema(text, "api-error.schema.json")
        assertEquals("malformed request body", JSON.parseObject(text).getString("message"), "body: $body")
        assertTrue(!text.contains("secret-hunter2"), "request input leaked: $text")
        assertEquals(before, listIds(), "a rejected body must not persist anything: $body")
    }

    @Test
    fun `POST amount beyond 64 bits is 400`() = withApp {
        // 20 digits: caught by the schema's maxLength and by the backend.
        assertBadRequest("""{"description":"x","amount":{"amount":"99999999999999999999","currency":"USD"},"type":"DEBIT"}""", "out of range")
        // 19 digits but > Long.MAX_VALUE: passes the schema, so only the backend can reject it.
        val nineteenDigits = """{"description":"x","amount":{"amount":"9999999999999999999","currency":"USD"},"type":"DEBIT"}"""
        assertMatchesSchema(nineteenDigits, "create-card-transaction-request.schema.json")
        val response = postJson(nineteenDigits)
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertMatchesSchema(response.bodyAsText(), "api-error.schema.json")
    }

    @Test
    fun `POST unsupported currency is 400`() = withApp {
        assertBadRequest("""{"description":"x","amount":{"amount":"100","currency":"GBP"},"type":"DEBIT"}""", "unsupported currency")
        assertBadRequest("""{"description":"x","amount":{"amount":"100","currency":"usd"},"type":"DEBIT"}""", "unsupported currency")
    }

    @Test
    fun `POST invalid type is 400`() = withApp {
        assertBadRequest("""{"description":"x","amount":{"amount":"100","currency":"USD"},"type":"REFUND"}""", "type must be one of")
    }

    @Test
    fun `POST missing field and unknown field are 400`() = withApp {
        // A missing field fails the DTO constructor's null check inside fastjson2, which reports the
        // constructor rather than the field, so the response names no field at all.
        assertBadRequest("""{"description":"x","type":"DEBIT"}""", "malformed request body")
        assertBadRequest("""{"description":"x","amount":{"currency":"USD"},"type":"DEBIT"}""", "malformed request body")
        assertBadRequest("""{"description":"x","amount":{"amount":"100"},"type":"DEBIT"}""", "malformed request body")
        assertBadRequest("""{"amount":{"amount":"100","currency":"USD"}}""", "malformed request body")
        assertBadRequest("""{"description":"x","amount":{"amount":"100","currency":"USD"},"type":"DEBIT","extra":1}""", "malformed request body")
        assertBadRequest("""{"description":"x","amount":{"amount":"100","currency":"USD","extra":1},"type":"DEBIT"}""", "malformed request body")
    }

    @Test
    fun `POST malformed JSON is 400`() = withApp {
        val response = postJson("{not json")
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertMatchesSchema(response.bodyAsText(), "api-error.schema.json")
    }

    @Test
    fun `domain conversion failures do not reflect supplied values`() = withApp {
        val marker = "secret-marker" + "x".repeat(4000)
        for ((request, message) in listOf(
            validRequest.replace("1800", marker) to "amount must be a base-10 integer string in minor units",
            validRequest.replace("1800", "9".repeat(4000)) to "amount is out of range for a 64-bit integer",
            validRequest.replace("USD", marker) to "unsupported currency; supported: ${Currency.entries.joinToString { it.code }}",
            validRequest.replace("DEBIT", marker) to "type must be one of CREDIT, DEBIT",
        )) {
            val response = postJson(request)
            assertEquals(HttpStatusCode.BadRequest, response.status)
            val body = response.bodyAsText()
            assertMatchesSchema(body, "api-error.schema.json")
            assertEquals(message, JSON.parseObject(body).getString("message"))
        }
        for (path in listOf("/api/card-transactions/$marker", "/$marker")) {
            val response = client.get(path)
            assertEquals(HttpStatusCode.NotFound, response.status)
            val body = response.bodyAsText()
            assertMatchesSchema(body, "api-error.schema.json")
            assertTrue(!body.contains(marker))
        }
    }

    @Test
    fun `parser failures do not echo request input or unknown keys`() = withApp {
        for (body in listOf(
            "password=hunter2&card=4111111111111111",
            // fastjson2's own diagnostic here is `Unknown Property secret-hunter2`.
            validRequest.dropLast(1) + " ,\"secret-hunter2\":1}",
            validRequest.replace("\"description\":\"Lunch\"", "\"description\":{\"secret-hunter2\":1},\"extra\":1"),
        )) {
            val response = postJson(body)
            assertEquals(HttpStatusCode.BadRequest, response.status)
            val text = response.bodyAsText()
            assertMatchesSchema(text, "api-error.schema.json")
            assertEquals("malformed request body", JSON.parseObject(text).getString("message"))
        }
    }

    // ---- Array body: several card transactions created all-or-nothing ----

    private val secondRequest = """{"description":"Ramen","amount":{"amount":"1200","currency":"JPY"},"type":"CREDIT"}"""

    private suspend fun ApplicationTestBuilder.listIds(): List<String> =
        JSON.parseObject(client.get("/api/card-transactions").bodyAsText()).getJSONArray("items")
            .let { items -> items.indices.map { items.getJSONObject(it).getString("id") } }

    private suspend fun ApplicationTestBuilder.assertBadRequestArray(body: String, expectedMessagePart: String) {
        assertViolatesSchema(body, "create-card-transactions-request.schema.json")
        val before = listIds()
        val response = postJson(body)
        assertEquals(HttpStatusCode.BadRequest, response.status, "body: $body -> ${response.bodyAsText()}")
        val text = response.bodyAsText()
        assertMatchesSchema(text, "api-error.schema.json")
        val error = JSON.parseObject(text)
        assertEquals("VALIDATION_ERROR", error.getString("code"))
        val message = error.getString("message")
        assertTrue(message.contains(expectedMessagePart), "expected '$expectedMessagePart' in: $message")
        assertNoParserDiagnostic(text)
        assertEquals(before, listIds(), "a rejected array body must not persist anything")
    }

    @Test
    fun `POST array creates every item and returns the list shape`() = withApp {
        val request = "[$validRequest,$secondRequest]"
        assertMatchesSchema(request, "create-card-transactions-request.schema.json")
        assertMatchesSchema(validRequest, "create-card-transactions-request.schema.json")

        val response = postJson(request)
        assertEquals(HttpStatusCode.Created, response.status)
        val body = response.bodyAsText()
        assertMatchesSchema(body, "card-transaction-list-response.schema.json")

        val items = JSON.parseObject(body).getJSONArray("items").let { array -> array.indices.map { array.getJSONObject(it) } }
        assertEquals(listOf("Lunch", "Ramen"), items.map { it.getString("description") })
        assertEquals(listOf("USD", "JPY"), items.map { it.getJSONObject("amount").getString("currency") })
        for (item in items) {
            val fetched = client.get("/api/card-transactions/${item.getString("id")}")
            assertEquals(HttpStatusCode.OK, fetched.status)
            // Parsed, not text: the assertion is about the resource, not the writer's field order.
            assertEquals(item, JSON.parseObject(fetched.bodyAsText()))
        }
    }

    @Test
    fun `POST single object still returns a bare card transaction`() = withApp {
        val body = postJson(validRequest).bodyAsText()
        assertMatchesSchema(body, "card-transaction.schema.json")
        assertViolatesSchema(body, "card-transaction-list-response.schema.json")
    }

    @Test
    fun `POST array is all-or-nothing and names the failing item`() = withApp {
        // Service-layer rules: the message is the single-object one behind the item's index.
        val blankSecond = secondRequest.replace("Ramen", "   ")
        assertBadRequestArray("[$validRequest,$blankSecond]", "[1]: description must not be blank")
        assertBadRequestArray("[$validRequest,${secondRequest.replace("1200", "0")}]", "[1]: amount must be positive")
        assertBadRequestArray("[${validRequest.replace("1800", "-1800")},$secondRequest]", "[0]: amount must be positive")
    }

    @Test
    fun `POST array wire-to-domain failures name the failing item`() = withApp {
        // Route-layer conversion runs before the service; it has to carry the index too.
        assertBadRequestArray("[$validRequest,${secondRequest.replace("1200", "99999999999999999999")}]", "[1]: amount is out of range for a 64-bit integer")
        assertBadRequestArray("[$validRequest,${secondRequest.replace("1200", "12.00")}]", "[1]: amount must be a base-10 integer string in minor units")
        assertBadRequestArray("[$validRequest,${secondRequest.replace("JPY", "XXX")}]", "[1]: unsupported currency")
        assertBadRequestArray("[${validRequest.replace("DEBIT", "REFUND")},$secondRequest]", "[0]: type must be one of")
    }

    @Test
    fun `POST single object errors carry no item index`() = withApp {
        for (body in listOf(
            validRequest.replace("Lunch", "   "),
            validRequest.replace("1800", "0"),
            validRequest.replace("1800", "99999999999999999999"),
        )) {
            val message = JSON.parseObject(postJson(body).bodyAsText()).getString("message")
            assertTrue(!message.startsWith("["), "single-object message must not be indexed: $message")
        }
    }

    @Test
    fun `POST empty array is 400`() = withApp {
        assertBadRequestArray("[]", "items must not be empty")
    }

    @Test
    fun `POST array item failures are 400`() = withApp {
        assertBadRequestArray("[$validRequest,${secondRequest.replace("\"currency\":\"JPY\"", "\"currency\":\"JPY\",\"x\":1")}]", "malformed request body")
        assertBadRequestArray("[$validRequest,{\"description\":\"x\",\"amount\":{\"currency\":\"USD\"},\"type\":\"DEBIT\"}]", "malformed request body")
        assertBadRequestArray("[{\"amount\":{\"amount\":\"100\",\"currency\":\"USD\"}}]", "malformed request body")
    }

    @Test
    fun `POST array with a null or scalar item is 400`() = withApp {
        // readArray hands back null for a null element; the body reader must refuse it before the
        // route dereferences it, or a client mistake would surface as a 500.
        for (body in listOf("[null]", "[$validRequest,null]", "[42]", "[\"x\"]")) {
            assertBadRequestArray(body, "malformed request body")
        }
    }

    @Test
    fun `POST a ref body is data, not a pointer`() = withApp {
        // Without DisableReferenceDetect fastjson2 resolves `$ref` inside the document being parsed
        // and hands the route an aliased or null item, which used to be a 500.
        for (body in listOf("[{\"\$ref\":\"$\"}]", "{\"\$ref\":\"#\"}", "[$validRequest,{\"\$ref\":\"$[0]\"}]")) {
            assertBadRequestArray(body, "malformed request body")
        }
    }

    @Test
    fun `POST scalar body is 400`() = withApp {
        for (body in listOf("true", "\"x\"", "42", "null")) {
            assertBadRequestArray(body, "malformed request body")
        }
    }
}
