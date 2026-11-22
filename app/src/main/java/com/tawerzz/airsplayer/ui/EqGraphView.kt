package com.tawerzz.airsplayer.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.tawerzz.airsplayer.audio.DspHolder
import com.tawerzz.airsplayer.audio.EqDspEngine
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

class EqGraphView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {
    var onSettingsChanged: ((EqDspEngine.Settings, Boolean) -> Unit)? = null
    var onNodeSelected: ((Int) -> Unit)? = null

    private val density = resources.displayMetrics.density
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x334C5868; strokeWidth = dp(1f) }
    private val axisPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x667D8A9A; strokeWidth = dp(1f) }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF9CA9B8.toInt(); textSize = dp(10f) }
    private val curvePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF65D6B4.toInt(); style = Paint.Style.STROKE; strokeWidth = dp(2.2f); strokeJoin = Paint.Join.ROUND
    }
    private val spectrumPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x4465D6B4; style = Paint.Style.FILL }
    private val pointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFF0F4F8.toInt(); style = Paint.Style.FILL }
    private val shelfPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFB89AE6.toInt(); style = Paint.Style.FILL }
    private val selectedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFD27A.toInt(); style = Paint.Style.STROKE; strokeWidth = dp(2f)
    }
    private val responsePath = Path()
    private val graphRect = RectF()
    private var spectrum = FloatArray(EqDspEngine.FFT_SIZE / 2)
    private var selected = 2
    private var dragging = false
    private var cacheVersion = Long.MIN_VALUE
    private var responsePoints = FloatArray(400)
    private val minFreq = 1.0
    private val maxFreq = 21500.0

    init {
        setLayerType(View.LAYER_TYPE_SOFTWARE, null)
        isClickable = true
    }

    fun setSpectrum(values: FloatArray) {
        spectrum = values
        invalidate()
    }

    fun selectedNode(): Int = selected

    fun selectNode(index: Int) {
        selected = index.coerceIn(0, EqDspEngine.FILTER_COUNT - 1)
        onNodeSelected?.invoke(selected)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val left = dp(42f)
        val right = width - dp(12f)
        val top = dp(12f)
        val bottom = height - dp(28f)
        if (right <= left || bottom <= top) return
        graphRect.set(left, top, right, bottom)

        canvas.drawRoundRect(graphRect, dp(12f), dp(12f), Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF171E29.toInt() })
        drawGrid(canvas)
        drawSpectrum(canvas)
        drawResponse(canvas)
        drawNodes(canvas)
    }

    private fun drawGrid(canvas: Canvas) {
        val gains = floatArrayOf(30f, 20f, 10f, 0f, -10f, -20f, -30f)
        for (gain in gains) {
            val y = gainToY(gain)
            canvas.drawLine(graphRect.left, y, graphRect.right, y, if (gain == 0f) axisPaint else gridPaint)
            textPaint.textAlign = Paint.Align.RIGHT
            canvas.drawText(if (gain > 0) "+${gain.toInt()}" else gain.toInt().toString(), graphRect.left - dp(7f), y + dp(3.5f), textPaint)
        }
        val frequencies = doubleArrayOf(10.0, 20.0, 50.0, 100.0, 200.0, 500.0, 1000.0, 2000.0, 5000.0, 10000.0, 20000.0)
        for (f in frequencies) {
            val x = freqToX(f)
            canvas.drawLine(x, graphRect.top, x, graphRect.bottom, gridPaint)
            textPaint.textAlign = Paint.Align.CENTER
            val label = when {
                f >= 1000 -> "${(f / 1000).toInt()}k"
                else -> f.toInt().toString()
            }
            canvas.drawText(label, x, height - dp(9f), textPaint)
        }
    }

    private fun drawSpectrum(canvas: Canvas) {
        if (spectrum.isEmpty()) return
        val bars = 88
        val sampleRateNyquist = DspHolder.engine.sampleRate / 2.0
        val barWidth = graphRect.width() / bars
        for (i in 0 until bars) {
            val f1 = minFreq * (maxFreq / minFreq).pow(i.toDouble() / bars)
            val f2 = minFreq * (maxFreq / minFreq).pow((i + 1.0) / bars)
            val center = (f1 + f2) * 0.5
            val bin = (center / sampleRateNyquist * spectrum.size).toInt().coerceIn(0, spectrum.size - 1)
            val level = spectrum[bin].coerceIn(0f, 1f)
            val h = graphRect.height() * 0.72f * level
            val x = graphRect.left + i * barWidth
            canvas.drawRoundRect(x + dp(1f), graphRect.bottom - h, x + barWidth - dp(1f), graphRect.bottom, dp(2f), dp(2f), spectrumPaint)
        }
    }

    private fun drawResponse(canvas: Canvas) {
        val settings = DspHolder.engine.settings
        if (cacheVersion != settings.version()) {
            val n = responsePoints.size / 2
            for (i in 0 until n) {
                val f = minFreq * (maxFreq / minFreq).pow(i.toDouble() / (n - 1))
                responsePoints[i * 2] = f.toFloat()
                responsePoints[i * 2 + 1] = DspHolder.engine.getResponseDb(f).toFloat()
            }
            cacheVersion = settings.version()
        }
        responsePath.reset()
        for (i in 0 until responsePoints.size / 2) {
            val x = freqToX(responsePoints[i * 2].toDouble())
            val y = gainToY(responsePoints[i * 2 + 1].coerceIn(-30f, 30f))
            if (i == 0) responsePath.moveTo(x, y) else responsePath.lineTo(x, y)
        }
        canvas.save()
        canvas.clipRect(graphRect)
        canvas.drawPath(responsePath, curvePaint)
        canvas.restore()
    }

    private fun drawNodes(canvas: Canvas) {
        val s = DspHolder.engine.settings
        val frequencies = s.frequenciesHz()
        val gains = s.gainsDb()
        for (i in 0 until EqDspEngine.FILTER_COUNT) {
            val x = freqToX(frequencies[i].toDouble())
            val y = gainToY(gains[i])
            canvas.drawCircle(x, y, dp(if (i == selected) 8f else 6f), if (i == 2 || i == 12) shelfPaint else pointPaint)
            if (i == selected) {
                canvas.drawCircle(x, y, dp(12f), selectedPaint)
                textPaint.textAlign = Paint.Align.CENTER
                textPaint.color = 0xFFFFD27A.toInt()
                val label = if (frequencies[i] >= 1000) "${(frequencies[i] / 1000f).let { String.format("%.1fk", it) }}" else "${frequencies[i].toInt()} Hz"
                canvas.drawText(label, x, max(graphRect.top + dp(12f), y - dp(16f)), textPaint)
                textPaint.color = 0xFF9CA9B8.toInt()
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val s = DspHolder.engine.settings
                val freqs = s.frequenciesHz()
                val gains = s.gainsDb()
                var closest = -1
                var best = dp(28f)
                for (i in 0 until EqDspEngine.FILTER_COUNT) {
                    val dx = event.x - freqToX(freqs[i].toDouble())
                    val dy = event.y - gainToY(gains[i])
                    val d = kotlin.math.sqrt(dx * dx + dy * dy)
                    if (d < best) { best = d; closest = i }
                }
                if (closest < 0) return false
                selected = closest
                dragging = true
                parent?.requestDisallowInterceptTouchEvent(true)
                onNodeSelected?.invoke(selected)
                updateNode(event.x, event.y, false)
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging) return false
                updateNode(event.x, event.y, false)
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!dragging) return false
                dragging = false
                val s = DspHolder.engine.settings
                onSettingsChanged?.invoke(s, true)
                parent?.requestDisallowInterceptTouchEvent(false)
                performClick()
                invalidate()
                return true
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun updateNode(x: Float, y: Float, commit: Boolean) {
        val s = DspHolder.engine.settings
        val freq = xToFreq(x)
        val gain = yToGain(y)
        val updated = s.withNode(selected, freq, gain, s.baseQ()[selected])
        DspHolder.engine.setSettings(updated)
        onSettingsChanged?.invoke(updated, commit)
    }

    private fun freqToX(frequency: Double): Float {
        val t = (log10(frequency.coerceIn(minFreq, maxFreq)) - log10(minFreq)) /
                (log10(maxFreq) - log10(minFreq))
        return (graphRect.left + t * graphRect.width()).toFloat()
    }

    private fun xToFreq(x: Float): Float {
        val t = ((x - graphRect.left) / graphRect.width()).toDouble().coerceIn(0.0, 1.0)
        return (minFreq * (maxFreq / minFreq).pow(t)).toFloat()
    }

    private fun gainToY(gain: Float): Float = graphRect.top + (30f - gain.coerceIn(-30f, 30f)) / 60f * graphRect.height()
    private fun yToGain(y: Float): Float = (30f - ((y - graphRect.top) / graphRect.height()) * 60f).coerceIn(-30f, 30f)
    private fun dp(value: Float): Float = value * density
}
