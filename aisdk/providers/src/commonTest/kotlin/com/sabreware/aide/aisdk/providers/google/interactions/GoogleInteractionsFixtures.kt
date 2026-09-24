package com.sabreware.aide.aisdk.providers.google.interactions

/**
 * The Interactions API's own wire bodies, copied from the reference's `google/src/interactions/__fixtures__/`.
 *
 * `*_JSON` is the `<name>.json` body of a non-streaming `POST /interactions`; `*_CHUNKS` is the
 * `<name>.chunks.txt` file, one SSE payload per line, which a test frames as `data: <line>\n\n` exactly
 * as the reference's `prepareChunksFixtureResponse` does. Agreement with these is agreement with what
 * Google actually sent, not with our own reading of its documentation. They are Kotlin constants rather
 * than JSON resources because commonTest has no portable resource reader — a classloader read would be
 * a `java.*` import, which `portabilityCheck` fails the build on.
 *
 * Every body is byte-for-byte EXCEPT the two image fixtures. Those are five to six megabytes each,
 * almost entirely one base64 JPEG and one thought signature; here the image data is cut to its first
 * 24 characters (a valid JPEG header, so media-type sniffing still answers `image/jpeg`) and the
 * signature to its first 64. Every other byte of those files — ids, usage, the step shapes, the
 * `mime_type`, the event framing — is verbatim.
 */
@Suppress("MaxLineLength", "LargeClass")
internal object GoogleInteractionsFixtures {

    /** `basic.json`. */
    const val BASIC_JSON: String = """{
  "id": "v1_ChdTbXNIYXFyUEV0ZUttdGtQNXVqVHdRRRIXU21zSGFxclBFdGVLbXRrUDV1alR3UUU",
  "status": "completed",
  "usage": {
    "total_tokens": 58,
    "total_input_tokens": 7,
    "input_tokens_by_modality": [
      {
        "modality": "text",
        "tokens": 7
      }
    ],
    "total_cached_tokens": 0,
    "total_output_tokens": 19,
    "total_tool_use_tokens": 0,
    "total_thought_tokens": 32
  },
  "created": "2026-05-15T18:51:55Z",
  "updated": "2026-05-15T18:51:55Z",
  "service_tier": "standard",
  "steps": [
    {
      "signature": "CqwBAQw51sfgKVnBHSz5praTe+uG0Anr7XQqpCF63u254O4l2U4+GL3n7WRshuMFMfTty31n/76lM81JlqplsBd+YnEEPdyYqh4RpVrMnvUgDP7rkuWFPutrEgLUU/r3LuD3z1dc3qiMjtw3r3RkXtdHNF2Om28zmRoMT0/u8yNkPJA7S2IEfzasBN5yobkpwgbPAQ73PDJZy8n0qjNQmSG/OCMCUDBZMH3C9A1rEg==",
      "type": "thought"
    },
    {
      "content": [
        {
          "text": "Hello! I'm doing well, thank you for asking.\n\nHow are you today?",
          "type": "text"
        }
      ],
      "type": "model_output"
    }
  ],
  "object": "interaction",
  "model": "gemini-2.5-flash"
}
"""

    /** `basic.chunks.txt`, one SSE payload per element. */
    val BASIC_CHUNKS: List<String> = listOf(
        """{"interaction":{"id":"v1_ChdUR3NIYXVyQkFlYVA2ZGtQajZERThBVRIXVEdzSGF1ckJBZWFQNmRrUGo2REU4QVU","status":"in_progress","object":"interaction","model":"gemini-2.5-flash"},"event_type":"interaction.created"}""",
        """{"interaction_id":"v1_ChdUR3NIYXVyQkFlYVA2ZGtQajZERThBVRIXVEdzSGF1ckJBZWFQNmRrUGo2REU4QVU","status":"in_progress","event_type":"interaction.status_update"}""",
        """{"index":0,"step":{"type":"thought"},"event_type":"step.start"}""",
        """{"index":0,"delta":{"signature":"CiQBDDnWxzCnKBoG0/vIUQ9fHy3JYGvjTmIZFY4uKrzvEqSAJh8KdQEMOdbH472i8Wb3Z10/9wPWQyeSH2KMnQfWxi4Z+jlD+igWd1veIZW9QWMqrhgPQqsabcZiwzNAyNaSOJFu0D2ulKtvde8IrVcZkeoG0IR7QVcZWFbKF/uuXxeAV2CYsqXF8Xhv362V/Lc17nWUjzFwvkrftAq8AQEMOdbHZ41h6ebbkW11izLCzDhm/aW2Zh5LR60hYvYMFrL22tOZFEHoBAzZJR+NaPaGbCCd6YtSKUMWXDckIRL42Ms6xISx5eW2xKiNk4Pf+6CDD09wP7kxx/jrvn17+oFEB86PDzUCjK79WXHTufzNZ2NFyXV9wqAX6VcrxSz6eA7BvXtZcHnP5mIlSpBwNDkuWwqtWZq7ebtySPbjjUq4tZw8xB/I0guOpC+u+lCAoSoCOtf8Y+Zc7SG6Cs8BAQw51sel75qdPe/A19DGEaH4sRjNgXHSNzW6zsOqn+nMZEQznZSP+EMitUNYJSCv9ceM5a2+hs9PhGJuPVKrSxnmVya1iHYdquju9DD0q+wSycGXsor1UI4TMic4jg2+5xAFSUir9qA6nSZeuRDjMkCQ0fbJESf2if70CBFNWQe1Q2kKpfc1CrZR2dtc28YfS/c8iyarj3cts6sybBAxgAR834OcVIttdMt8eimPXfCPAP0qGu1cxjXSk746D3XmMwQtpIfW1/xpIjVhJsGA","type":"thought_signature"},"event_type":"step.delta"}""",
        """{"index":0,"event_type":"step.stop"}""",
        """{"index":1,"step":{"type":"model_output"},"event_type":"step.start"}""",
        """{"index":1,"delta":{"text":"I'm doing great, thank you for asking!\n\nHow are you doing today? And what can I do for you?","type":"text"},"event_type":"step.delta"}""",
        """{"index":1,"event_type":"step.stop"}""",
        """{"interaction":{"id":"v1_ChdUR3NIYXVyQkFlYVA2ZGtQajZERThBVRIXVEdzSGF1ckJBZWFQNmRrUGo2REU4QVU","status":"completed","usage":{"total_tokens":148,"total_input_tokens":7,"input_tokens_by_modality":[{"modality":"text","tokens":7}],"total_cached_tokens":0,"total_output_tokens":26,"total_tool_use_tokens":0,"total_thought_tokens":115},"created":"2026-05-15T18:51:57Z","updated":"2026-05-15T18:51:57Z","service_tier":"standard","object":"interaction","model":"gemini-2.5-flash"},"event_type":"interaction.completed"}""",
    )

    /** `structured-output.json`. */
    const val STRUCTURED_OUTPUT_JSON: String = """{
  "id": "v1_ChdUV3NIYW9LTk9OYXlxdHNQb2RpcC1RRRIXVFdzSGFvS05PTmF5cXRzUG9kaXAtUUU",
  "status": "completed",
  "usage": {
    "total_tokens": 78,
    "total_input_tokens": 8,
    "input_tokens_by_modality": [
      {
        "modality": "text",
        "tokens": 8
      }
    ],
    "total_cached_tokens": 0,
    "total_output_tokens": 11,
    "total_tool_use_tokens": 0,
    "total_thought_tokens": 59
  },
  "created": "2026-05-15T18:51:59Z",
  "updated": "2026-05-15T18:51:59Z",
  "service_tier": "standard",
  "steps": [
    {
      "signature": "CqACAQw51sc7wSan2PLLtHG+z0j4AgWR8MPPe76QUDZpwhGeQ1AGXBWGqSm7nhjwbkyXhJ7JlfZ/R3iLZAmqxGH9mLrdkzQooTu5YKptE0+D1jb+PLv7PM/pkCeI8E2IUDtamWiQP2/eG3Rgouxd7+kNxcaERwGTLFsGMdZttew0aP8+xd0hKPAZVkkqKtjVVNYQaSVsnXpTxZrE+spikYx1T/lB9tl6tYkJrB/SmkGO/tsTqDVsaejBXheSM+pu/Nt7s9gAhiNxJnoTvFENezmmiGwiho1lkkPrK8u5uODO5MldRXefJnv8mkZAw/3l6Cxd63hKPGP5za6sjFr/0Scsv0LcrOU65e+YEqZOkp7OXcCoqTCZZITx89JjkOA/pN1T",
      "type": "thought"
    },
    {
      "content": [
        {
          "text": "{\"name\":\"John Doe\",\"age\":30}",
          "type": "text"
        }
      ],
      "type": "model_output"
    }
  ],
  "object": "interaction",
  "model": "gemini-2.5-flash"
}
"""

    /** `tool-call-step1.json`. */
    const val TOOL_CALL_STEP1_JSON: String = """{
  "id": "v1_ChdUMnNIYXVxU0lJX2lxdHNQX2FicXVBWRIXVDJzSGF1cVNJSV9pcXRzUF9hYnF1QVk",
  "status": "requires_action",
  "usage": {
    "total_tokens": 109,
    "total_input_tokens": 53,
    "input_tokens_by_modality": [
      {
        "modality": "text",
        "tokens": 53
      }
    ],
    "total_cached_tokens": 0,
    "total_output_tokens": 15,
    "total_tool_use_tokens": 0,
    "total_thought_tokens": 41
  },
  "created": "2026-05-15T18:52:00Z",
  "updated": "2026-05-15T18:52:00Z",
  "service_tier": "standard",
  "steps": [
    {
      "signature": "CtkBAQw51sduvOhi/4ERHaM4Pnc11QLqBpYXBj55LC+Gq9+/gkYFy1Vo2qWBLaCm64Snn6RkSxqeSLiz3aDeNbxSkcc8RQjxXL9CkvLk5HVXPd51mte2KjnT9F05LOKM9XZWgjivFDhW/Rc8C9Vt1hzC972uaN7acy7sgkbgaUNheVTrWbmEHI5wjzTZuBZvh1HHjZmyHftP31rEhPKsMEryLd0Kcd35sFISRq0c3RM7XaJf0TNzCfoqO6bm3aLrjqYkGO3Gk2cIdMXr0J3vm2Nm2/zbcKnSWGYifQ==",
      "type": "thought"
    },
    {
      "id": "zggxzq8r",
      "type": "function_call",
      "name": "getWeather",
      "arguments": {
        "location": "San Francisco"
      }
    }
  ],
  "object": "interaction",
  "model": "gemini-2.5-flash"
}
"""

    /** `tool-call-step1.chunks.txt`, one SSE payload per element. */
    val TOOL_CALL_STEP1_CHUNKS: List<String> = listOf(
        """{"interaction":{"id":"v1_ChdVbXNIYXVEUkVacmpxdHNQb3JQeXlBRRIXVW1zSGF1RFJFWnJqcXRzUG9yUHl5QUU","status":"in_progress","object":"interaction","model":"gemini-2.5-flash"},"event_type":"interaction.created"}""",
        """{"interaction_id":"v1_ChdVbXNIYXVEUkVacmpxdHNQb3JQeXlBRRIXVW1zSGF1RFJFWnJqcXRzUG9yUHl5QUU","status":"in_progress","event_type":"interaction.status_update"}""",
        """{"index":0,"step":{"type":"thought"},"event_type":"step.start"}""",
        """{"index":0,"delta":{"signature":"CiQBDDnWx+Xp0gYVp4DFf7zufCwAFeCgpkY+faWGOsR4MtgcquUKaAEMOdbHXVbxkBSTQkypbJ1bQZtWWnVUjKblZFAQLPjaHRid4KrReR5/R2AWjBJgLWs3wYF2bo3nfJUdz0y1AFrAHezMUFG3UdH69+UfTfVmiw7ISYrVEzUpGLW0V1CLlj7sIzap+o7jCvABAQw51seJ5cbYOObrS9Fl6UQu2vk9Vwq1nO6GiIkdPgrvuaJJNBlpUPw3ykmZJH6mJyyu4ho5+23dSosWdDMQKQ3e28VHCYkuHk43phE8KpSzfHwjPjuWgroAnLOadK9Kd4wHoW+nMs3EpWcG7pPyYdMv5FUYcX984Ts72+SOK3MC6bqAsbtX0vmdExzhZkKImEI7v2wkhaiXen/2DiKchLsw1OawCSTrxRDE4dxYym00S3ni8dPEUkILaya/HDHrpSsXRnZsqlc7KbyS9cW2DeJWkRVHJQK2+nIw1umTEgWAP3Zz6E8IF6m0y+1lwBzQ","type":"thought_signature"},"event_type":"step.delta"}""",
        """{"index":0,"event_type":"step.stop"}""",
        """{"index":1,"step":{"id":"61nzpsv4","signature":"","type":"function_call","name":"getWeather","arguments":{}},"event_type":"step.start"}""",
        """{"index":1,"delta":{"arguments":"{\"location\":\"San Francisco\"}","type":"arguments_delta"},"event_type":"step.delta"}""",
        """{"index":1,"event_type":"step.stop"}""",
        """{"interaction":{"id":"v1_ChdVbXNIYXVEUkVacmpxdHNQb3JQeXlBRRIXVW1zSGF1RFJFWnJqcXRzUG9yUHl5QUU","status":"requires_action","usage":{"total_tokens":133,"total_input_tokens":53,"input_tokens_by_modality":[{"modality":"text","tokens":53}],"total_cached_tokens":0,"total_output_tokens":15,"total_tool_use_tokens":0,"total_thought_tokens":65},"created":"2026-05-15T18:52:03Z","updated":"2026-05-15T18:52:03Z","service_tier":"standard","object":"interaction","model":"gemini-2.5-flash"},"event_type":"interaction.completed"}""",
    )

    /** `tool-call-step2.json`. */
    const val TOOL_CALL_STEP2_JSON: String = """{
  "id": "v1_ChdVR3NIYXVhR091S3NxdHNQdWI3b3NBWRIXVUdzSGF1YUdPdUtzcXRzUHViN29zQVk",
  "status": "completed",
  "usage": {
    "total_tokens": 111,
    "total_input_tokens": 95,
    "input_tokens_by_modality": [
      {
        "modality": "text",
        "tokens": 95
      }
    ],
    "total_cached_tokens": 0,
    "total_output_tokens": 16,
    "total_tool_use_tokens": 0,
    "total_thought_tokens": 0
  },
  "created": "2026-05-15T18:52:02Z",
  "updated": "2026-05-15T18:52:02Z",
  "service_tier": "standard",
  "steps": [
    {
      "signature": "CiRlMjQ4MzBhNy01Y2Q2LTQyZmUtOTk4Yi1lZTUzOWU3MmI5YzM=",
      "type": "thought"
    },
    {
      "content": [
        {
          "text": "The weather in San Francisco is sunny with a temperature of 8 degrees Celsius.",
          "type": "text"
        }
      ],
      "type": "model_output"
    }
  ],
  "object": "interaction",
  "model": "gemini-2.5-flash"
}
"""

    /** `tool-call-step2.chunks.txt`, one SSE payload per element. */
    val TOOL_CALL_STEP2_CHUNKS: List<String> = listOf(
        """{"interaction":{"id":"v1_ChZWR3NIYW9wMXJLYWEyUS1odWIyd0FREhZWR3NIYW9wMXJLYWEyUS1odWIyd0FR","status":"in_progress","object":"interaction","model":"gemini-2.5-flash"},"event_type":"interaction.created"}""",
        """{"interaction_id":"v1_ChZWR3NIYW9wMXJLYWEyUS1odWIyd0FREhZWR3NIYW9wMXJLYWEyUS1odWIyd0FR","status":"in_progress","event_type":"interaction.status_update"}""",
        """{"index":0,"step":{"type":"thought"},"event_type":"step.start"}""",
        """{"index":0,"delta":{"signature":"CiRlMjQ4MzBhNy01Y2Q2LTQyZmUtOTk4Yi1lZTUzOWU3MmI5YzM=","type":"thought_signature"},"event_type":"step.delta"}""",
        """{"index":0,"event_type":"step.stop"}""",
        """{"index":1,"step":{"type":"model_output"},"event_type":"step.start"}""",
        """{"index":1,"delta":{"text":"The weather in San","type":"text"},"event_type":"step.delta"}""",
        """{"index":1,"delta":{"text":" Francisco right now is sunny with a temperature of 27 degrees Celsius.","type":"text"},"event_type":"step.delta"}""",
        """{"index":1,"event_type":"step.stop"}""",
        """{"interaction":{"id":"v1_ChZWR3NIYW9wMXJLYWEyUS1odWIyd0FREhZWR3NIYW9wMXJLYWEyUS1odWIyd0FR","status":"completed","usage":{"total_tokens":180,"total_input_tokens":161,"input_tokens_by_modality":[{"modality":"text","tokens":161}],"total_cached_tokens":0,"total_output_tokens":19,"total_tool_use_tokens":0,"total_thought_tokens":0},"created":"2026-05-15T18:52:05Z","updated":"2026-05-15T18:52:05Z","service_tier":"standard","object":"interaction","model":"gemini-2.5-flash"},"event_type":"interaction.completed"}""",
    )

    /** `multi-turn-stateful-turn1.json`. */
    const val MULTI_TURN_STATEFUL_TURN1_JSON: String = """{
  "id": "v1_ChdWV3NIYXNYZEc5S19xdHNQcmVYeG1BRRIXVldzSGFzWGRHOUtfcXRzUHJlWHhtQUU",
  "status": "completed",
  "usage": {
    "total_tokens": 354,
    "total_input_tokens": 10,
    "input_tokens_by_modality": [
      {
        "modality": "text",
        "tokens": 10
      }
    ],
    "total_cached_tokens": 0,
    "total_output_tokens": 33,
    "total_tool_use_tokens": 0,
    "total_thought_tokens": 311
  },
  "created": "2026-05-15T18:52:08Z",
  "updated": "2026-05-15T18:52:08Z",
  "service_tier": "standard",
  "steps": [
    {
      "signature": "CqoJAQw51sc2bMhKBl/qCM3z+HFHx7pLOlZPIPkDjMX4WsnO4W+Z4GCgQTf0WP/4V/arsBaUmyMDpELUMQoL6b1saGrIgzPJg4Z+t7RClOOU7HSeRakWEOLrQEr000y6wT71SsMr09cpJa8epROwyHGwYij+hMZMPktpmqaxYKmihWRYhnwCk/sdjXyO9b25EbeOJQqkXGAuqWbiy0F6e8oJC22spT5SJRFdeyYsITGcvgDcdxvGOsSHqujQesTGulsIdVxQGv05iV+x96hAujiuiZnjqOnUbOoFu3KCUe9BKgMGHpb1E4sF0jVtnZ8casd2om86vg7yFfVdxikfRWmgLMkWap8BZlQ9wmDZ+L/U2gy47s+GJ6JgcBlMHjC1zJUIhbWDyNNm2ZJZq7/W98YQ/cWW66R4bTOQOtn9LWCoybjVq841j7P6egUpIm6kMqs0+HZiZAU/b27pnsUvDp1EIRGS3qGacFEV0IxFHXmiuMXdM2Mp1A/ohmWkP1mAIrPFAArzHmU1lowPA0wx8unbCKDR3aPRB1Ttsq6Kx1jc5+fT2Bf9w7UHzufy7JTIvbHQEOrnhnfdBGKXT9h3NdsnyHi4G4gLwOttrtqBHA1v0we7CDuyVmM2eXrleYcx0c2XJ7+T2nT6ISYZogXg49n3OQYKvQUPXSXFT3YTI0jliIXQ+J/gngtZi9rZLdrLOZi43Cyjovw6iHApOSp8KRsmrvNha9PwPm6v8mfgt+Ue5pGcW9NoZpY6fIxkTtF+SVsjAe0SmmrFgCHQnVuDND1QtrRKZZwg6qQje9Mm1KQhzS2C6ticvYejjgrdM1neJODgclrSlK1/T/1hA4ewNebYiP9niTffUVD5IBWkYke0bEKo52hniMn9UTsIViyd8Eo1m6sXSj2Yy3bNk5MxtxovYPIwxnmhUQOmJPZxbH4jRVU0XwFJ0A9wmSl82bIlyoGoW6TbI2q43WbKQW8snHP4vSldoIa1oZIFIDzka9tAqk7JIBcPBZ8zcETzzi2BN00LjBGZOMsXkEKEm6BsrS1lFnJapREyjPbB/AEkvYQXFRXt7w/jhvrrD3XqCakrcRHlrEb49O52zIjRnq08T9/H+yUlDXyMLUDm8mkzORTmrmF76sk3M7AiqqKK0IzOiYs7dZU3H7swasSk1QI9bVnZQkI8oI/W5v95ZDfc72h0lLd+qLuF5Euxq9ufP5S2y+Zy/OArVuuCoP5jO7juDKBuJauqhekn+NEVL6RG7XiJVp+LB7HeJ7vyVDS0FBwFJgOA9Z+FhajSAa32M7CirVTdyt1Q3OJS82/jRsfO+hcv2oZlMJNn0HF54ksRlqDe0ZDhZjn4KOlXGJYUVsohf5Yw+lWJnSplynbN+IEPMyfmVXbZMseNmoTlS8zLZzvPpvX8CjiSY4amIE0YSppJv3F6msjXWzkAEUT6dPWo9ExC/TuiK8B7a1J4WncN7M1BUYyWUuenzQcl+AUpVbAzuMiESn544NOpCehjNEPKDM+LXDo8bIJR1Xa4wv6Kx2PlBen3OGVLh0t/GtHoxHbIzODoPtu3KPSgMNKfbu+hEj7vzRSo9SGcxIs0QZQr",
      "type": "thought"
    },
    {
      "content": [
        {
          "text": "The three largest cities in Spain, by population, are:\n\n1.  **Madrid**\n2.  **Barcelona**\n3.  **Valencia**",
          "type": "text"
        }
      ],
      "type": "model_output"
    }
  ],
  "object": "interaction",
  "model": "gemini-2.5-flash"
}
"""

