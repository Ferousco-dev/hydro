package com.ferousco.hydro.core.json

import com.ferousco.hydro.core.error.EngineError
import com.ferousco.hydro.core.error.EngineResult

/**
 * Internal/boundary JSON AST used strictly at external serialization boundaries
 * (HTTP provider payloads, file persistence, and AI tool wire serialization).
 * Domain interfaces never expose raw JSON structures as primary models.
 */
sealed interface JsonValue {
    data class ObjectValue(val entries: Map<String, JsonValue>) : JsonValue {
        operator fun get(key: String): JsonValue? = entries[key]
        fun getString(key: String): String? = (entries[key] as? StringValue)?.value
        fun getDouble(key: String): Double? = (entries[key] as? NumberValue)?.toDoubleOrNull()
        fun getLong(key: String): Long? = (entries[key] as? NumberValue)?.toLongExactOrNull()
        fun getInt(key: String): Int? = getLong(key)?.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt()
        fun getBoolean(key: String): Boolean? = (entries[key] as? BooleanValue)?.value
        fun getObject(key: String): ObjectValue? = entries[key] as? ObjectValue
        fun getArray(key: String): ArrayValue? = entries[key] as? ArrayValue
    }

    data class ArrayValue(val items: List<JsonValue>) : JsonValue
    data class StringValue(val value: String) : JsonValue
    data class NumberValue(val raw: String, val doubleValue: Double) : JsonValue {
        fun toDoubleOrNull(): Double? = doubleValue.takeIf { it.isFinite() }
        fun toLongExactOrNull(): Long? {
            raw.toLongOrNull()?.let { return it }
            if (!doubleValue.isFinite()) return null
            val asLong = doubleValue.toLong()
            return if (asLong.toDouble() == doubleValue) asLong else null
        }
    }
    data class BooleanValue(val value: Boolean) : JsonValue
    data object NullValue : JsonValue
}

/**
 * Strict, bounded RFC-8259 JSON parser and deterministic serializer.
 */
object SafeJsonCodec {
    const val DEFAULT_MAX_CHARS: Int = 1_048_576
    const val DEFAULT_MAX_DEPTH: Int = 24

    fun parse(
        input: String,
        sourceName: String = "json",
        maxChars: Int = DEFAULT_MAX_CHARS,
        maxDepth: Int = DEFAULT_MAX_DEPTH
    ): EngineResult<JsonValue> {
        if (input.length > maxChars) {
            return EngineResult.Failure(
                EngineError.InvalidResponse(
                    providerId = sourceName,
                    fieldOrStage = "payload_size",
                    message = "JSON payload length (${input.length}) exceeds maximum allowed ($maxChars)."
                )
            )
        }
        return try {
            val parser = Parser(input, maxDepth)
            val root = parser.parseRoot()
            EngineResult.Success(root)
        } catch (ex: JsonSyntaxException) {
            EngineResult.Failure(
                EngineError.InvalidResponse(
                    providerId = sourceName,
                    fieldOrStage = "json_parse@${ex.offset}",
                    message = ex.message ?: "Malformed JSON payload."
                )
            )
        }
    }

    fun parseObject(
        input: String,
        sourceName: String = "json",
        maxChars: Int = DEFAULT_MAX_CHARS,
        maxDepth: Int = DEFAULT_MAX_DEPTH
    ): EngineResult<JsonValue.ObjectValue> {
        return when (val parsed = parse(input, sourceName, maxChars, maxDepth)) {
            is EngineResult.Failure -> parsed
            is EngineResult.Success -> when (val value = parsed.value) {
                is JsonValue.ObjectValue -> EngineResult.Success(value)
                else -> EngineResult.Failure(
                    EngineError.InvalidResponse(
                        providerId = sourceName,
                        fieldOrStage = "root",
                        message = "Expected JSON object at root but found ${value::class.simpleName}."
                    )
                )
            }
        }
    }

    fun stringify(value: JsonValue): String {
        val sb = StringBuilder()
        writeValue(value, sb)
        return sb.toString()
    }

    private fun writeValue(value: JsonValue, sb: StringBuilder) {
        when (value) {
            is JsonValue.NullValue -> sb.append("null")
            is JsonValue.BooleanValue -> sb.append(if (value.value) "true" else "false")
            is JsonValue.NumberValue -> {
                require(value.doubleValue.isFinite()) { "Non-finite JSON numbers are prohibited." }
                sb.append(value.raw)
            }
            is JsonValue.StringValue -> writeString(value.value, sb)
            is JsonValue.ArrayValue -> {
                sb.append('[')
                value.items.forEachIndexed { idx, item ->
                    if (idx > 0) sb.append(',')
                    writeValue(item, sb)
                }
                sb.append(']')
            }
            is JsonValue.ObjectValue -> {
                sb.append('{')
                var first = true
                for ((k, v) in value.entries) {
                    if (!first) sb.append(',')
                    first = false
                    writeString(k, sb)
                    sb.append(':')
                    writeValue(v, sb)
                }
                sb.append('}')
            }
        }
    }

