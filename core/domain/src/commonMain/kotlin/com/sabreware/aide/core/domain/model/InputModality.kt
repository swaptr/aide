package com.sabreware.aide.core.domain.model

import com.sabreware.aide.core.common.media.AttachmentKind
import com.sabreware.aide.core.domain.chat.AidePart

/**
 * The capability-gated input modalities — THE single definition of "which capability admits which
 * attachment". Every gate consults this mapping (the attach-time gate in ChatViewModel, the send-time
 * belt-and-braces filter in SendChatMessageUseCase, model-switch drops); adding a gated part type means
 * extending [AidePart.inputModalityOrNull] and the compiler finds the rest. Text is deliberately absent:
 * it is never gated.
 */
enum class InputModality { Image, Audio, Document }

fun ChatCapabilities.accepts(modality: InputModality): Boolean = when (modality) {
    InputModality.Image -> visionIn
    InputModality.Audio -> audioIn
    InputModality.Document -> documentIn
}

/** The modality a part needs, or null for never-gated parts (text, tool records, thinking). */
fun AidePart.inputModalityOrNull(): InputModality? = when (this) {
    is AidePart.ImageFile, is AidePart.ImageBytes -> InputModality.Image
    is AidePart.AudioFile -> InputModality.Audio
    is AidePart.DocumentFile -> InputModality.Document
    else -> null
}

/** The modality a picked file will need once attached, or null for ungated kinds (Text) and refused
 *  ones (Unsupported — rejected before any gate applies). */
fun AttachmentKind.inputModalityOrNull(): InputModality? = when (this) {
    AttachmentKind.Image -> InputModality.Image
    AttachmentKind.Audio -> InputModality.Audio
    AttachmentKind.Pdf -> InputModality.Document
    AttachmentKind.Text, AttachmentKind.Unsupported -> null
}
