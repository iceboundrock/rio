package ai.project.rio.contract

import com.networknt.schema.InputFormat
import com.networknt.schema.Schema
import com.networknt.schema.SchemaLocation
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SpecificationVersion
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.fail

/**
 * Validates JSON text against the shared contracts in contracts/schemas (Draft 2020-12).
 * The directory comes from the `contracts.schemas.dir` system property set in build.gradle.kts.
 *
 * Every *.schema.json file in that directory is registered under its `$id`
 * (https://rio.local/schemas/<file>), so `$ref`s between files resolve locally
 * without any network access.
 */
object JsonSchemaAssertions {

    private const val SCHEMA_ID_PREFIX = "https://rio.local/schemas/"

    private val schemasDir: Path = Path.of(
        System.getProperty("contracts.schemas.dir")
            ?: fail("System property contracts.schemas.dir is not set; run tests through Gradle"),
    ).also { check(Files.isDirectory(it)) { "schemas dir does not exist: $it" } }

    private val registry: SchemaRegistry = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12) { builder ->
        val schemaSources = Files.list(schemasDir).use { files ->
            files.filter { it.fileName.toString().endsWith(".schema.json") }
                .toList()
                .associate { SCHEMA_ID_PREFIX + it.fileName.toString() to Files.readString(it) }
        }
        builder.schemas(schemaSources)
    }

    private val cache = HashMap<String, Schema>()

    private fun schema(fileName: String): Schema = cache.getOrPut(fileName) {
        registry.getSchema(SchemaLocation.of(SCHEMA_ID_PREFIX + fileName))
    }

    /** Fails the test, listing every violation, if `json` does not satisfy `contracts/schemas/<fileName>`. */
    fun assertMatchesSchema(json: String, fileName: String) {
        val errors = schema(fileName).validate(json, InputFormat.JSON)
        if (errors.isNotEmpty()) {
            fail(
                "JSON does not match $fileName:\n" +
                    errors.joinToString("\n") { "  - ${it.message}" } +
                    "\nJSON was:\n$json",
            )
        }
    }

    /** The inverse, for checking the schema itself rejects bad data. */
    fun assertViolatesSchema(json: String, fileName: String) {
        val errors = schema(fileName).validate(json, InputFormat.JSON)
        if (errors.isEmpty()) fail("Expected JSON to violate $fileName but it validated:\n$json")
    }
}
