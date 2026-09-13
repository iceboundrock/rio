package ai.project.rio.http

/**
 * JSON Schema `pattern` is an ECMAScript regular expression (Draft 2020-12 §6.4), and the contracts
 * say a description must match `\S`. ECMAScript `\s` is a different set from Java's or Kotlin's: it
 * adds every Unicode space separator, both line separators, and U+FEFF, and it lacks the ASCII
 * separators U+001C..U+001F that `Char.isWhitespace` includes. Anything that decides "blank" on the
 * backend must use this set, or the browser's Ajv rejects a response the backend produced.
 */
object EcmaScript {

    /** Exactly the characters ECMAScript `\s` matches: WhiteSpace plus LineTerminator. */
    const val WHITESPACE: String =
        "\t\n\u000B\u000C\r \u00A0\u1680\u2000\u2001\u2002\u2003\u2004\u2005\u2006\u2007\u2008\u2009\u200A\u2028\u2029\u202F\u205F\u3000\uFEFF"

    fun isWhitespace(c: Char): Boolean = c in WHITESPACE
}
