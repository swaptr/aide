package com.sabreware.aide.app.assistant.domain

/** Where Aide stands as the device's digital assistant — the single axis the Assistant setup UI branches on. */
enum class AideAssistantStatus {
    /** Another app (or none) holds the assistant role — the user can switch to Aide. */
    NotDefault,

    /** Aide is the device's default digital assistant — nothing left to do. */
    Active,
}
