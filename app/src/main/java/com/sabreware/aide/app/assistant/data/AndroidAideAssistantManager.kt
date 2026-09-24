package com.sabreware.aide.app.assistant.data

import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.provider.Settings
import com.sabreware.aide.app.assistant.domain.AideAssistantManager
import com.sabreware.aide.app.assistant.domain.AideAssistantStatus

/**
 * [AideAssistantManager] over the platform role/assist APIs. Aide ships a `VoiceInteractionService`, so
 * holding [RoleManager.ROLE_ASSISTANT] means Aide is the device's active digital assistant. The role is
 * a package-level grant (permission-free to read), so a single [RoleManager.isRoleHeld] read is the live
 * status; [RoleManager.isRoleAvailable] guards the rare device that doesn't expose the role at all.
 *
 * `ROLE_ASSISTANT` is not requestable, so [RoleManager.createRequestRoleIntent] shows no dialog for it
 * (it returns RESULT_CANCELED with no UI). Instead the user is sent to the system "Assist & voice input"
 * screen via [Settings.ACTION_VOICE_INPUT_SETTINGS] to pick Aide — the documented path for the assist app.
 */
class AndroidAideAssistantManager(
    private val context: Context,
) : AideAssistantManager {

    private val roleManager: RoleManager?
        get() = context.getSystemService(RoleManager::class.java)

    override fun currentStatus(): AideAssistantStatus {
        val rm = roleManager
        val held = rm != null &&
            rm.isRoleAvailable(RoleManager.ROLE_ASSISTANT) &&
            rm.isRoleHeld(RoleManager.ROLE_ASSISTANT)
        return if (held) AideAssistantStatus.Active else AideAssistantStatus.NotDefault
    }

    override fun openAssistantSettings() {
        // NEW_TASK: launched from the application (non-Activity) context. runCatching guards the rare OEM
        // that doesn't expose the voice-input settings screen.
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_VOICE_INPUT_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }
}
