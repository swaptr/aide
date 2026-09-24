package com.sabreware.aide.aisdk.providers.anthropic

import com.sabreware.aide.aisdk.FileData
import com.sabreware.aide.aisdk.InvalidArgumentError
import com.sabreware.aide.aisdk.ProviderSkills
import com.sabreware.aide.aisdk.SkillUploadOptions
import com.sabreware.aide.aisdk.SkillUploadResult
import com.sabreware.aide.aisdk.util.MultipartPart
import com.sabreware.aide.aisdk.util.ProviderHttp
import com.sabreware.aide.aisdk.util.ProviderJson
import com.sabreware.aide.aisdk.util.combineHeaders
import io.ktor.http.encodeURLPathPart
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Anthropic's Skills API — `POST /v1/skills`, multipart, behind the `skills-2025-10-02` beta.
 *
 * Two wire details are the reason to read this file rather than guess:
 *
 * - Every file is one `files[]` part whose FILENAME is its path inside the skill — that repeated,
 *   differently-named field is what forced [ProviderHttp.postMultipartParts] to exist — and the parts
 *   carry no content type, matching the reference's untyped blobs.
 * - The upload response may omit the skill's `name` and `description`; the reference fetches them from
 *   the minted version (`GET /v1/skills/{id}/versions/{version}`), so one upload is two requests.
 */
internal class AnthropicSkills(
    private val http: ProviderHttp,
    private val baseUrl: String,
    private val headers: Map<String, String>,
) : ProviderSkills {

    override val provider: String = ANTHROPIC_PROVIDER_ID

    override suspend fun uploadSkill(options: SkillUploadOptions): SkillUploadResult {
        val parts = buildList {
            options.displayTitle?.let { add(MultipartPart.Field("display_title", it)) }
            options.files.forEach { file ->
                val bytes = when (val data = file.data) {
                    is FileData.Bytes -> data.bytes
                    is FileData.Text -> data.text.encodeToByteArray()
                    is FileData.Url, is FileData.Reference -> throw InvalidArgumentError(
                        message = "a skill file carries its content (Bytes or Text); " +
                            "'${file.path}' is ${data::class.simpleName}",
                        argument = "files",
                    )
                }
                add(MultipartPart.File(field = "files[]", fileName = file.path, bytes = bytes))
            }
        }

        val requestHeaders = combineHeaders(headers, mapOf("anthropic-beta" to SKILLS_BETA))
        val created = http.postMultipartParts("$baseUrl/skills", parts, requestHeaders)
        val skill =
            ProviderJson.decodeFromJsonElement(AnthropicSkillResponse.serializer(), created.value)

        // The upload response has no name/description of its own; the minted version does.
        val version = skill.latestVersion?.let { latest ->
            val url = "$baseUrl/skills/${latest.pathSegment(skill.id)}"
            ProviderJson.decodeFromJsonElement(
                AnthropicSkillVersionResponse.serializer(),
                http.getJson(url, requestHeaders).value,
            )
        }

        return SkillUploadResult(
            providerReference = mapOf(ANTHROPIC_PROVIDER_ID to skill.id),
            displayTitle = skill.displayTitle,
            name = version?.name ?: skill.name,
            description = version?.description ?: skill.description,
            latestVersion = skill.latestVersion,
            providerMetadata = mapOf(
                ANTHROPIC_PROVIDER_ID to buildJsonObject {
                    skill.source?.let { put("source", it) }
                    skill.createdAt?.let { put("createdAt", it) }
                    skill.updatedAt?.let { put("updatedAt", it) }
                },
            ),
        )
    }

    /** `{skillId}/versions/{version}`, each segment encoded — an id is data, not URL structure. */
    private fun String.pathSegment(skillId: String): String =
        "${skillId.encodeSkillSegment()}/versions/${encodeSkillSegment()}"

    private companion object {
        const val SKILLS_BETA = "skills-2025-10-02"

        /**
         * Percent-encodes a path segment, DOUBLE-encoding a bare `.` or `..` — URL parsing normalizes
         * both the literal and the single-encoded form, so a hostile id could otherwise walk the path.
         * Ref: `anthropic-skills.ts` `encodePathSegment`.
         */
        fun String.encodeSkillSegment(): String = when (val encoded = encodeURLPathPart()) {
            "." -> "%252E"
            ".." -> "%252E%252E"
            else -> encoded
        }
    }
}

/** `POST /v1/skills` response. */
@Serializable
internal data class AnthropicSkillResponse(
    val id: String,
    @SerialName("display_title") val displayTitle: String? = null,
    val name: String? = null,
    val description: String? = null,
    @SerialName("latest_version") val latestVersion: String? = null,
    val source: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
)

/** `GET /v1/skills/{id}/versions/{version}` response — the half that knows name and description. */
@Serializable
internal data class AnthropicSkillVersionResponse(
    val name: String? = null,
    val description: String? = null,
)
