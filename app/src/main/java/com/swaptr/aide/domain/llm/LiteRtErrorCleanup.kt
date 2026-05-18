package com.swaptr.aide.domain.llm

// Strips the verbose stack-trace blob LiteRT appends after "=== Source Location Trace".
fun cleanUpMediapipeTaskErrorMessage(message: String): String {
    val index = message.indexOf("=== Source Location Trace")
    if (index >= 0) {
        return message.substring(0, index)
    }
    return message
}
