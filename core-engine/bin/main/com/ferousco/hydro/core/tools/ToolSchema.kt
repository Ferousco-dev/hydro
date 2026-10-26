package com.ferousco.hydro.core.tools

import com.ferousco.hydro.core.error.EngineError
import com.ferousco.hydro.core.error.EngineResult
import com.ferousco.hydro.core.json.JsonValue
import com.ferousco.hydro.core.json.SafeJsonCodec
import java.time.Duration

enum class ToolCapability {
    /** Read current observations, forecasts, and historical weather from a weather provider. */
    WEATHER_PROVIDER_READ,

    /** Run Hydro's deterministic calculations (heat index, UV, planning, hydration). */
    DETERMINISTIC_ANALYSIS,

    /** Read Hydro's local environmental memory on the device. */
    LOCAL_HISTORY_READ,

    /** Read the device's current location fix. Never granted implicitly. */
    LOCATION_READ,

    /** Read route geometry/duration from a routing provider. */
    ROUTING_READ,

    /** Read geospatial heat-map layers. */
    HEAT_MAP_READ,

    /** Read supported wearable/Health Connect measurements. */
    WEARABLE_READ,

    /** Submit an alert to the Android notification layer. */
    NOTIFICATION_WRITE,

    /** Read third-party news, air-quality and satellite-climate data over the network. */
    EXTERNAL_DATA_READ
}

/**
 * Policy hook evaluated by the host application before executing any AI-requested tool.
 */
fun interface ToolPermissionGate {
    fun isCapabilityPermitted(capability: ToolCapability): Boolean
}

object AllowAllToolPermissionGate : ToolPermissionGate {
    override fun isCapabilityPermitted(capability: ToolCapability): Boolean = true
}

class SetBasedToolPermissionGate(
    private val permitted: Set<ToolCapability>
) : ToolPermissionGate {
    override fun isCapabilityPermitted(capability: ToolCapability): Boolean = capability in permitted
}

enum class ToolSchemaType(val jsonSchemaTypeName: String) {
    NUMBER("number"),
    INTEGER("integer"),
    STRING("string"),
    BOOLEAN("boolean"),
    ENUM("string"),
    ARRAY("array"),
    OBJECT("object")
}

data class ToolPropertySchema(
    val name: String,
    val type: ToolSchemaType,
    val description: String,
    val required: Boolean = true,
    val minimum: Double? = null,
    val maximum: Double? = null,
    val maxLength: Int? = null,
    val enumValues: List<String>? = null,
    val arrayItemType: ToolSchemaType? = null,
    val maxItems: Int? = null
) {
    init {
        require(name.matches(IDENTIFIER_REGEX)) { "Invalid property schema name '$name'." }
        if (type == ToolSchemaType.ENUM) {
            require(!enumValues.isNullOrEmpty()) { "ENUM property '$name' must define enumValues." }
        }
    }

    fun toJsonSchemaObject(): JsonValue.ObjectValue = JsonValue.ObjectValue(
        linkedMapOf<String, JsonValue>().apply {
            put("type", JsonValue.StringValue(type.jsonSchemaTypeName))
            put("description", JsonValue.StringValue(description))
            minimum?.let { put("minimum", SafeJsonCodec.number(it)) }
            maximum?.let { put("maximum", SafeJsonCodec.number(it)) }
            maxLength?.let { put("maxLength", SafeJsonCodec.number(it)) }
            enumValues?.let { vals ->
                put("enum", JsonValue.ArrayValue(vals.map(JsonValue::StringValue)))
            }
            arrayItemType?.let { itemType ->
                put(
                    "items",
                    JsonValue.ObjectValue(
                        linkedMapOf("type" to JsonValue.StringValue(itemType.jsonSchemaTypeName))
                    )
                )
            }
            maxItems?.let { put("maxItems", SafeJsonCodec.number(it)) }
        }
    )

    companion object {
        private val IDENTIFIER_REGEX = Regex("^[a-z][a-z0-9_]{0,63}$")
    }
}

