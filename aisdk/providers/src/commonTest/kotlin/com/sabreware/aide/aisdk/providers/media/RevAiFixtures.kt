package com.sabreware.aide.aisdk.providers.media

/**
 * Rev AI's three recorded steps — `revai/src/__fixtures__`, copied byte for byte.
 *
 * The transcript is a list of ELEMENTS rather than words: only the `text` ones carry timings, and the
 * `punct` ones between them belong to the word before rather than to a segment of their own.
 */
internal object RevAiFixtures {

    const val SUBMIT: String =
        """{"id":"test-id","created_on":"2026-02-12T23:26:22.276Z","name":"audio.mp3","status":"in_progress","type":"async","language":"en"}"""

    const val STATUS: String =
        """{"id":"test-id","created_on":"2026-02-12T23:26:22.276Z","completed_on":"2026-02-12T23:26:26.971Z","name":"audio.mp3","status":"transcribed","duration_seconds":2.51,"type":"async","language":"en"}"""

    const val TRANSCRIPT: String =
        """{"monologues":[{"speaker":0,"elements":[{"type":"text","value":"Hello","ts":0.075,"end_ts":0.425,"confidence":0.96},{"type":"punct","value":" "},{"type":"text","value":"from","ts":0.425,"end_ts":0.665,"confidence":0.98},{"type":"punct","value":" "},{"type":"text","value":"the","ts":0.665,"end_ts":0.785,"confidence":0.98},{"type":"punct","value":" "},{"type":"text","value":"Sal","ts":0.945,"end_ts":1.105,"confidence":0.64},{"type":"punct","value":","},{"type":"punct","value":" "},{"type":"text","value":"A-I-S-D-K","ts":1.185,"end_ts":2.145,"confidence":0.96},{"type":"punct","value":"."}]}]}"""
}
