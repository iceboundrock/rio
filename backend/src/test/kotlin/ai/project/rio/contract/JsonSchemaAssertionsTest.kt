package ai.project.rio.contract

import ai.project.rio.contract.JsonSchemaAssertions.assertMatchesSchema
import ai.project.rio.contract.JsonSchemaAssertions.assertViolatesSchema
import com.alibaba.fastjson2.JSON
import com.alibaba.fastjson2.JSONObject
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * fastjson2's JSONSchema fails open: a `$ref` it cannot resolve validates anything, and a keyword it
 * does not implement is simply ignored. These cases prove that every keyword the contracts use, and
 * every reference between them, still rejects what it is meant to reject after inlining.
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
     * fastjson2 compiles `pattern` as a Java regex and matches with find(); the schema means ECMAScript.
     * Without translation the first two documents validate here and fail in the browser.
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

    @Test
    fun `inlined schemas contain no references and refuse what the loader cannot resolve`() {
        val dir = Path.of(System.getProperty("contracts.schemas.dir"))
        val files = Files.list(dir).use { list -> list.map { it.fileName.toString() }.filter { it.endsWith(".schema.json") }.toList() }
        assertTrue(files.size >= 6, "expected the contract files, found $files")
        for (file in files) {
            val text = JSON.toJSONString(JsonSchemaAssertions.inlined(file))
            assertTrue(!text.contains("\"\$ref\"") && !text.contains("\"\$defs\""), "$file still references: $text")
        }
        fun ref(target: String) = JSONObject.of("\$ref", target)
        assertFailsWith<IllegalStateException> { JsonSchemaAssertions.inline(ref("nope.schema.json"), "money.schema.json") }
        assertFailsWith<IllegalStateException> { JsonSchemaAssertions.inline(ref("money.schema.json#/definitions/positive"), "money.schema.json") }
        assertFailsWith<IllegalStateException> { JsonSchemaAssertions.inline(ref("money.schema.json#/\$defs/missing"), "money.schema.json") }
        assertFailsWith<IllegalStateException> { JsonSchemaAssertions.inline(ref("money.schema.json#/\$defs/positive/allOf/0"), "money.schema.json") }
        assertFailsWith<IllegalStateException> { JsonSchemaAssertions.inline(JSONObject.of("\$ref", "#", "minLength", 1), "money.schema.json") }
        assertFailsWith<IllegalStateException> { JsonSchemaAssertions.inline(ref("#"), "money.schema.json", listOf("money.schema.json#")) }
    }
}