    /** `multi-turn-stateful-turn1.chunks.txt`, one SSE payload per element. */
    val MULTI_TURN_STATEFUL_TURN1_CHUNKS: List<String> = listOf(
        """{"interaction":{"id":"v1_ChdYR3NIYXB6dkJzTy1xdHNQcTR5RDZRVRIXWEdzSGFwenZCc08tcXRzUHE0eUQ2UVU","status":"in_progress","object":"interaction","model":"gemini-2.5-flash"},"event_type":"interaction.created"}""",
        """{"interaction_id":"v1_ChdYR3NIYXB6dkJzTy1xdHNQcTR5RDZRVRIXWEdzSGFwenZCc08tcXRzUHE0eUQ2UVU","status":"in_progress","event_type":"interaction.status_update"}""",
        """{"index":0,"step":{"type":"thought"},"event_type":"step.start"}""",
        """{"index":0,"delta":{"signature":"CiIBDDnWx/u1/022NQ/+63E/TFUvoYflMr8RHnB5PQHAWnFPCm4BDDnWxxwjTGpTlcpxPR2U5WElpmdIL5dD1R4eoqIe+cuM7jJjbUfcEEBODSyhvveA3WJDq2iuxff5stJeSlzVgLqUYOjPWjO3dNFxksy7tWYFInbzVeM/rXuVEoS/t3rnJCxp5c1q0cTai1koSgr+AQEMOdbHZWiaMtpUVtKIF2xvE1SduEpzWw8fR7MMNnBeqTxfCuUJF/5QgRfelmLS4Rywk0zxrUYHZ5ueXczlpFok14raJtz16js5Ys5j0RCrhrcPz3B7V2wKNHUmxNf+iWTmHl6tKB6HgR2n2EmK+Snpf9MJGseDZxOm3x/XdeHBgX8cuSlYykhxNSrldEhadDAj/j7YuvUi0WI3NNJSro6igLEHDNTkpFxLOu0hz1QKX2wUT5DiVeOUZPtjK+Eo5dadkBlmh4aTqZ4BCxCEdfVdT+uP5LQHKo9y+VRjCyHVqHdD9yc8vm7m4aac+rjY4+/T3FEg0qZwYUouKtjcCpwCAQw51scaPJnV/4uU4+QhzshsFGm2Dsqt3NNpIBg94Qb4g5RDPnNNfapCHIZFjKeqihhmBvQ3RX3+kjKTfDqImtqtRcBNLqBNN9kcjG+RpsmmZ9BtelfSdnrL6NX4SLTsh1mD6DhoR2VsgPMJI6JUhcXy9C0wt/HH7Z+Zwr5G8WFU3bFuwGiCPdfPhP0T/Cr8+SeoCD9q9L2Y441jgWnzUsTQht5X6GKJtVHvEfJJLRbr45eBkGTrpOKbzBiNklSf90fBfT7vnfUlFqNitQ74TaDsVPIAtxm01ZDOy7MJcZn+kZS7zuXHpKWswFyc999oT49GeNmRJpdpbHvJZAeDEVHXTVo9CLlLg/nSUwg9yxWXxBuankk+OqJXxnMKiAEBDDnWxxRLqSIgES8/I5Ot20EAmuS6ScjZjF8Mr0U0/U2Kd5e2CcjaGwJlQh1UwMMy7ISUFfnoTWgcDeHlc9CyqEDBAfVODKVMjoqioCOoUNl4KBavdSMIVf4XLx9IXsnYcp93xhLkO0LJHEOimdHFq1dj8xoemhcCz0wisKnfJ6qPq+FAlX/4","type":"thought_signature"},"event_type":"step.delta"}""",
        """{"index":0,"event_type":"step.stop"}""",
        """{"index":1,"step":{"type":"model_output"},"event_type":"step.start"}""",
        """{"index":1,"delta":{"text":"The three largest cities in Spain by population are:\n\n1.  **Madrid**\n2.  **Barcelona**\n3","type":"text"},"event_type":"step.delta"}""",
        """{"index":1,"delta":{"text":".  **Valencia**","type":"text"},"event_type":"step.delta"}""",
        """{"index":1,"event_type":"step.stop"}""",
        """{"interaction":{"id":"v1_ChdYR3NIYXB6dkJzTy1xdHNQcTR5RDZRVRIXWEdzSGFwenZCc08tcXRzUHE0eUQ2UVU","status":"completed","usage":{"total_tokens":178,"total_input_tokens":10,"input_tokens_by_modality":[{"modality":"text","tokens":10}],"total_cached_tokens":0,"total_output_tokens":31,"total_tool_use_tokens":0,"total_thought_tokens":137},"created":"2026-05-15T18:52:14Z","updated":"2026-05-15T18:52:14Z","service_tier":"standard","object":"interaction","model":"gemini-2.5-flash"},"event_type":"interaction.completed"}""",
    )

    /** `multi-turn-stateful-turn2.json`. */
    const val MULTI_TURN_STATEFUL_TURN2_JSON: String = """{
  "id": "v1_ChdWV3NIYXNYZEc5S19xdHNQcmVYeG1BRRIXV0dzSGFxTGVHbzZsbXRrUHA1ZkowUVU",
  "status": "completed",
  "usage": {
    "total_tokens": 519,
    "total_input_tokens": 12,
    "input_tokens_by_modality": [
      {
        "modality": "text",
        "tokens": 12
      }
    ],
    "total_cached_tokens": 0,
    "total_output_tokens": 43,
    "total_tool_use_tokens": 0,
    "total_thought_tokens": 464
  },
  "created": "2026-05-15T18:52:11Z",
  "updated": "2026-05-15T18:52:11Z",
  "service_tier": "standard",
  "steps": [
    {
      "signature": "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",
      "type": "thought"
    },
    {
      "content": [
        {
          "text": "The most famous landmark in Barcelona (the second city) is the **Sagrada Familia**.\n\nThis iconic, unfinished basilica, designed by Antoni Gaudí, is a UNESCO World Heritage Site and is instantly recognizable worldwide.",
          "type": "text"
        }
      ],
      "type": "model_output"
    }
  ],
  "object": "interaction",
  "model": "gemini-2.5-flash"
}
"""

    /** `multi-turn-stateful-turn2.chunks.txt`, one SSE payload per element. */
    val MULTI_TURN_STATEFUL_TURN2_CHUNKS: List<String> = listOf(
        """{"interaction":{"id":"v1_ChdYR3NIYXB6dkJzTy1xdHNQcTR5RDZRVRIXWG1zSGF0VG1KSml0cXRzUHpJZmd3QUU","status":"in_progress","object":"interaction","model":"gemini-2.5-flash"},"event_type":"interaction.created"}""",
        """{"interaction_id":"v1_ChdYR3NIYXB6dkJzTy1xdHNQcTR5RDZRVRIXWG1zSGF0VG1KSml0cXRzUHpJZmd3QUU","status":"in_progress","event_type":"interaction.status_update"}""",
        """{"index":0,"step":{"type":"thought"},"event_type":"step.start"}""",
        """{"index":0,"delta":{"signature":"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","type":"thought_signature"},"event_type":"step.delta"}""",
        """{"index":0,"event_type":"step.stop"}""",
        """{"index":1,"step":{"type":"model_output"},"event_type":"step.start"}""",
        """{"index":1,"delta":{"text":"The most famous landmark in **Barcelona** (the second largest city) is undoubtedly","type":"text"},"event_type":"step.delta"}""",
        """{"index":1,"delta":{"text":" the **Sagrada Familia**.\n\nIt's an iconic basilica designed by Antoni Gaudí, famous for its unique architecture and the fact that it has been under construction for over a century.","type":"text"},"event_type":"step.delta"}""",
        """{"index":1,"event_type":"step.stop"}""",
        """{"interaction":{"id":"v1_ChdYR3NIYXB6dkJzTy1xdHNQcTR5RDZRVRIXWG1zSGF0VG1KSml0cXRzUHpJZmd3QUU","status":"completed","usage":{"total_tokens":457,"total_input_tokens":12,"input_tokens_by_modality":[{"modality":"text","tokens":12}],"total_cached_tokens":0,"total_output_tokens":54,"total_tool_use_tokens":0,"total_thought_tokens":391},"created":"2026-05-15T18:52:17Z","updated":"2026-05-15T18:52:17Z","service_tier":"standard","object":"interaction","model":"gemini-2.5-flash"},"event_type":"interaction.completed"}""",
    )

    /** `multi-turn-stateless-turn1.json`. */
    const val MULTI_TURN_STATELESS_TURN1_JSON: String = """{
  "status": "completed",
  "usage": {
    "total_tokens": 319,
    "total_input_tokens": 10,
    "input_tokens_by_modality": [
      {
        "modality": "text",
        "tokens": 10
      }
    ],
    "total_cached_tokens": 0,
    "total_output_tokens": 33,
    "total_tool_use_tokens": 0,
    "total_thought_tokens": 276
  },
  "created": "2026-05-15T18:52:20Z",
  "updated": "2026-05-15T18:52:20Z",
  "service_tier": "standard",
  "steps": [
    {
      "signature": "CsEKAQw51sfpqF4Dh7j9oYuk8z+qpsZFzGSPsI7Fb3cXiaVNOLwP3Vef5i5UrARYVl745OfukzDB6Yg2IfGxKs9o2DR7Uq09COmNCTmSQCZjVkvgupIQi1A9eb7pefcRzcRUvA7amhlB9V6HfSoUnjAQ6NteqZr56bamOuEBEDqKqImngV/ShjoljA/EQYbB4DC3BdNtQUrxeXyD0aNI/AKR293JfVEpfSVO2xyZRoxazGbH3vxIDf1I3kTCS3k79f+aIINA0CoazA3t55ImDv/FA2balv1xoRyyZ3aSIWSRZrqrrIcC5Zbyi2tww+/D7bgcD8BRWyLZxS/oHxSo1U9tHI6xpX2K5sjMjYNq1ugVoBAZuzOyw8YB+6EsaJr+6giDzW67P+KkEco5z286K0jpDAvXJBzebMzJN0HbmjOK6C1jXDAKkiZHxhfn4ukpy5czgan35YPrmv6hDAF4snKCFPab9JHSoazHOTkopKkBDM8qH6wkzto9iwc3spWlKAra0MB2kAXeSfskPNRESxfPrlnGJNrPPJJVWQEP+hx01fekYIygvbElwYn+smYXo/biRabEDNcpRdY4VgqDplzPgEUIqQXAC7jXGAiRcNblekm+jXxodVEZInqOd2o4Z6fQ2dgd758vCANnTcKVTdV0KCy5epd0c0e9zlgGMti/L1skCBmXuadn/xDd9HFrhcNibmsIt0phRBfNH2DRZvYeERxKV96xcxgs0Zt6iukfDhru9mfFRczCiObUPdcpYJxYVTwV4JYpO+uGtV6dHfA+ZePOUk79Y4ajCmcmHjvSfip2nm2SvOTGGEINo390H68QElegbBA3VrVvPK+CXN8kjRr3yixv1fMeWwvrcxmQiPGeh6S0sGS1AwSsVwLthgC+ywk8ZMBW1fCWLnrkl41IYLs8UsdZZ4lKA83G35qOlJ01/4E602EE7fJArR8GYTawYrfcVEFgaBAy0lqK8gypKvNzPp33uBnug/5FKujRqcU+omg1L8/7oQuEz95ScKzchwp75BrIjhE5oHNZ+i+oVlWDWJMmP7Bemg8I2s2WGu0OM0Is9ReBxv/TZYjGp9GGimMGoYHi+H6EdOmaQc4wXAmQky48MUI+VPHnkphm/YaqONBRN2wmt8uw8Kl+xud+kO9gJaUSVJvEfW4yY/p4x71UrYJeJO9giAbs1f2qIqBuvFBC/T91uctvU8Vl20IJVRzBbfHCFLpIH/Vt23ZrD07baABdv+PD5cO7rI4yKrMttv78v7TqnNtGQORnxFOVLa0wtSI3on03aiuWyibAARdMB+Sr/Ukshr0RNN0P+ScmJEVg0fKyxFEFO/4AxQARo94MZjmWLx+L2JolQ0vKrkoY55keABvt0KKxdvqV59TcjweHWE0Xz8NiyQ+TJYtksQ7ShfJ72cZU96Id84VpCi3UEhl6ZlzSSyhhNsKVHBg7WUqJYFOhzgxlUEe17YZrREjuJf+u5P3RHz5sltte7OMDH7n+J20B7X7cwvT/JbcwIezfVS5G64Y7KciHKkHxGPQqqWpYtZY/jo07hqmMdvZaWKoN1DhcF5DE7mFqtxPpzIEwZhLS9AdAYtNFQQCSLVqHkKhWqCdhEfp2D80pJ/v6bqtptfX8ftJCLmb+N8MZbRcFGzjooSMaWgORAJ/NT97fw7Dje4UU1nYkoETk/WrCZfjmBn98wA1coS3+YSPWyiHDLF5sntgZhbnDldxIwSuE/KY2sI2HMHKUZuNo6Or/EoSvUAcTLCepoM8tGwZ5QvTbCpaw6AZjQ4OlydC71w==",
      "type": "thought"
    },
    {
      "content": [
        {
          "text": "The three largest cities in Spain, by population, are:\n\n1.  **Madrid**\n2.  **Barcelona**\n3.  **Valencia**",
          "type": "text"
        }
      ],
      "type": "model_output"
    }
  ],
  "object": "interaction",
  "model": "gemini-2.5-flash"
}
"""

    /** `multi-turn-stateless-turn1.chunks.txt`, one SSE payload per element. */
    val MULTI_TURN_STATELESS_TURN1_CHUNKS: List<String> = listOf(
        """{"interaction":{"id":"","status":"in_progress","object":"interaction","model":"gemini-2.5-flash"},"event_type":"interaction.created"}""",
        """{"interaction_id":"","status":"in_progress","event_type":"interaction.status_update"}""",
        """{"index":0,"step":{"type":"thought"},"event_type":"step.start"}""",
        """{"index":0,"delta":{"signature":"CiIBDDnWxy0RfY3UH8aiXVpdfyoNO/75pbEyVrPaKVW09y5vCnYBDDnWx4aQ0LIX5WA7oz+pOGGUTMSoxXvIXoO6DfNIqliKpJ3GCWNAW/v4TKWyUA5VvSvCVkZn3UaRLSnlHbX5NYAFxfZr9Bs2iSUaftWTzEbthgUBvURy4rs91FvhpbnukDC0tnicAzd6Jn3aPhsevzX0rJoHCvQBAQw51sfiZT6VOQYYrP/jdyjGP8DRnmtcucyE0GLj7hvSx+z9EZy//2ZezhH1HaXwezC+ZjaKSuBZ6H3bBvau+/DBj4/iLznLe/DHcyxTlnafo8nbX1YUv8/2yqH9ALl4H4PwGl7wkmELIN8Ju14gxKdnDllqxMkWMO0qdjYE4qvqsGNvfmWBzsIp76cU14fGS5+dv0VKOO21njq4oEkzyGslrzPIXD/3dd797x4sviKEwZTnOSF31eP7kQOcdxkLtX63a/oPSVHJt+KOnQshHHMS+FUFcE4ZsYGSUUp1uRxA8EhpjSPKZ9FPGWs1B/3uUZV2rgr5AQEMOdbHlH+AdeE77kdQ0eTVIepN2JPqWlZQqRrUYwboVBjss0TWjavEB0Xcgg3F4Gri1c0aXCOfUdNjXk2hDb0ib8YK+KBED9cPCLSXiELjpuJDZzbtYLJnGLxAoJSnzXFAG+y6nevjcaakfFC+cq1ThHYtWpvXg/hZHq8bcAgi0/Op3A+GlfFaz5MpgqBcZ2GadxP63H6FLqAXxm5cclgFNDFQLxej7TTbLNrSesL+z7R53GAIaQvNNQfP9IF9vu9k3wVew/FZxLe+VSWMeFU+J4i+ljYZCwB76PeB137HjVQGJQcGsGQIy9QJW+cIEqG6YWGkAUjq6QrnAQEMOdbHlnrRzNQqiWKZxfKJdBejvNQnlxN14gBuvcN+vnFZOrWBB+MYl4MlFw+Ufm9Ax7Ld7C/osSifsAGfsriv1n4yra4rERvPxY/uG4oqWlv9rgSs9y1IIqWGFbcgbaylNFaV70k49F7lUPNcBCtOJ0hNntHPW1chlpbSCu30r5VPhv1uIL+aM0WOliBrGt1DyGdlZOwqB8WSgiiqgmR3tNIHmNLvXaHLDuiNhhIrYbPFFIhx0a1HTyeHhh2clskfDgviNHZlx1ZIAm8B3ZRVctdoJAs4GAAcYiCPi+ocmyzdhp/cJgpHAQw51sdJzll/X/jTngNL4T3YT1B4Dt6etSK8zh8/HFhYsZSqzj7uhnQWeIOxq9ff4ThZcyTiUlRlRdo7xmNdcrrC/QsBntE=","type":"thought_signature"},"event_type":"step.delta"}""",
        """{"index":0,"event_type":"step.stop"}""",
        """{"index":1,"step":{"type":"model_output"},"event_type":"step.start"}""",
        """{"index":1,"delta":{"text":"The three largest cities in Spain are:\n\n1.  **Madrid**\n2.  **Barcelona**\n3.  **Valencia**","type":"text"},"event_type":"step.delta"}""",
        """{"index":1,"event_type":"step.stop"}""",
        """{"interaction":{"id":"","status":"completed","usage":{"total_tokens":213,"total_input_tokens":10,"input_tokens_by_modality":[{"modality":"text","tokens":10}],"total_cached_tokens":0,"total_output_tokens":29,"total_tool_use_tokens":0,"total_thought_tokens":174},"created":"2026-05-15T18:52:24Z","updated":"2026-05-15T18:52:24Z","service_tier":"standard","object":"interaction","model":"gemini-2.5-flash"},"event_type":"interaction.completed"}""",
    )

    /** `multi-turn-stateless-turn2.json`. */
    const val MULTI_TURN_STATELESS_TURN2_JSON: String = """{
  "status": "completed",
  "usage": {
    "total_tokens": 556,
    "total_input_tokens": 56,
    "input_tokens_by_modality": [
      {
        "modality": "text",
        "tokens": 56
      }
    ],
    "total_cached_tokens": 0,
    "total_output_tokens": 54,
    "total_tool_use_tokens": 0,
    "total_thought_tokens": 446
  },
  "created": "2026-05-15T18:52:23Z",
  "updated": "2026-05-15T18:52:23Z",
  "service_tier": "standard",
  "steps": [
    {
      "signature": "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",
      "type": "thought"
    },
    {
      "content": [
        {
          "text": "The most famous landmark in **Barcelona** is undoubtedly the **Sagrada Família**.\n\nIt's an unfinished Roman Catholic minor basilica designed by Catalan architect Antoni Gaudí, and it's an iconic symbol of the city known worldwide for its unique and intricate architecture.",
          "type": "text"
        }
      ],
      "type": "model_output"
    }
  ],
  "object": "interaction",
  "model": "gemini-2.5-flash"
}
"""

    /** `multi-turn-stateless-turn2.chunks.txt`, one SSE payload per element. */
    val MULTI_TURN_STATELESS_TURN2_CHUNKS: List<String> = listOf(
        """{"interaction":{"id":"","status":"in_progress","object":"interaction","model":"gemini-2.5-flash"},"event_type":"interaction.created"}""",
        """{"interaction_id":"","status":"in_progress","event_type":"interaction.status_update"}""",
        """{"index":0,"step":{"type":"thought"},"event_type":"step.start"}""",
        """{"index":0,"delta":{"signature":"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","type":"thought_signature"},"event_type":"step.delta"}""",
        """{"index":0,"event_type":"step.stop"}""",
        """{"index":1,"step":{"type":"model_output"},"event_type":"step.start"}""",
        """{"index":1,"delta":{"text":"The most famous landmark in **Barcelona** (the second largest","type":"text"},"event_type":"step.delta"}""",
        """{"index":1,"delta":{"text":" city) is undoubtedly the **Sagrada Familia**.","type":"text"},"event_type":"step.delta"}""",
        """{"index":1,"event_type":"step.stop"}""",
        """{"interaction":{"id":"","status":"completed","usage":{"total_tokens":469,"total_input_tokens":52,"input_tokens_by_modality":[{"modality":"text","tokens":52}],"total_cached_tokens":0,"total_output_tokens":22,"total_tool_use_tokens":0,"total_thought_tokens":395},"created":"2026-05-15T18:52:27Z","updated":"2026-05-15T18:52:27Z","service_tier":"standard","object":"interaction","model":"gemini-2.5-flash"},"event_type":"interaction.completed"}""",
    )

