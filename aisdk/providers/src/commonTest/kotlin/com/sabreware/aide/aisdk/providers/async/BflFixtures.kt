package com.sabreware.aide.aisdk.providers.async

/**
 * Black Forest Labs' and Luma's own wire bodies, lifted verbatim from
 * `black-forest-labs-image-model.test.ts` and `luma-image-model.test.ts`. See [FalFixtures] for why they
 * live in a Kotlin file rather than a JSON resource.
 */
internal object BflFixtures {

    /** The submit answer: an id, and the URL to poll — which is on a different host. */
    fun submitted(extra: String = ""): String = """
        {
          "id": "req-123",
          "polling_url": "https://api.example.com/poll"$extra
        }
    """

    const val SUBMITTED_WITH_BILLING: String = """
        {
          "id": "req-123",
          "polling_url": "https://api.example.com/poll",
          "cost": 0.08,
          "input_mp": 1.5,
          "output_mp": 2.0
        }
    """

    /** BFL fills the billing fields with JSON null rather than omitting them on the free tiers. */
    const val SUBMITTED_WITH_NULL_BILLING: String = """
        {
          "id": "req-123",
          "polling_url": "https://api.example.com/poll",
          "cost": null,
          "input_mp": null,
          "output_mp": null
        }
    """

    const val READY: String = """
        {
          "status": "Ready",
          "result": { "sample": "https://api.example.com/image.png" }
        }
    """

    const val READY_WITH_SEED: String = """
        {
          "status": "Ready",
          "result": { "sample": "https://api.example.com/image.png", "seed": 12345 }
        }
    """

    /** The same field under a second name; a client reading only `status` sees nothing at all. */
    const val READY_UNDER_STATE: String = """
        {
          "state": "Ready",
          "result": { "sample": "https://api.example.com/image.png" }
        }
    """

    const val READY_WITH_NO_SAMPLE: String = """{ "status": "Ready", "result": null }"""

    const val PENDING: String = """{ "status": "Pending" }"""

    const val ERRORED: String = """{ "status": "Error" }"""

    /** BFL nests its real message under `detail`, with a less specific one at the top level. */
    const val ERROR_WITH_DETAIL: String = """
        { "message": "Top-level message", "detail": { "error": "Invalid prompt" } }
    """
}

internal object LumaFixtures {

    const val QUEUED: String = """
        {
          "id": "test-generation-id",
          "generation_type": "image",
          "state": "queued",
          "created_at": "2024-01-01T00:00:00Z",
          "model": "test-model",
          "request": {
            "generation_type": "image",
            "model": "test-model",
            "prompt": "A cute baby sea otter"
          }
        }
    """

    const val COMPLETED: String = """
        {
          "id": "test-generation-id",
          "generation_type": "image",
          "state": "completed",
          "created_at": "2024-01-01T00:00:00Z",
          "assets": { "image": "https://api.example.com/image.png" },
          "model": "test-model",
          "request": {
            "generation_type": "image",
            "model": "test-model",
            "prompt": "A cute baby sea otter"
          }
        }
    """

    /**
     * Recorded wire for a completed generation that ECHOES BACK every reference the request carried —
     * all four kinds at once, which is the widest `request` block Luma produces. The reference keeps
     * five separate cases for it; one body carrying the lot pins the same property, which is that a
     * shape we do not model does not stop us reading the asset beside it.
     */
    const val COMPLETED_ECHOING_EVERY_REFERENCE: String = """
        {
          "id": "test-generation-id",
          "generation_type": "image",
          "state": "completed",
          "created_at": "2024-01-01T00:00:00Z",
          "assets": { "image": "https://api.example.com/image.png" },
          "model": "test-model",
          "request": {
            "generation_type": "image",
            "model": "test-model",
            "prompt": "A cute baby sea otter",
            "image_ref": [{ "url": "https://example.com/ref1.jpg", "weight": 0.85 }],
            "style_ref": [{ "url": "https://example.com/style.jpg", "weight": 0.8 }],
            "character_ref": {
              "identity0": { "images": ["https://example.com/person.jpg"] }
            },
            "modify_image_ref": { "url": "https://example.com/input.jpg", "weight": 1.0 }
          }
        }
    """

    const val FAILED: String = """
        {
          "id": "test-generation-id",
          "generation_type": "image",
          "state": "failed",
          "failure_reason": "Generation failed",
          "created_at": "2024-01-01T00:00:00Z",
          "model": "test-model"
        }
    """
}
