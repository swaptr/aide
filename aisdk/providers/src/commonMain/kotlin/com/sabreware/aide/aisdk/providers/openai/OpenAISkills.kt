package com.sabreware.aide.aisdk.providers.openai

import com.sabreware.aide.aisdk.InvalidArgumentError
import com.sabreware.aide.aisdk.ProviderSkills
import com.sabreware.aide.aisdk.SkillUploadOptions
import com.sabreware.aide.aisdk.SkillUploadResult
import com.sabreware.aide.aisdk.Warning
import com.sabreware.aide.aisdk.util.MultipartPart
import com.sabreware.aide.aisdk.util.ProviderHttp
import com.sabreware.aide.aisdk.util.ProviderJson
import com.sabreware.aide.aisdk.util.combineHeaders
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * OpenAI's Skills API — `POST /v1/skills`, multipart.
 *
 * The same part shape as Anthropic's: every file is one `files[]` part whose FILENAME is its path inside
 * the skill, and the parts carry no content type, matching the reference's untyped blobs. Two things
 * differ, and both are the reason this is not the Anthropic class pointed at another URL:
 *
 * - OpenAI's create response already carries the skill's `name` and `description` — it derives them from
 *   the uploaded `SKILL.md` — so one upload is ONE request. Anthropic needs a second fetch for the same
 *   two fields.
 * - There is no `display_title` field. A caller's [SkillUploadOptions.displayTitle] is reported as
 *   unsupported rather than sent under a guessed name, which the endpoint would reject as unknown.
 */
internal class OpenAISkills(
    http: ProviderHttp,
    private val baseUrl: String,
    private val headers: Map<String, String>,
) : ProviderSkills {

    override val provider: String = OPENAI_PROVIDER_ID

    private val http = http.withErrorStructure(OpenAIErrorStructure)

    override suspend fun uploadSkill(options: SkillUploadOptions): SkillUploadResult {
        val warnings = buildList {
            if (options.displayTitle != null) add(Warning.Unsupported(feature = "displayTitle"))
        }

        val parts = options.files.map { file ->
            val bytes = file.data.inlineBytesOrNull() ?: throw InvalidArgumentError(
                message = "a skill file carries its content (Bytes or Text); " +
                    "'${file.path}' is ${file.data::class.simpleName}",
                argument = "files",
            )
            MultipartPart.File(field = "files[]", fileName = file.path, bytes = bytes)
        }

        val created = http.postMultipartParts("$baseUrl/skills", parts, combineHeaders(headers))
        val skill = ProviderJson.decodeFromJsonElement(OpenAISkillResponse.serializer(), created.value)

        return SkillUploadResult(
            providerReference = mapOf(OPENAI_PROVIDER_ID to skill.id),
            name = skill.name,
            description = skill.description,
            latestVersion = skill.latestVersion,
            providerMetadata = mapOf(
                OPENAI_PROVIDER_ID to buildJsonObject {
                    skill.defaultVersion?.let { put("defaultVersion", it) }
                    skill.createdAt?.let { put("createdAt", it) }
                    skill.updatedAt?.let { put("updatedAt", it) }
                },
            ),
            warnings = warnings,
        )
    }
}

/**
 * `POST /v1/skills` response. Ref: `skills/openai-skills-api.ts`.
 *
 * `created_at` is required there; nullable here so a response that omits it costs a metadata field
 * rather than the whole upload — the id is what the caller needs, and it has already been minted.
 */
@Serializable
internal data class OpenAISkillResponse(
    val id: String,
    val name: String? = null,
    val description: String? = null,
    @SerialName("default_version") val defaultVersion: String? = null,
    @SerialName("latest_version") val latestVersion: String? = null,
    @SerialName("created_at") val createdAt: Long? = null,
    @SerialName("updated_at") val updatedAt: Long? = null,
)