    /** `google-search.json`. */
    val GOOGLE_SEARCH_JSON: String = """{
  "id": "v1_ChdiR3NIYXFQU0JLdUxtdGtQblltMHlRRRIXYkdzSGFxUFNCS3VMbXRrUG5ZbTB5UUU",
  "status": "completed",
  "usage": {
    "total_tokens": 1373,
    "total_input_tokens": 22,
    "input_tokens_by_modality": [
      {
        "modality": "text",
        "tokens": 22
      }
    ],
    "total_cached_tokens": 0,
    "total_output_tokens": 973,
    "total_tool_use_tokens": 129,
    "tool_use_tokens_by_modality": [
      {
        "modality": "text",
        "tokens": 129
      }
    ],
    "total_thought_tokens": 249
  },
  "created": "2026-05-15T18:52:36Z",
  "updated": "2026-05-15T18:52:36Z",
  "service_tier": "standard",
  "steps": [
    {
      "signature": "CokCAQw51sftIEy4s58iGFq70v3sd8+gkyZ5Hf8ZimBLmaiE4Jhq+Kz1S4TFU6LDN5P2tPhb4rKI1ghCTZN0i5JBVKEOXl3Ro7yii6KlWNcdkybjYHF3r4BmQ9rK0zrlehjia6oB3pqPI3ZK2WlftqZCH+bgERgkVfD7icaaGGfxmsIEPsa+Fd141UOBiQFIt0cYiYiLaccT7qZPMcgamNo28cxkSZNxEKmq1ef1v91YbU+yDYrNxS3JhhsUwFY0hEal+lvcypzpoJXnA9as/8GvJF/8h3H35UGO9lUiULDHJmgMaw3YxeY+et6ssLZO2CEpNhJYgY/hQMy8u3/KGAQihQ8cJrLb4qn0Qwr+BQEMOdbHTpsqtxoAbaA5iymsg6aSJbXONkYKFN9suxHod899Jch01qLojB8H3eIfQxBznSmwMb78VX9PAWInw/mQmsbUhj+B45DVQhxTfFXFDelxIeNa7dtzF8HMsZDpVgfhsIkm1lApZtEFrwwmkoHmTDLeShumZuBw6RV4i2qlSQj7V/2bpUZIw+IC6K+Z0nD39J4oprFeC4Ti6Z5uKxry/gX212Rr0/ZMCopWA7R0IL9XR4Bnd+o2tp4XGkIqlMC7iIVht6/B6lMzp0RZWnzyaSqEQPFjZXEIGQ82lWyRsx+BhegOp8gYpWa+1rzEBQ3t9ghel0e16+8ZLsl8BRW/Iwn2iS33kooG+h2Y9ArNr/BA9qec46roFmh4GN2cf3rltz7lTx9QS48+ai45NxSHBVg7IUbgJ00A6ojJI7AOpKPsRU8qJEHX1pk+oswz3da11oWW11Mel2Ic2mVYZUa4HUmVOXNqJUQ81lUZ0brriHZjLC320j7Q710GsAm1dJX2Hq8a52tTc+3R9YOFvQhXr50OUgIpbOospi5j1ZrO6fsbXaY1EMG/QGqblIkMB0hgL1SEunQTjBG4cJs40uR7IAdLtu96dSMqtNL5hUupQzOOvWHh6v/GWuT85Sekrwbh8jnvGI6l1r7c8qT2gLwvsi2fArFerQxrRkfHXQqG+GL7likkUSKs0kqw8vayglUf/gYGXbGEonuE0CTJsj31JfGyntDXWc08qaR5guMH5VNgEYfAy0sa5QRrIadjT9dFdn9oaI91n+GvBtfH9kkSubk38A9Jnx7Q/gGH0uncD66hRoaCVRhrmb+4KqP79grZJnuoR4wka1NJAbY5BeKJr8DRWs4t5wUCNxi5iareHG9MDzVzUhuITXIwvRzPqj/qybyH9kIcP6n95To6x0BUJJAZitsOikfwM5fMDPvbDWlikme8ZP+7CgHLaQ2gItDF2MqAmszHsZ2WHus4kE8f8mKZfhY/sqEQtT0U/4QUy79KVHbynoI63u+Abog=",
      "type": "thought"
    },
    {
      "content": [
        {
          "text": "Here's a look at some notable AI developments from the past week:\n\n*   **OpenAI Launches Self-Serve Advertising Platform for ChatGPT** (May 8, 2026): OpenAI introduced a self-serve Ads Manager platform, allowing advertisers to create, manage, and optimize campaigns directly within ChatGPT. This move signifies a significant expansion of OpenAI's advertising ambitions, with reported revenue targets of ${'$'}2.5 billion this year and ${'$'}100 billion annually by 2030. The platform supports various buying models and integrates with major advertising and ad-tech firms.\n*   **Meta Reportedly Developing Advanced Agentic AI Assistant** (May 8, 2026): Meta is reportedly building a highly personalized AI assistant, powered by its Muse Spark AI model, designed to autonomously perform tasks for users across different software and hardware environments. This system, said to be inspired by OpenClaw, aims to operate with significantly less human intervention than existing chatbots. Meta is also testing an internal AI agent called \"Hatch\" and plans to integrate agentic shopping features into Instagram by the end of the year.\n*   **Apple Reportedly Planning Third-Party AI Model Integration for iOS** (May 8, 2026): Apple is reportedly preparing a significant AI platform shift that would allow users to select third-party AI providers, such as Google and Anthropic, to power Apple Intelligence features across iOS 27, iPadOS 27, and macOS 27.\n*   **Anthropic Forms ${'$'}1.5 Billion AI Deployment Venture** (May 8, 2026): Anthropic has launched a joint venture, backed by firms including Blackstone, Goldman Sachs, Hellman & Friedman, Apollo, and General Atlantic, to accelerate AI deployment across private equity portfolio companies.\n*   **Anthropic Unveils 'Dreaming' System for Self-Improving AI Agents** (May 8, 2026): Anthropic introduced a new AI agent technique called \"dreaming,\" which enables autonomous systems to review past behavior, identify patterns, and improve future performance between sessions.\n*   **OpenAI Launches Real-Time Voice and Translation Models** (May 8, 2026): OpenAI has rolled out three new real-time audio models designed for conversational AI agents, capable of handling live voice interactions, translations, and speech-to-text tasks.\n*   **Google Tests Remy Personal AI Agent for Gemini** (May 8, 2026): Google is reportedly testing a new personal AI agent named Remy within an internal version of Gemini. This system is designed to perform actions on users' behalf for both work and personal tasks, learning user preferences over time.\n*   **SAP Unveils the Autonomous Enterprise and Business AI Platform** (May 12-13, 2026): SAP announced the \"Autonomous Enterprise\" and unveiled a new Business AI Platform, aiming to embed AI into core business processes. At the 2026 SAP Sapphire Keynote on May 15, SAP highlighted how AI-powered agents are helping customers accelerate their digital transformation, with examples like Levi Strauss having over 1,000 AI agents in production.\n*   **Microsoft Agent 365 Goes GA; OpenAI Ships GPT-5.5 Instant** (May 8, 2026 - reported May 9, 2026): Microsoft Agent 365 reached General Availability, providing cross-cloud agent governance. OpenAI also shipped GPT-5.5 Instant, claiming a 50% reduction in hallucinations.\n*   **Anthropic and SpaceX Announce Colossus One** (May 8, 2026 - reported May 9, 2026): Anthropic partnered with SpaceX on \"Colossus One,\" a massive computing infrastructure project equivalent to bringing over 220,000 Nvidia GPUs online, aiming to address the constraint of electricity in AI development. Anthropic also doubled Claude Code rate limits on the same day.\n*   **Meta AI Unveils TRIBE v2 Brain Predictive Foundation Model** (March 26, 2026, with continued traction in May 2026): While unveiled earlier, Meta AI's TRIBE v2, a predictive foundation model designed as a \"digital twin of human neural activity,\" continued to gain traction. This model can forecast brain responses to complex stimuli like sights, sounds, and language.",
          "annotations": [
            {
              "start_index": 461,
              "end_index": 561,
              "url": "https://vertexaisearch.cloud.google.com/grounding-api-redirect/AUZIYQHVLORG1H1bfUwpfjVbPBps-RSHiQknJIMD9kxMxFHjD3sae384WENu2UjLnXQUNZfKuRuWNlzg3skE931IeFc9IXd3b0HiIX10HBJt34aIs1BqDLBjsbqNVXRtG_EVKqmls5wXYY7y6wIdiCaKkPpgD0q0D7S3VmBt9uvYrNAvp8EJnNVeEKXkFhWWTFYFwJVACW7KRvSBTghD8L0bv70NJdKPXCTBgA==",
              "title": "marketingprofs.com",
              "type": "url_citation"
            },
            {
              "start_index": 562,
              "end_index": 843,
              "url": "https://vertexaisearch.cloud.google.com/grounding-api-redirect/AUZIYQHVLORG1H1bfUwpfjVbPBps-RSHiQknJIMD9kxMxFHjD3sae384WENu2UjLnXQUNZfKuRuWNlzg3skE931IeFc9IXd3b0HiIX10HBJt34aIs1BqDLBjsbqNVXRtG_EVKqmls5wXYY7y6wIdiCaKkPpgD0q0D7S3VmBt9uvYrNAvp8EJnNVeEKXkFhWWTFYFwJVACW7KRvSBTghD8L0bv70NJdKPXCTBgA==",
              "title": "marketingprofs.com",
              "type": "url_citation"
            },
            {
              "start_index": 844,
              "end_index": 972,
              "url": "https://vertexaisearch.cloud.google.com/grounding-api-redirect/AUZIYQHVLORG1H1bfUwpfjVbPBps-RSHiQknJIMD9kxMxFHjD3sae384WENu2UjLnXQUNZfKuRuWNlzg3skE931IeFc9IXd3b0HiIX10HBJt34aIs1BqDLBjsbqNVXRtG_EVKqmls5wXYY7y6wIdiCaKkPpgD0q0D7S3VmBt9uvYrNAvp8EJnNVeEKXkFhWWTFYFwJVACW7KRvSBTghD8L0bv70NJdKPXCTBgA==",
              "title": "marketingprofs.com",
              "type": "url_citation"
            },
            {
              "start_index": 973,
              "end_index": 1117,
              "url": "https://vertexaisearch.cloud.google.com/grounding-api-redirect/AUZIYQHVLORG1H1bfUwpfjVbPBps-RSHiQknJIMD9kxMxFHjD3sae384WENu2UjLnXQUNZfKuRuWNlzg3skE931IeFc9IXd3b0HiIX10HBJt34aIs1BqDLBjsbqNVXRtG_EVKqmls5wXYY7y6wIdiCaKkPpgD0q0D7S3VmBt9uvYrNAvp8EJnNVeEKXkFhWWTFYFwJVACW7KRvSBTghD8L0bv70NJdKPXCTBgA==",
              "title": "marketingprofs.com",
              "type": "url_citation"
            },
            {
              "start_index": 1118,
              "end_index": 1435,
              "url": "https://vertexaisearch.cloud.google.com/grounding-api-redirect/AUZIYQHVLORG1H1bfUwpfjVbPBps-RSHiQknJIMD9kxMxFHjD3sae384WENu2UjLnXQUNZfKuRuWNlzg3skE931IeFc9IXd3b0HiIX10HBJt34aIs1BqDLBjsbqNVXRtG_EVKqmls5wXYY7y6wIdiCaKkPpgD0q0D7S3VmBt9uvYrNAvp8EJnNVeEKXkFhWWTFYFwJVACW7KRvSBTghD8L0bv70NJdKPXCTBgA==",
              "title": "marketingprofs.com",
              "type": "url_citation"
            },
            {
              "start_index": 1436,
              "end_index": 1723,
              "url": "https://vertexaisearch.cloud.google.com/grounding-api-redirect/AUZIYQHVLORG1H1bfUwpfjVbPBps-RSHiQknJIMD9kxMxFHjD3sae384WENu2UjLnXQUNZfKuRuWNlzg3skE931IeFc9IXd3b0HiIX10HBJt34aIs1BqDLBjsbqNVXRtG_EVKqmls5wXYY7y6wIdiCaKkPpgD0q0D7S3VmBt9uvYrNAvp8EJnNVeEKXkFhWWTFYFwJVACW7KRvSBTghD8L0bv70NJdKPXCTBgA==",
              "title": "marketingprofs.com",
              "type": "url_citation"
            },
            {
              "start_index": 1724,
              "end_index": 2002,
              "url": "https://vertexaisearch.cloud.google.com/grounding-api-redirect/AUZIYQHVLORG1H1bfUwpfjVbPBps-RSHiQknJIMD9kxMxFHjD3sae384WENu2UjLnXQUNZfKuRuWNlzg3skE931IeFc9IXd3b0HiIX10HBJt34aIs1BqDLBjsbqNVXRtG_EVKqmls5wXYY7y6wIdiCaKkPpgD0q0D7S3VmBt9uvYrNAvp8EJnNVeEKXkFhWWTFYFwJVACW7KRvSBTghD8L0bv70NJdKPXCTBgA==",
              "title": "marketingprofs.com",
              "type": "url_citation"
            },
            {
              "start_index": 2003,
              "end_index": 2259,
              "url": "https://vertexaisearch.cloud.google.com/grounding-api-redirect/AUZIYQHVLORG1H1bfUwpfjVbPBps-RSHiQknJIMD9kxMxFHjD3sae384WENu2UjLnXQUNZfKuRuWNlzg3skE931IeFc9IXd3b0HiIX10HBJt34aIs1BqDLBjsbqNVXRtG_EVKqmls5wXYY7y6wIdiCaKkPpgD0q0D7S3VmBt9uvYrNAvp8EJnNVeEKXkFhWWTFYFwJVACW7KRvSBTghD8L0bv70NJdKPXCTBgA==",
              "title": "marketingprofs.com",
              "type": "url_citation"
            },
            {
              "start_index": 2260,
              "end_index": 2431,
              "url": "https://vertexaisearch.cloud.google.com/grounding-api-redirect/AUZIYQHVLORG1H1bfUwpfjVbPBps-RSHiQknJIMD9kxMxFHjD3sae384WENu2UjLnXQUNZfKuRuWNlzg3skE931IeFc9IXd3b0HiIX10HBJt34aIs1BqDLBjsbqNVXRtG_EVKqmls5wXYY7y6wIdiCaKkPpgD0q0D7S3VmBt9uvYrNAvp8EJnNVeEKXkFhWWTFYFwJVACW7KRvSBTghD8L0bv70NJdKPXCTBgA==",
              "title": "marketingprofs.com",
              "type": "url_citation"
            },
            {
              "start_index": 2432,
              "end_index": 2562,
              "url": "https://vertexaisearch.cloud.google.com/grounding-api-redirect/AUZIYQHVLORG1H1bfUwpfjVbPBps-RSHiQknJIMD9kxMxFHjD3sae384WENu2UjLnXQUNZfKuRuWNlzg3skE931IeFc9IXd3b0HiIX10HBJt34aIs1BqDLBjsbqNVXRtG_EVKqmls5wXYY7y6wIdiCaKkPpgD0q0D7S3VmBt9uvYrNAvp8EJnNVeEKXkFhWWTFYFwJVACW7KRvSBTghD8L0bv70NJdKPXCTBgA==",
              "title": "marketingprofs.com",
              "type": "url_citation"
            },
            {
              "start_index": 2563,
              "end_index": 2784,
              "url": "https://vertexaisearch.cloud.google.com/grounding-api-redirect/AUZIYQGtjT3kkLq9AbxFfPGw2pZT3JG6eqQo0qLByTT8Y0ngE0zSitOgAeI5T_itix4qlrwo2IQSCPFF3zg0YRl4yf0F0LaCO49DYN8e5SGwRm-Ewvjqqwr6kuEE5W1K3mz6G3lpvGjzsqAyYlsSnLxy5Cnt1UTxUecA5T_lLHPkjjC6k3igUGQESiGb50CLWi6wYNv-wQ==",
              "title": "sap.com",
              "type": "url_citation"
            },
            {
              "start_index": 2785,
              "end_index": 3004,
              "url": "https://vertexaisearch.cloud.google.com/grounding-api-redirect/AUZIYQGtjT3kkLq9AbxFfPGw2pZT3JG6eqQo0qLByTT8Y0ngE0zSitOgAeI5T_itix4qlrwo2IQSCPFF3zg0YRl4yf0F0LaCO49DYN8e5SGwRm-Ewvjqqwr6kuEE5W1K3mz6G3lpvGjzsqAyYlsSnLxy5Cnt1UTxUecA5T_lLHPkjjC6k3igUGQESiGb50CLWi6wYNv-wQ==",
              "title": "sap.com",
              "type": "url_citation"
            },
            {
              "start_index": 3005,
              "end_index": 3198,
              "url": "https://vertexaisearch.cloud.google.com/grounding-api-redirect/AUZIYQERkypNaBgFczDkBknqsO5pJgoXvwTHVdxiIJtRqmuRlA9JQxF2f_0AIdfaSDKLYx1BdlOrqhQxeeSkLYfD5LMs55XHZ8JJ1p-coOzopK3BeeejruwCKXI8xWOWbHFjwpNLiIlIJlo=",
              "title": "youtube.com",
              "type": "url_citation"
            },
            {
              "start_index": 3199,
              "end_index": 3279,
              "url": "https://vertexaisearch.cloud.google.com/grounding-api-redirect/AUZIYQERkypNaBgFczDkBknqsO5pJgoXvwTHVdxiIJtRqmuRlA9JQxF2f_0AIdfaSDKLYx1BdlOrqhQxeeSkLYfD5LMs55XHZ8JJ1p-coOzopK3BeeejruwCKXI8xWOWbHFjwpNLiIlIJlo=",
              "title": "youtube.com",
              "type": "url_citation"
            },
            {
              "start_index": 3280,
              "end_index": 3585,
              "url": "https://vertexaisearch.cloud.google.com/grounding-api-redirect/AUZIYQERkypNaBgFczDkBknqsO5pJgoXvwTHVdxiIJtRqmuRlA9JQxF2f_0AIdfaSDKLYx1BdlOrqhQxeeSkLYfD5LMs55XHZ8JJ1p-coOzopK3BeeejruwCKXI8xWOWbHFjwpNLiIlIJlo=",
              "title": "youtube.com",
              "type": "url_citation"
            },
            {
              "start_index": 3586,
              "end_index": 3649,
              "url": "https://vertexaisearch.cloud.google.com/grounding-api-redirect/AUZIYQERkypNaBgFczDkBknqsO5pJgoXvwTHVdxiIJtRqmuRlA9JQxF2f_0AIdfaSDKLYx1BdlOrqhQxeeSkLYfD5LMs55XHZ8JJ1p-coOzopK3BeeejruwCKXI8xWOWbHFjwpNLiIlIJlo=",
              "title": "youtube.com",
              "type": "url_citation"
            },
            {
              "start_index": 3650,
              "end_index": 3928,
              "url": "https://vertexaisearch.cloud.google.com/grounding-api-redirect/AUZIYQEcN03LQuDddOTTfQ-1mF5PLHJztkm0QJsaNAt4zmN494rK28A1pmwb4Bzbhi5oojmrh47neYbOLLSz9ofhZ-Qky0Ny-1qL8BVnTlRfW4FhGC_PTskDOo37jKRS309mo579e9lfzJsXzQ0EoA2CosgeJxslEYTUtEQc7Pa_LYom4ZsmZ7hUuBUCCidRG9xHyEEVul68p5zuTIeyY8gVFJaR-nr9_RPtbYWH",
              "title": "etcjournal.com",
              "type": "url_citation"
            },
            {
              "start_index": 3929,
              "end_index": 4022,
              "url": "https://vertexaisearch.cloud.google.com/grounding-api-redirect/AUZIYQEcN03LQuDddOTTfQ-1mF5PLHJztkm0QJsaNAt4zmN494rK28A1pmwb4Bzbhi5oojmrh47neYbOLLSz9ofhZ-Qky0Ny-1qL8BVnTlRfW4FhGC_PTskDOo37jKRS309mo579e9lfzJsXzQ0EoA2CosgeJxslEYTUtEQc7Pa_LYom4ZsmZ7hUuBUCCidRG9xHyEEVul68p5zuTIeyY8gVFJaR-nr9_RPtbYWH",
              "title": "etcjournal.com",
              "type": "url_citation"
            }
          ],
          "type": "text"
        }
      ],
      "type": "model_output"
    },
    {
      "id": "3tz1p6wn",
      "type": "google_search_call",
      "arguments": {
        "queries": [
          "notable AI developments May 8-15 2026",
          "AI news this week May 2026"
        ]
      },
      "search_type": "web_search"
    },
    {
      "call_id": "3tz1p6wn",
      "type": "google_search_result",
      "result": [
        {
          "search_suggestions": "<style>\n.container {\n  align-items: center;\n  border-radius: 8px;\n  display: flex;\n  font-family: Google Sans, Roboto, sans-serif;\n  font-size: 14px;\n  line-height: 20px;\n  padding: 8px 12px;\n}\n.chip {\n  display: inline-block;\n  border: solid 1px;\n  border-radius: 16px;\n  min-width: 14px;\n  padding: 5px 16px;\n  text-align: center;\n  user-select: none;\n  margin: 0 8px;\n  -webkit-tap-highlight-color: transparent;\n}\n.carousel {\n  overflow: auto;\n  scrollbar-width: none;\n  white-space: nowrap;\n  margin-right: -12px;\n}\n.headline {\n  display: flex;\n  margin-right: 4px;\n}\n.gradient-container {\n  position: relative;\n}\n.gradient {\n  position: absolute;\n  transform: translate(3px, -9px);\n  height: 36px;\n  width: 9px;\n}\n@media (prefers-color-scheme: light) {\n  .container {\n    background-color: #fafafa;\n    box-shadow: 0 0 0 1px #0000000f;\n  }\n  .headline-label {\n    color: #1f1f1f;\n  }\n  .chip {\n    background-color: #ffffff;\n    border-color: #d2d2d2;\n    color: #5e5e5e;\n    text-decoration: none;\n  }\n  .chip:hover {\n    background-color: #f2f2f2;\n  }\n  .chip:focus {\n    background-color: #f2f2f2;\n  }\n  .chip:active {\n    background-color: #d8d8d8;\n    border-color: #b6b6b6;\n  }\n  .logo-dark {\n    display: none;\n  }\n  .gradient {\n    background: linear-gradient(90deg, #fafafa 15%, #fafafa00 100%);\n  }\n}\n@media (prefers-color-scheme: dark) {\n  .container {\n    background-color: #1f1f1f;\n    box-shadow: 0 0 0 1px #ffffff26;\n  }\n  .headline-label {\n    color: #fff;\n  }\n  .chip {\n    background-color: #2c2c2c;\n    border-color: #3c4043;\n    color: #fff;\n    text-decoration: none;\n  }\n  .chip:hover {\n    background-color: #353536;\n  }\n  .chip:focus {\n    background-color: #353536;\n  }\n  .chip:active {\n    background-color: #464849;\n    border-color: #53575b;\n  }\n  .logo-light {\n    display: none;\n  }\n  .gradient {\n    background: linear-gradient(90deg, #1f1f1f 15%, #1f1f1f00 100%);\n  }\n}\n</style>\n<div class=\"container\">\n  <div class=\"headline\">\n    <svg class=\"logo-light\" width=\"18\" height=\"18\" viewBox=\"9 9 35 35\" fill=\"none\" xmlns=\"http://www.w3.org/2000/svg\">\n      <path fill-rule=\"evenodd\" clip-rule=\"evenodd\" d=\"M42.8622 27.0064C42.8622 25.7839 42.7525 24.6084 42.5487 23.4799H26.3109V30.1568H35.5897C35.1821 32.3041 33.9596 34.1222 32.1258 35.3448V39.6864H37.7213C40.9814 36.677 42.8622 32.2571 42.8622 27.0064V27.0064Z\" fill=\"#4285F4\"/>\n      <path fill-rule=\"evenodd\" clip-rule=\"evenodd\" d=\"M26.3109 43.8555C30.9659 43.8555 34.8687 42.3195 37.7213 39.6863L32.1258 35.3447C30.5898 36.3792 28.6306 37.0061 26.3109 37.0061C21.8282 37.0061 18.0195 33.9811 16.6559 29.906H10.9194V34.3573C13.7563 39.9841 19.5712 43.8555 26.3109 43.8555V43.8555Z\" fill=\"#34A853\"/>\n      <path fill-rule=\"evenodd\" clip-rule=\"evenodd\" d=\"M16.6559 29.8904C16.3111 28.8559 16.1074 27.7588 16.1074 26.6146C16.1074 25.4704 16.3111 24.3733 16.6559 23.3388V18.8875H10.9194C9.74388 21.2072 9.06992 23.8247 9.06992 26.6146C9.06992 29.4045 9.74388 32.022 10.9194 34.3417L15.3864 30.8621L16.6559 29.8904V29.8904Z\" fill=\"#FBBC05\"/>\n      <path fill-rule=\"evenodd\" clip-rule=\"evenodd\" d=\"M26.3109 16.2386C28.85 16.2386 31.107 17.1164 32.9095 18.8091L37.8466 13.8719C34.853 11.082 30.9659 9.3736 26.3109 9.3736C19.5712 9.3736 13.7563 13.245 10.9194 18.8875L16.6559 23.3388C18.0195 19.2636 21.8282 16.2386 26.3109 16.2386V16.2386Z\" fill=\"#EA4335\"/>\n    </svg>\n    <svg class=\"logo-dark\" width=\"18\" height=\"18\" viewBox=\"0 0 48 48\" xmlns=\"http://www.w3.org/2000/svg\">\n      <circle cx=\"24\" cy=\"23\" fill=\"#FFF\" r=\"22\"/>\n      <path d=\"M33.76 34.26c2.75-2.56 4.49-6.37 4.49-11.26 0-.89-.08-1.84-.29-3H24.01v5.99h8.03c-.4 2.02-1.5 3.56-3.07 4.56v.75l3.91 2.97h.88z\" fill=\"#4285F4\"/>\n      <path d=\"M15.58 25.77A8.845 8.845 0 0 0 24 31.86c1.92 0 3.62-.46 4.97-1.31l4.79 3.71C31.14 36.7 27.65 38 24 38c-5.93 0-11.01-3.4-13.45-8.36l.17-1.01 4.06-2.85h.8z\" fill=\"#34A853\"/>\n      <path d=\"M15.59 20.21a8.864 8.864 0 0 0 0 5.58l-5.03 3.86c-.98-2-1.53-4.25-1.53-6.64 0-2.39.55-4.64 1.53-6.64l1-.22 3.81 2.98.22 1.08z\" fill=\"#FBBC05\"/>\n      <path d=\"M24 14.14c2.11 0 4.02.75 5.52 1.98l4.36-4.36C31.22 9.43 27.81 8 24 8c-5.93 0-11.01 3.4-13.45 8.36l5.03 3.85A8.86 8.86 0 0 1 24 14.14z\" fill=\"#EA4335\"/>\n    </svg>\n    <div class=\"gradient-container\"><div class=\"gradient\"></div></div>\n  </div>\n  <div class=\"carousel\">\n    <a class=\"chip\" href=\"https://vertexaisearch.cloud.google.com/grounding-api-redirect/AUZIYQFFx9KZU7Oa1gZUC--MjMR3_QCAmo1BT0jSI3DMpV_ZJwnrWQIkPGjxYH0nzZ-DCkHzxwZulViyuddyo58dTnUOexbhhKwxBzcQqaT4h5ihG9l7mTKew4PsON0yGIwR440e-mRjuI82GLjEIGtz69SnDvJFBShzkdPoPEsItDPHMZjj2jEqJNSscxuxDmBvgx0s9Okl6n8I8p2Yo_dc\">AI news this week May 2026</a>\n    <a class=\"chip\" href=\"https://vertexaisearch.cloud.google.com/grounding-api-redirect/AUZIYQH6u3XYQ3OkYPHN9pstt1XvXkTOOggZP46PN8d7cV_9GyKZ8bP0sqbg80oaVe4ELCTvHJRsVJic1FSALDiqamqJ16pAP45ihIm08ZOHiQowuhrNxkTqHtKHsqrY6sAXwhbLbxN9L7y9z4eZpiK1k8W_hOSxwrGn0IVelwpEhLXIgH6dGQjgPFF_GBCTk3TBTjR3-JoJKMt-eToGJD9ml8-XxerJJ4p-DLI=\">notable AI developments May 8-15 2026</a>\n  </div>\n</div>\n"
        },
        {
          "search_suggestions": "<style>\n.container {\n  align-items: center;\n  border-radius: 8px;\n  display: flex;\n  font-family: Google Sans, Roboto, sans-serif;\n  font-size: 14px;\n  line-height: 20px;\n  padding: 8px 12px;\n}\n.chip {\n  display: inline-block;\n  border: solid 1px;\n  border-radius: 16px;\n  min-width: 14px;\n  padding: 5px 16px;\n  text-align: center;\n  user-select: none;\n  margin: 0 8px;\n  -webkit-tap-highlight-color: transparent;\n}\n.carousel {\n  overflow: auto;\n  scrollbar-width: none;\n  white-space: nowrap;\n  margin-right: -12px;\n}\n.headline {\n  display: flex;\n  margin-right: 4px;\n}\n.gradient-container {\n  position: relative;\n}\n.gradient {\n  position: absolute;\n  transform: translate(3px, -9px);\n  height: 36px;\n  width: 9px;\n}\n@media (prefers-color-scheme: light) {\n  .container {\n    background-color: #fafafa;\n    box-shadow: 0 0 0 1px #0000000f;\n  }\n  .headline-label {\n    color: #1f1f1f;\n  }\n  .chip {\n    background-color: #ffffff;\n    border-color: #d2d2d2;\n    color: #5e5e5e;\n    text-decoration: none;\n  }\n  .chip:hover {\n    background-color: #f2f2f2;\n  }\n  .chip:focus {\n    background-color: #f2f2f2;\n  }\n  .chip:active {\n    background-color: #d8d8d8;\n    border-color: #b6b6b6;\n  }\n  .logo-dark {\n    display: none;\n  }\n  .gradient {\n    background: linear-gradient(90deg, #fafafa 15%, #fafafa00 100%);\n  }\n}\n@media (prefers-color-scheme: dark) {\n  .container {\n    background-color: #1f1f1f;\n    box-shadow: 0 0 0 1px #ffffff26;\n  }\n  .headline-label {\n    color: #fff;\n  }\n  .chip {\n    background-color: #2c2c2c;\n    border-color: #3c4043;\n    color: #fff;\n    text-decoration: none;\n  }\n  .chip:hover {\n    background-color: #353536;\n  }\n  .chip:focus {\n    background-color: #353536;\n  }\n  .chip:active {\n    background-color: #464849;\n    border-color: #53575b;\n  }\n  .logo-light {\n    display: none;\n  }\n  .gradient {\n    background: linear-gradient(90deg, #1f1f1f 15%, #1f1f1f00 100%);\n  }\n}\n</style>\n<div class=\"container\">\n  <div class=\"headline\">\n    <svg class=\"logo-light\" width=\"18\" height=\"18\" viewBox=\"9 9 35 35\" fill=\"none\" xmlns=\"http://www.w3.org/2000/svg\">\n      <path fill-rule=\"evenodd\" clip-rule=\"evenodd\" d=\"M42.8622 27.0064C42.8622 25.7839 42.7525 24.6084 42.5487 23.4799H26.3109V30.1568H35.5897C35.1821 32.3041 33.9596 34.1222 32.1258 35.3448V39.6864H37.7213C40.9814 36.677 42.8622 32.2571 42.8622 27.0064V27.0064Z\" fill=\"#4285F4\"/>\n      <path fill-rule=\"evenodd\" clip-rule=\"evenodd\" d=\"M26.3109 43.8555C30.9659 43.8555 34.8687 42.3195 37.7213 39.6863L32.1258 35.3447C30.5898 36.3792 28.6306 37.0061 26.3109 37.0061C21.8282 37.0061 18.0195 33.9811 16.6559 29.906H10.9194V34.3573C13.7563 39.9841 19.5712 43.8555 26.3109 43.8555V43.8555Z\" fill=\"#34A853\"/>\n      <path fill-rule=\"evenodd\" clip-rule=\"evenodd\" d=\"M16.6559 29.8904C16.3111 28.8559 16.1074 27.7588 16.1074 26.6146C16.1074 25.4704 16.3111 24.3733 16.6559 23.3388V18.8875H10.9194C9.74388 21.2072 9.06992 23.8247 9.06992 26.6146C9.06992 29.4045 9.74388 32.022 10.9194 34.3417L15.3864 30.8621L16.6559 29.8904V29.8904Z\" fill=\"#FBBC05\"/>\n      <path fill-rule=\"evenodd\" clip-rule=\"evenodd\" d=\"M26.3109 16.2386C28.85 16.2386 31.107 17.1164 32.9095 18.8091L37.8466 13.8719C34.853 11.082 30.9659 9.3736 26.3109 9.3736C19.5712 9.3736 13.7563 13.245 10.9194 18.8875L16.6559 23.3388C18.0195 19.2636 21.8282 16.2386 26.3109 16.2386V16.2386Z\" fill=\"#EA4335\"/>\n    </svg>\n    <svg class=\"logo-dark\" width=\"18\" height=\"18\" viewBox=\"0 0 48 48\" xmlns=\"http://www.w3.org/2000/svg\">\n      <circle cx=\"24\" cy=\"23\" fill=\"#FFF\" r=\"22\"/>\n      <path d=\"M33.76 34.26c2.75-2.56 4.49-6.37 4.49-11.26 0-.89-.08-1.84-.29-3H24.01v5.99h8.03c-.4 2.02-1.5 3.56-3.07 4.56v.75l3.91 2.97h.88z\" fill=\"#4285F4\"/>\n      <path d=\"M15.58 25.77A8.845 8.845 0 0 0 24 31.86c1.92 0 3.62-.46 4.97-1.31l4.79 3.71C31.14 36.7 27.65 38 24 38c-5.93 0-11.01-3.4-13.45-8.36l.17-1.01 4.06-2.85h.8z\" fill=\"#34A853\"/>\n      <path d=\"M15.59 20.21a8.864 8.864 0 0 0 0 5.58l-5.03 3.86c-.98-2-1.53-4.25-1.53-6.64 0-2.39.55-4.64 1.53-6.64l1-.22 3.81 2.98.22 1.08z\" fill=\"#FBBC05\"/>\n      <path d=\"M24 14.14c2.11 0 4.02.75 5.52 1.98l4.36-4.36C31.22 9.43 27.81 8 24 8c-5.93 0-11.01 3.4-13.45 8.36l5.03 3.85A8.86 8.86 0 0 1 24 14.14z\" fill=\"#EA4335\"/>\n    </svg>\n    <div class=\"gradient-container\"><div class=\"gradient\"></div></div>\n  </div>\n  <div class=\"carousel\">\n    <a class=\"chip\" href=\"https://vertexaisearch.cloud.google.com/grounding-api-redirect/AUZIYQFFx9KZU7Oa1gZUC--MjMR3_QCAmo1BT0jSI3DMpV_ZJwnrWQIkPGjxYH0nzZ-DCkHzxwZulViyuddyo58dTnUOexbhhKwxBzcQqaT4h5ihG9l7mTKew4PsON0yGIwR440e-mRjuI82GLjEIGtz69SnDvJFBShzkdPoPEsItDPHMZjj2jEqJNSscxuxDmBvgx0s9Okl6n8I8p2Yo_dc\">AI news this week May 2026</a>\n    <a class=\"chip\" href=\"https://vertexaisearch.cloud.google.com/grounding-api-redirect/AUZIYQH6u3XYQ3OkYPHN9pstt1XvXkTOOggZP46PN8d7cV_9GyKZ8bP0sqbg80oaVe4ELCTvHJRsVJic1FSALDiqamqJ16pAP45ihIm08ZOHiQowuhrNxkTqHtKHsqrY6sAXwhbLbxN9L7y9z4eZpiK1k8W_hOSxwrGn0IVelwpEhLXIgH6dGQjgPFF_GBCTk3TBTjR3-JoJKMt-eToGJD9ml8-XxerJJ4p-DLI=\">notable AI developments May 8-15 2026</a>\n  </div>\n</div>\n"
        },
        {
          "search_suggestions": "<style>\n.container {\n  align-items: center;\n  border-radius: 8px;\n  display: flex;\n  font-family: Google Sans, Roboto, sans-serif;\n  font-size: 14px;\n  line-height: 20px;\n  padding: 8px 12px;\n}\n.chip {\n  display: inline-block;\n  border: solid 1px;\n  border-radius: 16px;\n  min-width: 14px;\n  padding: 5px 16px;\n  text-align: center;\n  user-select: none;\n  margin: 0 8px;\n  -webkit-tap-highlight-color: transparent;\n}\n.carousel {\n  overflow: auto;\n  scrollbar-width: none;\n  white-space: nowrap;\n  margin-right: -12px;\n}\n.headline {\n  display: flex;\n  margin-right: 4px;\n}\n.gradient-container {\n  position: relative;\n}\n.gradient {\n  position: absolute;\n  transform: translate(3px, -9px);\n  height: 36px;\n  width: 9px;\n}\n@media (prefers-color-scheme: light) {\n  .container {\n    background-color: #fafafa;\n    box-shadow: 0 0 0 1px #0000000f;\n  }\n  .headline-label {\n    color: #1f1f1f;\n  }\n  .chip {\n    background-color: #ffffff;\n    border-color: #d2d2d2;\n    color: #5e5e5e;\n    text-decoration: none;\n  }\n  .chip:hover {\n    background-color: #f2f2f2;\n  }\n  .chip:focus {\n    background-color: #f2f2f2;\n  }\n  .chip:active {\n    background-color: #d8d8d8;\n    border-color: #b6b6b6;\n  }\n  .logo-dark {\n    display: none;\n  }\n  .gradient {\n    background: linear-gradient(90deg, #fafafa 15%, #fafafa00 100%);\n  }\n}\n@media (prefers-color-scheme: dark) {\n  .container {\n    background-color: #1f1f1f;\n    box-shadow: 0 0 0 1px #ffffff26;\n  }\n  .headline-label {\n    color: #fff;\n  }\n  .chip {\n    background-color: #2c2c2c;\n    border-color: #3c4043;\n    color: #fff;\n    text-decoration: none;\n  }\n  .chip:hover {\n    background-color: #353536;\n  }\n  .chip:focus {\n    background-color: #353536;\n  }\n  .chip:active {\n    background-color: #464849;\n    border-color: #53575b;\n  }\n  .logo-light {\n    display: none;\n  }\n  .gradient {\n    background: linear-gradient(90deg, #1f1f1f 15%, #1f1f1f00 100%);\n  }\n}\n</style>\n<div class=\"container\">\n  <div class=\"headline\">\n    <svg class=\"logo-light\" width=\"18\" height=\"18\" viewBox=\"9 9 35 35\" fill=\"none\" xmlns=\"http://www.w3.org/2000/svg\">\n      <path fill-rule=\"evenodd\" clip-rule=\"evenodd\" d=\"M42.8622 27.0064C42.8622 25.7839 42.7525 24.6084 42.5487 23.4799H26.3109V30.1568H35.5897C35.1821 32.3041 33.9596 34.1222 32.1258 35.3448V39.6864H37.7213C40.9814 36.677 42.8622 32.2571 42.8622 27.0064V27.0064Z\" fill=\"#4285F4\"/>\n      <path fill-rule=\"evenodd\" clip-rule=\"evenodd\" d=\"M26.3109 43.8555C30.9659 43.8555 34.8687 42.3195 37.7213 39.6863L32.1258 35.3447C30.5898 36.3792 28.6306 37.0061 26.3109 37.0061C21.8282 37.0061 18.0195 33.9811 16.6559 29.906H10.9194V34.3573C13.7563 39.9841 19.5712 43.8555 26.3109 43.8555V43.8555Z\" fill=\"#34A853\"/>\n      <path fill-rule=\"evenodd\" clip-rule=\"evenodd\" d=\"M16.6559 29.8904C16.3111 28.8559 16.1074 27.7588 16.1074 26.6146C16.1074 25.4704 16.3111 24.3733 16.6559 23.3388V18.8875H10.9194C9.74388 21.2072 9.06992 23.8247 9.06992 26.6146C9.06992 29.4045 9.74388 32.022 10.9194 34.3417L15.3864 30.8621L16.6559 29.8904V29.8904Z\" fill=\"#FBBC05\"/>\n      <path fill-rule=\"evenodd\" clip-rule=\"evenodd\" d=\"M26.3109 16.2386C28.85 16.2386 31.107 17.1164 32.9095 18.8091L37.8466 13.8719C34.853 11.082 30.9659 9.3736 26.3109 9.3736C19.5712 9.3736 13.7563 13.245 10.9194 18.8875L16.6559 23.3388C18.0195 19.2636 21.8282 16.2386 26.3109 16.2386V16.2386Z\" fill=\"#EA4335\"/>\n    </svg>\n    <svg class=\"logo-dark\" width=\"18\" height=\"18\" viewBox=\"0 0 48 48\" xmlns=\"http://www.w3.org/2000/svg\">\n      <circle cx=\"24\" cy=\"23\" fill=\"#FFF\" r=\"22\"/>\n      <path d=\"M33.76 34.26c2.75-2.56 4.49-6.37 4.49-11.26 0-.89-.08-1.84-.29-3H24.01v5.99h8.03c-.4 2.02-1.5 3.56-3.07 4.56v.75l3.91 2.97h.88z\" fill=\"#4285F4\"/>\n      <path d=\"M15.58 25.77A8.845 8.845 0 0 0 24 31.86c1.92 0 3.62-.46 4.97-1.31l4.79 3.71C31.14 36.7 27.65 38 24 38c-5.93 0-11.01-3.4-13.45-8.36l.17-1.01 4.06-2.85h.8z\" fill=\"#34A853\"/>\n      <path d=\"M15.59 20.21a8.864 8.864 0 0 0 0 5.58l-5.03 3.86c-.98-2-1.53-4.25-1.53-6.64 0-2.39.55-4.64 1.53-6.64l1-.22 3.81 2.98.22 1.08z\" fill=\"#FBBC05\"/>\n      <path d=\"M24 14.14c2.11 0 4.02.75 5.52 1.98l4.36-4.36C31.22 9.43 27.81 8 24 8c-5.93 0-11.01 3.4-13.45 8.36l5.03 3.85A8.86 8.86 0 0 1 24 14.14z\" fill=\"#EA4335\"/>\n    </svg>\n    <div class=\"gradient-container\"><div class=\"gradient\"></div></div>\n  </div>\n  <div class=\"carousel\">\n    <a class=\"chip\" href=\"https://vertexaisearch.cloud.google.com/grounding-api-redirect/AUZIYQFFx9KZU7Oa1gZUC--MjMR3_QCAmo1BT0jSI3DMpV_ZJwnrWQIkPGjxYH0nzZ-DCkHzxwZulViyuddyo58dTnUOexbhhKwxBzcQqaT4h5ihG9l7mTKew4PsON0yGIwR440e-mRjuI82GLjEIGtz69SnDvJFBShzkdPoPEsItDPHMZjj2jEqJNSscxuxDmBvgx0s9Okl6n8I8p2Yo_dc\">AI news this week May 2026</a>\n    <a class=\"chip\" href=\"https://vertexaisearch.cloud.google.com/grounding-api-redirect/AUZIYQH6u3XYQ3OkYPHN9pstt1XvXkTOOggZP46PN8d7cV_9GyKZ8bP0sqbg80oaVe4ELCTvHJRsVJic1FSALDiqamqJ16pAP45ihIm08ZOHiQowuhrNxkTqHtKHsqrY6sAXwhbLbxN9L7y9z4eZpiK1k8W_hOSxwrGn0IVelwpEhLXIgH6dGQjgPFF_GBCTk3TBTjR3-JoJKMt-eToGJD9ml8-XxerJJ4p-DLI=\">notable AI developments May 8-15 2026</a>\n  </div>\n</div>\n"
        },
        {
          "search_suggestions": "<style>\n.container {\n  align-items: center;\n  border-radius: 8px;\n  display: flex;\n  font-family: Google Sans, Roboto, sans-serif;\n  font-size: 14px;\n  line-height: 20px;\n  padding: 8px 12px;\n}\n.chip {\n  display: inline-block;\n  border: solid 1px;\n  border-radius: 16px;\n  min-width: 14px;\n  padding: 5px 16px;\n  text-align: center;\n  user-select: none;\n  margin: 0 8px;\n  -webkit-tap-highlight-color: transparent;\n}\n.carousel {\n  overflow: auto;\n  scrollbar-width: none;\n  white-space: nowrap;\n  margin-right: -12px;\n}\n.headline {\n  display: flex;\n  margin-right: 4px;\n}\n.gradient-container {\n  position: relative;\n}\n.gradient {\n  position: absolute;\n  transform: translate(3px, -9px);\n  height: 36px;\n  width: 9px;\n}\n@media (prefers-color-scheme: light) {\n  .container {\n    background-color: #fafafa;\n    box-shadow: 0 0 0 1px #0000000f;\n  }\n  .headline-label {\n    color: #1f1f1f;\n  }\n  .chip {\n    background-color: #ffffff;\n    border-color: #d2d2d2;\n    color: #5e5e5e;\n    text-decoration: none;\n  }\n  .chip:hover {\n    background-color: #f2f2f2;\n  }\n  .chip:focus {\n    background-color: #f2f2f2;\n  }\n  .chip:active {\n    background-color: #d8d8d8;\n    border-color: #b6b6b6;\n  }\n  .logo-dark {\n    display: none;\n  }\n  .gradient {\n    background: linear-gradient(90deg, #fafafa 15%, #fafafa00 100%);\n  }\n}\n@media (prefers-color-scheme: dark) {\n  .container {\n    background-color: #1f1f1f;\n    box-shadow: 0 0 0 1px #ffffff26;\n  }\n  .headline-label {\n    color: #fff;\n  }\n  .chip {\n    background-color: #2c2c2c;\n    border-color: #3c4043;\n    color: #fff;\n    text-decoration: none;\n  }\n  .chip:hover {\n    background-color: #353536;\n  }\n  .chip:focus {\n    background-color: #353536;\n  }\n  .chip:active {\n    background-color: #464849;\n    border-color: #53575b;\n  }\n  .logo-light {\n    display: none;\n  }\n  .gradient {\n    background: linear-gradient(90deg, #1f1f1f 15%, #1f1f1f00 100%);\n  }\n}\n</style>\n<div class=\"container\">\n  <div class=\"headline\">\n    <svg class=\"logo-light\" width=\"18\" height=\"18\" viewBox=\"9 9 35 35\" fill=\"none\" xmlns=\"http://www.w3.org/2000/svg\">\n      <path fill-rule=\"evenodd\" clip-rule=\"evenodd\" d=\"M42.8622 27.0064C42.8622 25.7839 42.7525 24.6084 42.5487 23.4799H26.3109V30.1568H35.5897C35.1821 32.3041 33.9596 34.1222 32.1258 35.3448V39.6864H37.7213C40.9814 36.677 42.8622 32.2571 42.8622 27.0064V27.0064Z\" fill=\"#4285F4\"/>\n      <path fill-rule=\"evenodd\" clip-rule=\"evenodd\" d=\"M26.3109 43.8555C30.9659 43.8555 34.8687 42.3195 37.7213 39.6863L32.1258 35.3447C30.5898 36.3792 28.6306 37.0061 26.3109 37.0061C21.8282 37.0061 18.0195 33.9811 16.6559 29.906H10.9194V34.3573C13.7563 39.9841 19.5712 43.8555 26.3109 43.8555V43.8555Z\" fill=\"#34A853\"/>\n      <path fill-rule=\"evenodd\" clip-rule=\"evenodd\" d=\"M16.6559 29.8904C16.3111 28.8559 16.1074 27.7588 16.1074 26.6146C16.1074 25.4704 16.3111 24.3733 16.6559 23.3388V18.8875H10.9194C9.74388 21.2072 9.06992 23.8247 9.06992 26.6146C9.06992 29.4045 9.74388 32.022 10.9194 34.3417L15.3864 30.8621L16.6559 29.8904V29.8904Z\" fill=\"#FBBC05\"/>\n      <path fill-rule=\"evenodd\" clip-rule=\"evenodd\" d=\"M26.3109 16.2386C28.85 16.2386 31.107 17.1164 32.9095 18.8091L37.8466 13.8719C34.853 11.082 30.9659 9.3736 26.3109 9.3736C19.5712 9.3736 13.7563 13.245 10.9194 18.8875L16.6559 23.3388C18.0195 19.2636 21.8282 16.2386 26.3109 16.2386V16.2386Z\" fill=\"#EA4335\"/>\n    </svg>\n    <svg class=\"logo-dark\" width=\"18\" height=\"18\" viewBox=\"0 0 48 48\" xmlns=\"http://www.w3.org/2000/svg\">\n      <circle cx=\"24\" cy=\"23\" fill=\"#FFF\" r=\"22\"/>\n      <path d=\"M33.76 34.26c2.75-2.56 4.49-6.37 4.49-11.26 0-.89-.08-1.84-.29-3H24.01v5.99h8.03c-.4 2.02-1.5 3.56-3.07 4.56v.75l3.91 2.97h.88z\" fill=\"#4285F4\"/>\n      <path d=\"M15.58 25.77A8.845 8.845 0 0 0 24 31.86c1.92 0 3.62-.46 4.97-1.31l4.79 3.71C31.14 36.7 27.65 38 24 38c-5.93 0-11.01-3.4-13.45-8.36l.17-1.01 4.06-2.85h.8z\" fill=\"#34A853\"/>\n      <path d=\"M15.59 20.21a8.864 8.864 0 0 0 0 5.58l-5.03 3.86c-.98-2-1.53-4.25-1.53-6.64 0-2.39.55-4.64 1.53-6.64l1-.22 3.81 2.98.22 1.08z\" fill=\"#FBBC05\"/>\n      <path d=\"M24 14.14c2.11 0 4.02.75 5.52 1.98l4.36-4.36C31.22 9.43 27.81 8 24 8c-5.93 0-11.01 3.4-13.45 8.36l5.03 3.85A8.86 8.86 0 0 1 24 14.14z\" fill=\"#EA4335\"/>\n    </svg>\n    <div class=\"gradient-container\"><div class=\"gradient\"></div></div>\n  </div>\n  <div class=\"carousel\">\n    <a class=\"chip\" href=\"https://vertexaisearch.cloud.google.com/grounding-api-redirect/AUZIYQFFx9KZU7Oa1gZUC--MjMR3_QCAmo1BT0jSI3DMpV_ZJwnrWQIkPGjxYH0nzZ-DCkHzxwZulViyuddyo58dTnUOexbhhKwxBzcQqaT4h5ihG9l7mTKew4PsON0yGIwR440e-mRjuI82GLjEIGtz69SnDvJFBShzkdPoPEsItDPHMZjj2jEqJNSscxuxDmBvgx0s9Okl6n8I8p2Yo_dc\">AI news this week May 2026</a>\n    <a class=\"chip\" href=\"https://vertexaisearch.cloud.google.com/grounding-api-redirect/AUZIYQH6u3XYQ3OkYPHN9pstt1XvXkTOOggZP46PN8d7cV_9GyKZ8bP0sqbg80oaVe4ELCTvHJRsVJic1FSALDiqamqJ16pAP45ihIm08ZOHiQowuhrNxkTqHtKHsqrY6sAXwhbLbxN9L7y9z4eZpiK1k8W_hOSxwrGn0IVelwpEhLXIgH6dGQjgPFF_GBCTk3TBTjR3-JoJKMt-eToGJD9ml8-XxerJJ4p-DLI=\">notable AI developments May 8-15 2026</a>\n  </div>\n</div>\n"
        }
      ],
      "is_error": false
    }
  ],
  "object": "interaction",
  "model": "gemini-2.5-flash"
}
"""

