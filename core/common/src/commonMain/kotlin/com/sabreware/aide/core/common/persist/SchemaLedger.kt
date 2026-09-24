package com.sabreware.aide.core.common.persist

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.descriptors.elementDescriptors
import kotlinx.serialization.descriptors.elementNames
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import okio.FileSystem
import okio.Path

/**
 * The version-controlled record of every [PersistedDocument]'s shape — the document counterpart of Room's
 * exported `schemas/<Database>/N.json`.
 *
 * `schemas/documents/<name>/<version>.json` holds [render] of the document's descriptor at that version.
 * The owning module's `PersistedDocumentSchemaTest` calls [verify]: a missing file is written (commit it), a
 * mismatch fails with "bump the version". `./gradlew schemaCheck` then refuses any commit that REWRITES an
 * existing file, so a shape can only ever move forward to a new number.
 */
object SchemaLedger {

    /**
     * A deterministic JSON rendering of [descriptor]: every element's name, kind, nullability and whether it
     * has a default, recursively. Renaming a field, changing a type, making something nullable or dropping a
     * default all change the output; reordering nothing does.
     */
    fun render(descriptor: SerialDescriptor): String =
        DocumentJson.encodeToString(JsonElement.serializer(), node(descriptor, mutableSetOf()))

    /** Where [document]'s ledger entry lives under a module's `schemas/` directory. */
    fun entryPath(schemasDir: Path, document: PersistedDocument<*>): Path =
        schemasDir / "documents" / document.name / "${document.version}.json"

    /**
     * Checks every document against its ledger entry and returns one message per problem (empty = clean).
     * Writes the entry for a version that has none — that is the step that records a new version.
     */
    fun verify(fs: FileSystem, schemasDir: Path, documents: List<PersistedDocument<*>>): List<String> {
        val problems = mutableListOf<String>()
        val names = documents.groupBy { it.name }.filterValues { it.size > 1 }.keys
        names.forEach { problems += "two documents share the name \"$it\"" }
        for (document in documents) {
            val entry = entryPath(schemasDir, document)
            val rendered = render(document.serializer.descriptor) + "\n"
            val committed = if (fs.exists(entry)) fs.read(entry) { readUtf8() } else null
            when {
                committed == null -> {
                    entry.parent?.let(fs::createDirectories)
                    fs.write(entry) { writeUtf8(rendered) }
                    problems += "$document: new ledger entry written to $entry — commit it"
                }
                committed != rendered -> problems +=
                    "$document: the class no longer matches $entry. Bump `version` to " +
                    "${document.version + 1} and re-run this test to write the new entry; never edit a " +
                    "committed one (schemaCheck refuses it)."
            }
        }
        return problems
    }

    @OptIn(ExperimentalSerializationApi::class)
    private fun node(d: SerialDescriptor, path: MutableSet<String>): JsonObject = buildJsonObject {
        put("type", JsonPrimitive(d.serialName.removeSuffix("?")))
        put("kind", JsonPrimitive(d.kind.toString()))
        if (d.isNullable) put("nullable", JsonPrimitive(true))
        // A self-referencing class renders as a reference the second time it is met on the current path.
        if (!path.add(d.serialName)) return@buildJsonObject
        when (d.kind) {
            // Enum constants are part of the shape: renaming one orphans the value stored under the old name.
            SerialKind.ENUM ->
                put("values", JsonArray(d.elementNames.map(::JsonPrimitive)))
            StructureKind.LIST, StructureKind.MAP ->
                put("of", JsonArray(d.elementDescriptors.map { node(it, path) }))
            else -> if (d.elementsCount > 0) {
                put(
                    "fields",
                    JsonArray(
                        (0 until d.elementsCount).map { i ->
                            buildJsonObject {
                                put("name", JsonPrimitive(d.getElementName(i)))
                                if (d.isElementOptional(i)) put("default", JsonPrimitive(true))
                                put("schema", node(d.getElementDescriptor(i), path))
                            }
                        },
                    ),
                )
            }
        }
        path.remove(d.serialName)
    }
}
