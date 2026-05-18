package com.swaptr.aide.assistant

import android.service.voice.VoiceInteractionService
import dagger.hilt.android.AndroidEntryPoint

// Registers Aide as an eligible "Default digital assistant app" — required for
// the system to bind [AideVoiceInteractionSessionService] via res/xml/voice_interaction.xml.
@AndroidEntryPoint
class AideVoiceInteractionService : VoiceInteractionService()
