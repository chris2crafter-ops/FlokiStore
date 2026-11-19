package io.github.chris2crafter.thorstore.core

/**
 * Minimal JSON reader/writer so core, the catalog tool and the app share one format
 * without pulling in a library (org.json only exists on Android).
 * Objects become LinkedHashMap<String, Any?>, arrays List<Any?>, numbers Long or Double.
 */
object Json {

    fun parse(text: String): Any? {
        val p = Parser(text)
        val v = p.value()
        p.ws()
        require(p.i == text.length) { "Unerwartete Zeichen nach JSON bei ${p.i}" }
        return v
    }

    @Suppress("UNCHECKED_CAST")
    fun obj(text: String): Map<String, Any?> = parse(text) as Map<String, Any?>

    fun write(v: Any?, pretty: Boolean = false): String =
        StringBuilder().also { write(it, v, if (pretty) "" else null) }.toString()

    private fun write(sb: StringBuilder, v: Any?, indent: String?) {
        val next = indent?.let { "$it  " }
        when (v) {
            null -> sb.append("null")
            is String -> quote(sb, v)
            is Boolean, is Int, is Long -> sb.append(v.toString())
            is Number -> sb.append(v.toDouble().let { if (it == Math.floor(it) && !it.isInfinite()) it.toLong().toString() else it.toString() })
            is Map<*, *> -> {
                if (v.isEmpty()) { sb.append("{}"); return }
                sb.append('{')
                var first = true
                for ((k, value) in v) {
                    if (!first) sb.append(',')
                    first = false
                    if (next != null) sb.append('\n').append(next)
                    quote(sb, k.toString())
                    sb.append(if (next != null) ": " else ":")
                    write(sb, value, next)
                }
                if (indent != null) sb.append('\n').append(indent)
                sb.append('}')
            }
            is Iterable<*> -> {
                val list = v.toList()
                if (list.isEmpty()) { sb.append("[]"); return }
                // short scalar lists stay on one line
                val inline = list.all { it !is Map<*, *> && it !is Iterable<*> }
                sb.append('[')
                list.forEachIndexed { i, value ->
                    if (i > 0) sb.append(if (inline && next != null) ", " else ",")
                    if (next != null && !inline) sb.append('\n').append(next)
                    write(sb, value, next)
                }
                if (indent != null && !inline) sb.append('\n').append(indent)
                sb.append(']')
            }
            else -> quote(sb, v.toString())
        }
    }

    private fun quote(sb: StringBuilder, s: String) {
        sb.append('"')
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c < ' ') sb.append(String.format("\\u%04x", c.code)) else sb.append(c)
            }
        }
        sb.append('"')
    }

    private class Parser(val s: String) {
        var i = 0

        fun ws() {
            while (i < s.length && s[i].isWhitespace()) i++
        }

        fun value(): Any? {
            ws()
            require(i < s.length) { "JSON endet unerwartet" }
            return when (s[i]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> str()
                't' -> lit("true", true)
                'f' -> lit("false", false)
                'n' -> lit("null", null)
                else -> num()
            }
        }

        fun lit(word: String, v: Any?): Any? {
            require(s.startsWith(word, i)) { "Ungültiges Literal bei $i" }
            i += word.length
            return v
        }

        fun expect(c: Char) {
            require(i < s.length && s[i] == c) { "'$c' erwartet bei $i" }
            i++
        }

        fun obj(): Map<String, Any?> {
            val m = LinkedHashMap<String, Any?>()
            expect('{')
            ws()
            if (s[i] == '}') { i++; return m }
            while (true) {
                ws()
                val k = str()
                ws()
                expect(':')
                m[k] = value()
                ws()
                if (s[i] == ',') { i++; continue }
                expect('}')
                return m
            }
        }

        fun arr(): List<Any?> {
            val l = ArrayList<Any?>()
            expect('[')
            ws()
            if (s[i] == ']') { i++; return l }
            while (true) {
                l.add(value())
                ws()
                if (s[i] == ',') { i++; continue }
                expect(']')
                return l
            }
        }

        fun str(): String {
            expect('"')
            val b = StringBuilder()
            while (true) {
                require(i < s.length) { "String nicht abgeschlossen" }
                when (val c = s[i++]) {
                    '"' -> return b.toString()
                    '\\' -> when (val e = s[i++]) {
                        'n' -> b.append('\n')
                        't' -> b.append('\t')
                        'r' -> b.append('\r')
                        'b' -> b.append('\b')
                        'f' -> b.append('\u000c')
                        'u' -> { b.append(s.substring(i, i + 4).toInt(16).toChar()); i += 4 }
                        else -> b.append(e)
                    }
                    else -> b.append(c)
                }
            }
        }

        fun num(): Any {
            val start = i
            while (i < s.length && s[i] in "+-0123456789.eE") i++
            val t = s.substring(start, i)
            require(t.isNotEmpty()) { "Ungültiges Zeichen bei $start" }
            return t.toLongOrNull() ?: t.toDouble()
        }
    }
}

// Small typed accessors for parsed JSON maps
fun Map<String, Any?>.str(key: String): String? = this[key] as? String
fun Map<String, Any?>.bool(key: String): Boolean = this[key] as? Boolean ?: false
fun Map<String, Any?>.long(key: String): Long? = (this[key] as? Number)?.toLong()
@Suppress("UNCHECKED_CAST")
fun Map<String, Any?>.strList(key: String): List<String> = (this[key] as? List<Any?>)?.mapNotNull { it as? String } ?: emptyList()
@Suppress("UNCHECKED_CAST")
fun Map<String, Any?>.objList(key: String): List<Map<String, Any?>> = (this[key] as? List<Any?>)?.mapNotNull { it as? Map<String, Any?> } ?: emptyList()
