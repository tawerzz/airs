package com.tawerzz.airsplayer.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

/** Applies the Airs 13-filter cascade and its output GainNode to decoded PCM. */
@OptIn(UnstableApi::class)
class EqAudioProcessor(private val engine: EqDspEngine) : BaseAudioProcessor() {
    private var floatScratch = FloatArray(0)
    private var shortScratch = FloatArray(0)
    private var channels = 2

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        val encoding = inputAudioFormat.encoding
        if (encoding != C.ENCODING_PCM_FLOAT && encoding != C.ENCODING_PCM_16BIT) {
            return AudioProcessor.AudioFormat.NOT_SET
        }
        channels = inputAudioFormat.channelCount
        engine.configure(inputAudioFormat.sampleRate, channels)
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val byteCount = inputBuffer.remaining()
        if (byteCount <= 0) return
        val output = replaceOutputBuffer(byteCount).order(ByteOrder.nativeOrder())
        when (inputAudioFormat.encoding) {
            C.ENCODING_PCM_FLOAT -> {
                val sampleCount = byteCount / 4
                if (floatScratch.size < sampleCount) floatScratch = FloatArray(sampleCount)
                inputBuffer.duplicate().order(ByteOrder.nativeOrder()).asFloatBuffer()
                    .get(floatScratch, 0, sampleCount)
                engine.processInPlace(floatScratch, sampleCount / channels, channels)
                output.asFloatBuffer().put(floatScratch, 0, sampleCount)
            }
            C.ENCODING_PCM_16BIT -> {
                val sampleCount = byteCount / 2
                if (shortScratch.size < sampleCount) shortScratch = FloatArray(sampleCount)
                inputBuffer.duplicate().order(ByteOrder.nativeOrder()).asShortBuffer().let { src ->
                    for (i in 0 until sampleCount) shortScratch[i] = src.get().toFloat() / 32768f
                }
                engine.processInPlace(shortScratch, sampleCount / channels, channels)
                val dst = output.asShortBuffer()
                for (i in 0 until sampleCount) {
                    // PCM16 conversion is the format's normal quantization, not an added limiter.
                    val sample = (shortScratch[i].coerceIn(-1f, 1f) * 32768f).roundToInt()
                        .coerceIn(-32768, 32767)
                    dst.put(sample.toShort())
                }
            }
            else -> return
        }
        inputBuffer.position(inputBuffer.limit())
        output.position(byteCount)
        output.flip()
    }

    override fun onFlush(streamMetadata: AudioProcessor.StreamMetadata) {
        super.onFlush(streamMetadata)
        engine.resetStreamState()
    }

    override fun onReset() {
        floatScratch = FloatArray(0)
        shortScratch = FloatArray(0)
    }
}