    private fun writeString(str: String, sb: StringBuilder) {
        sb.append('"')
        var i = 0
        while (i < str.length) {
            val ch = str[i]
            when {
                ch == '"' -> sb.append("\\\"")
                ch == '\\' -> sb.append("\\\\")
                ch == '\b' -> sb.append("\\b")
                ch == '\u000C' -> sb.append("\\f")
                ch == '\n' -> sb.append("\\n")
                ch == '\r' -> sb.append("\\r")
                ch == '\t' -> sb.append("\\t")
                ch.code < 0x20 -> {
                    sb.append("\\u")
                    sb.append(ch.code.toString(16).padStart(4, '0'))
                }
                ch.code in 0xD800..0xDBFF -> {
                    val low = str.getOrNull(i + 1)
                    require(low != null && low.code in 0xDC00..0xDFFF) {
                        "Cannot encode unpaired high surrogate at index $i as JSON."
                    }
                    sb.append(ch).append(low)
                    i++
                }
                ch.code in 0xDC00..0xDFFF ->
                    throw IllegalArgumentException("Cannot encode unpaired low surrogate at index $i as JSON.")
                else -> sb.append(ch)
            }
            i++
        }
        sb.append('"')
    }

    fun number(value: Double): JsonValue.NumberValue {
        require(value.isFinite()) { "Cannot encode non-finite Double '$value' as JSON number." }
        val asLong = value.toLong()
        val raw = if (asLong.toDouble() == value && !value.toString().contains('E', ignoreCase = true)) {
            asLong.toString()
        } else {
            value.toString()
        }
        return JsonValue.NumberValue(raw, value)
    }

    fun number(value: Long): JsonValue.NumberValue =
        JsonValue.NumberValue(value.toString(), value.toDouble())

    fun number(value: Int): JsonValue.NumberValue =
        JsonValue.NumberValue(value.toString(), value.toDouble())

    private class JsonSyntaxException(message: String, val offset: Int) : RuntimeException(message)