    /** `google-search.chunks.txt`, one SSE payload per element. */
    val GOOGLE_SEARCH_CHUNKS: List<String> = listOf(
        """{"interaction":{"id":"v1_ChdkR3NIYW9QaElNS21xdHNQaHFLbm1RWRIXZEdzSGFvUGhJTUttcXRzUGhxS25tUVk","status":"in_progress","object":"interaction","model":"gemini-2.5-flash"},"event_type":"interaction.created"}""",
        """{"interaction_id":"v1_ChdkR3NIYW9QaElNS21xdHNQaHFLbm1RWRIXZEdzSGFvUGhJTUttcXRzUGhxS25tUVk","status":"in_progress","event_type":"interaction.status_update"}""",
        """{"index":0,"step":{"type":"thought"},"event_type":"step.start"}""",
        """{"index":0,"delta":{"signature":"CiRlMjQ4MzBhNy01Y2Q2LTQyZmUtOTk4Yi1lZTUzOWU3MmI5YzM=","type":"thought_signature"},"event_type":"step.delta"}""",
        """{"index":0,"event_type":"step.stop"}""",
        """{"index":1,"step":{"type":"model_output"},"event_type":"step.start"}""",
        """{"index":1,"delta":{"text":"Here's a notable AI development from this past week:\n\n**Anthropic's Enhanced Claude Managed Agents and Strategic Partnerships** (May 8, 2026)\n","type":"text"},"event_type":"step.delta"}""",
        """{"index":1,"delta":{"text":"Anthropic has significantly advanced its Claude Managed Agents with new features such as \"dreaming\" for improved memory and pattern recognition, multi-agent orchestration, and enhanced outcomes tracking. These updates aim to enable AI agents to manage complex tasks with reduced","type":"text"},"event_type":"step.delta"}""",
        """{"index":1,"delta":{"text":" human intervention. In a strategic move, Anthropic also announced a ${'$'}1.5 billion AI deployment venture with major Wall Street firms, including Blackstone and Goldman Sachs, to integrate AI systems into businesses. Furthermore, the company solidified a partnership with SpaceX, securing substantial compute capacity for its operations.\n\n**OpenAI's New Advertising Platform and Model Updates** (May","type":"text"},"event_type":"step.delta"}""",
        """{"index":1,"delta":{"text":" 8, 2026)\nOpenAI launched a self-serve Ads Manager platform for ChatGPT, signaling a major expansion of its advertising ambitions and aiming for significant ad revenue. Concurrently, OpenAI introduced","type":"text"},"event_type":"step.delta"}""",
        """{"index":1,"delta":{"text":" new real-time voice and translation models for AI agents and deployed GPT-5.5 Instant as a new default model, claiming a 50% reduction in hallucinations.\n\n**","type":"text"},"event_type":"step.delta"}""",
        """{"index":1,"delta":{"text":"Apple's Plans for Third-Party AI Integration** (May 8, 2026)\nReports emerged detailing Apple's intentions to allow users to select third-party AI models, such as Google and Anthropic, to","type":"text"},"event_type":"step.delta"}""",
        """{"index":1,"delta":{"text":" power Apple Intelligence features across its operating systems, including iOS 27, iPadOS 27, and macOS 27. This move suggests Apple's strategic pivot to enhance its AI competitiveness by embracing","type":"text"},"event_type":"step.delta"}""",
        """{"index":1,"delta":{"text":" a more open ecosystem.\n\n**Meta's Llama 4 Release** (May 2026)\nMeta reportedly launched Llama 4, its latest open-source AI model. This new model is designed to compete directly with leading","type":"text"},"event_type":"step.delta"}""",
        """{"index":1,"delta":{"text":" proprietary models like GPT-4.5 and Gemini Ultra, featuring improved reasoning, multilingual support, and efficient inference capabilities for edge devices.\n\n**Meta AI's TRIBE v2 Continues to Gain Traction** (May ","type":"text"},"event_type":"step.delta"}""",
        """{"index":1,"delta":{"text":"12, 2026)\nAlthough initially unveiled on March 26, 2026, Meta AI's TRIBE v2, a predictive foundation model serving as a digital twin of human neural activity, was","type":"text"},"event_type":"step.delta"}""",
        """{"index":1,"delta":{"text":" highlighted in recent reports (May 12, 2026) as a notable and emerging AI trend. The model, capable of forecasting brain responses to complex stimuli, continues to accelerate discovery cycles for neurological disorders and influence AI design with","type":"text"},"event_type":"step.delta"}""",
        """{"index":1,"delta":{"text":" biologically plausible mechanisms.","type":"text"},"event_type":"step.delta"}""",
        """{"index":1,"delta":{"annotations":[{"start_index":346,"end_index":439,"url":"https://vertexaisearch.cloud.google.com/grounding-api-redirect/AUZIYQHHGSWev89akKS5C6uhfwD95oxHuPPvxBBY4Fh6WhrTziLkAu9wUGgCu7MR3S0D4o6IkZVPbEPlLYQTiWVrwUGxU3Am6TCdu_uw9EsjDGp20LIJ3H0t3a0V22O1QT3eAtB8xvGawPapPjKkYTH3KGRebiqhOorHy4xxkjPGxHJHOte-Xeux1iaa6qCXyGJ1sHyGvaiDtSw6MVzrBIh7Shw=","title":"coaio.com","type":"url_citation"},{"start_index":441,"end_index":633,"url":"https://vertexaisearch.cloud.google.com/grounding-api-redirect/AUZIYQE02CD0bjSOdGKTx_eCTiRmdvFhtsv9bb7Cajc1OuyPPGeQaq6cXaN_siYgxwSO_8bNp2jnHxG2I8Zo9lrklm163CEwaZdKa19jYehTuZ2up4k-P-uh9XcRCq5atSuM8hSeGgNUTAQxZU7kB6_zGc8rfqbj6RgDahTBXY2FLcb51LrSMTUIg5XtcNo9y65zwoVhe49pV22EtTmKDcCnpxjn21y5cn0DeQ==","title":"marketingprofs.com","type":"url_citation"},{"start_index":441,"end_index":633,"url":"https://vertexaisearch.cloud.google.com/grounding-api-redirect/AUZIYQE5JK7Ub8mxgnWIYfZKheFMWLCuen1Obitbp3PEYGpogHTksgJpbjT1aKiWShtdGBMiQbE0T-M4IdpiPOz6IMuJWshQmMCxQEKXzXUG8B-QQmG2DfzjKN35jc4nVksFXgJUlxV7q8xdSfZZjnAjNzktWVd2hWHgnVbhCCgoS90=","title":"substack.com","type":"url_citation"},{"start_index":635,"end_index":754,"url":"https://vertexaisearch.cloud.google.com/grounding-api-redirect/AUZIYQHprsyFoEQu5Xr7D8YOQsxUTHrYoq__EfGGk4Ufx3wcWKv4_yNjOnfFZGnsTXliF_ITHk025laKcg2kceh-j2ipgHpdejVGvQkAq6qEnHqKr2ZXc9PAo2xjnEmz7V2-zXxd97zWdy0=","title":"youtube.com","type":"url_citation"},{"start_index":635,"end_index":754,"url":"https://vertexaisearch.cloud.google.com/grounding-api-redirect/AUZIYQE6r3maWOKAojVKc-jM_jjFDb8W13BhhMf_xWcUMxSLOC1DtgNsEnN9KnzRB_OG9KRYYfOWNPzrwuTad_t5Rqr9V7fdQQQEinLCVbVrfNojRVNuAc-k8pnUiGK4gHVvX9AXTI3Zano=","title":"youtube.com","type":"url_citation"},{"start_index":635,"end_index":754,"url":"https://vertexaisearch.cloud.google.com/grounding-api-redirect/AUZIYQEQD8MafrjNaxxhAkzjyvGQK5WSTgwV09zLWQQjePHQCHUj8i_T1wZodn3frxvVGgyx_8Wc_M5yBC7YqZ5LJW1hYZvL7G2Y-QKOpMIOaZkRGqSP8azr7TcD4Y_sPvGH8qqBFR9R44nbbSYgLMrsJVFqqFjF","title":"neuralbuddies.com","type":"url_citation"},{"start_index":757,"end_index":984,"url":"https://vertexaisearch.cloud.google.com/grounding-api-redirect/AUZIYQE02CD0bjSOdGKTx_eCTiRmdvFhtsv9bb7Cajc1OuyPPGeQaq6cXaN_siYgxwSO_8bNp2jnHxG2I8Zo9lrklm163CEwaZdKa19jYehTuZ2up4k-P-uh9XcRCq5atSuM8hSeGgNUTAQxZU7kB6_zGc8rfqbj6RgDahTBXY2FLcb51LrSMTUIg5XtcNo9y65zwoVhe49pV22EtTmKDcCnpxjn21y5cn0DeQ==","title":"marketingprofs.com","type":"url_citation"},{"start_index":757,"end_index":984,"url":"https://vertexaisearch.cloud.google.com/grounding-api-redirect/AUZIYQE6r3maWOKAojVKc-jM_jjFDb8W13BhhMf_xWcUMxSLOC1DtgNsEnN9KnzRB_OG9KRYYfOWNPzrwuTad_t5Rqr9V7fdQQQEinLCVbVrfNojRVNuAc-k8pnUiGK4gHVvX9AXTI3Zano=","title":"youtube.com","type":"url_citation"},{"start_index":986,"end_index":1074,"url":"https://vertexaisearch.cloud.google.com/grounding-api-redirect/AUZIYQE02CD0bjSOdGKTx_eCTiRmdvFhtsv9bb7Cajc1OuyPPGeQaq6cXaN_siYgxwSO_8bNp2jnHxG2I8Zo9lrklm163CEwaZdKa19jYehTuZ2up4k-P-uh9XcRCq5atSuM8hSeGgNUTAQxZU7kB6_zGc8rfqbj6RgDahTBXY2FLcb51LrSMTUIg5XtcNo9y65zwoVhe49pV22EtTmKDcCnpxjn21y5cn0DeQ==","title":"marketingprofs.com","type":"url_citation"},{"start_index":1075,"end_index":1170,"url":"https://vertexaisearch.cloud.google.com/grounding-api-redirect/AUZIYQHprsyFoEQu5Xr7D8YOQsxUTHrYoq__EfGGk4Ufx3wcWKv4_yNjOnfFZGnsTXliF_ITHk025laKcg2kceh-j2ipgHpdejVGvQkAq6qEnHqKr2ZXc9PAo2xjnEmz7V2-zXxd97zWdy0=","title":"youtube.com","type":"url_citation"},{"start_index":1173,"end_index":1467,"url":"https://vertexaisearch.cloud.google.com/grounding-api-redirect/AUZIYQE02CD0bjSOdGKTx_eCTiRmdvFhtsv9bb7Cajc1OuyPPGeQaq6cXaN_siYgxwSO_8bNp2jnHxG2I8Zo9lrklm163CEwaZdKa19jYehTuZ2up4k-P-uh9XcRCq5atSuM8hSeGgNUTAQxZU7kB6_zGc8rfqbj6RgDahTBXY2FLcb51LrSMTUIg5XtcNo9y65zwoVhe49pV22EtTmKDcCnpxjn21y5cn0DeQ==","title":"marketingprofs.com","type":"url_citation"},{"start_index":1173,"end_index":1467,"url":"https://vertexaisearch.cloud.google.com/grounding-api-redirect/AUZIYQHprsyFoEQu5Xr7D8YOQsxUTHrYoq__EfGGk4Ufx3wcWKv4_yNjOnfFZGnsTXliF_ITHk025laKcg2kceh-j2ipgHpdejVGvQkAq6qEnHqKr2ZXc9PAo2xjnEmz7V2-zXxd97zWdy0=","title":"youtube.com","type":"url_citation"},{"start_index":1688,"end_index":1903,"url":"https://vertexaisearch.cloud.google.com/grounding-api-redirect/AUZIYQHVwW0xMraieOkYP0ecOUlrLJreGsqkZVCGBXa4JT1958SGnfKSZIMO2JOGuOFRcfu-Y71ddITgnPrPEgHjQcwd1PiDZ9Pp8mcy4Rj_9aO9KwrKtAPF0XIQVAhVqxlrUKYUca_HwM4lmX5nkiwsE7EKNYJcMOLcE9vMg5zdhDI=","title":"techdg.in","type":"url_citation"},{"start_index":2205,"end_index":2405,"url":"https://vertexaisearch.cloud.google.com/grounding-api-redirect/AUZIYQEVu1mfWp48vNiLhKKqgeknJO8GDFLumUDNTNkVnsZ4Zms8qne6-PvsE4GvUX_sWhlHDTvurzkQjPhILxdQyXKvK90Fi7eZ6knHJFjYrW4eegv2EbBtcfYpMGYQa5TexjEXTCyvWTXRve86tag4_ZqgAbDjyzQI3VbRH4pgOTdV0VViICMrgm8uNf8yRMOeooA87qCtsxfa0G9e4l6fSSt4S_fmXKIbBuPk","title":"etcjournal.com","type":"url_citation"}],"type":"text_annotation_delta"},"event_type":"step.delta"}""",
        """{"index":1,"event_type":"step.stop"}""",
        """{"index":2,"step":{"id":"7xveqyd2","signature":"","type":"google_search_call"},"event_type":"step.start"}""",
        """{"index":2,"delta":{"type":"google_search_call","arguments":{"queries":["notable AI developments last week May 8-15 2026","AI news May 8 2026","AI breakthroughs May 2026","major AI announcements May 2026"]}},"event_type":"step.delta"}""",
        """{"index":2,"event_type":"step.stop"}""",
        """{"index":3,"step":{"call_id":"7xveqyd2","signature":"","type":"google_search_result"},"event_type":"step.start"}""",
        """{"index":3,"delta":{"type":"google_search_result","result":[{"search_suggestions":"<style>\n.container {\n  align-items: center;\n  border-radius: 8px;\n  display: flex;\n  font-family: Google Sans, Roboto, sans-serif;\n  font-size: 14px;\n  line-height: 20px;\n  padding: 8px 12px;\n}\n.chip {\n  display: inline-block;\n  border: solid 1px;\n  border-radius: 16px;\n  min-width: 14px;\n  padding: 5px 16px;\n  text-align: center;\n  user-select: none;\n  margin: 0 8px;\n  -webkit-tap-highlight-color: transparent;\n}\n.carousel {\n  overflow: auto;\n  scrollbar-width: none;\n  white-space: nowrap;\n  margin-right: -12px;\n}\n.headline {\n  display: flex;\n  margin-right: 4px;\n}\n.gradient-container {\n  position: relative;\n}\n.gradient {\n  position: absolute;\n  transform: translate(3px, -9px);\n  height: 36px;\n  width: 9px;\n}\n@media (prefers-color-scheme: light) {\n  .container {\n    background-color: #fafafa;\n    box-shadow: 0 0 0 1px #0000000f;\n  }\n  .headline-label {\n    color: #1f1f1f;\n  }\n  .chip {\n    background-color: #ffffff;\n    border-color: #d2d2d2;\n    color: #5e5e5e;\n    text-decoration: none;\n  }\n  .chip:hover {\n    background-color: #f2f2f2;\n  }\n  .chip:focus {\n    background-color: #f2f2f2;\n  }\n  .chip:active {\n    background-color: #d8d8d8;\n    border-color: #b6b6b6;\n  }\n  .logo-dark {\n    display: none;\n  }\n  .gradient {\n    background: linear-gradient(90deg, #fafafa 15%, #fafafa00 100%);\n  }\n}\n@media (prefers-color-scheme: dark) {\n  .container {\n    background-color: #1f1f1f;\n    box-shadow: 0 0 0 1px #ffffff26;\n  }\n  .headline-label {\n    color: #fff;\n  }\n  .chip {\n    background-color: #2c2c2c;\n    border-color: #3c4043;\n    color: #fff;\n    text-decoration: none;\n  }\n  .chip:hover {\n    background-color: #353536;\n  }\n  .chip:focus {\n    background-color: #353536;\n  }\n  .chip:active {\n    background-color: #464849;\n    border-color: #53575b;\n  }\n  .logo-light {\n    display: none;\n  }\n  .gradient {\n    background: linear-gradient(90deg, #1f1f1f 15%, #1f1f1f00 100%);\n  }\n}\n</style>\n<div class=\"container\">\n  <div class=\"headline\">\n    <svg class=\"logo-light\" width=\"18\" height=\"18\" viewBox=\"9 9 35 35\" fill=\"none\" xmlns=\"http://www.w3.org/2000/svg\">\n      <path fill-rule=\"evenodd\" clip-rule=\"evenodd\" d=\"M42.8622 27.0064C42.8622 25.7839 42.7525 24.6084 42.5487 23.4799H26.3109V30.1568H35.5897C35.1821 32.3041 33.9596 34.1222 32.1258 35.3448V39.6864H37.7213C40.9814 36.677 42.8622 32.2571 42.8622 27.0064V27.0064Z\" fill=\"#4285F4\"/>\n      <path fill-rule=\"evenodd\" clip-rule=\"evenodd\" d=\"M26.3109 43.8555C30.9659 43.8555 34.8687 42.3195 37.7213 39.6863L32.1258 35.3447C30.5898 36.3792 28.6306 37.0061 26.3109 37.0061C21.8282 37.0061 18.0195 33.9811 16.6559 29.906H10.9194V34.3573C13.7563 39.9841 19.5712 43.8555 26.3109 43.8555V43.8555Z\" fill=\"#34A853\"/>\n      <path fill-rule=\"evenodd\" clip-rule=\"evenodd\" d=\"M16.6559 29.8904C16.3111 28.8559 16.1074 27.7588 16.1074 26.6146C16.1074 25.4704 16.3111 24.3733 16.6559 23.3388V18.8875H10.9194C9.74388 21.2072 9.06992 23.8247 9.06992 26.6146C9.06992 29.4045 9.74388 32.022 10.9194 34.3417L15.3864 30.8621L16.6559 29.8904V29.8904Z\" fill=\"#FBBC05\"/>\n      <path fill-rule=\"evenodd\" clip-rule=\"evenodd\" d=\"M26.3109 16.2386C28.85 16.2386 31.107 17.1164 32.9095 18.8091L37.8466 13.8719C34.853 11.082 30.9659 9.3736 26.3109 9.3736C19.5712 9.3736 13.7563 13.245 10.9194 18.8875L16.6559 23.3388C18.0195 19.2636 21.8282 16.2386 26.3109 16.2386V16.2386Z\" fill=\"#EA4335\"/>\n    </svg>\n    <svg class=\"logo-dark\" width=\"18\" height=\"18\" viewBox=\"0 0 48 48\" xmlns=\"http://www.w3.org/2000/svg\">\n      <circle cx=\"24\" cy=\"23\" fill=\"#FFF\" r=\"22\"/>\n      <path d=\"M33.76 34.26c2.75-2.56 4.49-6.37 4.49-11.26 0-.89-.08-1.84-.29-3H24.01v5.99h8.03c-.4 2.02-1.5 3.56-3.07 4.56v.75l3.91 2.97h.88z\" fill=\"#4285F4\"/>\n      <path d=\"M15.58 25.77A8.845 8.845 0 0 0 24 31.86c1.92 0 3.62-.46 4.97-1.31l4.79 3.71C31.14 36.7 27.65 38 24 38c-5.93 0-11.01-3.4-13.45-8.36l.17-1.01 4.06-2.85h.8z\" fill=\"#34A853\"/>\n      <path d=\"M15.59 20.21a8.864 8.864 0 0 0 0 5.58l-5.03 3.86c-.98-2-1.53-4.25-1.53-6.64 0-2.39.55-4.64 1.53-6.64l1-.22 3.81 2.98.22 1.08z\" fill=\"#FBBC05\"/>\n      <path d=\"M24 14.14c2.11 0 4.02.75 5.52 1.98l4.36-4.36C31.22 9.43 27.81 8 24 8c-5.93 0-11.01 3.4-13.45 8.36l5.03 3.85A8.86 8.86 0 0 1 24 14.14z\" fill=\"#EA4335\"/>\n    </svg>\n    <div class=\"gradient-container\"><div class=\"gradient\"></div></div>\n  </div>\n  <div class=\"carousel\">\n    <a class=\"chip\" href=\"https://vertexaisearch.cloud.google.com/grounding-api-redirect/AUZIYQEWMnkwLNBvnx5IQlDJh11kzI8LauYP0aC8P6PhFOPJJFhcEHvrEo2TNbAz8nhc0_5OLWHaYPE-k7YoUnUxFA3EVtaBPsgV3mH5QyVK0-xQm7O6jhppnB1MghD9VyEGptV8C5_sGbEetHf_0oLueEFLXuosLqx7Hw9ssmte7s-c4TSbtohlTR6g4BD55honYnhR87w2gqvRGsvPDMM=\">AI breakthroughs May 2026</a>\n    <a class=\"chip\" href=\"https://vertexaisearch.cloud.google.com/grounding-api-redirect/AUZIYQGLjBuoA3P8hBD8dqDeywnnIAMAQVdJDbQ7XT_RbAQldsaLJqteItdfKNjlux8QuG_yX7G9Z1q82PGVuUaNPcyeiIt5AcNzrRTPdCX1EmyROcz3BUvCLah68JjVuenBcMGXYhDIv9IKg-_GE7y6Noo-_xZnBe4FHpbdzZLCE7wg-qWIxYWg3x-VM99Gw1nDCtpWazvcHIoaTWg6hFkOZOBBP42MoRT89gFKjMmbT4UAxV_a\">notable AI developments last week May 8-15 2026</a>\n    <a class=\"chip\" href=\"https://vertexaisearch.cloud.google.com/grounding-api-redirect/AUZIYQGEme-FkSzLXLhbCp2sw8I_6erizJGjr2E04U7CUCowV8RX5yY19bdNfP_AQ207BS0NT4AKq0GoWmh-gcvBuz5ouLBj2ueT5teYB97rEAsnb8N0tggmiXZzxRpdW2eG9kqF01rbu_bkJMc1kIr2SE4HX9_H3ri7nJIu469nZo4pbXs2coqogScNmg4CJSB4bhaJkUN8JQ==\">AI news May 8 2026</a>\n    <a class=\"chip\" href=\"https://vertexaisearch.cloud.google.com/grounding-api-redirect/AUZIYQGCP5-L2phyiPjI0pyPw7KXVBYJ5xEDb13DgYVWeWIa20mT22m9y4zOe09CnbDEu97K3YX4V1N8-d4s_4aT1oBAXrZ511A123PTibQiCH2PofmgXqC5Qaa_wQ4tWGOY0smYBBcYvNL49-BODxpjs3corGQ2gSEECVHug1BneqzkX_hXrHPO_GFbiLCTeKJFZv7iOoLoCKhLCqsaXxt9tNFw5BA=\">major AI announcements May 2026</a>\n  </div>\n</div>\n"},{"search_suggestions":"<style>\n.container {\n  align-items: center;\n  border-radius: 8px;\n  display: flex;\n  font-family: Google Sans, Roboto, sans-serif;\n  font-size: 14px;\n  line-height: 20px;\n  padding: 8px 12px;\n}\n.chip {\n  display: inline-block;\n  border: solid 1px;\n  border-radius: 16px;\n  min-width: 14px;\n  padding: 5px 16px;\n  text-align: center;\n  user-select: none;\n  margin: 0 8px;\n  -webkit-tap-highlight-color: transparent;\n}\n.carousel {\n  overflow: auto;\n  scrollbar-width: none;\n  white-space: nowrap;\n  margin-right: -12px;\n}\n.headline {\n  display: flex;\n  margin-right: 4px;\n}\n.gradient-container {\n  position: relative;\n}\n.gradient {\n  position: absolute;\n  transform: translate(3px, -9px);\n  height: 36px;\n  width: 9px;\n}\n@media (prefers-color-scheme: light) {\n  .container {\n    background-color: #fafafa;\n    box-shadow: 0 0 0 1px #0000000f;\n  }\n  .headline-label {\n    color: #1f1f1f;\n  }\n  .chip {\n    background-color: #ffffff;\n    border-color: #d2d2d2;\n    color: #5e5e5e;\n    text-decoration: none;\n  }\n  .chip:hover {\n    background-color: #f2f2f2;\n  }\n  .chip:focus {\n    background-color: #f2f2f2;\n  }\n  .chip:active {\n    background-color: #d8d8d8;\n    border-color: #b6b6b6;\n  }\n  .logo-dark {\n    display: none;\n  }\n  .gradient {\n    background: linear-gradient(90deg, #fafafa 15%, #fafafa00 100%);\n  }\n}\n@media (prefers-color-scheme: dark) {\n  .container {\n    background-color: #1f1f1f;\n    box-shadow: 0 0 0 1px #ffffff26;\n  }\n  .headline-label {\n    color: #fff;\n  }\n  .chip {\n    background-color: #2c2c2c;\n    border-color: #3c4043;\n    color: #fff;\n    text-decoration: none;\n  }\n  .chip:hover {\n    background-color: #353536;\n  }\n  .chip:focus {\n    background-color: #353536;\n  }\n  .chip:active {\n    background-color: #464849;\n    border-color: #53575b;\n  }\n  .logo-light {\n    display: none;\n  }\n  .gradient {\n    background: linear-gradient(90deg, #1f1f1f 15%, #1f1f1f00 100%);\n  }\n}\n</style>\n<div class=\"container\">\n  <div class=\"headline\">\n    <svg class=\"logo-light\" width=\"18\" height=\"18\" viewBox=\"9 9 35 35\" fill=\"none\" xmlns=\"http://www.w3.org/2000/svg\">\n      <path fill-rule=\"evenodd\" clip-rule=\"evenodd\" d=\"M42.8622 27.0064C42.8622 25.7839 42.7525 24.6084 42.5487 23.4799H26.3109V30.1568H35.5897C35.1821 32.3041 33.9596 34.1222 32.1258 35.3448V39.6864H37.7213C40.9814 36.677 42.8622 32.2571 42.8622 27.0064V27.0064Z\" fill=\"#4285F4\"/>\n      <path fill-rule=\"evenodd\" clip-rule=\"evenodd\" d=\"M26.3109 43.8555C30.9659 43.8555 34.8687 42.3195 37.7213 39.6863L32.1258 35.3447C30.5898 36.3792 28.6306 37.0061 26.3109 37.0061C21.8282 37.0061 18.0195 33.9811 16.6559 29.906H10.9194V34.3573C13.7563 39.9841 19.5712 43.8555 26.3109 43.8555V43.8555Z\" fill=\"#34A853\"/>\n      <path fill-rule=\"evenodd\" clip-rule=\"evenodd\" d=\"M16.6559 29.8904C16.3111 28.8559 16.1074 27.7588 16.1074 26.6146C16.1074 25.4704 16.3111 24.3733 16.6559 23.3388V18.8875H10.9194C9.74388 21.2072 9.06992 23.8247 9.06992 26.6146C9.06992 29.4045 9.74388 32.022 10.9194 34.3417L15.3864 30.8621L16.6559 29.8904V29.8904Z\" fill=\"#FBBC05\"/>\n      <path fill-rule=\"evenodd\" clip-rule=\"evenodd\" d=\"M26.3109 16.2386C28.85 16.2386 31.107 17.1164 32.9095 18.8091L37.8466 13.8719C34.853 11.082 30.9659 9.3736 26.3109 9.3736C19.5712 9.3736 13.7563 13.245 10.9194 18.8875L16.6559 23.3388C18.0195 19.2636 21.8282 16.2386 26.3109 16.2386V16.2386Z\" fill=\"#EA4335\"/>\n    </svg>\n    <svg class=\"logo-dark\" width=\"18\" height=\"18\" viewBox=\"0 0 48 48\" xmlns=\"http://www.w3.org/2000/svg\">\n      <circle cx=\"24\" cy=\"23\" fill=\"#FFF\" r=\"22\"/>\n      <path d=\"M33.76 34.26c2.75-2.56 4.49-6.37 4.49-11.26 0-.89-.08-1.84-.29-3H24.01v5.99h8.03c-.4 2.02-1.5 3.56-3.07 4.56v.75l3.91 2.97h.88z\" fill=\"#4285F4\"/>\n      <path d=\"M15.58 25.77A8.845 8.845 0 0 0 24 31.86c1.92 0 3.62-.46 4.97-1.31l4.79 3.71C31.14 36.7 27.65 38 24 38c-5.93 0-11.01-3.4-13.45-8.36l.17-1.01 4.06-2.85h.8z\" fill=\"#34A853\"/>\n      <path d=\"M15.59 20.21a8.864 8.864 0 0 0 0 5.58l-5.03 3.86c-.98-2-1.53-4.25-1.53-6.64 0-2.39.55-4.64 1.53-6.64l1-.22 3.81 2.98.22 1.08z\" fill=\"#FBBC05\"/>\n      <path d=\"M24 14.14c2.11 0 4.02.75 5.52 1.98l4.36-4.36C31.22 9.43 27.81 8 24 8c-5.93 0-11.01 3.4-13.45 8.36l5.03 3.85A8.86 8.86 0 0 1 24 14.14z\" fill=\"#EA4335\"/>\n    </svg>\n    <div class=\"gradient-container\"><div class=\"gradient\"></div></div>\n  </div>\n  <div class=\"carousel\">\n    <a class=\"chip\" href=\"https://vertexaisearch.cloud.google.com/grounding-api-redirect/AUZIYQEWMnkwLNBvnx5IQlDJh11kzI8LauYP0aC8P6PhFOPJJFhcEHvrEo2TNbAz8nhc0_5OLWHaYPE-k7YoUnUxFA3EVtaBPsgV3mH5QyVK0-xQm7O6jhppnB1MghD9VyEGptV8C5_sGbEetHf_0oLueEFLXuosLqx7Hw9ssmte7s-c4TSbtohlTR6g4BD55honYnhR87w2gqvRGsvPDMM=\">AI breakthroughs May 2026</a>\n    <a class=\"chip\" href=\"https://vertexaisearch.cloud.google.com/grounding-api-redirect/AUZIYQGLjBuoA3P8hBD8dqDeywnnIAMAQVdJDbQ7XT_RbAQldsaLJqteItdfKNjlux8QuG_yX7G9Z1q82PGVuUaNPcyeiIt5AcNzrRTPdCX1EmyROcz3BUvCLah68JjVuenBcMGXYhDIv9IKg-_GE7y6Noo-_xZnBe4FHpbdzZLCE7wg-qWIxYWg3x-VM99Gw1nDCtpWazvcHIoaTWg6hFkOZOBBP42MoRT89gFKjMmbT4UAxV_a\">notable AI developments last week May 8-15 2026</a>\n    <a class=\"chip\" href=\"https://vertexaisearch.cloud.google.com/grounding-api-redirect/AUZIYQGEme-FkSzLXLhbCp2sw8I_6erizJGjr2E04U7CUCowV8RX5yY19bdNfP_AQ207BS0NT4AKq0GoWmh-gcvBuz5ouLBj2ueT5teYB97rEAsnb8N0tggmiXZzxRpdW2eG9kqF01rbu_bkJMc1kIr2SE4HX9_H3ri7nJIu469nZo4pbXs2coqogScNmg4CJSB4bhaJkUN8JQ==\">AI news May 8 2026</a>\n    <a class=\"chip\" href=\"https://vertexaisearch.cloud.google.com/grounding-api-redirect/AUZIYQGCP5-L2phyiPjI0pyPw7KXVBYJ5xEDb13DgYVWeWIa20mT22m9y4zOe09CnbDEu97K3YX4V1N8-d4s_4aT1oBAXrZ511A123PTibQiCH2PofmgXqC5Qaa_wQ4tWGOY0smYBBcYvNL49-BODxpjs3corGQ2gSEECVHug1BneqzkX_hXrHPO_GFbiLCTeKJFZv7iOoLoCKhLCqsaXxt9tNFw5BA=\">major AI announcements May 2026</a>\n  </div>\n</div>\n"},{"search_suggestions":"<style>\n.container {\n  align-items: center;\n  border-radius: 8px;\n  display: flex;\n  font-family: Google Sans, Roboto, sans-serif;\n  font-size: 14px;\n  line-height: 20px;\n  padding: 8px 12px;\n}\n.chip {\n  display: inline-block;\n  border: solid 1px;\n  border-radius: 16px;\n  min-width: 14px;\n  padding: 5px 16px;\n  text-align: center;\n  user-select: none;\n  margin: 0 8px;\n  -webkit-tap-highlight-color: transparent;\n}\n.carousel {\n  overflow: auto;\n  scrollbar-width: none;\n  white-space: nowrap;\n  margin-right: -12px;\n}\n.headline {\n  display: flex;\n  margin-right: 4px;\n}\n.gradient-container {\n  position: relative;\n}\n.gradient {\n  position: absolute;\n  transform: translate(3px, -9px);\n  height: 36px;\n  width: 9px;\n}\n@media (prefers-color-scheme: light) {\n  .container {\n    background-color: #fafafa;\n    box-shadow: 0 0 0 1px #0000000f;\n  }\n  .headline-label {\n    color: #1f1f1f;\n  }\n  .chip {\n    background-color: #ffffff;\n    border-color: #d2d2d2;\n    color: #5e5e5e;\n    text-decoration: none;\n  }\n  .chip:hover {\n    background-color: #f2f2f2;\n  }\n  .chip:focus {\n    background-color: #f2f2f2;\n  }\n  .chip:active {\n    background-color: #d8d8d8;\n    border-color: #b6b6b6;\n  }\n  .logo-dark {\n    display: none;\n  }\n  .gradient {\n    background: linear-gradient(90deg, #fafafa 15%, #fafafa00 100%);\n  }\n}\n@media (prefers-color-scheme: dark) {\n  .container {\n    background-color: #1f1f1f;\n    box-shadow: 0 0 0 1px #ffffff26;\n  }\n  .headline-label {\n    color: #fff;\n  }\n  .chip {\n    background-color: #2c2c2c;\n    border-color: #3c4043;\n    color: #fff;\n    text-decoration: none;\n  }\n  .chip:hover {\n    background-color: #353536;\n  }\n  .chip:focus {\n    background-color: #353536;\n  }\n  .chip:active {\n    background-color: #464849;\n    border-color: #53575b;\n  }\n  .logo-light {\n    display: none;\n  }\n  .gradient {\n    background: linear-gradient(90deg, #1f1f1f 15%, #1f1f1f00 100%);\n  }\n}\n</style>\n<div class=\"container\">\n  <div class=\"headline\">\n    <svg class=\"logo-light\" width=\"18\" height=\"18\" viewBox=\"9 9 35 35\" fill=\"none\" xmlns=\"http://www.w3.org/2000/svg\">\n      <path fill-rule=\"evenodd\" clip-rule=\"evenodd\" d=\"M42.8622 27.0064C42.8622 25.7839 42.7525 24.6084 42.5487 23.4799H26.3109V30.1568H35.5897C35.1821 32.3041 33.9596 34.1222 32.1258 35.3448V39.6864H37.7213C40.9814 36.677 42.8622 32.2571 42.8622 27.0064V27.0064Z\" fill=\"#4285F4\"/>\n      <path fill-rule=\"evenodd\" clip-rule=\"evenodd\" d=\"M26.3109 43.8555C30.9659 43.8555 34.8687 42.3195 37.7213 39.6863L32.1258 35.3447C30.5898 36.3792 28.6306 37.0061 26.3109 37.0061C21.8282 37.0061 18.0195 33.9811 16.6559 29.906H10.9194V34.3573C13.7563 39.9841 19.5712 43.8555 26.3109 43.8555V43.8555Z\" fill=\"#34A853\"/>\n      <path fill-rule=\"evenodd\" clip-rule=\"evenodd\" d=\"M16.6559 29.8904C16.3111 28.8559 16.1074 27.7588 16.1074 26.6146C16.1074 25.4704 16.3111 24.3733 16.6559 23.3388V18.8875H10.9194C9.74388 21.2072 9.06992 23.8247 9.06992 26.6146C9.06992 29.4045 9.74388 32.022 10.9194 34.3417L15.3864 30.8621L16.6559 29.8904V29.8904Z\" fill=\"#FBBC05\"/>\n      <path fill-rule=\"evenodd\" clip-rule=\"evenodd\" d=\"M26.3109 16.2386C28.85 16.2386 31.107 17.1164 32.9095 18.8091L37.8466 13.8719C34.853 11.082 30.9659 9.3736 26.3109 9.3736C19.5712 9.3736 13.7563 13.245 10.9194 18.8875L16.6559 23.3388C18.0195 19.2636 21.8282 16.2386 26.3109 16.2386V16.2386Z\" fill=\"#EA4335\"/>\n    </svg>\n    <svg class=\"logo-dark\" width=\"18\" height=\"18\" viewBox=\"0 0 48 48\" xmlns=\"http://www.w3.org/2000/svg\">\n      <circle cx=\"24\" cy=\"23\" fill=\"#FFF\" r=\"22\"/>\n      <path d=\"M33.76 34.26c2.75-2.56 4.49-6.37 4.49-11.26 0-.89-.08-1.84-.29-3H24.01v5.99h8.03c-.4 2.02-1.5 3.56-3.07 4.56v.75l3.91 2.97h.88z\" fill=\"#4285F4\"/>\n      <path d=\"M15.58 25.77A8.845 8.845 0 0 0 24 31.86c1.92 0 3.62-.46 4.97-1.31l4.79 3.71C31.14 36.7 27.65 38 24 38c-5.93 0-11.01-3.4-13.45-8.36l.17-1.01 4.06-2.85h.8z\" fill=\"#34A853\"/>\n      <path d=\"M15.59 20.21a8.864 8.864 0 0 0 0 5.58l-5.03 3.86c-.98-2-1.53-4.25-1.53-6.64 0-2.39.55-4.64 1.53-6.64l1-.22 3.81 2.98.22 1.08z\" fill=\"#FBBC05\"/>\n      <path d=\"M24 14.14c2.11 0 4.02.75 5.52 1.98l4.36-4.36C31.22 9.43 27.81 8 24 8c-5.93 0-11.01 3.4-13.45 8.36l5.03 3.85A8.86 8.86 0 0 1 24 14.14z\" fill=\"#EA4335\"/>\n    </svg>\n    <div class=\"gradient-container\"><div class=\"gradient\"></div></div>\n  </div>\n  <div class=\"carousel\">\n    <a class=\"chip\" href=\"https://vertexaisearch.cloud.google.com/grounding-api-redirect/AUZIYQEWMnkwLNBvnx5IQlDJh11kzI8LauYP0aC8P6PhFOPJJFhcEHvrEo2TNbAz8nhc0_5OLWHaYPE-k7YoUnUxFA3EVtaBPsgV3mH5QyVK0-xQm7O6jhppnB1MghD9VyEGptV8C5_sGbEetHf_0oLueEFLXuosLqx7Hw9ssmte7s-c4TSbtohlTR6g4BD55honYnhR87w2gqvRGsvPDMM=\">AI breakthroughs May 2026</a>\n    <a class=\"chip\" href=\"https://vertexaisearch.cloud.google.com/grounding-api-redirect/AUZIYQGLjBuoA3P8hBD8dqDeywnnIAMAQVdJDbQ7XT_RbAQldsaLJqteItdfKNjlux8QuG_yX7G9Z1q82PGVuUaNPcyeiIt5AcNzrRTPdCX1EmyROcz3BUvCLah68JjVuenBcMGXYhDIv9IKg-_GE7y6Noo-_xZnBe4FHpbdzZLCE7wg-qWIxYWg3x-VM99Gw1nDCtpWazvcHIoaTWg6hFkOZOBBP42MoRT89gFKjMmbT4UAxV_a\">notable AI developments last week May 8-15 2026</a>\n    <a class=\"chip\" href=\"https://vertexaisearch.cloud.google.com/grounding-api-redirect/AUZIYQGEme-FkSzLXLhbCp2sw8I_6erizJGjr2E04U7CUCowV8RX5yY19bdNfP_AQ207BS0NT4AKq0GoWmh-gcvBuz5ouLBj2ueT5teYB97rEAsnb8N0tggmiXZzxRpdW2eG9kqF01rbu_bkJMc1kIr2SE4HX9_H3ri7nJIu469nZo4pbXs2coqogScNmg4CJSB4bhaJkUN8JQ==\">AI news May 8 2026</a>\n    <a class=\"chip\" href=\"https://vertexaisearch.cloud.google.com/grounding-api-redirect/AUZIYQGCP5-L2phyiPjI0pyPw7KXVBYJ5xEDb13DgYVWeWIa20mT22m9y4zOe09CnbDEu97K3YX4V1N8-d4s_4aT1oBAXrZ511A123PTibQiCH2PofmgXqC5Qaa_wQ4tWGOY0smYBBcYvNL49-BODxpjs3corGQ2gSEECVHug1BneqzkX_hXrHPO_GFbiLCTeKJFZv7iOoLoCKhLCqsaXxt9tNFw5BA=\">major AI announcements May 2026</a>\n  </div>\n</div>\n"},{"search_suggestions":"<style>\n.container {\n  align-items: center;\n  border-radius: 8px;\n  display: flex;\n  font-family: Google Sans, Roboto, sans-serif;\n  font-size: 14px;\n  line-height: 20px;\n  padding: 8px 12px;\n}\n.chip {\n  display: inline-block;\n  border: solid 1px;\n  border-radius: 16px;\n  min-width: 14px;\n  padding: 5px 16px;\n  text-align: center;\n  user-select: none;\n  margin: 0 8px;\n  -webkit-tap-highlight-color: transparent;\n}\n.carousel {\n  overflow: auto;\n  scrollbar-width: none;\n  white-space: nowrap;\n  margin-right: -12px;\n}\n.headline {\n  display: flex;\n  margin-right: 4px;\n}\n.gradient-container {\n  position: relative;\n}\n.gradient {\n  position: absolute;\n  transform: translate(3px, -9px);\n  height: 36px;\n  width: 9px;\n}\n@media (prefers-color-scheme: light) {\n  .container {\n    background-color: #fafafa;\n    box-shadow: 0 0 0 1px #0000000f;\n  }\n  .headline-label {\n    color: #1f1f1f;\n  }\n  .chip {\n    background-color: #ffffff;\n    border-color: #d2d2d2;\n    color: #5e5e5e;\n    text-decoration: none;\n  }\n  .chip:hover {\n    background-color: #f2f2f2;\n  }\n  .chip:focus {\n    background-color: #f2f2f2;\n  }\n  .chip:active {\n    background-color: #d8d8d8;\n    border-color: #b6b6b6;\n  }\n  .logo-dark {\n    display: none;\n  }\n  .gradient {\n    background: linear-gradient(90deg, #fafafa 15%, #fafafa00 100%);\n  }\n}\n@media (prefers-color-scheme: dark) {\n  .container {\n    background-color: #1f1f1f;\n    box-shadow: 0 0 0 1px #ffffff26;\n  }\n  .headline-label {\n    color: #fff;\n  }\n  .chip {\n    background-color: #2c2c2c;\n    border-color: #3c4043;\n    color: #fff;\n    text-decoration: none;\n  }\n  .chip:hover {\n    background-color: #353536;\n  }\n  .chip:focus {\n    background-color: #353536;\n  }\n  .chip:active {\n    background-color: #464849;\n    border-color: #53575b;\n  }\n  .logo-light {\n    display: none;\n  }\n  .gradient {\n    background: linear-gradient(90deg, #1f1f1f 15%, #1f1f1f00 100%);\n  }\n}\n</style>\n<div class=\"container\">\n  <div class=\"headline\">\n    <svg class=\"logo-light\" width=\"18\" height=\"18\" viewBox=\"9 9 35 35\" fill=\"none\" xmlns=\"http://www.w3.org/2000/svg\">\n      <path fill-rule=\"evenodd\" clip-rule=\"evenodd\" d=\"M42.8622 27.0064C42.8622 25.7839 42.7525 24.6084 42.5487 23.4799H26.3109V30.1568H35.5897C35.1821 32.3041 33.9596 34.1222 32.1258 35.3448V39.6864H37.7213C40.9814 36.677 42.8622 32.2571 42.8622 27.0064V27.0064Z\" fill=\"#4285F4\"/>\n      <path fill-rule=\"evenodd\" clip-rule=\"evenodd\" d=\"M26.3109 43.8555C30.9659 43.8555 34.8687 42.3195 37.7213 39.6863L32.1258 35.3447C30.5898 36.3792 28.6306 37.0061 26.3109 37.0061C21.8282 37.0061 18.0195 33.9811 16.6559 29.906H10.9194V34.3573C13.7563 39.9841 19.5712 43.8555 26.3109 43.8555V43.8555Z\" fill=\"#34A853\"/>\n      <path fill-rule=\"evenodd\" clip-rule=\"evenodd\" d=\"M16.6559 29.8904C16.3111 28.8559 16.1074 27.7588 16.1074 26.6146C16.1074 25.4704 16.3111 24.3733 16.6559 23.3388V18.8875H10.9194C9.74388 21.2072 9.06992 23.8247 9.06992 26.6146C9.06992 29.4045 9.74388 32.022 10.9194 34.3417L15.3864 30.8621L16.6559 29.8904V29.8904Z\" fill=\"#FBBC05\"/>\n      <path fill-rule=\"evenodd\" clip-rule=\"evenodd\" d=\"M26.3109 16.2386C28.85 16.2386 31.107 17.1164 32.9095 18.8091L37.8466 13.8719C34.853 11.082 30.9659 9.3736 26.3109 9.3736C19.5712 9.3736 13.7563 13.245 10.9194 18.8875L16.6559 23.3388C18.0195 19.2636 21.8282 16.2386 26.3109 16.2386V16.2386Z\" fill=\"#EA4335\"/>\n    </svg>\n    <svg class=\"logo-dark\" width=\"18\" height=\"18\" viewBox=\"0 0 48 48\" xmlns=\"http://www.w3.org/2000/svg\">\n      <circle cx=\"24\" cy=\"23\" fill=\"#FFF\" r=\"22\"/>\n      <path d=\"M33.76 34.26c2.75-2.56 4.49-6.37 4.49-11.26 0-.89-.08-1.84-.29-3H24.01v5.99h8.03c-.4 2.02-1.5 3.56-3.07 4.56v.75l3.91 2.97h.88z\" fill=\"#4285F4\"/>\n      <path d=\"M15.58 25.77A8.845 8.845 0 0 0 24 31.86c1.92 0 3.62-.46 4.97-1.31l4.79 3.71C31.14 36.7 27.65 38 24 38c-5.93 0-11.01-3.4-13.45-8.36l.17-1.01 4.06-2.85h.8z\" fill=\"#34A853\"/>\n      <path d=\"M15.59 20.21a8.864 8.864 0 0 0 0 5.58l-5.03 3.86c-.98-2-1.53-4.25-1.53-6.64 0-2.39.55-4.64 1.53-6.64l1-.22 3.81 2.98.22 1.08z\" fill=\"#FBBC05\"/>\n      <path d=\"M24 14.14c2.11 0 4.02.75 5.52 1.98l4.36-4.36C31.22 9.43 27.81 8 24 8c-5.93 0-11.01 3.4-13.45 8.36l5.03 3.85A8.86 8.86 0 0 1 24 14.14z\" fill=\"#EA4335\"/>\n    </svg>\n    <div class=\"gradient-container\"><div class=\"gradient\"></div></div>\n  </div>\n  <div class=\"carousel\">\n    <a class=\"chip\" href=\"https://vertexaisearch.cloud.google.com/grounding-api-redirect/AUZIYQEWMnkwLNBvnx5IQlDJh11kzI8LauYP0aC8P6PhFOPJJFhcEHvrEo2TNbAz8nhc0_5OLWHaYPE-k7YoUnUxFA3EVtaBPsgV3mH5QyVK0-xQm7O6jhppnB1MghD9VyEGptV8C5_sGbEetHf_0oLueEFLXuosLqx7Hw9ssmte7s-c4TSbtohlTR6g4BD55honYnhR87w2gqvRGsvPDMM=\">AI breakthroughs May 2026</a>\n    <a class=\"chip\" href=\"https://vertexaisearch.cloud.google.com/grounding-api-redirect/AUZIYQGLjBuoA3P8hBD8dqDeywnnIAMAQVdJDbQ7XT_RbAQldsaLJqteItdfKNjlux8QuG_yX7G9Z1q82PGVuUaNPcyeiIt5AcNzrRTPdCX1EmyROcz3BUvCLah68JjVuenBcMGXYhDIv9IKg-_GE7y6Noo-_xZnBe4FHpbdzZLCE7wg-qWIxYWg3x-VM99Gw1nDCtpWazvcHIoaTWg6hFkOZOBBP42MoRT89gFKjMmbT4UAxV_a\">notable AI developments last week May 8-15 2026</a>\n    <a class=\"chip\" href=\"https://vertexaisearch.cloud.google.com/grounding-api-redirect/AUZIYQGEme-FkSzLXLhbCp2sw8I_6erizJGjr2E04U7CUCowV8RX5yY19bdNfP_AQ207BS0NT4AKq0GoWmh-gcvBuz5ouLBj2ueT5teYB97rEAsnb8N0tggmiXZzxRpdW2eG9kqF01rbu_bkJMc1kIr2SE4HX9_H3ri7nJIu469nZo4pbXs2coqogScNmg4CJSB4bhaJkUN8JQ==\">AI news May 8 2026</a>\n    <a class=\"chip\" href=\"https://vertexaisearch.cloud.google.com/grounding-api-redirect/AUZIYQGCP5-L2phyiPjI0pyPw7KXVBYJ5xEDb13DgYVWeWIa20mT22m9y4zOe09CnbDEu97K3YX4V1N8-d4s_4aT1oBAXrZ511A123PTibQiCH2PofmgXqC5Qaa_wQ4tWGOY0smYBBcYvNL49-BODxpjs3corGQ2gSEECVHug1BneqzkX_hXrHPO_GFbiLCTeKJFZv7iOoLoCKhLCqsaXxt9tNFw5BA=\">major AI announcements May 2026</a>\n  </div>\n</div>\n"},{"search_suggestions":"<style>\n.container {\n  align-items: center;\n  border-radius: 8px;\n  display: flex;\n  font-family: Google Sans, Roboto, sans-serif;\n  font-size: 14px;\n  line-height: 20px;\n  padding: 8px 12px;\n}\n.chip {\n  display: inline-block;\n  border: solid 1px;\n  border-radius: 16px;\n  min-width: 14px;\n  padding: 5px 16px;\n  text-align: center;\n  user-select: none;\n  margin: 0 8px;\n  -webkit-tap-highlight-color: transparent;\n}\n.carousel {\n  overflow: auto;\n  scrollbar-width: none;\n  white-space: nowrap;\n  margin-right: -12px;\n}\n.headline {\n  display: flex;\n  margin-right: 4px;\n}\n.gradient-container {\n  position: relative;\n}\n.gradient {\n  position: absolute;\n  transform: translate(3px, -9px);\n  height: 36px;\n  width: 9px;\n}\n@media (prefers-color-scheme: light) {\n  .container {\n    background-color: #fafafa;\n    box-shadow: 0 0 0 1px #0000000f;\n  }\n  .headline-label {\n    color: #1f1f1f;\n  }\n  .chip {\n    background-color: #ffffff;\n    border-color: #d2d2d2;\n    color: #5e5e5e;\n    text-decoration: none;\n  }\n  .chip:hover {\n    background-color: #f2f2f2;\n  }\n  .chip:focus {\n    background-color: #f2f2f2;\n  }\n  .chip:active {\n    background-color: #d8d8d8;\n    border-color: #b6b6b6;\n  }\n  .logo-dark {\n    display: none;\n  }\n  .gradient {\n    background: linear-gradient(90deg, #fafafa 15%, #fafafa00 100%);\n  }\n}\n@media (prefers-color-scheme: dark) {\n  .container {\n    background-color: #1f1f1f;\n    box-shadow: 0 0 0 1px #ffffff26;\n  }\n  .headline-label {\n    color: #fff;\n  }\n  .chip {\n    background-color: #2c2c2c;\n    border-color: #3c4043;\n    color: #fff;\n    text-decoration: none;\n  }\n  .chip:hover {\n    background-color: #353536;\n  }\n  .chip:focus {\n    background-color: #353536;\n  }\n  .chip:active {\n    background-color: #464849;\n    border-color: #53575b;\n  }\n  .logo-light {\n    display: none;\n  }\n  .gradient {\n    background: linear-gradient(90deg, #1f1f1f 15%, #1f1f1f00 100%);\n  }\n}\n</style>\n<div class=\"container\">\n  <div class=\"headline\">\n    <svg class=\"logo-light\" width=\"18\" height=\"18\" viewBox=\"9 9 35 35\" fill=\"none\" xmlns=\"http://www.w3.org/2000/svg\">\n      <path fill-rule=\"evenodd\" clip-rule=\"evenodd\" d=\"M42.8622 27.0064C42.8622 25.7839 42.7525 24.6084 42.5487 23.4799H26.3109V30.1568H35.5897C35.1821 32.3041 33.9596 34.1222 32.1258 35.3448V39.6864H37.7213C40.9814 36.677 42.8622 32.2571 42.8622 27.0064V27.0064Z\" fill=\"#4285F4\"/>\n      <path fill-rule=\"evenodd\" clip-rule=\"evenodd\" d=\"M26.3109 43.8555C30.9659 43.8555 34.8687 42.3195 37.7213 39.6863L32.1258 35.3447C30.5898 36.3792 28.6306 37.0061 26.3109 37.0061C21.8282 37.0061 18.0195 33.9811 16.6559 29.906H10.9194V34.3573C13.7563 39.9841 19.5712 43.8555 26.3109 43.8555V43.8555Z\" fill=\"#34A853\"/>\n      <path fill-rule=\"evenodd\" clip-rule=\"evenodd\" d=\"M16.6559 29.8904C16.3111 28.8559 16.1074 27.7588 16.1074 26.6146C16.1074 25.4704 16.3111 24.3733 16.6559 23.3388V18.8875H10.9194C9.74388 21.2072 9.06992 23.8247 9.06992 26.6146C9.06992 29.4045 9.74388 32.022 10.9194 34.3417L15.3864 30.8621L16.6559 29.8904V29.8904Z\" fill=\"#FBBC05\"/>\n      <path fill-rule=\"evenodd\" clip-rule=\"evenodd\" d=\"M26.3109 16.2386C28.85 16.2386 31.107 17.1164 32.9095 18.8091L37.8466 13.8719C34.853 11.082 30.9659 9.3736 26.3109 9.3736C19.5712 9.3736 13.7563 13.245 10.9194 18.8875L16.6559 23.3388C18.0195 19.2636 21.8282 16.2386 26.3109 16.2386V16.2386Z\" fill=\"#EA4335\"/>\n    </svg>\n    <svg class=\"logo-dark\" width=\"18\" height=\"18\" viewBox=\"0 0 48 48\" xmlns=\"http://www.w3.org/2000/svg\">\n      <circle cx=\"24\" cy=\"23\" fill=\"#FFF\" r=\"22\"/>\n      <path d=\"M33.76 34.26c2.75-2.56 4.49-6.37 4.49-11.26 0-.89-.08-1.84-.29-3H24.01v5.99h8.03c-.4 2.02-1.5 3.56-3.07 4.56v.75l3.91 2.97h.88z\" fill=\"#4285F4\"/>\n      <path d=\"M15.58 25.77A8.845 8.845 0 0 0 24 31.86c1.92 0 3.62-.46 4.97-1.31l4.79 3.71C31.14 36.7 27.65 38 24 38c-5.93 0-11.01-3.4-13.45-8.36l.17-1.01 4.06-2.85h.8z\" fill=\"#34A853\"/>\n      <path d=\"M15.59 20.21a8.864 8.864 0 0 0 0 5.58l-5.03 3.86c-.98-2-1.53-4.25-1.53-6.64 0-2.39.55-4.64 1.53-6.64l1-.22 3.81 2.98.22 1.08z\" fill=\"#FBBC05\"/>\n      <path d=\"M24 14.14c2.11 0 4.02.75 5.52 1.98l4.36-4.36C31.22 9.43 27.81 8 24 8c-5.93 0-11.01 3.4-13.45 8.36l5.03 3.85A8.86 8.86 0 0 1 24 14.14z\" fill=\"#EA4335\"/>\n    </svg>\n    <div class=\"gradient-container\"><div class=\"gradient\"></div></div>\n  </div>\n  <div class=\"carousel\">\n    <a class=\"chip\" href=\"https://vertexaisearch.cloud.google.com/grounding-api-redirect/AUZIYQEWMnkwLNBvnx5IQlDJh11kzI8LauYP0aC8P6PhFOPJJFhcEHvrEo2TNbAz8nhc0_5OLWHaYPE-k7YoUnUxFA3EVtaBPsgV3mH5QyVK0-xQm7O6jhppnB1MghD9VyEGptV8C5_sGbEetHf_0oLueEFLXuosLqx7Hw9ssmte7s-c4TSbtohlTR6g4BD55honYnhR87w2gqvRGsvPDMM=\">AI breakthroughs May 2026</a>\n    <a class=\"chip\" href=\"https://vertexaisearch.cloud.google.com/grounding-api-redirect/AUZIYQGLjBuoA3P8hBD8dqDeywnnIAMAQVdJDbQ7XT_RbAQldsaLJqteItdfKNjlux8QuG_yX7G9Z1q82PGVuUaNPcyeiIt5AcNzrRTPdCX1EmyROcz3BUvCLah68JjVuenBcMGXYhDIv9IKg-_GE7y6Noo-_xZnBe4FHpbdzZLCE7wg-qWIxYWg3x-VM99Gw1nDCtpWazvcHIoaTWg6hFkOZOBBP42MoRT89gFKjMmbT4UAxV_a\">notable AI developments last week May 8-15 2026</a>\n    <a class=\"chip\" href=\"https://vertexaisearch.cloud.google.com/grounding-api-redirect/AUZIYQGEme-FkSzLXLhbCp2sw8I_6erizJGjr2E04U7CUCowV8RX5yY19bdNfP_AQ207BS0NT4AKq0GoWmh-gcvBuz5ouLBj2ueT5teYB97rEAsnb8N0tggmiXZzxRpdW2eG9kqF01rbu_bkJMc1kIr2SE4HX9_H3ri7nJIu469nZo4pbXs2coqogScNmg4CJSB4bhaJkUN8JQ==\">AI news May 8 2026</a>\n    <a class=\"chip\" href=\"https://vertexaisearch.cloud.google.com/grounding-api-redirect/AUZIYQGCP5-L2phyiPjI0pyPw7KXVBYJ5xEDb13DgYVWeWIa20mT22m9y4zOe09CnbDEu97K3YX4V1N8-d4s_4aT1oBAXrZ511A123PTibQiCH2PofmgXqC5Qaa_wQ4tWGOY0smYBBcYvNL49-BODxpjs3corGQ2gSEECVHug1BneqzkX_hXrHPO_GFbiLCTeKJFZv7iOoLoCKhLCqsaXxt9tNFw5BA=\">major AI announcements May 2026</a>\n  </div>\n</div>\n"},{"search_suggestions":"<style>\n.container {\n  align-items: center;\n  border-radius: 8px;\n  display: flex;\n  font-family: Google Sans, Roboto, sans-serif;\n  font-size: 14px;\n  line-height: 20px;\n  padding: 8px 12px;\n}\n.chip {\n  display: inline-block;\n  border: solid 1px;\n  border-radius: 16px;\n  min-width: 14px;\n  padding: 5px 16px;\n  text-align: center;\n  user-select: none;\n  margin: 0 8px;\n  -webkit-tap-highlight-color: transparent;\n}\n.carousel {\n  overflow: auto;\n  scrollbar-width: none;\n  white-space: nowrap;\n  margin-right: -12px;\n}\n.headline {\n  display: flex;\n  margin-right: 4px;\n}\n.gradient-container {\n  position: relative;\n}\n.gradient {\n  position: absolute;\n  transform: translate(3px, -9px);\n  height: 36px;\n  width: 9px;\n}\n@media (prefers-color-scheme: light) {\n  .container {\n    background-color: #fafafa;\n    box-shadow: 0 0 0 1px #0000000f;\n  }\n  .headline-label {\n    color: #1f1f1f;\n  }\n  .chip {\n    background-color: #ffffff;\n    border-color: #d2d2d2;\n    color: #5e5e5e;\n    text-decoration: none;\n  }\n  .chip:hover {\n    background-color: #f2f2f2;\n  }\n  .chip:focus {\n    background-color: #f2f2f2;\n  }\n  .chip:active {\n    background-color: #d8d8d8;\n    border-color: #b6b6b6;\n  }\n  .logo-dark {\n    display: none;\n  }\n  .gradient {\n    background: linear-gradient(90deg, #fafafa 15%, #fafafa00 100%);\n  }\n}\n@media (prefers-color-scheme: dark) {\n  .container {\n    background-color: #1f1f1f;\n    box-shadow: 0 0 0 1px #ffffff26;\n  }\n  .headline-label {\n    color: #fff;\n  }\n  .chip {\n    background-color: #2c2c2c;\n    border-color: #3c4043;\n    color: #fff;\n    text-decoration: none;\n  }\n  .chip:hover {\n    background-color: #353536;\n  }\n  .chip:focus {\n    background-color: #353536;\n  }\n  .chip:active {\n    background-color: #464849;\n    border-color: #53575b;\n  }\n  .logo-light {\n    display: none;\n  }\n  .gradient {\n    background: linear-gradient(90deg, #1f1f1f 15%, #1f1f1f00 100%);\n  }\n}\n</style>\n<div class=\"container\">\n  <div class=\"headline\">\n    <svg class=\"logo-light\" width=\"18\" height=\"18\" viewBox=\"9 9 35 35\" fill=\"none\" xmlns=\"http://www.w3.org/2000/svg\">\n      <path fill-rule=\"evenodd\" clip-rule=\"evenodd\" d=\"M42.8622 27.0064C42.8622 25.7839 42.7525 24.6084 42.5487 23.4799H26.3109V30.1568H35.5897C35.1821 32.3041 33.9596 34.1222 32.1258 35.3448V39.6864H37.7213C40.9814 36.677 42.8622 32.2571 42.8622 27.0064V27.0064Z\" fill=\"#4285F4\"/>\n      <path fill-rule=\"evenodd\" clip-rule=\"evenodd\" d=\"M26.3109 43.8555C30.9659 43.8555 34.8687 42.3195 37.7213 39.6863L32.1258 35.3447C30.5898 36.3792 28.6306 37.0061 26.3109 37.0061C21.8282 37.0061 18.0195 33.9811 16.6559 29.906H10.9194V34.3573C13.7563 39.9841 19.5712 43.8555 26.3109 43.8555V43.8555Z\" fill=\"#34A853\"/>\n      <path fill-rule=\"evenodd\" clip-rule=\"evenodd\" d=\"M16.6559 29.8904C16.3111 28.8559 16.1074 27.7588 16.1074 26.6146C16.1074 25.4704 16.3111 24.3733 16.6559 23.3388V18.8875H10.9194C9.74388 21.2072 9.06992 23.8247 9.06992 26.6146C9.06992 29.4045 9.74388 32.022 10.9194 34.3417L15.3864 30.8621L16.6559 29.8904V29.8904Z\" fill=\"#FBBC05\"/>\n      <path fill-rule=\"evenodd\" clip-rule=\"evenodd\" d=\"M26.3109 16.2386C28.85 16.2386 31.107 17.1164 32.9095 18.8091L37.8466 13.8719C34.853 11.082 30.9659 9.3736 26.3109 9.3736C19.5712 9.3736 13.7563 13.245 10.9194 18.8875L16.6559 23.3388C18.0195 19.2636 21.8282 16.2386 26.3109 16.2386V16.2386Z\" fill=\"#EA4335\"/>\n    </svg>\n    <svg class=\"logo-dark\" width=\"18\" height=\"18\" viewBox=\"0 0 48 48\" xmlns=\"http://www.w3.org/2000/svg\">\n      <circle cx=\"24\" cy=\"23\" fill=\"#FFF\" r=\"22\"/>\n      <path d=\"M33.76 34.26c2.75-2.56 4.49-6.37 4.49-11.26 0-.89-.08-1.84-.29-3H24.01v5.99h8.03c-.4 2.02-1.5 3.56-3.07 4.56v.75l3.91 2.97h.88z\" fill=\"#4285F4\"/>\n      <path d=\"M15.58 25.77A8.845 8.845 0 0 0 24 31.86c1.92 0 3.62-.46 4.97-1.31l4.79 3.71C31.14 36.7 27.65 38 24 38c-5.93 0-11.01-3.4-13.45-8.36l.17-1.01 4.06-2.85h.8z\" fill=\"#34A853\"/>\n      <path d=\"M15.59 20.21a8.864 8.864 0 0 0 0 5.58l-5.03 3.86c-.98-2-1.53-4.25-1.53-6.64 0-2.39.55-4.64 1.53-6.64l1-.22 3.81 2.98.22 1.08z\" fill=\"#FBBC05\"/>\n      <path d=\"M24 14.14c2.11 0 4.02.75 5.52 1.98l4.36-4.36C31.22 9.43 27.81 8 24 8c-5.93 0-11.01 3.4-13.45 8.36l5.03 3.85A8.86 8.86 0 0 1 24 14.14z\" fill=\"#EA4335\"/>\n    </svg>\n    <div class=\"gradient-container\"><div class=\"gradient\"></div></div>\n  </div>\n  <div class=\"carousel\">\n    <a class=\"chip\" href=\"https://vertexaisearch.cloud.google.com/grounding-api-redirect/AUZIYQEWMnkwLNBvnx5IQlDJh11kzI8LauYP0aC8P6PhFOPJJFhcEHvrEo2TNbAz8nhc0_5OLWHaYPE-k7YoUnUxFA3EVtaBPsgV3mH5QyVK0-xQm7O6jhppnB1MghD9VyEGptV8C5_sGbEetHf_0oLueEFLXuosLqx7Hw9ssmte7s-c4TSbtohlTR6g4BD55honYnhR87w2gqvRGsvPDMM=\">AI breakthroughs May 2026</a>\n    <a class=\"chip\" href=\"https://vertexaisearch.cloud.google.com/grounding-api-redirect/AUZIYQGLjBuoA3P8hBD8dqDeywnnIAMAQVdJDbQ7XT_RbAQldsaLJqteItdfKNjlux8QuG_yX7G9Z1q82PGVuUaNPcyeiIt5AcNzrRTPdCX1EmyROcz3BUvCLah68JjVuenBcMGXYhDIv9IKg-_GE7y6Noo-_xZnBe4FHpbdzZLCE7wg-qWIxYWg3x-VM99Gw1nDCtpWazvcHIoaTWg6hFkOZOBBP42MoRT89gFKjMmbT4UAxV_a\">notable AI developments last week May 8-15 2026</a>\n    <a class=\"chip\" href=\"https://vertexaisearch.cloud.google.com/grounding-api-redirect/AUZIYQGEme-FkSzLXLhbCp2sw8I_6erizJGjr2E04U7CUCowV8RX5yY19bdNfP_AQ207BS0NT4AKq0GoWmh-gcvBuz5ouLBj2ueT5teYB97rEAsnb8N0tggmiXZzxRpdW2eG9kqF01rbu_bkJMc1kIr2SE4HX9_H3ri7nJIu469nZo4pbXs2coqogScNmg4CJSB4bhaJkUN8JQ==\">AI news May 8 2026</a>\n    <a class=\"chip\" href=\"https://vertexaisearch.cloud.google.com/grounding-api-redirect/AUZIYQGCP5-L2phyiPjI0pyPw7KXVBYJ5xEDb13DgYVWeWIa20mT22m9y4zOe09CnbDEu97K3YX4V1N8-d4s_4aT1oBAXrZ511A123PTibQiCH2PofmgXqC5Qaa_wQ4tWGOY0smYBBcYvNL49-BODxpjs3corGQ2gSEECVHug1BneqzkX_hXrHPO_GFbiLCTeKJFZv7iOoLoCKhLCqsaXxt9tNFw5BA=\">major AI announcements May 2026</a>\n  </div>\n</div>\n"},{"search_suggestions":"<style>\n.container {\n  align-items: center;\n  border-radius: 8px;\n  display: flex;\n  font-family: Google Sans, Roboto, sans-serif;\n  font-size: 14px;\n  line-height: 20px;\n  padding: 8px 12px;\n}\n.chip {\n  display: inline-block;\n  border: solid 1px;\n  border-radius: 16px;\n  min-width: 14px;\n  padding: 5px 16px;\n  text-align: center;\n  user-select: none;\n  margin: 0 8px;\n  -webkit-tap-highlight-color: transparent;\n}\n.carousel {\n  overflow: auto;\n  scrollbar-width: none;\n  white-space: nowrap;\n  margin-right: -12px;\n}\n.headline {\n  display: flex;\n  margin-right: 4px;\n}\n.gradient-container {\n  position: relative;\n}\n.gradient {\n  position: absolute;\n  transform: translate(3px, -9px);\n  height: 36px;\n  width: 9px;\n}\n@media (prefers-color-scheme: light) {\n  .container {\n    background-color: #fafafa;\n    box-shadow: 0 0 0 1px #0000000f;\n  }\n  .headline-label {\n    color: #1f1f1f;\n  }\n  .chip {\n    background-color: #ffffff;\n    border-color: #d2d2d2;\n    color: #5e5e5e;\n    text-decoration: none;\n  }\n  .chip:hover {\n    background-color: #f2f2f2;\n  }\n  .chip:focus {\n    background-color: #f2f2f2;\n  }\n  .chip:active {\n    background-color: #d8d8d8;\n    border-color: #b6b6b6;\n  }\n  .logo-dark {\n    display: none;\n  }\n  .gradient {\n    background: linear-gradient(90deg, #fafafa 15%, #fafafa00 100%);\n  }\n}\n@media (prefers-color-scheme: dark) {\n  .container {\n    background-color: #1f1f1f;\n    box-shadow: 0 0 0 1px #ffffff26;\n  }\n  .headline-label {\n    color: #fff;\n  }\n  .chip {\n    background-color: #2c2c2c;\n    border-color: #3c4043;\n    color: #fff;\n    text-decoration: none;\n  }\n  .chip:hover {\n    background-color: #353536;\n  }\n  .chip:focus {\n    background-color: #353536;\n  }\n  .chip:active {\n    background-color: #464849;\n    border-color: #53575b;\n  }\n  .logo-light {\n    display: none;\n  }\n  .gradient {\n    background: linear-gradient(90deg, #1f1f1f 15%, #1f1f1f00 100%);\n  }\n}\n</style>\n<div class=\"container\">\n  <div class=\"headline\">\n    <svg class=\"logo-light\" width=\"18\" height=\"18\" viewBox=\"9 9 35 35\" fill=\"none\" xmlns=\"http://www.w3.org/2000/svg\">\n      <path fill-rule=\"evenodd\" clip-rule=\"evenodd\" d=\"M42.8622 27.0064C42.8622 25.7839 42.7525 24.6084 42.5487 23.4799H26.3109V30.1568H35.5897C35.1821 32.3041 33.9596 34.1222 32.1258 35.3448V39.6864H37.7213C40.9814 36.677 42.8622 32.2571 42.8622 27.0064V27.0064Z\" fill=\"#4285F4\"/>\n      <path fill-rule=\"evenodd\" clip-rule=\"evenodd\" d=\"M26.3109 43.8555C30.9659 43.8555 34.8687 42.3195 37.7213 39.6863L32.1258 35.3447C30.5898 36.3792 28.6306 37.0061 26.3109 37.0061C21.8282 37.0061 18.0195 33.9811 16.6559 29.906H10.9194V34.3573C13.7563 39.9841 19.5712 43.8555 26.3109 43.8555V43.8555Z\" fill=\"#34A853\"/>\n      <path fill-rule=\"evenodd\" clip-rule=\"evenodd\" d=\"M16.6559 29.8904C16.3111 28.8559 16.1074 27.7588 16.1074 26.6146C16.1074 25.4704 16.3111 24.3733 16.6559 23.3388V18.8875H10.9194C9.74388 21.2072 9.06992 23.8247 9.06992 26.6146C9.06992 29.4045 9.74388 32.022 10.9194 34.3417L15.3864 30.8621L16.6559 29.8904V29.8904Z\" fill=\"#FBBC05\"/>\n      <path fill-rule=\"evenodd\" clip-rule=\"evenodd\" d=\"M26.3109 16.2386C28.85 16.2386 31.107 17.1164 32.9095 18.8091L37.8466 13.8719C34.853 11.082 30.9659 9.3736 26.3109 9.3736C19.5712 9.3736 13.7563 13.245 10.9194 18.8875L16.6559 23.3388C18.0195 19.2636 21.8282 16.2386 26.3109 16.2386V16.2386Z\" fill=\"#EA4335\"/>\n    </svg>\n    <svg class=\"logo-dark\" width=\"18\" height=\"18\" viewBox=\"0 0 48 48\" xmlns=\"http://www.w3.org/2000/svg\">\n      <circle cx=\"24\" cy=\"23\" fill=\"#FFF\" r=\"22\"/>\n      <path d=\"M33.76 34.26c2.75-2.56 4.49-6.37 4.49-11.26 0-.89-.08-1.84-.29-3H24.01v5.99h8.03c-.4 2.02-1.5 3.56-3.07 4.56v.75l3.91 2.97h.88z\" fill=\"#4285F4\"/>\n      <path d=\"M15.58 25.77A8.845 8.845 0 0 0 24 31.86c1.92 0 3.62-.46 4.97-1.31l4.79 3.71C31.14 36.7 27.65 38 24 38c-5.93 0-11.01-3.4-13.45-8.36l.17-1.01 4.06-2.85h.8z\" fill=\"#34A853\"/>\n      <path d=\"M15.59 20.21a8.864 8.864 0 0 0 0 5.58l-5.03 3.86c-.98-2-1.53-4.25-1.53-6.64 0-2.39.55-4.64 1.53-6.64l1-.22 3.81 2.98.22 1.08z\" fill=\"#FBBC05\"/>\n      <path d=\"M24 14.14c2.11 0 4.02.75 5.52 1.98l4.36-4.36C31.22 9.43 27.81 8 24 8c-5.93 0-11.01 3.4-13.45 8.36l5.03 3.85A8.86 8.86 0 0 1 24 14.14z\" fill=\"#EA4335\"/>\n    </svg>\n    <div class=\"gradient-container\"><div class=\"gradient\"></div></div>\n  </div>\n  <div class=\"carousel\">\n    <a class=\"chip\" href=\"https://vertexaisearch.cloud.google.com/grounding-api-redirect/AUZIYQEWMnkwLNBvnx5IQlDJh11kzI8LauYP0aC8P6PhFOPJJFhcEHvrEo2TNbAz8nhc0_5OLWHaYPE-k7YoUnUxFA3EVtaBPsgV3mH5QyVK0-xQm7O6jhppnB1MghD9VyEGptV8C5_sGbEetHf_0oLueEFLXuosLqx7Hw9ssmte7s-c4TSbtohlTR6g4BD55honYnhR87w2gqvRGsvPDMM=\">AI breakthroughs May 2026</a>\n    <a class=\"chip\" href=\"https://vertexaisearch.cloud.google.com/grounding-api-redirect/AUZIYQGLjBuoA3P8hBD8dqDeywnnIAMAQVdJDbQ7XT_RbAQldsaLJqteItdfKNjlux8QuG_yX7G9Z1q82PGVuUaNPcyeiIt5AcNzrRTPdCX1EmyROcz3BUvCLah68JjVuenBcMGXYhDIv9IKg-_GE7y6Noo-_xZnBe4FHpbdzZLCE7wg-qWIxYWg3x-VM99Gw1nDCtpWazvcHIoaTWg6hFkOZOBBP42MoRT89gFKjMmbT4UAxV_a\">notable AI developments last week May 8-15 2026</a>\n    <a class=\"chip\" href=\"https://vertexaisearch.cloud.google.com/grounding-api-redirect/AUZIYQGEme-FkSzLXLhbCp2sw8I_6erizJGjr2E04U7CUCowV8RX5yY19bdNfP_AQ207BS0NT4AKq0GoWmh-gcvBuz5ouLBj2ueT5teYB97rEAsnb8N0tggmiXZzxRpdW2eG9kqF01rbu_bkJMc1kIr2SE4HX9_H3ri7nJIu469nZo4pbXs2coqogScNmg4CJSB4bhaJkUN8JQ==\">AI news May 8 2026</a>\n    <a class=\"chip\" href=\"https://vertexaisearch.cloud.google.com/grounding-api-redirect/AUZIYQGCP5-L2phyiPjI0pyPw7KXVBYJ5xEDb13DgYVWeWIa20mT22m9y4zOe09CnbDEu97K3YX4V1N8-d4s_4aT1oBAXrZ511A123PTibQiCH2PofmgXqC5Qaa_wQ4tWGOY0smYBBcYvNL49-BODxpjs3corGQ2gSEECVHug1BneqzkX_hXrHPO_GFbiLCTeKJFZv7iOoLoCKhLCqsaXxt9tNFw5BA=\">major AI announcements May 2026</a>\n  </div>\n</div>\n"},{"search_suggestions":"<style>\n.container {\n  align-items: center;\n  border-radius: 8px;\n  display: flex;\n  font-family: Google Sans, Roboto, sans-serif;\n  font-size: 14px;\n  line-height: 20px;\n  padding: 8px 12px;\n}\n.chip {\n  display: inline-block;\n  border: solid 1px;\n  border-radius: 16px;\n  min-width: 14px;\n  padding: 5px 16px;\n  text-align: center;\n  user-select: none;\n  margin: 0 8px;\n  -webkit-tap-highlight-color: transparent;\n}\n.carousel {\n  overflow: auto;\n  scrollbar-width: none;\n  white-space: nowrap;\n  margin-right: -12px;\n}\n.headline {\n  display: flex;\n  margin-right: 4px;\n}\n.gradient-container {\n  position: relative;\n}\n.gradient {\n  position: absolute;\n  transform: translate(3px, -9px);\n  height: 36px;\n  width: 9px;\n}\n@media (prefers-color-scheme: light) {\n  .container {\n    background-color: #fafafa;\n    box-shadow: 0 0 0 1px #0000000f;\n  }\n  .headline-label {\n    color: #1f1f1f;\n  }\n  .chip {\n    background-color: #ffffff;\n    border-color: #d2d2d2;\n    color: #5e5e5e;\n    text-decoration: none;\n  }\n  .chip:hover {\n    background-color: #f2f2f2;\n  }\n  .chip:focus {\n    background-color: #f2f2f2;\n  }\n  .chip:active {\n    background-color: #d8d8d8;\n    border-color: #b6b6b6;\n  }\n  .logo-dark {\n    display: none;\n  }\n  .gradient {\n    background: linear-gradient(90deg, #fafafa 15%, #fafafa00 100%);\n  }\n}\n@media (prefers-color-scheme: dark) {\n  .container {\n    background-color: #1f1f1f;\n    box-shadow: 0 0 0 1px #ffffff26;\n  }\n  .headline-label {\n    color: #fff;\n  }\n  .chip {\n    background-color: #2c2c2c;\n    border-color: #3c4043;\n    color: #fff;\n    text-decoration: none;\n  }\n  .chip:hover {\n    background-color: #353536;\n  }\n  .chip:focus {\n    background-color: #353536;\n  }\n  .chip:active {\n    background-color: #464849;\n    border-color: #53575b;\n  }\n  .logo-light {\n    display: none;\n  }\n  .gradient {\n    background: linear-gradient(90deg, #1f1f1f 15%, #1f1f1f00 100%);\n  }\n}\n</style>\n<div class=\"container\">\n  <div class=\"headline\">\n    <svg class=\"logo-light\" width=\"18\" height=\"18\" viewBox=\"9 9 35 35\" fill=\"none\" xmlns=\"http://www.w3.org/2000/svg\">\n      <path fill-rule=\"evenodd\" clip-rule=\"evenodd\" d=\"M42.8622 27.0064C42.8622 25.7839 42.7525 24.6084 42.5487 23.4799H26.3109V30.1568H35.5897C35.1821 32.3041 33.9596 34.1222 32.1258 35.3448V39.6864H37.7213C40.9814 36.677 42.8622 32.2571 42.8622 27.0064V27.0064Z\" fill=\"#4285F4\"/>\n      <path fill-rule=\"evenodd\" clip-rule=\"evenodd\" d=\"M26.3109 43.8555C30.9659 43.8555 34.8687 42.3195 37.7213 39.6863L32.1258 35.3447C30.5898 36.3792 28.6306 37.0061 26.3109 37.0061C21.8282 37.0061 18.0195 33.9811 16.6559 29.906H10.9194V34.3573C13.7563 39.9841 19.5712 43.8555 26.3109 43.8555V43.8555Z\" fill=\"#34A853\"/>\n      <path fill-rule=\"evenodd\" clip-rule=\"evenodd\" d=\"M16.6559 29.8904C16.3111 28.8559 16.1074 27.7588 16.1074 26.6146C16.1074 25.4704 16.3111 24.3733 16.6559 23.3388V18.8875H10.9194C9.74388 21.2072 9.06992 23.8247 9.06992 26.6146C9.06992 29.4045 9.74388 32.022 10.9194 34.3417L15.3864 30.8621L16.6559 29.8904V29.8904Z\" fill=\"#FBBC05\"/>\n      <path fill-rule=\"evenodd\" clip-rule=\"evenodd\" d=\"M26.3109 16.2386C28.85 16.2386 31.107 17.1164 32.9095 18.8091L37.8466 13.8719C34.853 11.082 30.9659 9.3736 26.3109 9.3736C19.5712 9.3736 13.7563 13.245 10.9194 18.8875L16.6559 23.3388C18.0195 19.2636 21.8282 16.2386 26.3109 16.2386V16.2386Z\" fill=\"#EA4335\"/>\n    </svg>\n    <svg class=\"logo-dark\" width=\"18\" height=\"18\" viewBox=\"0 0 48 48\" xmlns=\"http://www.w3.org/2000/svg\">\n      <circle cx=\"24\" cy=\"23\" fill=\"#FFF\" r=\"22\"/>\n      <path d=\"M33.76 34.26c2.75-2.56 4.49-6.37 4.49-11.26 0-.89-.08-1.84-.29-3H24.01v5.99h8.03c-.4 2.02-1.5 3.56-3.07 4.56v.75l3.91 2.97h.88z\" fill=\"#4285F4\"/>\n      <path d=\"M15.58 25.77A8.845 8.845 0 0 0 24 31.86c1.92 0 3.62-.46 4.97-1.31l4.79 3.71C31.14 36.7 27.65 38 24 38c-5.93 0-11.01-3.4-13.45-8.36l.17-1.01 4.06-2.85h.8z\" fill=\"#34A853\"/>\n      <path d=\"M15.59 20.21a8.864 8.864 0 0 0 0 5.58l-5.03 3.86c-.98-2-1.53-4.25-1.53-6.64 0-2.39.55-4.64 1.53-6.64l1-.22 3.81 2.98.22 1.08z\" fill=\"#FBBC05\"/>\n      <path d=\"M24 14.14c2.11 0 4.02.75 5.52 1.98l4.36-4.36C31.22 9.43 27.81 8 24 8c-5.93 0-11.01 3.4-13.45 8.36l5.03 3.85A8.86 8.86 0 0 1 24 14.14z\" fill=\"#EA4335\"/>\n    </svg>\n    <div class=\"gradient-container\"><div class=\"gradient\"></div></div>\n  </div>\n  <div class=\"carousel\">\n    <a class=\"chip\" href=\"https://vertexaisearch.cloud.google.com/grounding-api-redirect/AUZIYQEWMnkwLNBvnx5IQlDJh11kzI8LauYP0aC8P6PhFOPJJFhcEHvrEo2TNbAz8nhc0_5OLWHaYPE-k7YoUnUxFA3EVtaBPsgV3mH5QyVK0-xQm7O6jhppnB1MghD9VyEGptV8C5_sGbEetHf_0oLueEFLXuosLqx7Hw9ssmte7s-c4TSbtohlTR6g4BD55honYnhR87w2gqvRGsvPDMM=\">AI breakthroughs May 2026</a>\n    <a class=\"chip\" href=\"https://vertexaisearch.cloud.google.com/grounding-api-redirect/AUZIYQGLjBuoA3P8hBD8dqDeywnnIAMAQVdJDbQ7XT_RbAQldsaLJqteItdfKNjlux8QuG_yX7G9Z1q82PGVuUaNPcyeiIt5AcNzrRTPdCX1EmyROcz3BUvCLah68JjVuenBcMGXYhDIv9IKg-_GE7y6Noo-_xZnBe4FHpbdzZLCE7wg-qWIxYWg3x-VM99Gw1nDCtpWazvcHIoaTWg6hFkOZOBBP42MoRT89gFKjMmbT4UAxV_a\">notable AI developments last week May 8-15 2026</a>\n    <a class=\"chip\" href=\"https://vertexaisearch.cloud.google.com/grounding-api-redirect/AUZIYQGEme-FkSzLXLhbCp2sw8I_6erizJGjr2E04U7CUCowV8RX5yY19bdNfP_AQ207BS0NT4AKq0GoWmh-gcvBuz5ouLBj2ueT5teYB97rEAsnb8N0tggmiXZzxRpdW2eG9kqF01rbu_bkJMc1kIr2SE4HX9_H3ri7nJIu469nZo4pbXs2coqogScNmg4CJSB4bhaJkUN8JQ==\">AI news May 8 2026</a>\n    <a class=\"chip\" href=\"https://vertexaisearch.cloud.google.com/grounding-api-redirect/AUZIYQGCP5-L2phyiPjI0pyPw7KXVBYJ5xEDb13DgYVWeWIa20mT22m9y4zOe09CnbDEu97K3YX4V1N8-d4s_4aT1oBAXrZ511A123PTibQiCH2PofmgXqC5Qaa_wQ4tWGOY0smYBBcYvNL49-BODxpjs3corGQ2gSEECVHug1BneqzkX_hXrHPO_GFbiLCTeKJFZv7iOoLoCKhLCqsaXxt9tNFw5BA=\">major AI announcements May 2026</a>\n  </div>\n</div>\n"}],"is_error":false},"event_type":"step.delta"}""",
        """{"index":3,"event_type":"step.stop"}""",
        """{"interaction":{"id":"v1_ChdkR3NIYW9QaElNS21xdHNQaHFLbm1RWRIXZEdzSGFvUGhJTUttcXRzUGhxS25tUVk","status":"completed","usage":{"total_tokens":1983,"total_input_tokens":22,"input_tokens_by_modality":[{"modality":"text","tokens":22}],"total_cached_tokens":0,"total_output_tokens":590,"total_tool_use_tokens":198,"tool_use_tokens_by_modality":[{"modality":"text","tokens":198}],"total_thought_tokens":1173},"created":"2026-05-15T18:52:46Z","updated":"2026-05-15T18:52:46Z","service_tier":"standard","object":"interaction","model":"gemini-2.5-flash"},"event_type":"interaction.completed"}""",
    )

