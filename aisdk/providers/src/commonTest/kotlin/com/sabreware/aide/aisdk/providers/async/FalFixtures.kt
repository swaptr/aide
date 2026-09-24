package com.sabreware.aide.aisdk.providers.async

/**
 * fal's own wire bodies, lifted verbatim from the reference's `fal-image-model.test.ts` and
 * `fal-video-model.test.ts`.
 *
 * They live here rather than inline in the assertions for the reason the audit gave: a wire body written
 * by the same person who wrote the parser proves internal consistency and nothing else. These are the
 * bodies the reference recorded against the live service, so agreeing with them is an independent check
 * on our idea of the protocol. They are Kotlin constants rather than JSON resources because commonTest
 * has no portable resource reader — a classloader read would be a `java.*` import, which
 * `portabilityCheck` fails the build on.
 */
internal object FalFixtures {

    /** The plain one-image answer every fal image model gives. */
    const val ONE_IMAGE = """
        {
          "images": [
            {
              "url": "https://api.example.com/image.png",
              "width": 1024,
              "height": 1024,
              "content_type": "image/png"
            }
          ]
        }
    """

    /** Two images, for the `n = 2` path. */
    const val TWO_IMAGES = """
        {
          "images": [
            {
              "url": "https://api.example.com/image.png",
              "width": 1024,
              "height": 1024,
              "content_type": "image/png"
            },
            {
              "url": "https://api.example.com/image.png",
              "width": 1024,
              "height": 1024,
              "content_type": "image/png"
            }
          ]
        }
    """

    /** `fal-ai/lora`: the documented output schema, with the debug latents a caller pays for. */
    const val LORA = """
        {
          "images": [
            {
              "url": "https://api.example.com/image.png",
              "width": 1024,
              "height": 1024,
              "content_type": "image/png",
              "file_data": "<image file_data>",
              "file_size": 123,
              "file_name": "<image file_name>"
            }
          ],
          "prompt": "<prompt>",
          "seed": 123,
          "has_nsfw_concepts": [true],
          "debug_latents": {
            "url": "<debug_latents url>",
            "content_type": "<debug_latents content_type>",
            "file_name": "<debug_latents file_name>",
            "file_data": "<debug_latents file_data>",
            "file_size": 123
          },
          "debug_per_pass_latents": {
            "url": "<debug_per_pass_latents url>",
            "content_type": "<debug_per_pass_latents content_type>",
            "file_name": "<debug_per_pass_latents file_name>",
            "file_data": "<debug_per_pass_latents file_data>",
            "file_size": 456
          }
        }
    """

    /** `fal-ai/lcm`: the older models spell the safety flag `nsfw_content_detected`. */
    const val LCM = """
        {
          "images": [
            { "url": "https://api.example.com/image.png", "width": 1024, "height": 1024 }
          ],
          "seed": 123,
          "num_inference_steps": 456,
          "nsfw_content_detected": [false]
        }
    """

    /** A handful of models answer with a single `image` object rather than an `images` array. */
    const val SINGLE_IMAGE_KEY = """
        {
          "image": {
            "url": "https://api.example.com/image.png",
            "width": 1024,
            "height": 1024,
            "content_type": "image/png"
          }
        }
    """

    /** Recorded wire where fal filled the file fields with JSON null rather than omitting them. */
    const val NULL_FILE_FIELDS = """
        {
          "images": [
            {
              "url": "https://api.example.com/image.png",
              "content_type": "image/png",
              "file_name": null,
              "file_size": null,
              "width": 944,
              "height": 1104
            }
          ],
          "timings": { "inference": 5.875932216644287 },
          "seed": 328395684,
          "has_nsfw_concepts": [false],
          "prompt": "A female model holding this book, keeping the book unchanged."
        }
    """

    /** Recorded wire with an EMPTY timings object — the shape a strict parser drops. */
    const val EMPTY_TIMINGS = """
        {
          "images": [
            {
              "url": "https://api.example.com/image.png",
              "content_type": "image/png",
              "file_name": null,
              "file_size": null,
              "width": 880,
              "height": 1184
            }
          ],
          "timings": {},
          "seed": 235205040,
          "has_nsfw_concepts": [false],
          "prompt": "Change the plates to colorful ones"
        }
    """

    /** Recorded wire where the dimensions themselves came back null. */
    const val NULL_DIMENSIONS = """
        {
          "images": [
            {
              "url": "https://api.example.com/image.png",
              "content_type": "image/png",
              "file_name": "output.png",
              "file_size": 663399,
              "width": null,
              "height": null
            }
          ],
          "description": "here is an image with null width and height"
        }
    """

    /** FastAPI's validation envelope, which is what a rejected fal parameter comes back as. */
    const val VALIDATION_ERROR = """
        {
          "detail": [
            { "loc": ["prompt"], "msg": "Invalid prompt", "type": "value_error" }
          ]
        }
    """

    // --- video ----------------------------------------------------------------------------------

    const val QUEUE_SUBMITTED = """
        {
          "request_id": "test-request-id-123",
          "response_url":
            "https://queue.fal.run/fal-ai/luma-dream-machine/requests/test-request-id-123"
        }
    """

    const val QUEUE_COMPLETED = """
        {
          "video": {
            "url": "https://fal.media/files/video-output.mp4",
            "width": 1920,
            "height": 1080,
            "duration": 5.0,
            "fps": 24,
            "content_type": "video/mp4"
          },
          "seed": 12345,
          "timings": { "inference": 45.5 }
        }
    """

    /** fal reports "not finished yet" as a 500 with this body, not as a status field. */
    const val QUEUE_IN_PROGRESS = """{ "detail": "Request is still in progress" }"""

    const val QUEUE_SERVER_ERROR = """
        { "error": { "message": "Internal server error", "code": 500 } }
    """
}
