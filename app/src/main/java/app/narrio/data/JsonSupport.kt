package app.narrio.data

import kotlinx.serialization.json.*

val NarrioJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }
fun JsonElement?.stringValue(): String = when (this) {
    is JsonPrimitive -> contentOrNull.orEmpty()
    is JsonArray -> joinToString(", ") { it.stringValue() }
    else -> ""
}
fun JsonObject.text(key: String) = get(key).stringValue()
fun JsonObject.number(key: String) = text(key).toDoubleOrNull()?.toLong() ?: 0L
fun JsonObject.flag(key: String) = get(key)?.jsonPrimitive?.booleanOrNull == true
fun JsonObject.objects(key: String) = (get(key) as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
