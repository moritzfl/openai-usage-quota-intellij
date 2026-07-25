package de.moritzf.quota.shared

import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull

internal object JsonSupport {
    val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
    }

    /**
     * Decodes one payload section leniently: a missing, null, or unparsable section yields null
     * instead of failing the whole payload, so the remaining quota sections stay usable.
     */
    fun <T> decodeSectionOrNull(element: JsonElement?, deserializer: DeserializationStrategy<T>): T? {
        val present = element?.takeUnless { it is JsonNull } ?: return null
        return runCatching { json.decodeFromJsonElement(deserializer, present) }.getOrNull()
    }

    /**
     * Decodes array items individually: unparsable items are dropped and anything that is not an
     * array yields an empty list, so one broken entry cannot break its siblings.
     */
    fun <T> decodeListItemsLeniently(element: JsonElement?, deserializer: DeserializationStrategy<T>): List<T> {
        val array = element as? JsonArray ?: return emptyList()
        return array.mapNotNull { item ->
            runCatching { json.decodeFromJsonElement(deserializer, item) }.getOrNull()
        }
    }
}
