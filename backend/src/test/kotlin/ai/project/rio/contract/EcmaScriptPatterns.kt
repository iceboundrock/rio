package ai.project.rio.contract

import ai.project.rio.http.EcmaScript

/**
 * Rewrites a JSON Schema `pattern` from the dialect the schema means (ECMAScript, as Ajv compiles it
 * with the `u` flag) into one `java.util.regex` reads the same way, because fastjson2 hands the
 * pattern to `Pattern.compile` unchanged and the two engines disagree on:
 *
 * - `\s` / `\S`: ECMAScript whitespace is [EcmaScript.WHITESPACE]; Java's is six ASCII characters.
 * - `$`: end of input in ECMAScript; in Java also before a final line terminator, so `^1800$`
 *   accepts "1800\n" under `find()`.
 * - `.`: Java also excludes U+0085.
 * - `\v`: U+000B in ECMAScript; a whole vertical-whitespace class in Java.
 * - inside `[...]`: `[` and `&` are literal in ECMAScript but open a nested class or intersection in Java.
 *
 * Everything else the contracts could plausibly use (`^`, groups, quantifiers, `\d \w`, literal
 * escapes, `\uXXXX`) reads the same in both. Constructs whose meaning differs by engine or JDK and
 * that no contract uses (`\b`, `\B`, `\p{..}`, `\u{..}`, `\k<..>`, `\0`, `\S` inside a class) are
 * refused rather than guessed at, so adding one to a contract is a loud failure here, not a silent
 * disagreement with the browser.
 */
object EcmaScriptPatterns {

    private val whitespaceClassBody = EcmaScript.WHITESPACE.map { "\\u%04X".format(it.code) }.joinToString("")

    fun toJava(pattern: String): String {
        val out = StringBuilder()
        var inClass = false
        var i = 0
        while (i < pattern.length) {
            val c = pattern[i++]
            when {
                c == '\\' -> {
                    val e = pattern.getOrNull(i++) ?: error("pattern ends in a backslash: $pattern")
                    out.append(
                        when (e) {
                            's' -> if (inClass) whitespaceClassBody else "[$whitespaceClassBody]"
                            'S' -> if (inClass) unsupported(pattern, "\\S inside a class") else "[^$whitespaceClassBody]"
                            'v' -> "\\x0B"
                            'b', 'B', 'p', 'P', 'k', '0' -> unsupported(pattern, "\\$e")
                            'u' -> if (pattern.getOrNull(i) == '{') unsupported(pattern, "\\u{...}") else "\\u"
                            else -> "\\$e"
                        },
                    )
                }
                inClass -> when (c) {
                    ']' -> { inClass = false; out.append(c) }
                    '[', '&' -> out.append('\\').append(c)
                    else -> out.append(c)
                }
                c == '[' -> { inClass = true; out.append(c) }
                c == '$' -> out.append("\\z")
                c == '.' -> out.append("[^\\n\\r\\u2028\\u2029]")
                else -> out.append(c)
            }
        }
        check(!inClass) { "unterminated character class: $pattern" }
        return out.toString()
    }

    private fun unsupported(pattern: String, what: String): Nothing =
        error("$what has no single Java equivalent; not translating pattern: $pattern")
}
