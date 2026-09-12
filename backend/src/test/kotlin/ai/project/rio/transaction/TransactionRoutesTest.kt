package ai.project.rio.transaction

import ai.project.rio.contract.JsonSchemaAssertions.assertMatchesSchema
import ai.project.rio.contract.JsonSchemaAssertions.assertViolatesSchema
import ai.project.rio.db.Database
import ai.project.rio.db.SchemaInitializer
import ai.project.rio.http.configureErrorHandling
import ai.project.rio.module
import ai.project.rio.money.Currency
import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
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
import io.ktor.serialization.kotlinx.json.json
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Exercises the real Ktor pipeline against a temporary SQLite file and validates every
 * response body against the shared JSON Schemas in contracts/schemas.
 */
class TransactionRoutesTest {

    private lateinit var dbFile: Path

    @BeforeTest
    fun setUp() {
        dbFile = Files.createTempFile("transaction-routes-test", ".db")
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
        client.post("/api/transactions") {
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
        fun message(): String = Json.parseToJsonElement(body).jsonObject["message"]!!.jsonPrimitive.content
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
            val response = rawRequest(port, "POST /api/transactions", headers, validRequest)
            assertEquals(status, response.status, "Content-Type: $type; ${response.raw}")
            assertTrue(response.hasHeader("Accept-Post: application/json"))
            assertMatchesSchema(response.body, if (status == 201) "transaction.schema.json" else "api-error.schema.json")
            if (message != null) {
                assertEquals("VALIDATION_ERROR", Json.parseToJsonElement(response.body).jsonObject["code"]!!.jsonPrimitive.content)
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
            Triple("GET /api/transactions", null, 200),
            Triple("GET /api/transactions/nope", null, 404),
            Triple("GET /api/no-route", null, 404),
            Triple("POST /api/transactions", "text/plain", 415),
            Triple("POST /api/transactions", "application/json", 400),
            Triple("POST /api/transactions", "application/json", 201),
            Triple("GET /test-failure", null, 500),
        )
        val repository = TransactionRepository(Database.open(dbFile))
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
                    200 -> "transaction-list-response.schema.json"
                    201 -> "transaction.schema.json"
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
     * case-insensitive (12.4.2). Anything outside the Accept grammar - a bare `*`, a quoted qvalue,
     * whitespace inside a media range - is a malformed header (400), not a preference the server
     * guesses at; a quoted-string parameter other than `q` is grammatical and may contain `,` or `;`.
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
        val repository = TransactionRepository(Database.open(dbFile))
        for (accept in acceptable + unacceptable + malformed) {
            val expectedError = when (accept) {
                in unacceptable -> 406 to "no acceptable response media type"
                in malformed -> 400 to "malformed Accept header"
                else -> null
            }
            val headers = accept.orEmpty().map { "Accept: $it" }
            for ((target, success, schema) in listOf(
                Triple("GET /api/transactions", 200, "transaction-list-response.schema.json"),
                Triple("POST /api/transactions", 201, "transaction.schema.json"),
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
                assertMatchesSchema(response.body, if (expectedError != null) "api-error.schema.json" else schema)
                if (expectedError != null) {
                    assertEquals("VALIDATION_ERROR", Json.parseToJsonElement(response.body).jsonObject["code"]!!.jsonPrimitive.content)
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
        for (method in listOf("PUT", "DELETE", "PATCH")) {
            val response = rawRequest(port, "$method /api/transactions")
            assertEquals(405, response.status, response.raw)
            assertMatchesSchema(response.body, "api-error.schema.json")
            assertEquals("method not allowed", response.message())
        }
    }

    @Test
    fun `a malformed Content-Type cannot make the error handler itself fail`() = withRawServer({
        // No ContentNegotiation: receive() fails, and the handler for that failure must not re-parse
        // the header unguarded - a throw inside StatusPages escapes as a plain-text engine 500.
        configureErrorHandling()
        routing { transactionRoutes(TransactionService(TransactionRepository(Database.open(dbFile)))) }
    }) { port ->
        val malformed = rawRequest(port, "POST /api/transactions", listOf("Content-Type: not a mime type"), validRequest)
        assertEquals(400, malformed.status, malformed.raw)
        assertTrue(malformed.hasHeader("Content-Type: application/json"), malformed.raw)
        assertMatchesSchema(malformed.body, "api-error.schema.json")
        assertEquals("malformed Content-Type header", malformed.message())
        assertTrue(!malformed.body.contains("not a mime type"), malformed.body)

        // The genuine misconfiguration this handler exists for still reports a server fault.
        val declared = rawRequest(port, "POST /api/transactions", listOf("Content-Type: application/json"), validRequest)
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
                val response = client.post("/api/transactions") {
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
            routing { transactionRoutes(TransactionService(TransactionRepository(Database.open(dbFile)))) }
        }
        val response = postJson(validRequest)
        assertEquals(HttpStatusCode.InternalServerError, response.status)
        val body = response.bodyAsText()
        assertMatchesSchema(body, "api-error.schema.json")
        assertEquals("INTERNAL_ERROR", Json.parseToJsonElement(body).jsonObject["code"]!!.jsonPrimitive.content)
    }

    @Test
    fun `misconfigured receive converter produces a server warning`() = testApplication {
        val events = ListAppender<ILoggingEvent>().apply { start() }
        var logger: Logger? = null
        application {
            logger = log as Logger
            logger.addAppender(events)
            install(ContentNegotiation) {
                json()
                // Valid JSON can no longer be converted to the request DTO; responses still serialize.
                ignoreType<CreateTransactionRequest>()
            }
            configureErrorHandling()
            routing { transactionRoutes(TransactionService(TransactionRepository(Database.open(dbFile)))) }
        }
        try {
            val response = postJson(validRequest)
            assertEquals(HttpStatusCode.InternalServerError, response.status)
            assertMatchesSchema(response.bodyAsText(), "api-error.schema.json")
            assertEquals("INTERNAL_ERROR", Json.parseToJsonElement(response.bodyAsText()).jsonObject["code"]!!.jsonPrimitive.content)
            assertTrue(events.list.any {
                it.level == Level.WARN && it.formattedMessage.contains("ContentNegotiation configuration") &&
                    it.throwableProxy?.className == "io.ktor.server.plugins.CannotTransformContentToTypeException"
            })
        } finally {
            logger?.detachAppender(events)
            events.stop()
        }
    }

    // ---- Cross-layer currency and media-type contracts ----
    @Test
    fun `every supported currency persists and satisfies the contract`() = withApp {
        for (currency in Currency.entries) {
            val request = """{"description":"Currency test","amount":{"amount":"9223372036854775807","currency":"${currency.code}"},"type":"CREDIT"}"""
            assertMatchesSchema(request, "create-transaction-request.schema.json")
            val response = postJson(request)
            assertEquals(HttpStatusCode.Created, response.status)
            val body = response.bodyAsText()
            assertMatchesSchema(body, "transaction.schema.json")
            val json = Json.parseToJsonElement(body).jsonObject
            assertEquals(currency.code, json["amount"]!!.jsonObject["currency"]!!.jsonPrimitive.content)
            assertEquals("9223372036854775807", json["amount"]!!.jsonObject["amount"]!!.jsonPrimitive.content)
            val fetched = client.get("/api/transactions/${json["id"]!!.jsonPrimitive.content}")
            assertEquals(HttpStatusCode.OK, fetched.status)
            assertEquals(body, fetched.bodyAsText())
        }
        assertMatchesSchema(client.get("/api/transactions").bodyAsText(), "transaction-list-response.schema.json")
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
            val response = client.post("/api/transactions") {
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
            assertEquals("VALIDATION_ERROR", Json.parseToJsonElement(body).jsonObject["code"]!!.jsonPrimitive.content)
            val message = if (type == null) "missing Content-Type" else "unsupported Content-Type"
            assertEquals(message, Json.parseToJsonElement(body).jsonObject["message"]!!.jsonPrimitive.content)
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
        val error = Json.parseToJsonElement(body).jsonObject
        assertEquals("VALIDATION_ERROR", error["code"]!!.jsonPrimitive.content)
        assertEquals("unsupported Content-Type", error["message"]!!.jsonPrimitive.content)
    }

    // ---- GET list ----

    @Test
    fun `GET list returns seeded transactions matching the schema`() = withApp {
        val response = client.get("/api/transactions")
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertMatchesSchema(body, "transaction-list-response.schema.json")
        val items = Json.parseToJsonElement(body).jsonObject["items"]!!
        assertEquals(SchemaInitializer.SEED.size, items.jsonArraySize())
    }

    // ---- GET one ----

    @Test
    fun `GET known id returns the transaction`() = withApp {
        val response = client.get("/api/transactions/seed-0002")
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertMatchesSchema(body, "transaction.schema.json")
        val json = Json.parseToJsonElement(body).jsonObject
        assertEquals("Blue Bottle Coffee", json["description"]!!.jsonPrimitive.content)
        assertEquals("525", json["amount"]!!.jsonObject["amount"]!!.jsonPrimitive.content)
        assertEquals("USD", json["amount"]!!.jsonObject["currency"]!!.jsonPrimitive.content)
        assertEquals("2026-09-02T15:30:00Z", json["createdAt"]!!.jsonPrimitive.content)
    }

    @Test
    fun `GET unknown id returns 404 with the error shape`() = withApp {
        val response = client.get("/api/transactions/nope")
        assertEquals(HttpStatusCode.NotFound, response.status)
        val body = response.bodyAsText()
        assertMatchesSchema(body, "api-error.schema.json")
        assertEquals("NOT_FOUND", Json.parseToJsonElement(body).jsonObject["code"]!!.jsonPrimitive.content)
    }

    @Test
    fun `unmatched route returns 404 with the error shape`() = withApp {
        val response = client.get("/api/nothing-here")
        assertEquals(HttpStatusCode.NotFound, response.status)
        assertMatchesSchema(response.bodyAsText(), "api-error.schema.json")
    }

    // ---- POST ----

    @Test
    fun `POST valid transaction returns 201 and persists it`() = withApp {
        assertMatchesSchema(validRequest, "create-transaction-request.schema.json")

        val response = postJson(validRequest)
        assertEquals(HttpStatusCode.Created, response.status)
        val body = response.bodyAsText()
        assertMatchesSchema(body, "transaction.schema.json")

        val created = Json.parseToJsonElement(body).jsonObject
        assertEquals("Lunch", created["description"]!!.jsonPrimitive.content)
        assertEquals("1800", created["amount"]!!.jsonObject["amount"]!!.jsonPrimitive.content)
        assertEquals("DEBIT", created["type"]!!.jsonPrimitive.content)
        assertEquals("COMPLETED", created["status"]!!.jsonPrimitive.content)

        val id = created["id"]!!.jsonPrimitive.content
        val fetched = client.get("/api/transactions/$id")
        assertEquals(HttpStatusCode.OK, fetched.status)
        assertEquals(body, fetched.bodyAsText())
    }

    @Test
    fun `POST JPY transaction round-trips zero-precision money`() = withApp {
        val response = postJson("""{"description":"Ramen","amount":{"amount":"1200","currency":"JPY"},"type":"DEBIT"}""")
        assertEquals(HttpStatusCode.Created, response.status)
        val body = response.bodyAsText()
        assertMatchesSchema(body, "transaction.schema.json")
        assertEquals("JPY", Json.parseToJsonElement(body).jsonObject["amount"]!!.jsonObject["currency"]!!.jsonPrimitive.content)
    }

    private suspend fun ApplicationTestBuilder.assertBadRequest(body: String, expectedMessagePart: String) {
        assertViolatesSchema(body, "create-transaction-request.schema.json")
        val response = postJson(body)
        assertEquals(HttpStatusCode.BadRequest, response.status, "body: $body -> ${response.bodyAsText()}")
        val text = response.bodyAsText()
        assertMatchesSchema(text, "api-error.schema.json")
        val error = Json.parseToJsonElement(text).jsonObject
        assertEquals("VALIDATION_ERROR", error["code"]!!.jsonPrimitive.content)
        val message = error["message"]!!.jsonPrimitive.content
        assertTrue(message.contains(expectedMessagePart), "expected '$expectedMessagePart' in: $message")
    }

    @Test
    fun `POST blank description is 400`() = withApp {
        assertBadRequest("""{"description":"   ","amount":{"amount":"100","currency":"USD"},"type":"DEBIT"}""", "description")
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

    @Test
    fun `POST numeric JSON amount is 400`() = withApp {
        assertBadRequest("""{"description":"x","amount":{"amount":1800,"currency":"USD"},"type":"DEBIT"}""", "malformed request body")
    }

    @Test
    fun `POST amount beyond 64 bits is 400`() = withApp {
        // 20 digits: caught by the schema's maxLength and by the backend.
        assertBadRequest("""{"description":"x","amount":{"amount":"99999999999999999999","currency":"USD"},"type":"DEBIT"}""", "out of range")
        // 19 digits but > Long.MAX_VALUE: passes the schema, so only the backend can reject it.
        val nineteenDigits = """{"description":"x","amount":{"amount":"9999999999999999999","currency":"USD"},"type":"DEBIT"}"""
        assertMatchesSchema(nineteenDigits, "create-transaction-request.schema.json")
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
        assertBadRequest("""{"description":"x","type":"DEBIT"}""", "missing required fields: amount")
        // MoneyDto.amount and CreateTransactionRequest.amount share a name; the path has to tell them apart.
        assertBadRequest("""{"description":"x","amount":{"currency":"USD"},"type":"DEBIT"}""", "missing required fields: amount.amount")
        assertBadRequest("""{"description":"x","amount":{"amount":"100"},"type":"DEBIT"}""", "missing required fields: amount.currency")
        assertBadRequest("""{"amount":{"amount":"100","currency":"USD"}}""", "missing required fields: description, type")
        assertBadRequest("""{"description":"x","amount":{"amount":"100","currency":"USD"},"type":"DEBIT","extra":1}""", "malformed request body")
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
            assertEquals(message, Json.parseToJsonElement(body).jsonObject["message"]!!.jsonPrimitive.content)
        }
        for (path in listOf("/api/transactions/$marker", "/$marker")) {
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
            validRequest.dropLast(1) + " ,\"secret-hunter2\":1}",
            validRequest.replace("\"description\":\"Lunch\"", "\"description\":{\"secret-hunter2\":1}"),
        )) {
            val response = postJson(body)
            assertEquals(HttpStatusCode.BadRequest, response.status)
            val text = response.bodyAsText()
            assertMatchesSchema(text, "api-error.schema.json")
            assertEquals("malformed request body", Json.parseToJsonElement(text).jsonObject["message"]!!.jsonPrimitive.content)
        }
    }

    private fun kotlinx.serialization.json.JsonElement.jsonArraySize(): Int =
        (this as kotlinx.serialization.json.JsonArray).size
}
