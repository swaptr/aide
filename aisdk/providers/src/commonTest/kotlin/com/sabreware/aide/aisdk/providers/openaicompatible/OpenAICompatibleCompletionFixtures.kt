package com.sabreware.aide.aisdk.providers.openaicompatible

/**
 * The legacy Completions wire as the reference recorded it — `packages/openai/src/completion/__fixtures__`
 * and the inline bodies of `openai-completion-language-model.test.ts` — copied byte for byte.
 *
 * A hand-written fixture proves a mapper consistent with itself and nothing else; these are the bytes
 * `gpt-3.5-turbo-instruct` actually sent, which is the only wire the completion model has to agree with.
 */
internal object OpenAICompatibleCompletionFixtures {

    /** `openai-completion-text.json`: one document, truncated at the length limit. */
    const val TEXT: String = """{
  "id": "cmpl-D8ZFHlGItjM5Nghki1LmZIRscBz2P",
  "object": "text_completion",
  "created": 1770934479,
  "model": "gpt-3.5-turbo-instruct:20230824-v2",
  "choices": [
    {
      "text": "The new holiday is called \"Gratitude Day\" and it celebrates the importance of",
      "index": 0,
      "logprobs": null,
      "finish_reason": "length"
    }
  ],
  "usage": {
    "prompt_tokens": 14,
    "completion_tokens": 16,
    "total_tokens": 30
  }
}"""

