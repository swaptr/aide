package com.swaptr.aide.assistant

import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService

// Vended by `res/xml/voice_interaction.xml` (sessionService=). Not @AndroidEntryPoint
// because the session is not an Android component; deps go through EntryPointAccessors.
class AideVoiceInteractionSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession =
        AideAssistantSession(this)
}