    /** `image-output.json`. */
    const val IMAGE_OUTPUT_JSON: String = """{
  "id": "v1_ChdmMnNIYXNPWEJwamxxdHNQc3YtX3FBWRIXZjJzSGFzT1hCcGpscXRzUHN2LV9xQVk",
  "status": "completed",
  "usage": {
    "total_tokens": 1477,
    "total_input_tokens": 12,
    "input_tokens_by_modality": [
      {
        "modality": "text",
        "tokens": 12
      }
    ],
    "total_cached_tokens": 0,
    "total_output_tokens": 1251,
    "output_tokens_by_modality": [
      {
        "modality": "image",
        "tokens": 1120
      }
    ],
    "total_tool_use_tokens": 0,
    "total_thought_tokens": 214
  },
  "created": "2026-05-15T18:53:05Z",
  "updated": "2026-05-15T18:53:05Z",
  "service_tier": "standard",
  "steps": [
    {
      "signature": "ErfBvQEKssG9AQEMOdbH82B7fq5whyWKuoLPQfhfU1SZT4fMzuXLkq7rqcQ4B/U+",
      "type": "thought"
    },
    {
      "content": [
        {
          "mime_type": "image/jpeg",
          "data": "/9j/4AAQSkZJRgABAQEBLAEs",
          "type": "image"
        }
      ],
      "type": "model_output"
    }
  ],
  "object": "interaction",
  "model": "gemini-3-pro-image-preview"
}
"""

