package com.tawerzz.airsplayer.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Applies the Airs 13-filter cascade and output GainNode using float PCM internally and on output. */
@OptIn(UnstableApi::class)
class EqAudioProcessor(private val engine: EqDspEngine) : BaseAudioProcessor() {
    private var floatScratch = FloatArray(0)
    private var channels = 2

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        val encoding = inputAudioFormat.encoding
        if (encoding != C.ENCODING_PCM_FLOAT && encoding != C.ENCODING_PCM_16BIT) {
            return AudioProcessor.AudioFormat.NOT_SET
        }

        channels = inputAudioFormat.channelCount
        engine.configure(inputAudioFormat.sampleRate, channels)

        // Always emit float PCM. This keeps peaks above +/-1.0 intact through the EQ stage,
        // so they can be attenuated by Android's output volume before final device conversion.
        return AudioProcessor.AudioFormat(
            inputAudioFormat.sampleRate,
            inputAudioFormat.channelCount,
            C.ENCODING_PCM_FLOAT
        )
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val inputBytes = inputBuffer.remaining()
        if (inputBytes <= 0) return

        val sampleCount = when (inputAudioFormat.encoding) {
            C.ENCODING_PCM_FLOAT -> inputBytes / 4
            C.ENCODING_PCM_16BIT -> inputBytes / 2
            else -> return
        }
        if (sampleCount == 0 || channels <= 0) return

        if (floatScratch.size < sampleCount) {
            floatScratch = FloatArray(sampleCount)
        }

        val source = inputBuffer.duplicate().order(ByteOrder.nativeOrder())
        when (inputAudioFormat.encoding) {
            C.ENCODING_PCM_FLOAT -> {
                source.asFloatBuffer().get(floatScratch, 0, sampleCount)
            }
            C.ENCODING_PCM_16BIT -> {
                val shorts = source.asShortBuffer()
                for (i in 0 until sampleCount) {
                    floatScratch[i] = shorts.get().toFloat() / 32768f
                }
            }
        }

        engine.processInPlace(floatScratch, sampleCount / channels, channels)

        val outputBytes = sampleCount * 4
        val output = replaceOutputBuffer(outputBytes).order(ByteOrder.nativeOrder())
        output.asFloatBuffer().put(floatScratch, 0, sampleCount)
        output.position(outputBytes)
        output.flip()

        inputBuffer.position(inputBuffer.limit())
    }

    override fun onFlush(streamMetadata: AudioProcessor.StreamMetadata) {
        super.onFlush(streamMetadata)
        engine.resetStreamState()
    }

    override fun onReset() {
        floatScratch = FloatArray(0)
    }
}
