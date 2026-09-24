package com.sabreware.aide.aisdk.providers.deepseek

/**
 * Wire bodies from `deepseek-files.test.ts`, transcribed value for value.
 *
 * The reference builds its responses with `prepareFileResponse({ id, expiresAt, body })`; the same
 * knobs are reproduced so each test below names the case the reference names.
 */
internal object DeepSeekFilesFixtures {

    /** `prepareFileResponse()` with the defaults. */
    fun uploaded(id: String = "file-api-abc123", expiresAt: Long? = null): String =
        """{"id":"$id","object":"file","bytes":1024,"created_at":1700000000,""" +
            """"filename":"comic-cat.png","purpose":"user_data","expires_at":${expiresAt ?: "null"}}"""

    /** `createFilesWithMockFetch()`'s response — the one every acceptance test answers with. */
    const val UPLOADED_IMAGE: String =
        """{"id":"file-api-abc123","object":"file","bytes":1024,"created_at":1700000000,""" +
            """"filename":"image.png","purpose":"user_data","expires_at":null}"""

    /** `should tolerate omitted optional response metadata`. */
    const val UPLOADED_OMITTED: String = """{"id":"file-api-incomplete"}"""

    /** `should tolerate null optional response metadata`. */
    const val UPLOADED_NULLS: String = """{"id":"file-api-incomplete","object":null,"bytes":null,""" +
        """"created_at":null,"filename":null,"purpose":null,"expires_at":null}"""

    /** `should reject a response without a file id`. */
    const val UPLOADED_NO_ID: String = """{"object":"file","bytes":1024,"created_at":1700000000,""" +
        """"filename":"comic-cat.png","purpose":"user_data"}"""

    /** `should reject an invalid %s response field`: the valid document with ONE field replaced. */
    fun uploadedWith(field: String, invalidValue: String): String {
        val valid = linkedMapOf(
            "id" to "\"file-api-invalid\"",
            "object" to "\"file\"",
            "bytes" to "1024",
            "created_at" to "1700000000",
            "filename" to "\"comic-cat.png\"",
            "purpose" to "\"user_data\"",
        )
        valid[field] = invalidValue
        return valid.entries.joinToString(",", prefix = "{", postfix = "}") { (key, value) -> "\"$key\":$value" }
    }
}
