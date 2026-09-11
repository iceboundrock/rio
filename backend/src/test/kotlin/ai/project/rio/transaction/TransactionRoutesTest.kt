package ai.project.rio.transaction

import ai.project.rio.contract.JsonSchemaAssertions.assertMatchesSchema
import ai.project.rio.contract.JsonSchemaAssertions.assertViolatesSchema
import ai.project.rio.db.Database
import ai.project.rio.db.SchemaInitializer
import ai.project.rio.module
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
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
        assertBadRequest("""{"description":"x","type":"DEBIT"}""", "malformed request body")
        assertBadRequest("""{"description":"x","amount":{"amount":"100","currency":"USD"},"type":"DEBIT","extra":1}""", "malformed request body")
    }

    @Test
    fun `POST malformed JSON is 400`() = withApp {
        val response = postJson("{not json")
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertMatchesSchema(response.bodyAsText(), "api-error.schema.json")
    }

    private fun kotlinx.serialization.json.JsonElement.jsonArraySize(): Int =
        (this as kotlinx.serialization.json.JsonArray).size
}
