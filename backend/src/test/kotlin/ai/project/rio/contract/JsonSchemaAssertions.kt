package ai.project.rio.contract

import ai.project.rio.http.JsonSyntax
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
 * in ECMAScript mode, the dialect the schema means and Ajv uses in the browser.
 * JsonSchemaAssertionsTest is the proof that each keyword, and each cross-file reference, bites.
 */
object JsonSchemaAssertions {

    private const val SCHEMA_ID_PREFIX = "https://rio.local/schemas/"

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
    internal fun registry(schemas: Map<String, String>): SchemaRegistry =
        SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12) { builder ->
            builder.schemas(schemas)
            builder.schemaRegistryConfig(
                SchemaRegistryConfig.builder()
                    .regularExpressionFactory(ECMAScriptRegularExpressionFactory.getInstance())
                    .build(),
            )
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
