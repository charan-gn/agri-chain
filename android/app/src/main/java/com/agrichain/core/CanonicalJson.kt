package com.agrichain.core

object CanonicalJson {

    fun encode(value: Any?): String = buildString { appendValue(this, value) }

    private fun appendValue(sb: StringBuilder, value: Any?) {
        when (value) {
            null -> sb.append("null")
            is String -> appendString(sb, value, isKey = false)
            is Byte, is Short, is Int, is Long -> sb.append(value.toString())
            is Boolean -> sb.append(value.toString())
            is Map<*, *> -> {
                val keys = value.keys.map {
                    require(it is String) { "JSON object keys must be strings" }
                    validateKey(it)
                    it
                }.sorted()
                sb.append('{')
                keys.forEachIndexed { index, key ->
                    if (index > 0) sb.append(',')
                    appendString(sb, key, isKey = true)
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

    private fun validateKey(key: String) {
        for (char in key) {
            require(char.code in 0x20..0x7E) {
                "Canonical JSON object keys must be printable ASCII"
            }
        }
    }

    private fun appendString(sb: StringBuilder, value: String, isKey: Boolean) {
        sb.append('"')
        var index = 0
        while (index < value.length) {
            val char = value[index]
            if (isKey) {
                require(char.code in 0x20..0x7E) {
                    "Canonical JSON object keys must be printable ASCII"
                }
            } else if (Character.isHighSurrogate(char)) {
                require(index + 1 < value.length && Character.isLowSurrogate(value[index + 1])) {
                    "Canonical JSON strings must be valid Unicode"
                }
                sb.append(char)
                sb.append(value[index + 1])
                index += 2
                continue
            } else if (Character.isLowSurrogate(char)) {
                throw IllegalArgumentException("Canonical JSON strings must be valid Unicode")
            }

            when (char) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (char.code < 0x20) {
                    sb.append("\\u%04x".format(char.code))
                } else {
                    sb.append(char)
                }
            }
            index++
        }
        sb.append('"')
    }
}
