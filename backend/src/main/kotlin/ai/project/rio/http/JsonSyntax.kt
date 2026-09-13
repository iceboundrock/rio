package ai.project.rio.http

import com.alibaba.fastjson2.JSONException

/**
 * Checks that a text is exactly one JSON value under the RFC 8259 grammar and nothing more.
 *
 * fastjson2 has no strict mode: its reader skips `/* */` and `//` comments, tolerates a comma before
 * `}` or `]`, and no [com.alibaba.fastjson2.JSONReader.Feature] switches either off. The request
 * converter runs this check on the raw body before fastjson2 binds it, so `malformed request body`
 * means what RFC 8259 means; the schema tests run it on every response before validating. The check
 * rejects a byte order mark (RFC 8259 §8.1 permits either choice) and nesting deeper than fastjson2's
 * own default, so the two never disagree about a body. It does not recurse, so a deeply nested body
 * costs a stack entry per level and never a thread's stack.
 */
object JsonSyntax {

    /** The same ceiling fastjson2's `JSONReader.Context.maxLevel` defaults to. */
    private const val MAX_DEPTH = 2048

    /** Throws [JSONException], naming the offset and never quoting the text, unless [text] is one RFC 8259 value. */
    fun requireStrict(text: String) {
        Scanner(text).document()
    }

    private class Scanner(private val s: String) {
        private var i = 0

        /** The containers still open, innermost last: true for an object, false for an array. */
        private val open = ArrayDeque<Boolean>()

        fun document() {
            whitespace()
            value()
            while (open.isNotEmpty()) {
                // Just after a value inside the innermost open container.
                whitespace()
                val isObject = open.last()
                when {
                    next(if (isObject) '}' else ']') -> open.removeLast()
                    next(',') -> {
                        whitespace()
                        if (isObject) key()
                        value()
                    }
                    else -> fail(if (isObject) "expected ',' or '}'" else "expected ',' or ']'")
                }
            }
            whitespace()
            if (i < s.length) fail("content after the JSON value")
        }

        /**
         * Scans a scalar, or opens containers until it reaches one: an empty container is closed at
         * once, a non-empty one is pushed and its first (key and) value scanned. What follows the
         * value is [document]'s business.
         */
        private fun value() {
            while (true) {
                if (i >= s.length) fail("unexpected end of input")
                when (s[i]) {
                    '{' -> {
                        push(isObject = true)
                        if (next('}')) {
                            open.removeLast()
                            return
                        }
                        key()
                    }
                    '[' -> {
                        push(isObject = false)
                        if (next(']')) {
                            open.removeLast()
                            return
                        }
                    }
                    '"' -> return string()
                    't' -> return literal("true")
                    'f' -> return literal("false")
                    'n' -> return literal("null")
                    '-', in '0'..'9' -> return number()
                    else -> fail("unexpected character")
                }
            }
        }

        private fun push(isObject: Boolean) {
            if (open.size >= MAX_DEPTH) fail("nesting deeper than $MAX_DEPTH")
            open.addLast(isObject)
            i++ // the bracket
            whitespace()
        }

        private fun key() {
            if (i >= s.length || s[i] != '"') fail("expected a string key")
            string()
            whitespace()
            if (!next(':')) fail("expected ':'")
            whitespace()
        }

        private fun string() {
            i++ // opening quote
            while (true) {
                if (i >= s.length) fail("unterminated string")
                val c = s[i++]
                when {
                    c == '"' -> return
                    c == '\\' -> escape()
                    c < ' ' -> fail("control character in string")
                }
            }
        }

        private fun escape() {
            if (i >= s.length) fail("unterminated escape")
            when (s[i++]) {
                '"', '\\', '/', 'b', 'f', 'n', 'r', 't' -> Unit
                'u' -> repeat(4) {
                    if (i >= s.length || !s[i].isAsciiHexDigit()) fail("expected four hex digits after \\u")
                    i++
                }
                else -> fail("invalid escape")
            }
        }

        private fun Char.isAsciiHexDigit(): Boolean = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

        private fun literal(word: String) {
            if (!s.startsWith(word, i)) fail("unexpected character")
            i += word.length
        }

        private fun number() {
            next('-')
            when {
                next('0') -> Unit
                digits() -> Unit
                else -> fail("expected a digit")
            }
            if (next('.') && !digits()) fail("expected a digit after '.'")
            if (next('e') || next('E')) {
                if (!next('+')) next('-')
                if (!digits()) fail("expected a digit in the exponent")
            }
        }

        /** Consumes a run of ASCII digits; false if there was none. */
        private fun digits(): Boolean {
            val start = i
            while (i < s.length && s[i] in '0'..'9') i++
            return i > start
        }

        private fun whitespace() {
            while (i < s.length && (s[i] == ' ' || s[i] == '\t' || s[i] == '\n' || s[i] == '\r')) i++
        }

        private fun next(c: Char): Boolean {
            if (i < s.length && s[i] == c) {
                i++
                return true
            }
            return false
        }

        private fun fail(reason: String): Nothing = throw JSONException("not RFC 8259 JSON at offset $i: $reason")
    }
}