    /** `image-output.chunks.txt`, one SSE payload per element. */
    val IMAGE_OUTPUT_CHUNKS: List<String> = listOf(
        """{"interaction":{"id":"v1_ChdrbXNIYXRYSEM3ZXhxdHNQMHFHY3FRRRIXa21zSGF0WEhDN2V4cXRzUDBxR2NxUUU","status":"in_progress","object":"interaction","model":"gemini-3-pro-image-preview"},"event_type":"interaction.created"}""",
        """{"interaction_id":"v1_ChdrbXNIYXRYSEM3ZXhxdHNQMHFHY3FRRRIXa21zSGF0WEhDN2V4cXRzUDBxR2NxUUU","status":"in_progress","event_type":"interaction.status_update"}""",
        """{"index":0,"step":{"type":"thought"},"event_type":"step.start"}""",
        """{"index":0,"delta":{"signature":"EqGhogEKnKGiAQEMOdbHyFhuL3SSgwTmgdvBS9yL3JS+UdUWxdukRFZmow34TcmM","type":"thought_signature"},"event_type":"step.delta"}""",
        """{"index":0,"event_type":"step.stop"}""",
        """{"index":1,"step":{"type":"model_output"},"event_type":"step.start"}""",
        """{"index":1,"delta":{"mime_type":"image/jpeg","data":"/9j/4AAQSkZJRgABAQEBLAEs","type":"image"},"event_type":"step.delta"}""",
        """{"index":1,"event_type":"step.stop"}""",
        """{"interaction":{"id":"v1_ChdrbXNIYXRYSEM3ZXhxdHNQMHFHY3FRRRIXa21zSGF0WEhDN2V4cXRzUDBxR2NxUUU","status":"completed","usage":{"total_tokens":1512,"total_input_tokens":12,"input_tokens_by_modality":[{"modality":"text","tokens":12}],"total_cached_tokens":0,"total_output_tokens":1276,"output_tokens_by_modality":[{"modality":"image","tokens":1120}],"total_tool_use_tokens":0,"total_thought_tokens":224},"created":"2026-05-15T18:53:22Z","updated":"2026-05-15T18:53:22Z","service_tier":"standard","object":"interaction","model":"gemini-3-pro-image-preview"},"event_type":"interaction.completed"}""",
    )

