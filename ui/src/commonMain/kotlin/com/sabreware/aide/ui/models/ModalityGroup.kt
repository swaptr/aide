package com.sabreware.aide.ui.models

import com.sabreware.aide.core.designsystem.resources.*
import com.sabreware.aide.core.domain.model.Modality
import com.sabreware.aide.core.domain.model.ProviderCatalog
import com.sabreware.aide.core.domain.model.ProviderId
import org.jetbrains.compose.resources.DrawableResource

/**
 * Top-level grouping for the in-use list and the Add-model wizard.
 *
 * Speech is TWO groups, not one "Voice": transcription and synthesis are separate models, separately
 * downloaded, separately active — someone who only wants dictation should never have to reason about a
 * voice, and vice versa. (VAD is deliberately absent: it is auto-provisioned, never chosen.)
 */
enum class ModalityGroup(val label: String, val blurb: String) {
    // Named for the FEATURE each model powers, not for the pipeline direction: "speech to text" /
    // "text to speech" are engineering terms, and three labels all containing "text" read as one blur.
    LANGUAGE("Chat", "Reasoning and tools"),
    SPEECH_TO_TEXT("Dictation", "Speak instead of typing"),
    TEXT_TO_SPEECH("Speech", "Hear replies read aloud"),
    IMAGE("Image", "Picture generation"),
}

fun ModalityGroup.iconRes(): DrawableResource = when (this) {
    // Lucide, picked for direction of travel: a text mark for what you type, a mic for what goes IN,
    // a speaker for what comes OUT. Two mic glyphs side by side told the user nothing.
    ModalityGroup.LANGUAGE -> Res.drawable.ic_lc_type
    ModalityGroup.SPEECH_TO_TEXT -> Res.drawable.ic_lc_mic
    ModalityGroup.TEXT_TO_SPEECH -> Res.drawable.ic_lc_volume_2
    ModalityGroup.IMAGE -> Res.drawable.ic_lc_image
}

/** The speech modality a group selects, or null for non-speech groups. */
fun ModalityGroup.speechKind(): Modality? = when (this) {
    ModalityGroup.SPEECH_TO_TEXT -> Modality.Asr
    ModalityGroup.TEXT_TO_SPEECH -> Modality.Tts
    else -> null
}

/** The group a speech asset belongs to. */
fun Modality.speechGroup(): ModalityGroup =
    if (this == Modality.Tts) ModalityGroup.TEXT_TO_SPEECH else ModalityGroup.SPEECH_TO_TEXT

/** The group any non-chat model belongs to: the two speech roles, or image. */
fun Modality.group(): ModalityGroup = when (this) {
    Modality.Image -> ModalityGroup.IMAGE
    Modality.Chat -> ModalityGroup.LANGUAGE
    else -> speechGroup()
}

/**
 * The modality a group's active-model slot is keyed by, for the groups that HAVE one — the speech
 * roles and image. Chat's pick lives in the model registry instead.
 */
fun ModalityGroup.pickModality(): Modality? = when (this) {
    ModalityGroup.SPEECH_TO_TEXT -> Modality.Asr
    ModalityGroup.TEXT_TO_SPEECH -> Modality.Tts
    ModalityGroup.IMAGE -> Modality.Image
    ModalityGroup.LANGUAGE -> null
}

/**
 * Whether the wizard offers the image group. On: the image catalog is a port
 * ([com.sabreware.aide.core.domain.image.ImageModelCatalog]) the wizard lists, and the `GenerateImage`
 * tool draws with the model picked there. Cloud-only — no on-device image model exists, so the
 * add-model page shows no On-device tab for it.
 */
const val IS_IMAGE_SUPPORTED = true

/** Where a model runs — drives the leading provider icon. */
enum class ProviderKind { LOCAL, CLOUD }

/** Maps an open [ProviderId] to its UI location bucket via the provider's locality trait. */
fun ProviderId.providerKind(): ProviderKind =
    if (ProviderCatalog.isBuiltIn(this)) ProviderKind.LOCAL else ProviderKind.CLOUD

fun ProviderKind.iconRes(): DrawableResource = when (this) {
    ProviderKind.LOCAL -> Res.drawable.ic_lc_smartphone
    ProviderKind.CLOUD -> Res.drawable.ic_lc_cloud
}
