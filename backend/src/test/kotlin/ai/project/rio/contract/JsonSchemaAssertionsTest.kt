package ai.project.rio.contract

import ai.project.rio.contract.JsonSchemaAssertions.assertMatchesSchema
import ai.project.rio.contract.JsonSchemaAssertions.assertViolatesSchema
import com.networknt.schema.InputFormat
import com.networknt.schema.SchemaException
import com.networknt.schema.SchemaLocation
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * The oracle must agree with Ajv in the browser. These cases are every keyword the contracts use,
 * every reference between them, and each divergence found so far (#32) between a Java-flavoured
 * reading of the contracts and the ECMAScript one the schema means.
 */
class JsonSchemaAssertionsTest {

    private val money = """{"amount":"1800","currency":"USD"}"""
    private val transaction = """{"id":"seed-0001","description":"Lunch","amount":$money,"type":"DEBIT","status":"COMPLETED","createdAt":"2026-09-10T18:00:00Z"}"""
    private val request = """{"description":"Lunch","amount":$money,"type":"DEBIT"}"""

    private fun transaction(vararg changes: Pair<String, String>): String =
        changes.fold(transaction) { json, (from, to) -> json.replace(from, to).also { check(it != json) { "nothing replaced for $from" } } }

    private fun request(vararg changes: Pair<String, String>): String =
        changes.fold(request) { json, (from, to) -> json.replace(from, to).also { check(it != json) { "nothing replaced for $from" } } }

    @Test
    fun `valid documents match every contract`() {
        assertMatchesSchema(money, "money.schema.json")
        assertMatchesSchema(transaction, "card-transaction.schema.json")
        assertMatchesSchema("""{"items":[$transaction,${transaction("seed-0001" to "seed-0002")}]}""", "card-transaction-list-response.schema.json")
        assertMatchesSchema("""{"items":[]}""", "card-transaction-list-response.schema.json")
        assertMatchesSchema(request, "create-card-transaction-request.schema.json")
        assertMatchesSchema(request, "create-card-transactions-request.schema.json")
        assertMatchesSchema("[$request]", "create-card-transactions-request.schema.json")
        for (code in listOf("VALIDATION_ERROR", "NOT_FOUND", "INTERNAL_ERROR", "IDEMPOTENCY_CONFLICT")) {
            assertMatchesSchema("""{"code":"$code","message":"x"}""", "api-error.schema.json")
        }
    }

    @Test
    fun `cross-file ref into money positive is live`() {
        // Only money#/$defs/positive rejects these; the money root accepts them. That pair is the
        // proof the $defs reference resolved rather than validating anything.
        for (amount in listOf("0", "-1")) {
            assertMatchesSchema(money.replace("1800", amount), "money.schema.json")
            assertViolatesSchema(transaction("1800" to amount), "card-transaction.schema.json")
            assertViolatesSchema(request("1800" to amount), "create-card-transaction-request.schema.json")
        }
        val twenty = "1".repeat(20)
        assertMatchesSchema(money.replace("1800", twenty), "money.schema.json")
        assertViolatesSchema(money.replace("1800", twenty + "1"), "money.schema.json")
        assertViolatesSchema(transaction("1800" to twenty), "card-transaction.schema.json")
        assertMatchesSchema(transaction("1800" to "1".repeat(19)), "card-transaction.schema.json")
        // additionalProperties, required, type and enum survive the `#` self-reference inlining.
        assertViolatesSchema(transaction(""","currency":"USD"}""" to ""","currency":"USD","note":"x"}"""), "card-transaction.schema.json")
        assertViolatesSchema(transaction(""","currency":"USD"}""" to "}"), "card-transaction.schema.json")
        assertViolatesSchema(transaction("\"1800\"" to "1800"), "card-transaction.schema.json")
        assertViolatesSchema(transaction("\"amount\":$money" to "\"amount\":1800"), "card-transaction.schema.json")
        assertViolatesSchema(transaction("USD" to "GBP"), "card-transaction.schema.json")
    }

    @Test
    fun `list response items reference the transaction schema`() {
        assertViolatesSchema("""{"items":[$transaction,${transaction("\"id\":\"seed-0001\"," to "")}]}""", "card-transaction-list-response.schema.json")
        assertViolatesSchema("""{"items":[42]}""", "card-transaction-list-response.schema.json")
        assertViolatesSchema("""{"items":{}}""", "card-transaction-list-response.schema.json")
        assertViolatesSchema("""{"items":[],"extra":1}""", "card-transaction-list-response.schema.json")
        assertViolatesSchema("""{}""", "card-transaction-list-response.schema.json")
    }

    @Test
    fun `create body is one request or a non-empty array of them`() {
        assertViolatesSchema("[]", "create-card-transactions-request.schema.json")
        assertViolatesSchema("[$request,${request("DEBIT" to "REFUND")}]", "create-card-transactions-request.schema.json")
        assertViolatesSchema("[[$request]]", "create-card-transactions-request.schema.json")
        for (scalar in listOf("42", "\"x\"", "true", "null")) {
            assertViolatesSchema(scalar, "create-card-transactions-request.schema.json")
        }
    }

    @Test
    fun `request keywords are enforced`() {
        assertViolatesSchema(request("\"type\":\"DEBIT\"" to "\"type\":\"DEBIT\",\"extra\":1"), "create-card-transaction-request.schema.json")
        assertViolatesSchema(request("\"description\":\"Lunch\"," to ""), "create-card-transaction-request.schema.json")
        assertViolatesSchema(request("\"amount\":$money," to ""), "create-card-transaction-request.schema.json")
        assertViolatesSchema(request(",\"type\":\"DEBIT\"" to ""), "create-card-transaction-request.schema.json")
        assertViolatesSchema(request("\"Lunch\"" to "\"\""), "create-card-transaction-request.schema.json")
        assertViolatesSchema(request("\"Lunch\"" to "\"   \""), "create-card-transaction-request.schema.json")
        assertViolatesSchema(request("\"Lunch\"" to "1"), "create-card-transaction-request.schema.json")
        assertViolatesSchema(request("DEBIT" to "REFUND"), "create-card-transaction-request.schema.json")
    }

    @Test
    fun `transaction scalars are enforced`() {
        assertViolatesSchema(transaction("\"seed-0001\"" to "\"\""), "card-transaction.schema.json")
        assertViolatesSchema(transaction("2026-09-10T18:00:00Z" to "2026-09-10 18:00:00Z"), "card-transaction.schema.json")
        assertViolatesSchema(transaction("2026-09-10T18:00:00Z" to "2026-09-10T18:00:00"), "card-transaction.schema.json")
        assertMatchesSchema(transaction("2026-09-10T18:00:00Z" to "2026-09-10T18:00:00.123456Z"), "card-transaction.schema.json")
        assertViolatesSchema(transaction("COMPLETED" to "NEW"), "card-transaction.schema.json")
    }

    /**
     * `pattern` means ECMAScript (Ajv compiles it with the `u` flag). Under java.util.regex the first
     * two documents validate here and fail in the browser: `\\S` matches U+FEFF and `$` also matches
     * before a final newline.
     */
    @Test
    fun `patterns are read with ECMAScript semantics`() {
        assertViolatesSchema(transaction("\"Lunch\"" to "\"\uFEFF\""), "card-transaction.schema.json")
        assertViolatesSchema(transaction("\"1800\"" to "\"1800\\n\""), "card-transaction.schema.json")
        assertViolatesSchema(request("\"Lunch\"" to "\"\u00A0\u2003\u3000\""), "create-card-transaction-request.schema.json")
        assertMatchesSchema(request("\"Lunch\"" to "\"\\u001C\""), "create-card-transaction-request.schema.json")
    }

    /** JSON.parse alone accepts these; the browser's JSON.parse does not, so neither may the oracle. */
    @Test
    fun `non-RFC 8259 text is not JSON to the oracle`() {
        for (text in listOf("$money/* c */", money.dropLast(1) + ",}", "// c\n$money", "$money // c")) {
            assertFailsWith<AssertionError>(text) { assertMatchesSchema(text, "money.schema.json") }
            assertFailsWith<AssertionError>(text) { assertViolatesSchema(text, "money.schema.json") }
        }
    }

    @Test
    fun `api error keywords are enforced`() {
        assertViolatesSchema("""{"code":"NOPE","message":"x"}""", "api-error.schema.json")
        assertViolatesSchema("""{"code":"NOT_FOUND"}""", "api-error.schema.json")
        assertViolatesSchema("""{"code":"NOT_FOUND","message":"x","extra":1}""", "api-error.schema.json")
        assertViolatesSchema("""{"code":"NOT_FOUND","message":1}""", "api-error.schema.json")
    }

    /**
     * Remote fetching is off, so a `$ref` the registry cannot resolve is an error, never a pass.
     * The target is a real file: with networknt's opt-in `fetchRemoteResources()` this `file:` ref
     * would load and validate, so the test pins the fetcher being off, not merely a missing target.
     * networknt resolves references lazily, so the error surfaces on the first validation.
     */
    @Test
    fun `an unresolvable ref is an error instead of validating anything`() {
        val id = "https://rio.local/schemas/broken.schema.json"
        val onDisk = Path.of(System.getProperty("contracts.schemas.dir")).resolve("money.schema.json").toUri()
        val registry = JsonSchemaAssertions.registry(mapOf(id to """{"${'$'}ref":"$onDisk"}"""))
        assertFailsWith<SchemaException> {
            registry.getSchema(SchemaLocation.of(id)).validate(money, InputFormat.JSON)
        }
    }
}