    private class Parser(
        private val text: String,
        private val maxDepth: Int
    ) {
        private var pos: Int = 0

        fun parseRoot(): JsonValue {
            skipWhitespace()
            if (pos >= text.length) {
                throw JsonSyntaxException("Empty JSON input.", pos)
            }
            val value = parseValue(depth = 0)
            skipWhitespace()
            if (pos != text.length) {
                throw JsonSyntaxException("Unexpected trailing character '${text[pos]}'.", pos)
            }
            return value
        }

        private fun parseValue(depth: Int): JsonValue {
            if (depth > maxDepth) {
                throw JsonSyntaxException("JSON nesting exceeds maximum depth ($maxDepth).", pos)
            }
            skipWhitespace()
            if (pos >= text.length) {
                throw JsonSyntaxException("Unexpected end of JSON input.", pos)
            }
            return when (val ch = text[pos]) {
                '{' -> parseObject(depth + 1)
                '[' -> parseArray(depth + 1)
                '"' -> JsonValue.StringValue(parseString())
                't' -> {
                    expectLiteral("true")
                    JsonValue.BooleanValue(true)
                }
                'f' -> {
                    expectLiteral("false")
                    JsonValue.BooleanValue(false)
                }
                'n' -> {
                    expectLiteral("null")
                    JsonValue.NullValue
                }
                '-', in '0'..'9' -> parseNumber()
                else -> throw JsonSyntaxException("Unexpected character '$ch'.", pos)
            }
        }

        private fun parseObject(depth: Int): JsonValue.ObjectValue {
            pos++ // consume '{'
            skipWhitespace()
            if (pos < text.length && text[pos] == '}') {
                pos++
                return JsonValue.ObjectValue(emptyMap())
            }
            val map = LinkedHashMap<String, JsonValue>()
            while (true) {
                skipWhitespace()
                if (pos >= text.length || text[pos] != '"') {
                    throw JsonSyntaxException("Expected quoted object key.", pos)
                }
                val key = parseString()
                if (map.containsKey(key)) {
                    throw JsonSyntaxException("Duplicate JSON object key '$key'.", pos)
                }
                skipWhitespace()
                if (pos >= text.length || text[pos] != ':') {
                    throw JsonSyntaxException("Expected ':' after object key '$key'.", pos)
                }
                pos++ // consume ':'
                val value = parseValue(depth)
                map[key] = value
                skipWhitespace()
                if (pos >= text.length) {
                    throw JsonSyntaxException("Unterminated JSON object.", pos)
                }
                when (text[pos]) {
                    ',' -> pos++
                    '}' -> {
                        pos++
                        return JsonValue.ObjectValue(map)
                    }
                    else -> throw JsonSyntaxException("Expected ',' or '}' in JSON object.", pos)
                }
            }
        }

        private fun parseArray(depth: Int): JsonValue.ArrayValue {
            pos++ // consume '['
            skipWhitespace()
            if (pos < text.length && text[pos] == ']') {
                pos++
                return JsonValue.ArrayValue(emptyList())
            }
            val items = ArrayList<JsonValue>()
            while (true) {
                items.add(parseValue(depth))
                skipWhitespace()
                if (pos >= text.length) {
                    throw JsonSyntaxException("Unterminated JSON array.", pos)
                }
                when (text[pos]) {
                    ',' -> pos++
                    ']' -> {
                        pos++
                        return JsonValue.ArrayValue(items)
                    }
                    else -> throw JsonSyntaxException("Expected ',' or ']' in JSON array.", pos)
                }
            }
        }

        private fun parseString(): String {
            pos++ // consume '"'
            val sb = StringBuilder()
            while (pos < text.length) {
                val ch = text[pos++]
                when {
                    ch == '"' -> return sb.toString()
                    ch == '\\' -> {
                        if (pos >= text.length) {
                            throw JsonSyntaxException("Unterminated escape sequence.", pos)
                        }
                        when (val esc = text[pos++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (pos + 4 > text.length) {
                                    throw JsonSyntaxException("Incomplete unicode escape.", pos)
                                }
                                val hex = text.substring(pos, pos + 4)
                                val code = hex.toIntOrNull(16)
                                    ?: throw JsonSyntaxException("Invalid unicode escape '\\u$hex'.", pos)
                                pos += 4
                                when {
                                    code in 0xD800..0xDBFF -> {
                                        // A high surrogate is only valid when immediately followed by
                                        // an escaped low surrogate (RFC 8259 §7).
                                        if (pos + 6 > text.length || text[pos] != '\\' || text[pos + 1] != 'u') {
                                            throw JsonSyntaxException(
                                                "High surrogate '\\u$hex' must be followed by a low surrogate escape.",
                                                pos
                                            )
                                        }
                                        val lowHex = text.substring(pos + 2, pos + 6)
                                        val low = lowHex.toIntOrNull(16)
                                            ?: throw JsonSyntaxException("Invalid unicode escape '\\u$lowHex'.", pos + 2)
                                        if (low !in 0xDC00..0xDFFF) {
                                            throw JsonSyntaxException(
                                                "High surrogate '\\u$hex' is not followed by a low surrogate.",
                                                pos + 2
                                            )
                                        }
                                        pos += 6
                                        sb.append(code.toChar()).append(low.toChar())
                                    }
                                    code in 0xDC00..0xDFFF ->
                                        throw JsonSyntaxException("Unpaired low surrogate '\\u$hex'.", pos - 4)
                                    else -> sb.append(code.toChar())
                                }
                            }
                            else -> throw JsonSyntaxException("Invalid escape character '\\$esc'.", pos - 1)
                        }
                    }
                    ch.code < 0x20 -> throw JsonSyntaxException("Unescaped control character in JSON string.", pos - 1)
                    ch.code in 0xD800..0xDBFF -> {
                        if (pos >= text.length || text[pos].code !in 0xDC00..0xDFFF) {
                            throw JsonSyntaxException("Unpaired high surrogate in JSON string.", pos - 1)
                        }
                        sb.append(ch).append(text[pos++])
                    }
                    ch.code in 0xDC00..0xDFFF ->
                        throw JsonSyntaxException("Unpaired low surrogate in JSON string.", pos - 1)
                    else -> sb.append(ch)
                }
            }
            throw JsonSyntaxException("Unterminated JSON string.", pos)
        }

        private fun parseNumber(): JsonValue.NumberValue {
            val start = pos
            if (text[pos] == '-') pos++
            if (pos >= text.length || text[pos] !in '0'..'9') {
                throw JsonSyntaxException("Invalid number format.", start)
            }
            if (text[pos] == '0') {
                pos++
                if (pos < text.length && text[pos] in '0'..'9') {
                    throw JsonSyntaxException("Leading zeros are not permitted in JSON numbers.", start)
                }
            } else {
                while (pos < text.length && text[pos] in '0'..'9') pos++
            }
            if (pos < text.length && text[pos] == '.') {
                pos++
                if (pos >= text.length || text[pos] !in '0'..'9') {
                    throw JsonSyntaxException("Expected digit after decimal point.", pos)
                }
                while (pos < text.length && text[pos] in '0'..'9') pos++
            }
            if (pos < text.length && (text[pos] == 'e' || text[pos] == 'E')) {
                pos++
                if (pos < text.length && (text[pos] == '+' || text[pos] == '-')) pos++
                if (pos >= text.length || text[pos] !in '0'..'9') {
                    throw JsonSyntaxException("Expected digit in exponent.", pos)
                }
                while (pos < text.length && text[pos] in '0'..'9') pos++
            }
            val raw = text.substring(start, pos)
            val parsed = raw.toDoubleOrNull()
                ?: throw JsonSyntaxException("Unparseable number '$raw'.", start)
            if (!parsed.isFinite()) {
                throw JsonSyntaxException("Numeric value '$raw' overflows IEEE-754 finite range.", start)
            }
            return JsonValue.NumberValue(raw, parsed)
        }

        private fun expectLiteral(literal: String) {
            if (!text.startsWith(literal, pos)) {
                throw JsonSyntaxException("Expected literal '$literal'.", pos)
            }
            pos += literal.length
        }

        private fun skipWhitespace() {
            while (pos < text.length) {
                when (text[pos]) {
                    ' ', '\t', '\r', '\n' -> pos++
                    else -> return
                }
            }
        }
    }
}
