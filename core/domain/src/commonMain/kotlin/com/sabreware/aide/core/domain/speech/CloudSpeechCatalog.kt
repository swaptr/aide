package com.sabreware.aide.core.domain.speech

import com.sabreware.aide.core.domain.model.Modality
import com.sabreware.aide.core.domain.model.ModelDescriptor
import com.sabreware.aide.core.domain.model.ModelSelectionStore
import com.sabreware.aide.core.domain.model.ProviderId
import com.sabreware.aide.core.domain.model.activeModelFor
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first

/**
 * A speech model served over a wire — the cloud peer of [SpeechAssetSpec].
 *
 * Nothing is downloaded, so every [com.sabreware.aide.core.domain.download.DownloadableSpec] field is null
 * and [requiresDownload] reads false; what remains is identity: who serves it, which role it fills, and the
 * [remoteName] the vendor's endpoint expects. [id] is `"<connectionId>:<remoteName>"`, the same namespacing
 * the chat catalog uses (`openai-k3f9x2:gpt-4o`), so the one active-model slot per modality
 * ([com.sabreware.aide.core.domain.model.activeModelFor]) can hold an on-device asset id or a cloud id and
 * the [CloudSpeechCatalog] can tell whose it is without parsing the string.
 */
data class CloudSpeechModelSpec(
    override val id: String,
    override val displayName: String,
    override val modality: Modality,
    override val provider: ProviderId,
    /** The vendor's own model id, sent on the wire. */
    val remoteName: String,
    /** One line for the picker row. */
    val blurb: String? = null,
    /** The model a vendor uses when the user has not picked one of its models for this role. */
    val isDefault: Boolean = false,
) : ModelDescriptor {
    override val downloadUrl: String? get() = null
    override val fileName: String? get() = null
    override val sizeBytes: Long? get() = null
}

/**
 * The cloud speech models the user's connections serve, per connection and role.
 *
 * A port rather than a table in `:data:speech` because two consumers sit above that module: the Models UI
 * lists these rows, and the speech engine repository asks who owns the active model to decide which provider
 * Auto mode should try first. Neither may reach into `:data`.
 *
 * Curated on purpose, like the image catalog: the speech endpoints do not advertise which of a vendor's
 * models transcribe or speak, and a wrong guess surfaces as a 400 at the first mic tap. Each connection
 * contributes its vendor's rows under its own ids ([CloudSpeechModelSpec.provider] is the connection).
 */
interface CloudSpeechCatalog {
    /** Every connection's speech models; null until the connections have been read. */
    val models: StateFlow<List<CloudSpeechModelSpec>?>

    /** What is known right now. For painting. */
    val all: List<CloudSpeechModelSpec> get() = models.value.orEmpty()

    /** This connection's models for [modality], in display order. Empty when it serves none. */
    fun forProvider(provider: ProviderId, modality: Modality): List<CloudSpeechModelSpec> =
        all.filter { it.provider == provider && it.modality == modality }

    /** The model [provider] falls back to for [modality]; null when it serves that role not at all. */
    fun default(provider: ProviderId, modality: Modality): CloudSpeechModelSpec? =
        forProvider(provider, modality).let { rows -> rows.firstOrNull { it.isDefault } ?: rows.firstOrNull() }

    fun findById(id: String): CloudSpeechModelSpec? = all.firstOrNull { it.id == id }
}

/** [CloudSpeechCatalog.findById] once the connections are known — for acting, never for painting. */
suspend fun CloudSpeechCatalog.awaitById(id: String): CloudSpeechModelSpec? =
    models.filterNotNull().first().firstOrNull { it.id == id }

/**
 * The wire model [provider] should use for [modality] right now.
 *
 * The user's active pick wins when it is one of this connection's models; otherwise the vendor default. A
 * pick that belongs to another provider — Sherpa's asset id, or another connection's model — is not an
 * error here, because the active slot is per MODALITY, not per provider, and a provider that is pinned
 * while the slot names someone else's model must still speak. A connection with no default for the role is
 * a wiring bug: it should not have been bound as capable of it.
 */
suspend fun CloudSpeechCatalog.resolveRemoteName(
    selection: ModelSelectionStore,
    provider: ProviderId,
    modality: Modality,
): String {
    val active = selection.activeModelFor(modality).first()?.let { awaitById(it) }?.takeIf { it.provider == provider }
    val chosen = active ?: models.filterNotNull().first()
        .filter { it.provider == provider && it.modality == modality }
        .let { rows -> rows.firstOrNull { it.isDefault } ?: rows.firstOrNull() }
        ?: throw IllegalStateException("${provider.value} serves no ${modality.value} models")
    return chosen.remoteName
}
