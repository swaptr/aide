package com.sabreware.aide.aisdk

/**
 * A provider's skill store — uploads a bundle of files the vendor's agent runtime can load as a skill.
 *
 * A skill is a directory shipped whole: instructions, scripts, assets, each addressed by its path
 * relative to the skill root. The result's [SkillUploadResult.providerReference] is the id later calls
 * name — on Anthropic, inside a request's `container.skills`.
 *
 * Ref: `skills/v4` (`SkillsV4`), version prefix dropped per the porting rules.
 */
public interface ProviderSkills {

    /** Always [SPECIFICATION_VERSION]; lets a consumer reject an implementation built against an older spec. */
    public val specificationVersion: String get() = SPECIFICATION_VERSION

    /** The provider's id — the key its skill ids land under in [SkillUploadResult.providerReference]. */
    public val provider: String

    /** Uploads a new skill from its files. */
    public suspend fun uploadSkill(options: SkillUploadOptions): SkillUploadResult
}

/**
 * One file inside a skill.
 *
 * [data] accepts only the forms that ARE bytes — [FileData.Bytes] or [FileData.Text]; the same rule as
 * [FileUploadOptions.data], for the same reason.
 */
public data class SkillFile(
    /** The file's path relative to the skill root, e.g. `SKILL.md` or `scripts/run.py`. */
    val path: String,
    /** The file's content — [FileData.Bytes] or [FileData.Text] only. */
    val data: FileData,
)

/** Everything one skill upload needs. */
public data class SkillUploadOptions(
    /** The files that make up the skill. */
    val files: List<SkillFile>,
    /** Human-readable title, where the vendor stores one. */
    val displayTitle: String? = null,
    /** Provider-namespaced options, passed through verbatim — see [ProviderOptions]. */
    val providerOptions: ProviderOptions? = null,
)

/** The result of [ProviderSkills.uploadSkill]. */
public data class SkillUploadResult(
    /** Provider id → the skill id that vendor knows this upload by. */
    val providerReference: Map<String, String>,
    /** The title the vendor recorded, when it reports one. */
    val displayTitle: String? = null,
    /** The skill's machine name, when the vendor derives one. */
    val name: String? = null,
    /** What the skill does, when the vendor extracted a description. */
    val description: String? = null,
    /** The version minted by this upload — what a request pins to survive later uploads. */
    val latestVersion: String? = null,
    /** Provider-namespaced output — source, timestamps — carried verbatim; see [ProviderMetadata]. */
    val providerMetadata: ProviderMetadata? = null,
    /** Anything the provider had to ignore about the call — see [Warning]. */
    val warnings: List<Warning> = emptyList(),
)
