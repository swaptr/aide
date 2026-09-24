package com.sabreware.aide.data.speech.audio

import okio.FileSystem
import okio.Path
import okio.buffer

/**
 * Writes 16-bit PCM mono WAV from float samples in [-1, 1] — the app's on-device audio-clip container.
 *
 * The bytes come from [WavCodec], so the header is stated once for the clip recorder and the cloud
 * transcription upload alike; okio carries them to disk on every target.
 */
object WavWriter {

    fun write(fileSystem: FileSystem, path: Path, samples: FloatArray, sampleRate: Int) {
        fileSystem.sink(path).buffer().use { out -> out.write(WavCodec.encode(samples, sampleRate)) }
    }
}
