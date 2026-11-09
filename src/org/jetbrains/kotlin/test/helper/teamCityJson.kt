package org.jetbrains.kotlin.test.helper

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonTransformingSerializer

/**
 * Shared decoder for TeamCity responses (CLI `--json` output and guest REST):
 * extra fields are ignored so that responses can evolve without breaking the plugin.
 */
internal val teamCityJson: Json = Json { ignoreUnknownKeys = true }

/**
 * Decodes [json] into [T], returning null when it is not readable,
 * so callers can keep the last known status or fall back to plain-text parsing.
 */
internal inline fun <reified T> decodeTeamCityJsonOrNull(json: String): T? =
    try {
        teamCityJson.decodeFromString<T>(json)
    } catch (_: SerializationException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

/**
 * TeamCity sends one-element collections as a plain object instead of an array;
 * a list decoded with it accepts both shapes. Anything else (e.g. a number) reads as an empty list.
 */
internal open class SingleObjectAsListSerializer<T>(elementSerializer: KSerializer<T>) :
    JsonTransformingSerializer<List<T>>(ListSerializer(elementSerializer)) {

    final override fun transformDeserialize(element: JsonElement): JsonElement = when (element) {
        is JsonArray -> element
        is JsonObject -> JsonArray(listOf(element))
        else -> JsonArray(emptyList())
    }

    final override fun transformSerialize(element: JsonElement): JsonElement = element
}
