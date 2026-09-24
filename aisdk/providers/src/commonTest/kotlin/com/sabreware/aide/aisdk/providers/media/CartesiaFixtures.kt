package com.sabreware.aide.aisdk.providers.media

/**
 * `cartesia/src/__fixtures__/cartesia-transcription.json`, copied byte for byte.
 *
 * Cartesia calls a word `word` in the one place the contract calls it `text`, so the whole per-word
 * timing array reads back as empty strings against any mapper that assumed otherwise.
 */
internal object CartesiaFixtures {

    const val TRANSCRIPTION: String =
        """{"text":"Hello from the Vercel AI SDK.","language":"en","duration":2.479,"words":[{"word":"Hello","start":0.199,"end":0.479},{"word":"from","start":0.5,"end":0.639},{"word":"the","start":0.66,"end":0.759},{"word":"Vercel","start":0.759,"end":1.12},{"word":"AI","start":1.2,"end":1.519},{"word":"SDK.","start":1.58,"end":2.479}]}"""
}
