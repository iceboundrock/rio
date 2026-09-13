package ai.project.rio.http

import com.alibaba.fastjson2.JSON
import com.alibaba.fastjson2.JSONException
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Each rejected text here is one fastjson2 itself accepts (or would accept once bound), which is why
 * the check exists; each accepted text is RFC 8259 the check must not get in the way of.
 */
class JsonSyntaxTest {

    private fun accepts(text: String) = JsonSyntax.requireStrict(text)

    private fun rejects(text: String) {
        val e = assertFailsWith<JSONException>("expected rejection of: $text") { JsonSyntax.requireStrict(text) }
        assertTrue(e.message!!.startsWith("not RFC 8259 JSON at offset"), e.message)
        // The message must never quote the input: ErrorHandling does not echo it, but logs would.
        assertTrue(text.length < 3 || !e.message!!.contains(text.take(3)), "message quotes the input: ${e.message}")
    }

    @Test
    fun `accepts every RFC 8259 form`() {
        accepts("""{"description":"Lunch","amount":{"amount":"1800","currency":"USD"},"type":"DEBIT"}""")
        accepts(" \t\r\n[ {\"a\" : [ ] , \"b\" : { } } , 1 , -0 , 0.5 , 1e10 , 1E-2 , -12.5e+3 , true , false , null ] \n")
        accepts("\"\"")
        accepts(""""\" \\ \/ \b \f \n \r \t \u00e9 \uD83D\uDE00 plain é 😀 // not a comment /* nor this */ ,}"""")
        accepts("42")
        accepts("null")
        accepts("[".repeat(2048) + "]".repeat(2048))
    }

    @Test
    fun `rejects the extensions fastjson2 tolerates`() {
        for (text in listOf(
            """{"a":1,}""",
            """[1,2,]""",
            """{"a":[1,],}""",
            """{"a":1}/* c */""",
            """{"a":1}// c""",
            "{\"a\":1,// c\n\"b\":2}",
            "// c\n{\"a\":1}",
            "\uFEFF{}",
        )) {
            rejects(text)
            // Each of these is a syntax the lenient parser lets through; that is why the check exists.
            assertTrue(runCatching { JSON.parse(text) }.isSuccess, "fastjson2 rejects this on its own, so the case is moot: $text")
        }
    }

    @Test
    fun `rejects other non-JSON syntax`() {
        for (text in listOf("""{"a":/* c */1}""", """{'a':1}""", """{a:1}""", """{"a":NaN}""", """{"a":Infinity}""", """{"a":1}{"a":1}""", """{"a":1} x""")) {
            rejects(text)
        }
    }

    @Test
    fun `rejects malformed numbers strings and structure`() {
        for (text in listOf(
            "", " ", "01", "+1", ".5", "1.", "1e", "1e+", "-", "--1", "0x10", "1_000",
            "\"unterminated", "\"tab\there\"", "\"nl\nhere\"", "\"\\x41\"", "\"\\u12\"", "\"\\u12G4\"", "\"\\'\"", "\"\\",
            "{", "}", "[", "]", "{\"a\"}", "{\"a\":}", "{\"a\" 1}", "{\"a\":1 \"b\":2}", "[1 2]", "{1:2}", "{\"a\":1,,\"b\":2}", "[,1]",
            "tru", "True", "nul", "undefined", "truefalse",
            "[".repeat(2049) + "]".repeat(2049),
        )) {
            rejects(text)
        }
    }
}
