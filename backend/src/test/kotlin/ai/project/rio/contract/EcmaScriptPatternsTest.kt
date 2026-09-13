package ai.project.rio.contract

import ai.project.rio.http.EcmaScript
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Each case is a text on which Java's reading of the pattern disagrees with ECMAScript's (checked
 * against Ajv, which compiles patterns with the `u` flag); the translated pattern must side with
 * ECMAScript, and an untranslated one is shown to side with Java so the case is known to be live.
 */
class EcmaScriptPatternsTest {

    private fun java(pattern: String, text: String) = Regex(pattern).containsMatchIn(text)
    private fun ecma(pattern: String, text: String) = Regex(EcmaScriptPatterns.toJava(pattern)).containsMatchIn(text)

    @Test
    fun `whitespace classes use the ECMAScript set`() {
        for (c in EcmaScript.WHITESPACE) {
            assertTrue(ecma("\\s", c.toString()), "\\s should match U+%04X".format(c.code))
            assertFalse(ecma("\\S", c.toString()), "\\S should not match U+%04X".format(c.code))
            assertTrue(ecma("^[\\s]$", c.toString()), "[\\s] should match U+%04X".format(c.code))
        }
        for (c in listOf('\u001C', '\u001F', '\u0085', 'a', '0')) {
            assertFalse(ecma("\\s", c.toString()), "\\s should not match U+%04X".format(c.code))
            assertTrue(ecma("\\S", c.toString()), "\\S should match U+%04X".format(c.code))
        }
        // Java on its own: neither U+FEFF nor U+00A0 is whitespace.
        assertTrue(java("\\S", "\uFEFF"))
        assertFalse(java("\\s", "\u00A0"))
    }

    @Test
    fun `dollar is end of input, not before a final newline`() {
        val amount = "^-?(0|[1-9][0-9]*)$"
        assertTrue(java(amount, "1800\n"))
        assertFalse(ecma(amount, "1800\n"))
        assertTrue(ecma(amount, "1800"))
        assertFalse(ecma(amount, "1800\r\n"))
    }

    @Test
    fun `dot and v match what ECMAScript says`() {
        assertFalse(java(".", "\u0085"))
        assertTrue(ecma(".", "\u0085"))
        for (terminator in listOf("\n", "\r", "\u2028", "\u2029")) assertFalse(ecma(".", terminator))
        assertTrue(ecma("^\\v$", "\u000B"))
        assertFalse(ecma("^\\v$", "\n"))
        assertTrue(java("^\\v$", "\n"))
    }

    @Test
    fun `class contents that Java would read as syntax are literal`() {
        assertTrue(ecma("^[[&]+$", "[&&["))
        assertEquals("\\^\\$\\.\\[\\]\\\\/\\u00e9(a|b){1,2}[^a-z]\\d\\w", EcmaScriptPatterns.toJava("\\^\\$\\.\\[\\]\\\\/\\u00e9(a|b){1,2}[^a-z]\\d\\w"))
    }

    @Test
    fun `constructs with no single Java equivalent are refused`() {
        for (pattern in listOf("\\bword\\b", "\\B", "\\p{L}", "\\u{1F600}", "(?<n>a)\\k<n>", "\\0", "[\\S]", "[abc", "abc\\")) {
            assertFailsWith<IllegalStateException>(pattern) { EcmaScriptPatterns.toJava(pattern) }
        }
    }

    @Test
    fun `every contract pattern translates`() {
        // The patterns in contracts/schemas as of this test; a new one that uses a refused construct
        // fails JsonSchemaAssertions at load instead, which is the intended way to find out.
        for (pattern in listOf("^-?(0|[1-9][0-9]*)$", "^[1-9][0-9]*$", "\\S", "^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(\\.[0-9]+)?Z$")) {
            Regex(EcmaScriptPatterns.toJava(pattern))
        }
    }
}
