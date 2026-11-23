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
        for (char in value) {
            require(char.code in 0x20..0x7E) { "Canonical JSON is ASCII printable only" }
            when (char) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                else -> sb.append(char)
            }
        }
        sb.append('"')
    }
}