    /** `image-output-modify.json`. */
    const val IMAGE_OUTPUT_MODIFY_JSON: String = """{
  "id": "v1_ChdvMnNIYW9mdU9hR2dxdHNQLTlhby1BVRIXdW1zSGFwUHJCYVRrcXRzUDUtdmkwQUU",
  "status": "completed",
  "usage": {
    "total_tokens": 1403,
    "total_input_tokens": 6,
    "input_tokens_by_modality": [
      {
        "modality": "text",
        "tokens": 6
      }
    ],
    "total_cached_tokens": 0,
    "total_output_tokens": 1250,
    "output_tokens_by_modality": [
      {
        "modality": "image",
        "tokens": 1120
      }
    ],
    "total_tool_use_tokens": 0,
    "total_thought_tokens": 147
  },
  "created": "2026-05-15T18:54:03Z",
  "updated": "2026-05-15T18:54:03Z",
  "service_tier": "standard",
  "steps": [
    {
      "signature": "EtelrAEK0qWsAQEMOdbHrNGdTsR+PMjj0Ut2JONRZvXMI+SFaC21MIkv9jRLKJLc",
      "type": "thought"
    },
    {
      "content": [
        {
          "mime_type": "image/jpeg",
          "data": "/9j/4AAQSkZJRgABAQEBLAEs",
          "type": "image"
        }
      ],
      "type": "model_output"
    }
  ],
  "object": "interaction",
  "model": "gemini-3-pro-image-preview"
}
"""

