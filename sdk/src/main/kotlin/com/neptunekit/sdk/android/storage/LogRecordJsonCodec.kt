package com.neptunekit.sdk.android.storage

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.neptunekit.sdk.android.model.LogSource

internal object LogRecordJsonCodec {
    private val mapper = ObjectMapper()

    fun encodeAttributes(attributes: Map<String, String>): String =
        mapper.writeValueAsString(attributes)

    fun decodeAttributes(json: String): Map<String, String> {
        val node = mapper.readTree(json)
        val attributes = linkedMapOf<String, String>()
        node.fields().forEachRemaining { entry ->
            attributes[entry.key] = entry.value.asText()
        }
        return attributes
    }

    fun encodeSource(source: LogSource?): String? {
        if (source == null) {
            return null
        }

        return mapper.writeValueAsString(
            mapper.createObjectNode().apply {
                putNullable("sdkName", source.sdkName)
                putNullable("sdkVersion", source.sdkVersion)
                putNullable("file", source.file)
                putNullable("function", source.function)
                if (source.line == null) {
                    putNull("line")
                } else {
                    put("line", source.line)
                }
            },
        )
    }

    fun decodeSource(json: String?): LogSource? {
        if (json == null) {
            return null
        }

        val node = mapper.readTree(json)
        return LogSource(
            sdkName = node.readNullableText("sdkName"),
            sdkVersion = node.readNullableText("sdkVersion"),
            file = node.readNullableText("file"),
            function = node.readNullableText("function"),
            line = node.readNullableInt("line"),
        )
    }
}

private fun com.fasterxml.jackson.databind.node.ObjectNode.putNullable(fieldName: String, value: String?) {
    if (value == null) {
        putNull(fieldName)
    } else {
        put(fieldName, value)
    }
}

private fun JsonNode.readNullableText(fieldName: String): String? {
    val field = get(fieldName) ?: return null
    return if (field.isNull) null else field.asText()
}

private fun JsonNode.readNullableInt(fieldName: String): Int? {
    val field = get(fieldName) ?: return null
    return if (field.isNull) null else field.asInt()
}