    /** `openai-completion-text.chunks.txt`, one entry per recorded line; usage arrives on the tail. */
    val textChunks: List<String> = listOf(
        """{"id":"cmpl-D8ZFN477TMm6AoQohx2jSTOJMh60M","object":"text_completion","created":1770934485,"choices":[{"text":"The","index":0,"logprobs":null,"finish_reason":null}],"model":"gpt-3.5-turbo-instruct:20230824-v2","usage":null}""",
        """{"id":"cmpl-D8ZFN477TMm6AoQohx2jSTOJMh60M","object":"text_completion","created":1770934485,"choices":[{"text":" holiday","index":0,"logprobs":null,"finish_reason":null}],"model":"gpt-3.5-turbo-instruct:20230824-v2","usage":null}""",
        """{"id":"cmpl-D8ZFN477TMm6AoQohx2jSTOJMh60M","object":"text_completion","created":1770934485,"choices":[{"text":" is","index":0,"logprobs":null,"finish_reason":null}],"model":"gpt-3.5-turbo-instruct:20230824-v2","usage":null}""",
        """{"id":"cmpl-D8ZFN477TMm6AoQohx2jSTOJMh60M","object":"text_completion","created":1770934485,"choices":[{"text":" called","index":0,"logprobs":null,"finish_reason":null}],"model":"gpt-3.5-turbo-instruct:20230824-v2","usage":null}""",
        """{"id":"cmpl-D8ZFN477TMm6AoQohx2jSTOJMh60M","object":"text_completion","created":1770934485,"choices":[{"text":" \"","index":0,"logprobs":null,"finish_reason":null}],"model":"gpt-3.5-turbo-instruct:20230824-v2","usage":null}""",
        """{"id":"cmpl-D8ZFN477TMm6AoQohx2jSTOJMh60M","object":"text_completion","created":1770934485,"choices":[{"text":"Gr","index":0,"logprobs":null,"finish_reason":null}],"model":"gpt-3.5-turbo-instruct:20230824-v2","usage":null}""",
        """{"id":"cmpl-D8ZFN477TMm6AoQohx2jSTOJMh60M","object":"text_completion","created":1770934485,"choices":[{"text":"atitude","index":0,"logprobs":null,"finish_reason":null}],"model":"gpt-3.5-turbo-instruct:20230824-v2","usage":null}""",
        """{"id":"cmpl-D8ZFN477TMm6AoQohx2jSTOJMh60M","object":"text_completion","created":1770934485,"choices":[{"text":" Day","index":0,"logprobs":null,"finish_reason":null}],"model":"gpt-3.5-turbo-instruct:20230824-v2","usage":null}""",
        """{"id":"cmpl-D8ZFN477TMm6AoQohx2jSTOJMh60M","object":"text_completion","created":1770934485,"choices":[{"text":"\"","index":0,"logprobs":null,"finish_reason":null}],"model":"gpt-3.5-turbo-instruct:20230824-v2","usage":null}""",
        """{"id":"cmpl-D8ZFN477TMm6AoQohx2jSTOJMh60M","object":"text_completion","created":1770934485,"choices":[{"text":" and","index":0,"logprobs":null,"finish_reason":null}],"model":"gpt-3.5-turbo-instruct:20230824-v2","usage":null}""",
        """{"id":"cmpl-D8ZFN477TMm6AoQohx2jSTOJMh60M","object":"text_completion","created":1770934485,"choices":[{"text":" it","index":0,"logprobs":null,"finish_reason":null}],"model":"gpt-3.5-turbo-instruct:20230824-v2","usage":null}""",
        """{"id":"cmpl-D8ZFN477TMm6AoQohx2jSTOJMh60M","object":"text_completion","created":1770934485,"choices":[{"text":" is","index":0,"logprobs":null,"finish_reason":null}],"model":"gpt-3.5-turbo-instruct:20230824-v2","usage":null}""",
        """{"id":"cmpl-D8ZFN477TMm6AoQohx2jSTOJMh60M","object":"text_completion","created":1770934485,"choices":[{"text":" a","index":0,"logprobs":null,"finish_reason":null}],"model":"gpt-3.5-turbo-instruct:20230824-v2","usage":null}""",
        """{"id":"cmpl-D8ZFN477TMm6AoQohx2jSTOJMh60M","object":"text_completion","created":1770934485,"choices":[{"text":" day","index":0,"logprobs":null,"finish_reason":null}],"model":"gpt-3.5-turbo-instruct:20230824-v2","usage":null}""",
        """{"id":"cmpl-D8ZFN477TMm6AoQohx2jSTOJMh60M","object":"text_completion","created":1770934485,"choices":[{"text":" dedicated","index":0,"logprobs":null,"finish_reason":null}],"model":"gpt-3.5-turbo-instruct:20230824-v2","usage":null}""",
        """{"id":"cmpl-D8ZFN477TMm6AoQohx2jSTOJMh60M","object":"text_completion","created":1770934485,"choices":[{"text":" to","index":0,"logprobs":null,"finish_reason":"length"}],"model":"gpt-3.5-turbo-instruct:20230824-v2","usage":null}""",
        """{"id":"cmpl-D8ZFN477TMm6AoQohx2jSTOJMh60M","object":"text_completion","created":1770934485,"model":"gpt-3.5-turbo-instruct:20230824-v2","usage":{"prompt_tokens":14,"completion_tokens":16,"total_tokens":30},"choices":[]}""",
    )

    /** The reference's `TEST_LOGPROBS`, exactly as `JSON.stringify` inlines it into its stream chunks. */
    const val LOGPROBS: String =
        """{"tokens":[" ever"," after",".\n\n","The"," end","."],"token_logprobs":[-0.0664508,-0.014520033,-1.3820221,-0.7890417,-0.5323165,-0.10247037],"top_logprobs":[{" ever":-0.0664508},{" after":-0.014520033},{".\n\n":-1.3820221},{"The":-0.7890417},{" end":-0.5323165},{".":-0.10247037}]}"""

    /** `doGenerate > should extract text response`. */
    const val HELLO_WORLD: String =
        """{"id":"cmpl-96cAM1v77r4jXa4qb2NSmRREV5oWB","object":"text_completion","created":1711363706,"model":"gpt-3.5-turbo-instruct","choices":[{"text":"Hello, World!","index":0,"finish_reason":"stop"}],"usage":{"prompt_tokens":4,"total_tokens":34,"completion_tokens":30}}"""

