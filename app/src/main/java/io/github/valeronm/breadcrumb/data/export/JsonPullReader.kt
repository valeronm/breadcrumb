package io.github.valeronm.breadcrumb.data.export

import java.io.Reader

/**
 * A strict, minimal streaming JSON pull-reader — [android.util.JsonReader]'s shape, but pure
 * Kotlin so its consumers parse (and test) on any JVM: the backup import and the online place
 * search both read through it. Numbers come back as Long when integral, Double otherwise.
 * Buffers its own block of input and reuses one token buffer: at millions of points the per-char
 * `Reader.read()` calls and per-token builders would dominate a restore.
 */
internal class JsonPullReader(private val reader: Reader) {

    private val buf = CharArray(8 * 1024)
    private var len = 0
    private var pos = 0
    private var eof = false
    private val token = StringBuilder()

    fun beginObject() = expect('{')
    fun endObject() = expect('}')
    fun beginArray() = expect('[')
    fun endArray() = expect(']')

    /** True while the current object/array has another element; consumes the separating comma. */
    fun hasNext(): Boolean {
        when (peekChar()) {
            '}', ']' -> return false
            ',' -> pos++
            else -> {}
        }
        return true
    }

    fun nextName(): String {
        val name = nextString()
        expect(':')
        return name
    }

    fun nextString(): String {
        check(peekChar() == '"') { "expected string" }
        pos++
        token.setLength(0)
        while (true) {
            when (val c = advance()) {
                '"' -> return token.toString()
                '\\' -> when (val e = advance()) {
                    '"', '\\', '/' -> token.append(e)
                    'n' -> token.append('\n')
                    'r' -> token.append('\r')
                    't' -> token.append('\t')
                    'b' -> token.append('\b')
                    'f' -> token.append('\u000C')
                    'u' -> {
                        val hex = String(CharArray(4) { advance() })
                        token.append(hex.toInt(16).toChar())
                    }
                    else -> error("bad escape '\\$e'")
                }
                else -> {
                    check(c >= ' ') { "raw control char in string" }
                    token.append(c)
                }
            }
        }
    }

    fun nextStringOrNull(): String? = when (val v = nextPrimitive()) {
        null -> null
        is String -> v
        else -> error("expected string, got $v")
    }

    fun nextNumber(): Number = checkNotNull(nextNumberOrNull()) { "expected number, got null" }

    fun nextNumberOrNull(): Number? = when (val v = nextPrimitive()) {
        null -> null
        is Number -> v
        else -> error("expected number, got $v")
    }

    /** A scalar: String, Long, Double, Boolean, or null. */
    fun nextPrimitive(): Any? = when (peekChar()) {
        '"' -> nextString()
        't' -> literal("true", true)
        'f' -> literal("false", false)
        'n' -> literal("null", null)
        else -> number()
    }

    /** Skips one whole value of any shape — an unknown key's payload. */
    fun skipValue() {
        when (peekChar()) {
            '{' -> {
                beginObject()
                while (hasNext()) {
                    nextName()
                    skipValue()
                }
                endObject()
            }
            '[' -> {
                beginArray()
                while (hasNext()) skipValue()
                endArray()
            }
            else -> nextPrimitive()
        }
    }

    /** The next significant character, whitespace skipped, without consuming it. */
    fun peekChar(): Char {
        skipWs()
        check(fill()) { "unexpected end of input" }
        return buf[pos]
    }

    /** Asserts the input is exhausted — a parsed document may not trail extra content. */
    fun expectEnd() {
        skipWs()
        check(!fill()) { "trailing content after document" }
    }

    private fun number(): Number {
        token.setLength(0)
        while (fill() && buf[pos] in "0123456789+-.eE") token.append(buf[pos++])
        val text = token.toString()
        check(text.isNotEmpty()) { "expected value" }
        return if (text.none { it in ".eE" }) text.toLong() else text.toDouble()
    }

    private fun <T> literal(text: String, value: T): T {
        for (c in text) check(advance() == c) { "bad literal" }
        return value
    }

    private fun expect(c: Char) {
        check(peekChar() == c) { "expected '$c', got '${buf[pos]}'" }
        pos++
    }

    private fun advance(): Char {
        check(fill()) { "unexpected end of input" }
        return buf[pos++]
    }

    /** Ensures at least one buffered char; false at end of input. */
    private fun fill(): Boolean {
        if (pos < len) return true
        if (eof) return false
        len = reader.read(buf, 0, buf.size)
        pos = 0
        if (len <= 0) {
            eof = true
            len = 0
            return false
        }
        return true
    }

    private fun skipWs() {
        while (fill() && buf[pos].isWhitespace()) pos++
    }
}
