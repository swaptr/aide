package com.sabreware.aide.aisdk.providers.openaicompatible

/** The reference's recorded transcription documents, byte for byte. */
internal object OpenAICompatibleTranscriptionFixtures {

    /** `packages/openai/src/transcription/__fixtures__/openai-diarized-transcription.json`. */
    const val DIARIZED: String = """
{
  "task": "transcribe",
  "duration": 3.2,
  "text": "Hello from Alice. Hello from Bob.",
  "segments": [
    {
      "type": "transcript.text.segment",
      "id": "seg_001",
      "start": 0,
      "end": 1.5,
      "text": "Hello from Alice.",
      "speaker": "A"
    },
    {
      "type": "transcript.text.segment",
      "id": "seg_002",
      "start": 1.5,
      "end": 3.2,
      "text": "Hello from Bob.",
      "speaker": "B"
    }
  ]
}
"""
}
