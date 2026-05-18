package com.swaptr.aide.ime.text

import android.text.InputType
import android.view.inputmethod.EditorInfo

// Why field text must be withheld; lets consumers decide policy without re-parsing EditorInfo.
enum class SensitiveReason {
    None,
    Password,
    Email,
    Phone,
    PostalAddress,
    NoPersonalizedLearning,
}

// Deliberately ignores TYPE_TEXT_FLAG_NO_SUGGESTIONS — set by many non-sensitive editors
// (Keep, code editors) purely to suppress spell-check underlines.
object SensitiveFieldPolicy {

    fun classify(info: EditorInfo?): SensitiveReason {
        if (info == null) return SensitiveReason.None
        if (info.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING != 0) {
            return SensitiveReason.NoPersonalizedLearning
        }
        val cls = info.inputType and InputType.TYPE_MASK_CLASS
        val variation = info.inputType and InputType.TYPE_MASK_VARIATION

        if (cls == InputType.TYPE_CLASS_PHONE) return SensitiveReason.Phone

        if (cls == InputType.TYPE_CLASS_TEXT) {
            when (variation) {
                InputType.TYPE_TEXT_VARIATION_PASSWORD,
                InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
                InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
                -> return SensitiveReason.Password
                InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
                InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS,
                InputType.TYPE_TEXT_VARIATION_EMAIL_SUBJECT,
                -> return SensitiveReason.Email
                InputType.TYPE_TEXT_VARIATION_POSTAL_ADDRESS,
                -> return SensitiveReason.PostalAddress
            }
        }

        if (cls == InputType.TYPE_CLASS_NUMBER &&
            variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
        ) {
            return SensitiveReason.Password
        }

        return SensitiveReason.None
    }

    fun isSensitive(info: EditorInfo?): Boolean = classify(info) != SensitiveReason.None
}
