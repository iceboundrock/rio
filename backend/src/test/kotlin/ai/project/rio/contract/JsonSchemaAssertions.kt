package ai.project.rio.contract

import ai.project.rio.http.JsonSyntax
import com.alibaba.fastjson2.JSON
import com.alibaba.fastjson2.JSONArray
import com.alibaba.fastjson2.JSONObject
import com.alibaba.fastjson2.schema.JSONSchema
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.fail

/**
 * Validates JSON text against the shared contracts in contracts/schemas (Draft 2020-12).
 * The directory comes from the `contracts.schemas.dir` system property set in build.gradle.kts.
 *
 * fastjson2's JSONSchema resolves only same-document `$ref`s (`#`, `#/$defs/<name>`), and a `$ref`
 * it cannot resolve becomes a reference that validates *anything*: a contract would silently stop
 * being enforced. So every `$ref` is replaced here, before fastjson2 sees the schema, by a copy of
 * what it points at, and anything this loader cannot resolve is an error rather than a pass. Each
 * `pattern` is rewritten by [EcmaScriptPatterns] on the way, because fastjson2 compiles it as a Java
 * regex while the schema (and Ajv in the browser) means ECMAScript. JsonSchemaAssertionsTest is the
 * proof that each keyword, and each cross-file reference, still bites.
 */
object JsonSchemaAssertions {

    private val schemasDir: Path = Path.of(
        System.getProperty("contracts.schemas.dir")
            ?: fail("System property contracts.schemas.dir is not set; run tests through Gradle"),
    ).also { check(Files.isDirectory(it)) { "schemas dir does not exist: $it" } }

    /** Every contract file by file name, parsed once and never mutated; [inline] copies out of it. */
    private val sources: Map<String, JSONObject> = Files.list(schemasDir).use { files ->
        files.filter { it.fileName.toString().endsWith(".schema.json") }
            .toList()
            .associate { it.fileName.toString() to JSON.parseObject(Files.readString(it)) }
    }

    // $schema and $id describe the file, not the value, and a stale $id on an inlined copy would claim
    // it is a different document; $defs is only reachable through a $ref, and every $ref is gone.
    private val FILE_KEYS = setOf("\$schema", "\$id", "\$defs")

    private const val DEFS = "/\$defs/"

    private val cache = HashMap<String, JSONSchema>()

    fun readSchema(fileName: String): String = Files.readString(schemasDir.resolve(fileName))

    /** Fails the test, with the first violation, if `json` does not satisfy `contracts/schemas/<fileName>`. */
    fun assertMatchesSchema(json: String, fileName: String) {
        val result = schema(fileName).validate(parse(json, fileName))
        if (!result.isSuccess) fail("JSON does not match $fileName:\n  - ${result.message}\nJSON was:\n$json")
    }

    /** The inverse, for checking the schema itself rejects bad data. */
    fun assertViolatesSchema(json: String, fileName: String) {
        if (schema(fileName).validate(parse(json, fileName)).isSuccess) {
            fail("Expected JSON to violate $fileName but it validated:\n$json")
        }
    }

    // JsonSyntax first: JSON.parse alone would let a response with a comment or trailing comma reach
    // the validator, and the browser's parser would have rejected it.
    private fun parse(json: String, fileName: String): Any? =
        runCatching { JsonSyntax.requireStrict(json); JSON.parse(json) }.getOrElse {
            fail("not JSON, so it cannot be checked against $fileName: ${it.message}\nJSON was:\n$json")
        }

    private fun schema(fileName: String): JSONSchema = cache.getOrPut(fileName) { JSONSchema.of(inlined(fileName)) }

    /** `contracts/schemas/<fileName>` with every `$ref` replaced by a copy of what it points at. */
    internal fun inlined(fileName: String): JSONObject = inline(source(fileName), fileName) as JSONObject

    internal fun inline(node: Any?, fileName: String, refs: List<String> = emptyList()): Any? = when (node) {
        is JSONObject -> {
            val ref = node["\$ref"]
            when {
                ref == null -> JSONObject().also { copy ->
                    for ((key, value) in node) {
                        copy[key] = when {
                            key in FILE_KEYS -> continue
                            // A `pattern` keyword is a string; a property called "pattern" is an object.
                            key == "pattern" && value is String -> EcmaScriptPatterns.toJava(value)
                            else -> inline(value, fileName, refs)
                        }
                    }
                }
                // 2020-12 allows keywords beside a $ref; these contracts never use them, and silently
                // dropping them would weaken a schema, so refuse instead of guessing.
                node.size != 1 || ref !is String -> error("a \$ref must be the only key and a string, in $fileName: $node")
                else -> {
                    val (targetFile, fragment) = split(ref, fileName)
                    val key = "$targetFile#$fragment"
                    check(key !in refs) { "cyclic \$ref: ${(refs + key).joinToString(" -> ")}" }
                    inline(target(targetFile, fragment), targetFile, refs + key)
                }
            }
        }
        is JSONArray -> JSONArray(node.map { inline(it, fileName, refs) })
        else -> node
    }

    /** `<file>#<fragment>`; an empty file part means the current file, an empty fragment the file root. */
    private fun split(ref: String, fileName: String): Pair<String, String> {
        val file = ref.substringBefore('#').ifEmpty { fileName }
        check(!file.contains('/') && file.endsWith(".schema.json")) { "unsupported \$ref target '$ref' in $fileName" }
        return file to ref.substringAfter('#', "")
    }

    private fun target(file: String, fragment: String): JSONObject = when {
        fragment.isEmpty() -> source(file)
        fragment.startsWith(DEFS) && !fragment.removePrefix(DEFS).contains('/') ->
            source(file).getJSONObject("\$defs")?.getJSONObject(fragment.removePrefix(DEFS))
                ?: error("no \$defs entry '$fragment' in $file")
        // fastjson2 cannot follow nested pointers at all; neither will this loader pretend to.
        else -> error("unsupported \$ref fragment '#$fragment' in $file")
    }

    private fun source(file: String): JSONObject = sources[file] ?: error("unknown schema file '$file'")
}
