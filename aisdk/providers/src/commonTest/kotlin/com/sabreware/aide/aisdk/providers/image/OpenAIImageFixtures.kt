package com.sabreware.aide.aisdk.providers.image

/**
 * OpenAI's own recorded image wire, copied verbatim from the reference's
 * `packages/openai/src/image/__fixtures__/`.
 *
 * These three are among the 357 fixture files the reference keeps on disk — bodies captured from the
 * live service rather than written by whoever wrote the parser — which is what makes agreeing with them
 * an independent check on our idea of the protocol rather than a check that we are self-consistent. The
 * base64 payloads are truncated exactly as the reference truncated them.
 */
internal object OpenAIImageFixtures {

    /** `openai-image.json`: two images, one of them carrying a revised prompt. */
    const val TWO_IMAGES: String = """
        {
          "created": 1770935200,
          "data": [
            {
              "b64_json": "iVBORw0KGgoAAAANSUhEUgAABAAAAAQACAIAAADwf7zUAAA3CGNhQlgAADcIanVtYgAAAB5qdW1kYzJwYQARABCAAACqADibcQNj",
              "revised_prompt": "A small and adorable baby sea otter. This little creature is covered in a thick and fluffy brown fur, its tiny paws are slightly visible. The otter has bright, curious eyes and it's floating on its back on a calm sea, surrounded by floating seaweed."
            },
            {
              "b64_json": "iVBORw0KGgoAAAANSUhEUgAABAAAAAQACAIAAADwf7zUAAEp2GNhQlgAASnYanVtYgAAAB5qdW1kYzJwYQARABCAAACqADibcQNj"
            }
          ]
        }
    """

    /** `openai-image-edit.json`: what the edit endpoint answers, with the settings it resolved. */
    const val EDIT: String = """
        {
          "created": 1770935251,
          "background": "opaque",
          "data": [
            {
              "b64_json": "iVBORw0KGgoAAAANSUhEUgAABAAAAAQACAIAAADwf7zUAAFEE2NhQlgAAUQTanVtYgAAAB5qdW1kYzJwYQARABCAAACqADibcQNj"
            }
          ],
          "output_format": "png",
          "quality": "high",
          "size": "1024x1024"
        }
    """

    /**
     * `gpt-image-response-format-error.json`: what the newer image models answer when a client sends
     * `response_format`. They default to base64 and reject the parameter outright.
     */
    const val RESPONSE_FORMAT_REJECTED: String = """
        {
          "error": {
            "message": "Unknown parameter: 'response_format'.",
            "type": "invalid_request_error",
            "param": "response_format",
            "code": "unknown_parameter"
          }
        }
    """

    /** The first fixture's first image, for asserting the payload arrived untouched. */
    const val FIRST_IMAGE_B64: String =
        "iVBORw0KGgoAAAANSUhEUgAABAAAAAQACAIAAADwf7zUAAA3CGNhQlgAADcIanVtYgAAAB5qdW1kYzJwYQARABCAAACqADibcQNj"
}
