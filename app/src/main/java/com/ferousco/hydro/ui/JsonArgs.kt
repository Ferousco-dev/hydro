package com.ferousco.hydro.ui

import com.ferousco.hydro.core.json.JsonValue
import com.ferousco.hydro.core.json.SafeJsonCodec

/** Builds the JSON argument string a tool call expects from simple Kotlin values. */
internal fun jsonArgs(vararg pairs: Pair<String, Any>): String = SafeJsonCodec.stringify(
    JsonValue.ObjectValue(
        LinkedHashMap<String, JsonValue>().apply {
            pairs.forEach { (key, value) ->
                put(
                    key,
                    when (value) {
                        is Double -> JsonValue.NumberValue(value.toString(), value)
                        is Int -> JsonValue.NumberValue(value.toString(), value.toDouble())
                        else -> JsonValue.StringValue(value.toString())
                    }
                )
            }
        }
    )
)
