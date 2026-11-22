package pt.sangriaman.relogiovoz

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sin

/**
 * O Tricky em 3D: imagens geradas a partir do modelo 3D do coelhinho (olhos abertos,
 * a piscar e duas posições da boca a falar), animadas com respiração, inclinação e
 * efeitos por cima. Estados: "idle", "listening", "thinking", "speaking".
 */
class PlushView(ctx: Context) : View(ctx) {

    var mode: String = "idle"
        set(value) {
            field = value
            invalidate()
        }

    private fun load(id: Int): Bitmap = BitmapFactory.decodeResource(resources, id)
    private val idle = load(R.drawable.tricky_idle)
    private val blink = load(R.drawable.tricky_blink)
    private val talk1 = load(R.drawable.tricky_talk1)
    private val talk2 = load(R.drawable.tricky_talk2)

    private var t = 0f
    private val anim = ValueAnimator.ofFloat(0f, 1000f).apply {
        duration = 1_000_000L
        interpolator = LinearInterpolator()
        repeatCount = ValueAnimator.INFINITE
        addUpdateListener {
            t = it.animatedValue as Float
            invalidate()
        }
    }

    private val bmpPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(45, 0, 40, 45) }
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(70, 255, 255, 255) }
    private val wave = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        anim.start()
    }

    override fun onDetachedFromWindow() {
        anim.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        val aspect = idle.width.toFloat() / idle.height
        val h = min(height * 0.92f, width / aspect)
        val w = h * aspect
        val cx = width / 2f
        val bottom = height * 0.97f

        val frame = when (mode) {
            "speaking" -> when ((t * 8f).toInt() % 4) {
                0 -> talk2
                1 -> talk1
                2 -> idle
                else -> talk1
            }
            "thinking" -> idle
            else -> if ((t % 4.1f) < 0.15f) blink else idle
        }

        val breathe = 1f + sin(t * 2f) * 0.012f
        val hop = if (mode == "listening") -abs(sin(t * 6f)) * h * 0.025f else 0f
        val tilt = when (mode) {
            "listening" -> 6f
            "thinking" -> -5f + sin(t * 1.5f) * 2f
            "speaking" -> sin(t * 3f) * 3f
            else -> sin(t * 0.7f) * 1.5f
        }

        // sombra no chão
        val sw = w * 0.55f
        c.drawOval(RectF(cx - sw / 2, bottom - h * 0.025f, cx + sw / 2, bottom + h * 0.02f), shadow)

        if (mode == "listening") {
            val cy = bottom - h * 0.6f
            c.drawCircle(cx, cy, h * 0.42f, glow)
            wave.strokeWidth = h * 0.012f
            for (i in 0..2) {
                val phase = (t * 1.4f + i / 3f) % 1f
                wave.alpha = (255 * (1f - phase)).toInt()
                val r = h * (0.38f + phase * 0.2f)
                c.drawArc(RectF(cx - r, cy - r, cx + r, cy + r), 200f, 40f, false, wave)
                c.drawArc(RectF(cx - r, cy - r, cx + r, cy + r), 300f, 40f, false, wave)
            }
        }

        c.save()
        c.rotate(tilt, cx, bottom)
        c.scale(1f / breathe, breathe, cx, bottom)
        c.translate(0f, hop)
        c.drawBitmap(frame, null, RectF(cx - w / 2, bottom - h, cx + w / 2, bottom), bmpPaint)
        c.restore()

        if (mode == "thinking") {
            for (i in 0..2) {
                val up = sin(t * 5f - i * 0.8f) * h * 0.02f
                c.drawCircle(cx + w * (0.38f + i * 0.12f), bottom - h * 0.92f + up, h * 0.022f, dot)
            }
        }
    }
}