data class ToolObjectSchema(
    val properties: List<ToolPropertySchema>,
    val allowAdditionalProperties: Boolean = false
) {
    private val byName = properties.associateBy { it.name }

    fun toJsonSchemaObject(): JsonValue.ObjectValue {
        val propsMap = LinkedHashMap<String, JsonValue>()
        for (prop in properties) {
            propsMap[prop.name] = prop.toJsonSchemaObject()
        }
        val requiredList = properties.filter { it.required }.map { JsonValue.StringValue(it.name) }
        return JsonValue.ObjectValue(
            linkedMapOf(
                "type" to JsonValue.StringValue("object"),
                "properties" to JsonValue.ObjectValue(propsMap),
                "required" to JsonValue.ArrayValue(requiredList),
                "additionalProperties" to JsonValue.BooleanValue(allowAdditionalProperties)
            )
        )
    }

    fun validateStructural(rawArgs: JsonValue.ObjectValue): EngineResult<Unit> {
        if (!allowAdditionalProperties) {
            val unknownKeys = rawArgs.entries.keys.filter { it !in byName }
            if (unknownKeys.isNotEmpty()) {
                return EngineResult.Failure(
                    EngineError.InvalidInput(
                        field = unknownKeys.first(),
                        rejectedValueSummary = "unexpected_property",
                        message = "Unsupported tool parameter(s): ${unknownKeys.joinToString(", ")}."
                    )
                )
            }
        }

        for (prop in properties) {
            val value = rawArgs[prop.name]
            if (value == null || value is JsonValue.NullValue) {
                if (prop.required) {
                    return EngineResult.Failure(
                        EngineError.MissingRequiredInputs(
                            operation = "tool_argument_validation",
                            missingFields = listOf(prop.name),
                            message = "Missing required tool parameter '${prop.name}'."
                        )
                    )
                }
                continue
            }

            val err = validatePropertyValue(prop, value)
            if (err != null) return EngineResult.Failure(err)
        }
        return EngineResult.Success(Unit)
    }

    private fun validatePropertyValue(prop: ToolPropertySchema, value: JsonValue): EngineError? {
        return when (prop.type) {
            ToolSchemaType.NUMBER -> {
                val num = (value as? JsonValue.NumberValue)?.toDoubleOrNull()
                    ?: return EngineError.InvalidInput(prop.name, value::class.simpleName ?: "invalid", "Expected finite number for '${prop.name}'.")
                checkRange(prop, num)
            }
            ToolSchemaType.INTEGER -> {
                val num = (value as? JsonValue.NumberValue)?.toLongExactOrNull()
                    ?: return EngineError.InvalidInput(prop.name, value::class.simpleName ?: "invalid", "Expected exact integer for '${prop.name}'.")
                checkRange(prop, num.toDouble())
            }
            ToolSchemaType.STRING -> {
                val str = (value as? JsonValue.StringValue)?.value
                    ?: return EngineError.InvalidInput(prop.name, value::class.simpleName ?: "invalid", "Expected string for '${prop.name}'.")
                if (prop.maxLength != null && str.length > prop.maxLength) {
                    return EngineError.InvalidInput(prop.name, "length=${str.length}", "Parameter '${prop.name}' exceeds maxLength=${prop.maxLength}.")
                }
                null
            }
            ToolSchemaType.ENUM -> {
                val str = (value as? JsonValue.StringValue)?.value
                    ?: return EngineError.InvalidInput(prop.name, value::class.simpleName ?: "invalid", "Expected enum string for '${prop.name}'.")
                val allowed = prop.enumValues.orEmpty()
                if (str !in allowed) {
                    return EngineError.InvalidInput(prop.name, str, "Parameter '${prop.name}' must be one of ${allowed.joinToString(", ")}.")
                }
                null
            }
            ToolSchemaType.BOOLEAN -> {
                if (value !is JsonValue.BooleanValue) {
                    EngineError.InvalidInput(prop.name, value::class.simpleName ?: "invalid", "Expected boolean for '${prop.name}'.")
                } else {
                    null
                }
            }
            ToolSchemaType.ARRAY -> {
                val arr = (value as? JsonValue.ArrayValue)?.items
                    ?: return EngineError.InvalidInput(prop.name, value::class.simpleName ?: "invalid", "Expected array for '${prop.name}'.")
                if (prop.maxItems != null && arr.size > prop.maxItems) {
                    return EngineError.InvalidInput(prop.name, "size=${arr.size}", "Array '${prop.name}' exceeds maxItems=${prop.maxItems}.")
                }
                val itemType = prop.arrayItemType
                if (itemType != null) {
                    for (item in arr) {
                        val validItem = when (itemType) {
                            ToolSchemaType.STRING, ToolSchemaType.ENUM -> item is JsonValue.StringValue
                            ToolSchemaType.NUMBER -> item is JsonValue.NumberValue
                            ToolSchemaType.INTEGER -> (item as? JsonValue.NumberValue)?.toLongExactOrNull() != null
                            ToolSchemaType.BOOLEAN -> item is JsonValue.BooleanValue
                            ToolSchemaType.OBJECT -> item is JsonValue.ObjectValue
                            ToolSchemaType.ARRAY -> item is JsonValue.ArrayValue
                        }
                        if (!validItem) {
                            return EngineError.InvalidInput(prop.name, "invalid_array_item", "Invalid item type in array '${prop.name}'.")
                        }
                    }
                }
                null
            }
            ToolSchemaType.OBJECT -> {
                if (value !is JsonValue.ObjectValue) {
                    EngineError.InvalidInput(prop.name, value::class.simpleName ?: "invalid", "Expected object for '${prop.name}'.")
                } else {
                    null
                }
            }
        }
    }

    private fun checkRange(prop: ToolPropertySchema, numericValue: Double): EngineError? {
        if (prop.minimum != null && numericValue < prop.minimum) {
            return EngineError.InvalidInput(
                field = prop.name,
                rejectedValueSummary = "below_minimum",
                message = "Parameter '${prop.name}' must be >= ${prop.minimum}."
            )
        }
        if (prop.maximum != null && numericValue > prop.maximum) {
            return EngineError.InvalidInput(
                field = prop.name,
                rejectedValueSummary = "above_maximum",
                message = "Parameter '${prop.name}' must be <= ${prop.maximum}."
            )
        }
        return null
    }
}

