package com.swaptr.aide.data.download

// `kind` namespaces the id so LLM "foo" and speech "foo" don't collide on WorkManager's
// flat unique-work key.
data class AssetHandle(val kind: String, val id: String) {
    val uniqueWorkName: String get() = "${kind}_download_$id"
    val tag: String get() = "${kind}_download:$id"
}
