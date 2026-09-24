package com.sabreware.aide.app.assistant

import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService

// Vended by `res/xml/voice_interaction.xml` (sessionService=). The session is not an Android
// component, so AideAssistantSession resolves its deps through Koin (KoinComponent.inject()).
class AideVoiceInteractionSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession =
        AideAssistantSession(this)
}