enum class ToolFailureMode {
    STRUCTURED_ERROR_NON_RETRYABLE_BY_DEFAULT,
    STRUCTURED_ERROR_WITH_TRANSIENT_HINT
}

data class ToolFailureSpec(
    val executionTimeout: Duration = Duration.ofSeconds(15),
    val mode: ToolFailureMode = ToolFailureMode.STRUCTURED_ERROR_WITH_TRANSIENT_HINT,
    val documentedErrorCodes: List<String>
) {
    init {
        require(!executionTimeout.isNegative && !executionTimeout.isZero) {
            "executionTimeout must be positive."
        }
    }
}

data class ToolDescriptor(
    val name: String,
    val description: String,
    val requiredCapabilities: Set<ToolCapability>,
    val parameterSchema: ToolObjectSchema,
    val resultSchema: ToolObjectSchema,
    val failureSpec: ToolFailureSpec
) {
    init {
        require(name.matches(TOOL_NAME_REGEX)) {
            "Tool name '$name' must match snake_case identifier pattern."
        }
        require(description.isNotBlank()) { "Tool description must not be blank." }
    }

    fun toJsonDeclaration(): JsonValue.ObjectValue = JsonValue.ObjectValue(
        linkedMapOf(
            "name" to JsonValue.StringValue(name),
            "description" to JsonValue.StringValue(description),
            "parameters" to parameterSchema.toJsonSchemaObject(),
            "result_schema" to resultSchema.toJsonSchemaObject(),
            "required_capabilities" to JsonValue.ArrayValue(requiredCapabilities.map { JsonValue.StringValue(it.name) })
        )
    )

    /**
     * Compact OpenAPI function specification (`name`, `description`, `parameters`) directly
     * compatible with `com.google.ai.edge.litertlm.OpenApiTool` and tailored to conserve
     * tokens inside compact on-device context windows (e.g., Gemma 3n E2B-IT 4K window).
     */
    fun toOpenApiFunctionDeclaration(): JsonValue.ObjectValue = JsonValue.ObjectValue(
        linkedMapOf(
            "name" to JsonValue.StringValue(name),
            "description" to JsonValue.StringValue(description),
            "parameters" to parameterSchema.toJsonSchemaObject()
        )
    )

    companion object {
        val TOOL_NAME_REGEX = Regex("^[a-z][a-z0-9_]{1,63}$")
    }
}
