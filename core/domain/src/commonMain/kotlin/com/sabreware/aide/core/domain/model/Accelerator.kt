package com.sabreware.aide.core.domain.model

// Ported from Google AI Edge Gallery (data/Types.kt). The label string is the wire
// value carried by gallery's allowlist JSON (defaultConfig.accelerators / visionAccelerator)
// so case-sensitive matching against `label` is the contract — do not lowercase here.
enum class Accelerator(val label: String) {
    CPU(label = "CPU"),
    GPU(label = "GPU"),
    NPU(label = "NPU"),
    TPU(label = "TPU"),
}
