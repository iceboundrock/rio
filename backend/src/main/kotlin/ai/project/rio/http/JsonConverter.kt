package ai.project.rio.http

import com.alibaba.fastjson2.JSON
import com.alibaba.fastjson2.JSONException
import com.alibaba.fastjson2.JSONReader
import com.alibaba.fastjson2.reader.ObjectReaderCreator
import com.alibaba.fastjson2.reader.ObjectReaderProvider
import io.ktor.http.ContentType
import io.ktor.http.content.OutgoingContent
import io.ktor.http.content.TextContent
import io.ktor.http.withCharsetIfNeeded
import io.ktor.serialization.Configuration
import io.ktor.serialization.ContentConverter
import io.ktor.util.reflect.TypeInfo
import io.ktor.util.reflect.reifiedType
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.charsets.Charset
import io.ktor.utils.io.core.readText
import io.ktor.utils.io.readRemaining

/**
 * ContentNegotiation converter backed by fastjson2, binding request bodies straight into the DTO
 * constructors and writing responses from their getters.
 *
 * Reading is strict on everything the schemas express as a shape - unknown keys, trailing input,
 * single quotes - but not on scalar kinds: fastjson2 turns a JSON number, boolean, object or array
 * into the text of a String field, so `{"amount": 1800}` binds as "1800". contracts/schemas is what
 * rejects that, on both sides of the wire (Ajv before the browser sends, the schema tests after the
 * backend answers). JSON comments are skipped by the parser and cannot be switched off.
 *
 * Without kotlin-reflect on the classpath fastjson2 logs one java.util.logging warning per Kotlin
 * class it binds, suggesting the dependency; constructor parameter names still resolve from the class
 * file's local variable table, which kotlinc emits, so the warning is noise for these DTOs.
 */
class Fastjson2Converter : ContentConverter {

    override suspend fun serialize(
        contentType: ContentType,
        charset: Charset,
        typeInfo: TypeInfo,
        value: Any?,
    ): OutgoingContent =
        // withCharsetIfNeeded leaves application/json untouched (JSON is UTF-8 by definition), so a
        // success response carries exactly the media type ValidateAccept admitted.
        TextContent(JSON.toJSONString(value), contentType.withCharsetIfNeeded(charset))

    override suspend fun deserialize(charset: Charset, typeInfo: TypeInfo, content: ByteReadChannel): Any {
        val text = content.readRemaining().readText(charset)
        // parseObject answers null for an empty body and for the literal `null`. Handing that back
        // makes ContentNegotiation raise CannotTransformContentToTypeException, which ErrorHandling.kt
        // reads as a server misconfiguration (500) for a body the client declared as JSON; a body that
        // cannot be read is the client's 400, so fail here instead.
        return JSON.parseObject(text, typeInfo.reifiedType, JSONReader.Context(readers, *READ_FEATURES))
            ?: throw JSONException("request body is empty")
    }

    private companion object {
        val READ_FEATURES = arrayOf(
            // The contract kotlinx enforced with ignoreUnknownKeys = false.
            JSONReader.Feature.ErrorOnUnknownProperties,
            // `{"$ref": "..."}` in a request body is data, not a pointer into the document being
            // parsed: without this fastjson2 resolves it and hands the route an aliased or null item.
            JSONReader.Feature.DisableReferenceDetect,
            // RFC 8259 has no single-quoted strings.
            JSONReader.Feature.DisableSingleQuote,
        )

        // The default provider generates readers with ASM, and the generated reader for a class
        // without a default constructor - every Kotlin data class here - skips unknown keys no matter
        // what ErrorOnUnknownProperties says (fastjson2 2.0.65; verified for records too). The
        // reflection-based creator's ObjectReaderNoneDefaultConstructor honours the feature, so the
        // request side uses its own provider and never the process-wide default.
        val readers = ObjectReaderProvider(ObjectReaderCreator.INSTANCE)
    }
}

/** Registers [Fastjson2Converter] for [contentType], mirroring Ktor's own `json()` helper. */
fun Configuration.fastjson2(contentType: ContentType = ContentType.Application.Json) {
    register(contentType, Fastjson2Converter())
}
