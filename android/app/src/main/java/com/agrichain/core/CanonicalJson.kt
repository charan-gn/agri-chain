package com.agrichain.core

object CanonicalJson {

    fun encode(value: Any?): String = buildString { appendValue(this, value) }

    private fun appendValue(sb: StringBuilder, value: Any?) {
        when (value) {
            null -> sb.append("null")
            is String -> appendString(sb, value)
            is Int, is Long -> sb.append(value.toString())
            is Boolean -> sb.append(value.toString())
            is Map<*, *> -> {
                val keys = value.keys.map {
                    require(it is String) { "JSON object keys must be strings" }
                    require(it.all { char -> char.code in 0x20..0x7E }) {
                        "JSON object keys must be ASCII printable"
                    }
                    it
                }.sorted()
                sb.append('{')
                keys.forEachIndexed { index, key ->
                    if (index > 0) sb.append(',')
                    appendString(sb, key)
                    sb.append(':')
                    appendValue(sb, value[key])
                }
                sb.append('}')
            }
            is Iterable<*> -> {
                sb.append('[')
                value.forEachIndexed { index, item ->
                    if (index > 0) sb.append(',')
                    appendValue(sb, item)
                }
                sb.append(']')
            }
            else -> throw IllegalArgumentException("Unsupported JSON value: ${value::class.java.name}")
        }
    }

    private fun appendString(sb: StringBuilder, value: String) {
        sb.append('"')
        var index = 0
        while (index < value.length) {
            val char = value[index]
            when {
                char == '"' -> sb.append("\\\"")
                char == '\\' -> sb.append("\\\\")
                char == '\b' -> sb.append("\\b")
                char == '\u000C' -> sb.append("\\f")
                char == '\n' -> sb.append("\\n")
                char == '\r' -> sb.append("\\r")
                char == '\t' -> sb.append("\\t")
                char.code < 0x20 ->
                    sb.append("\\u").append(char.code.toString(16).padStart(4, '0'))
                char.isHighSurrogate() -> {
                    require(index + 1 < value.length && value[index + 1].isLowSurrogate()) {
                        "Canonical JSON strings must not contain unpaired surrogates"
                    }
                    sb.append(char).append(value[index + 1])
                    index++
                }
                char.isLowSurrogate() ->
                    throw IllegalArgumentException(
                        "Canonical JSON strings must not contain unpaired surrogates"
                    )
                else -> sb.append(char)
            }
            index++
        }
        sb.append('"')
    }
}