    /** `image-output-modify.chunks.txt`, one SSE payload per element. */
    val IMAGE_OUTPUT_MODIFY_CHUNKS: List<String> = listOf(
        """{"interaction":{"id":"v1_Chd5MnNIYXJ1ME5aYXltdGtQM1AtNWdRSRIXMzJzSGFyZTlES0t6bXRrUHY4RFE4QUU","status":"in_progress","object":"interaction","model":"gemini-3-pro-image-preview"},"event_type":"interaction.created"}""",
        """{"interaction_id":"v1_Chd5MnNIYXJ1ME5aYXltdGtQM1AtNWdRSRIXMzJzSGFyZTlES0t6bXRrUHY4RFE4QUU","status":"in_progress","event_type":"interaction.status_update"}""",
        """{"index":0,"step":{"type":"thought"},"event_type":"step.start"}""",
        """{"index":0,"delta":{"signature":"EvOmoQEK7qahAQEMOdbH78vBTzzXDyRHYIONd+vAb6OMxFsrFyPKmkpW0o1jMBxT","type":"thought_signature"},"event_type":"step.delta"}""",
        """{"index":0,"event_type":"step.stop"}""",
        """{"index":1,"step":{"type":"model_output"},"event_type":"step.start"}""",
        """{"index":1,"delta":{"mime_type":"image/jpeg","data":"/9j/4AAQSkZJRgABAQEBLAEs","type":"image"},"event_type":"step.delta"}""",
        """{"index":1,"event_type":"step.stop"}""",
        """{"interaction":{"id":"v1_Chd5MnNIYXJ1ME5aYXltdGtQM1AtNWdRSRIXMzJzSGFyZTlES0t6bXRrUHY4RFE4QUU","status":"completed","usage":{"total_tokens":1433,"total_input_tokens":6,"input_tokens_by_modality":[{"modality":"text","tokens":6}],"total_cached_tokens":0,"total_output_tokens":1254,"output_tokens_by_modality":[{"modality":"image","tokens":1120}],"total_tool_use_tokens":0,"total_thought_tokens":173},"created":"2026-05-15T18:54:41Z","updated":"2026-05-15T18:54:41Z","service_tier":"standard","object":"interaction","model":"gemini-3-pro-image-preview"},"event_type":"interaction.completed"}""",
    )
}
