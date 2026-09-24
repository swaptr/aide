package com.sabreware.aide.core.common.persist

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.okio.OkioSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import okio.BufferedSink
import okio.BufferedSource

/**
 * The one JSON dialect every [PersistedDocument] is written in.
 *
 * - `ignoreUnknownKeys` — a file written by a newer build (a downgrade, allowed in dev) still reads.
 * - `coerceInputValues` — a null or unknown-enum value where the class now wants something else takes the
 *   property's default instead of failing the whole document.
 * - `encodeDefaults` — the file states every field, so what is on disk is readable without the code.
 * - `prettyPrint` — these files are small and a person on any desktop should be able to open one.
 */
internal val DocumentJson: Json = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
    encodeDefaults = true
    prettyPrint = true
}

/**
 * The on-disk envelope: `{"version": N, "data": {…}}`. The version makes a file self-describing when it is
 * opened by hand or attached to a bug; the read deliberately does not branch on it (see [PersistedDocument]).
 */
internal class DocumentSerializer<T>(private val document: PersistedDocument<T>) : OkioSerializer<T> {

    override val defaultValue: T get() = document.default

    override suspend fun readFrom(source: BufferedSource): T {
        val text = source.readUtf8()
        return try {
            val root = DocumentJson.parseToJsonElement(text).jsonObject
            val data = root[DATA] ?: throw SerializationException("no \"$DATA\" member")
            DocumentJson.decodeFromJsonElement(document.serializer, data)
        } catch (e: SerializationException) {
            throw CorruptionException("$document: unreadable (${e.message})", e)
        } catch (e: IllegalArgumentException) {
            // `jsonObject` on a non-object root, or a decoder rejecting a value's shape.
            throw CorruptionException("$document: unreadable (${e.message})", e)
        }
    }

    override suspend fun writeTo(t: T, sink: BufferedSink) {
        val envelope = buildJsonObject {
            put(VERSION, JsonPrimitive(document.version))
            put(DATA, DocumentJson.encodeToJsonElement(document.serializer, t))
        }
        sink.writeUtf8(DocumentJson.encodeToString(JsonElement.serializer(), envelope))
    }

    companion object {
        const val VERSION = "version"
        const val DATA = "data"
    }
}
