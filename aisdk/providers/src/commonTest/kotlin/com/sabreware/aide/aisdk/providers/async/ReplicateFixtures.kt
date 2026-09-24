package com.sabreware.aide.aisdk.providers.async

/**
 * Replicate's own wire bodies, lifted verbatim from the reference's `replicate-image-model.test.ts` and
 * `replicate-video-model.test.ts`. See [FalFixtures] for why they live in a Kotlin file rather than a
 * JSON resource.
 */
internal object ReplicateFixtures {

    /**
     * A `Prefer: wait` answer, exactly as the reference recorded it.
     *
     * Note the `status` — it says `processing` and yet carries a finished `output`. That combination is
     * why a status check must never be allowed to overrule an output that is already present.
     */
    fun prediction(
        output: String = """["https://replicate.delivery/xezq/abc/out-0.webp"]""",
        status: String = "processing",
    ): String = """
        {
          "id": "s7x1e3dcmhrmc0cm8rbatcneec",
          "model": "black-forest-labs/flux-schnell",
          "version": "dp-4d0bcc010b3049749a251855f12800be",
          "input": {
            "num_outputs": 1,
            "prompt": "The Loch Ness Monster getting a manicure"
          },
          "logs": "",
          "output": $output,
          "data_removed": false,
          "error": null,
          "status": "$status",
          "created_at": "2025-01-08T13:24:38.692Z",
          "urls": {
            "cancel": "https://api.replicate.com/v1/predictions/s7x1e3dcmhrmc0cm8rbatcneec/cancel",
            "get": "https://api.replicate.com/v1/predictions/s7x1e3dcmhrmc0cm8rbatcneec",
            "stream": "https://stream.replicate.com/v1/files/bcwr-3okdfv3o2wehstv5f2okyftwxy57hhypqsi6osiim5iaq5k7u24a"
          }
        }
    """

    /** The video shape: one prediction, polled through its own `urls.get`. */
    fun videoPrediction(
        id: String = "test-prediction-id",
        status: String = "succeeded",
        output: String = "\"https://replicate.delivery/video.mp4\"",
        error: String = "null",
        metrics: String = """{ "predict_time": 25.5 }""",
    ): String = """
        {
          "id": "$id",
          "status": "$status",
          "output": $output,
          "error": $error,
          "urls": { "get": "https://api.replicate.com/v1/predictions/$id" },
          "metrics": $metrics
        }
    """
}