    /** `doGenerate > should extract usage`. */
    const val USAGE_ONLY: String =
        """{"id":"cmpl-96cAM1v77r4jXa4qb2NSmRREV5oWB","object":"text_completion","created":1711363706,"model":"gpt-3.5-turbo-instruct","choices":[{"text":"","index":0,"finish_reason":"stop"}],"usage":{"prompt_tokens":20,"total_tokens":25,"completion_tokens":5}}"""

    /** `doGenerate > should send additional response information`. */
    const val METADATA: String =
        """{"id":"test-id","object":"text_completion","created":123,"model":"test-model","choices":[{"text":"","index":0,"finish_reason":"stop"}],"usage":{"prompt_tokens":4,"total_tokens":34,"completion_tokens":30}}"""

    /** `doGenerate > should extract logprobs`, with [LOGPROBS] on the choice. */
    const val WITH_LOGPROBS: String =
        """{"id":"cmpl-96cAM1v77r4jXa4qb2NSmRREV5oWB","object":"text_completion","created":1711363706,"model":"gpt-3.5-turbo-instruct","choices":[{"text":"","index":0,"logprobs":$LOGPROBS,"finish_reason":"stop"}],"usage":{"prompt_tokens":4,"total_tokens":34,"completion_tokens":30}}"""

    /** `doGenerate > should support unknown finish reason`. */
    const val UNKNOWN_FINISH: String =
        """{"id":"cmpl-96cAM1v77r4jXa4qb2NSmRREV5oWB","object":"text_completion","created":1711363706,"model":"gpt-3.5-turbo-instruct","choices":[{"text":"","index":0,"finish_reason":"eos"}],"usage":{"prompt_tokens":4,"total_tokens":34,"completion_tokens":30}}"""

    /** `doStream > should stream text deltas`: every chunk carries the logprobs, the tail the usage. */
    val streamedDeltas: List<String> = listOf(
        """{"id":"cmpl-96c64EdfhOw8pjFFgVpLuT8k2MtdT","object":"text_completion","created":1711363440,"choices":[{"text":"Hello","index":0,"logprobs":$LOGPROBS,"finish_reason":null}],"model":"gpt-3.5-turbo-instruct"}""",
        """{"id":"cmpl-96c64EdfhOw8pjFFgVpLuT8k2MtdT","object":"text_completion","created":1711363440,"choices":[{"text":", ","index":0,"logprobs":$LOGPROBS,"finish_reason":null}],"model":"gpt-3.5-turbo-instruct"}""",
        """{"id":"cmpl-96c64EdfhOw8pjFFgVpLuT8k2MtdT","object":"text_completion","created":1711363440,"choices":[{"text":"World!","index":0,"logprobs":$LOGPROBS,"finish_reason":null}],"model":"gpt-3.5-turbo-instruct"}""",
        """{"id":"cmpl-96c3yLQE1TtZCd6n6OILVmzev8M8H","object":"text_completion","created":1711363310,"choices":[{"text":"","index":0,"logprobs":$LOGPROBS,"finish_reason":"stop"}],"model":"gpt-3.5-turbo-instruct"}""",
        """{"id":"cmpl-96c3yLQE1TtZCd6n6OILVmzev8M8H","object":"text_completion","created":1711363310,"model":"gpt-3.5-turbo-instruct","usage":{"prompt_tokens":10,"total_tokens":372,"completion_tokens":362},"choices":[]}""",
    )

    /** `doStream > should throw an api error when the first stream chunk is an error`. */
    const val ERROR_FIRST: String =
        """{"error":{"message": "The server had an error processing your request. Sorry about that! You can retry your request, or contact us through our help center at help.openai.com if you keep seeing this error.","type":"server_error","param":null,"code":null}}"""

    /** `doStream > should forward error stream parts after output has started`. */
    val errorAfterOutput: List<String> = listOf(
        """{"id":"cmpl-error-after-output","object":"text_completion","created":1711363440,"choices":[{"text":"Hello","index":0,"logprobs":null,"finish_reason":null}],"model":"gpt-3.5-turbo-instruct"}""",
        """{"error":{"message":"stream failed after output","type":"server_error","param":null,"code":null}}""",
    )
}
