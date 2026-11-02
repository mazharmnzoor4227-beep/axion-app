package com.mazharansari.axion.agent

import kotlinx.serialization.json.*

/** Tiny JSON-schema builder for tool parameters. */
class SchemaBuilder {
    private val props = mutableMapOf<String, JsonElement>()
    private val req = mutableListOf<String>()
    fun str(name: String, desc: String, required: Boolean = true) { props[name] = buildJsonObject { put("type", "string"); put("description", desc) }; if (required) req += name }
    fun int(name: String, desc: String, required: Boolean = false) { props[name] = buildJsonObject { put("type", "integer"); put("description", desc) }; if (required) req += name }
    fun bool(name: String, desc: String, required: Boolean = false) { props[name] = buildJsonObject { put("type", "boolean"); put("description", desc) }; if (required) req += name }
    fun build(): JsonObject = buildJsonObject {
        put("type", "object")
        put("properties", JsonObject(props))
        if (req.isNotEmpty()) putJsonArray("required") { req.forEach { add(it) } }
    }
}

fun schema(block: SchemaBuilder.() -> Unit): JsonObject = SchemaBuilder().apply(block).build()

fun JsonObject.s(k: String): String? = (this[k] as? JsonPrimitive)?.contentOrNull
fun JsonObject.i(k: String): Int? = (this[k] as? JsonPrimitive)?.let { it.intOrNull ?: it.contentOrNull?.toIntOrNull() }
fun JsonObject.b(k: String): Boolean? = (this[k] as? JsonPrimitive)?.let { it.booleanOrNull ?: it.contentOrNull?.toBooleanStrictOrNull() }
