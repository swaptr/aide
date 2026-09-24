package com.sabreware.aide.app.assistant

import android.service.voice.VoiceInteractionService

// Registers Aide as an eligible "Default digital assistant app" — required for
// the system to bind [AideVoiceInteractionSessionService] via res/xml/voice_interaction.xml.
class AideVoiceInteractionService : VoiceInteractionService()
