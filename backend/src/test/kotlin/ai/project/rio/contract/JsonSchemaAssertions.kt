package ai.project.rio.contract

import ai.project.rio.http.JsonSyntax
import com.alibaba.fastjson2.JSON
import com.networknt.schema.InputFormat
import com.networknt.schema.Schema
import com.networknt.schema.SchemaLocation
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SchemaRegistryConfig
import com.networknt.schema.SpecificationVersion
import com.networknt.schema.regex.ECMAScriptRegularExpressionFactory
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.fail

/**
 * Validates JSON text against the shared contracts in contracts/schemas (Draft 2020-12).
 * The directory comes from the `contracts.schemas.dir` system property set in build.gradle.kts.
 *
 * Every *.schema.json file in that directory is registered under its `$id`
 * (https://rio.local/schemas/<file>), so `$ref`s between files resolve locally. Remote fetching is
 * off, so a `$ref` that resolves to nothing is an error, never a pass. `pattern` is matched by joni
 * in networknt's ECMAScript mode. That is not a full ECMA-262 engine: it agrees with Ajv on the
 * constructs the contracts use, and the one known divergence, an unescaped `.`, is refused at load
 * (see [refuseBareDots]). JsonSchemaAssertionsTest is the proof that each keyword, each cross-file
 * reference and each pattern construct bites; a construct new to the contracts needs a case there.
 */
object JsonSchemaAssertions {

    private const val SCHEMA_ID_PREFIX = "https://rio.local/schemas/"

    /** The regex text `[^\n\r\u2028\u2029]`: ECMAScript's `.` written as a class both engines agree on. */
    private const val PORTABLE_DOT = "[^\\n\\r\\u2028\\u2029]"

    private val schemasDir: Path = Path.of(
        System.getProperty("contracts.schemas.dir")
            ?: fail("System property contracts.schemas.dir is not set; run tests through Gradle"),
    ).also { check(Files.isDirectory(it)) { "schemas dir does not exist: $it" } }

    private val registry: SchemaRegistry = registry(
        Files.list(schemasDir).use { files ->
            files.filter { it.fileName.toString().endsWith(".schema.json") }
                .toList()
                .associate { SCHEMA_ID_PREFIX + it.fileName.toString() to Files.readString(it) }
        },
    )

    private val cache = HashMap<String, Schema>()

    /** A Draft 2020-12 registry over `schemas` (absolute `$id` to text), configured as the oracle is. */
    internal fun registry(schemas: Map<String, String>): SchemaRegistry {
        schemas.forEach { (id, text) -> refuseBareDots(id, JSON.parse(text)) }
        return engine(schemas)
    }

    /** [registry] without [refuseBareDots]; only for pinning what the engine itself does with a pattern. */
    internal fun engine(schemas: Map<String, String>): SchemaRegistry =
        SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12) { builder ->
            builder.schemas(schemas)
            builder.schemaRegistryConfig(
                SchemaRegistryConfig.builder()
                    .regularExpressionFactory(ECMAScriptRegularExpressionFactory.getInstance())
                    .build(),
            )
        }

    /**
     * joni's `.` excludes only U+000A where ECMAScript's `/./u` also excludes U+000D, U+2028 and
     * U+2029 (pinned in JsonSchemaAssertionsTest), so a `pattern` with an unescaped `.` outside a
     * character class could pass here and fail in the browser. No contract uses one; this keeps it
     * that way until the engine agrees with Ajv (#68). The pattern is never rewritten: the author
     * writes `[^\n\r\u2028\u2029]`, which both engines read the same way. Escaped `\.` and `.`
     * inside `[...]` are literal in both engines and are allowed.
     */
    private fun refuseBareDots(id: String, node: Any?) {
        when (node) {
            is Map<*, *> -> node.forEach { (key, value) ->
                if (key == "pattern" && value is String) check(!hasBareDot(value)) {
                    "$id: pattern $value has an unescaped `.`, which joni reads differently from Ajv; write $PORTABLE_DOT instead"
                }
                if (key == "patternProperties" && value is Map<*, *>) value.keys.forEach { check(!hasBareDot(it as String)) { "$id: patternProperties key $it has an unescaped `.`; write $PORTABLE_DOT instead" } }
                refuseBareDots(id, value)
            }
            is List<*> -> node.forEach { refuseBareDots(id, it) }
        }
    }

    private fun hasBareDot(pattern: String): Boolean {
        var inClass = false
        var i = 0
        while (i < pattern.length) {
            when (pattern[i++]) {
                '\\' -> i++
                '[' -> inClass = true
                ']' -> inClass = false
                '.' -> if (!inClass) return true
            }
        }
        return false
    }

    fun readSchema(fileName: String): String = Files.readString(schemasDir.resolve(fileName))

    private fun schema(fileName: String): Schema = cache.getOrPut(fileName) {
        registry.getSchema(SchemaLocation.of(SCHEMA_ID_PREFIX + fileName))
    }

    /** Fails the test, listing every violation, if `json` does not satisfy `contracts/schemas/<fileName>`. */
    fun assertMatchesSchema(json: String, fileName: String) {
        val errors = schema(fileName).validate(strict(json, fileName), InputFormat.JSON)
        if (errors.isNotEmpty()) {
            fail("JSON does not match $fileName:\n" + errors.joinToString("\n") { "  - ${it.message}" } + "\nJSON was:\n$json")
        }
    }

    /** The inverse, for checking the schema itself rejects bad data. */
    fun assertViolatesSchema(json: String, fileName: String) {
        if (schema(fileName).validate(strict(json, fileName), InputFormat.JSON).isEmpty()) {
            fail("Expected JSON to violate $fileName but it validated:\n$json")
        }
    }

    // The browser's JSON.parse rejects comments and trailing commas; the oracle must not be more
    // lenient than that, whatever the validator's own parser accepts.
    private fun strict(json: String, fileName: String): String =
        runCatching { JsonSyntax.requireStrict(json); json }.getOrElse {
            fail("not JSON, so it cannot be checked against $fileName: ${it.message}\nJSON was:\n$json")
        }
}
