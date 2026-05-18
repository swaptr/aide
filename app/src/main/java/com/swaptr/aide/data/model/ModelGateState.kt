package com.swaptr.aide.data.model

import com.swaptr.aide.data.catalog.ModelSpec

// Single source of truth for "can the app run an LLM call right now?" — gates IME, VMs etc.
sealed interface ModelGateState {
    data class Ready(val spec: ModelSpec) : ModelGateState
    data class Downloading(val spec: ModelSpec, val progress: Float) : ModelGateState
    data object NoModel : ModelGateState
}
