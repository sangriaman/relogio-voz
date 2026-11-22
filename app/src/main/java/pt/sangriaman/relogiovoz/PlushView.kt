package pt.sangriaman.relogiovoz

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sin

/**
 * O Tricky: o coelhinho de peluche (branco, orelhas cor-de-rosa, caracol bordado na testa,
 * nariz cor-de-rosa, barriga cor-de-rosa com as patas por cima).
 * Estados: "idle" (respira, pisca e abana as orelhas), "listening" (orelhas em pé e ondas),
 * "thinking" (olha para cima, três pontinhos), "speaking" (mexe a boca e acena).
 */
class PlushView(ctx: Context) : View(ctx) {

    var mode: String = "idle"
        set(value) {
            field = value
            invalidate()
        }

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

    private fun fill(c: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = c }
    private fun line(c: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = c
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val white = fill(Color.rgb(250, 248, 244))
    private val shade = line(Color.rgb(205, 200, 195))
    private val pink = fill(Color.rgb(246, 196, 210))
    private val pinkEdge = fill(Color.rgb(252, 226, 233))
    private val nose = fill(Color.rgb(236, 110, 140))
    private val black = fill(Color.rgb(20, 20, 22))
    private val mouth = line(Color.rgb(20, 20, 22))
    private val curl = line(Color.rgb(120, 130, 140))
    private val glow = fill(Color.argb(70, 120, 200, 255))
    private val wave = line(Color.rgb(120, 200, 255))

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
        val u = min(width / 1.3f, height / 2.45f)
        val cx = width / 2f
        val y0 = (height - 2.32f * u) / 2f + sin(t * 2f) * 0.01f * u // respirar
        shade.strokeWidth = u * 0.012f
        mouth.strokeWidth = u * 0.022f
        curl.strokeWidth = u * 0.016f
        wave.strokeWidth = u * 0.025f

        val listening = mode == "listening"
        val speaking = mode == "speaking"
        val thinking = mode == "thinking"

        // ---------------- brilho e ondas quando está a ouvir
        if (listening) {
            c.drawCircle(cx, y0 + 1.15f * u, 0.85f * u, glow)
            for (i in 0..2) {
                val phase = (t * 1.4f + i / 3f) % 1f
                wave.alpha = (255 * (1f - phase)).toInt()
                val r = u * (0.75f + phase * 0.45f)
                val oy = y0 + 1.15f * u
                c.drawArc(RectF(cx - r, oy - r, cx + r, oy + r), 200f, 40f, false, wave)
                c.drawArc(RectF(cx - r, oy - r, cx + r, oy + r), 300f, 40f, false, wave)
            }
        }

        // ---------------- orelhas
        val sway = sin(t * 1.3f) * 3f
        val earAngle = when {
            listening -> 5f
            thinking -> 16f
            else -> 12f + sway
        }
        val lift = if (listening) 0.06f * u else 0f
        drawEar(c, cx - 0.2f * u, y0 + 0.42f * u - lift, -earAngle, u)
        drawEar(c, cx + 0.2f * u, y0 + 0.42f * u - lift, earAngle + (if (speaking) sin(t * 4f) * 4f else 0f), u)

        // ---------------- pés e corpo
        foot(c, RectF(cx - 0.40f * u, y0 + 2.08f * u, cx - 0.12f * u, y0 + 2.30f * u))
        foot(c, RectF(cx + 0.12f * u, y0 + 2.08f * u, cx + 0.40f * u, y0 + 2.30f * u))
        val body = RectF(cx - 0.36f * u, y0 + 1.40f * u, cx + 0.36f * u, y0 + 2.20f * u)
        c.drawRoundRect(body, 0.2f * u, 0.2f * u, white)
        c.drawRoundRect(body, 0.2f * u, 0.2f * u, shade)
        // barriga cor-de-rosa
        c.drawOval(RectF(cx - 0.27f * u, y0 + 1.60f * u, cx + 0.24f * u, y0 + 2.16f * u), pink)

        // patas por cima da barriga (a da direita acena quando fala)
        val wavePaw = if (speaking) sin(t * 7f) * 18f else 0f
        paw(c, cx - 0.20f * u, y0 + 1.84f * u, 25f, u)
        paw(c, cx + 0.20f * u, y0 + 1.84f * u, -25f + wavePaw, u)

        // ---------------- cabeça (um pouco mais larga em baixo)
        val tilt = when {
            listening -> 7f
            thinking -> -5f
            else -> sin(t * 0.7f) * 1.5f
        }
        c.save()
        c.rotate(tilt, cx, y0 + 1.45f * u)
        val head = Path().apply {
            moveTo(cx, y0 + 0.74f * u)
            cubicTo(cx + 0.40f * u, y0 + 0.74f * u, cx + 0.62f * u, y0 + 1.20f * u, cx + 0.56f * u, y0 + 1.36f * u)
            cubicTo(cx + 0.50f * u, y0 + 1.55f * u, cx - 0.50f * u, y0 + 1.55f * u, cx - 0.56f * u, y0 + 1.36f * u)
            cubicTo(cx - 0.62f * u, y0 + 1.20f * u, cx - 0.40f * u, y0 + 0.74f * u, cx, y0 + 0.74f * u)
            close()
        }
        c.drawPath(head, white)
        c.drawPath(head, shade)

        // caracol bordado na testa
        val fy = y0 + 0.87f * u
        val cp = Path().apply {
            moveTo(cx - 0.11f * u, fy - 0.01f * u)
            quadTo(cx - 0.06f * u, fy + 0.05f * u, cx - 0.01f * u, fy + 0.01f * u)
            quadTo(cx + 0.03f * u, fy - 0.05f * u, cx - 0.005f * u, fy - 0.05f * u)
            quadTo(cx - 0.03f * u, fy - 0.03f * u, cx + 0.01f * u, fy + 0.02f * u)
            quadTo(cx + 0.06f * u, fy + 0.05f * u, cx + 0.11f * u, fy - 0.01f * u)
        }
        c.drawPath(cp, curl)

        // olhos (piscam; olham para cima a pensar)
        val ey = y0 + 1.12f * u + if (thinking) -0.04f * u else 0f
        val blink = (t % 4.1f) < 0.14f && !thinking
        for (side in listOf(-1f, 1f)) {
            val ex = cx + side * 0.25f * u
            if (blink) {
                c.drawLine(ex - 0.05f * u, ey, ex + 0.05f * u, ey, mouth)
            } else {
                c.drawCircle(ex, ey, 0.055f * u, black)
                c.drawCircle(ex - 0.018f * u, ey - 0.02f * u, 0.015f * u, white)
            }
        }

        // nariz cor-de-rosa (triângulo arredondado)
        val ny = y0 + 1.14f * u
        val np = Path().apply {
            moveTo(cx - 0.08f * u, ny - 0.04f * u)
            quadTo(cx, ny - 0.07f * u, cx + 0.08f * u, ny - 0.04f * u)
            quadTo(cx + 0.06f * u, ny + 0.04f * u, cx, ny + 0.06f * u)
            quadTo(cx - 0.06f * u, ny + 0.04f * u, cx - 0.08f * u, ny - 0.04f * u)
            close()
        }
        c.drawPath(np, nose)

        // boca
        val my = y0 + 1.24f * u
        if (speaking) {
            val open = abs(sin(t * 11f)) * 0.09f * u + 0.02f * u
            c.drawOval(RectF(cx - 0.09f * u, my - 0.02f * u, cx + 0.09f * u, my + open), black)
            c.drawOval(RectF(cx - 0.05f * u, my + open * 0.4f, cx + 0.05f * u, my + open), nose)
        } else {
            val mp = Path().apply {
                moveTo(cx - 0.28f * u, my - 0.02f * u)
                quadTo(cx - 0.14f * u, my + 0.13f * u, cx, my)
                quadTo(cx + 0.14f * u, my + 0.13f * u, cx + 0.28f * u, my - 0.02f * u)
            }
            c.drawLine(cx, ny + 0.06f * u, cx, my, mouth)
            c.drawPath(mp, mouth)
        }
        c.restore()

        // pontinhos a pensar
        if (thinking) {
            for (i in 0..2) {
                val up = sin(t * 5f - i * 0.8f) * 0.04f * u
                c.drawCircle(cx + (0.45f + i * 0.14f) * u, y0 + 0.70f * u + up, 0.045f * u, white)
            }
        }
    }

    private fun drawEar(c: Canvas, x: Float, y: Float, angle: Float, u: Float) {
        c.save()
        c.rotate(angle, x, y + 0.38f * u)
        val r = RectF(x - 0.15f * u, y - 0.42f * u, x + 0.15f * u, y + 0.42f * u)
        c.drawOval(r, pinkEdge)
        c.drawOval(RectF(r.left + 0.03f * u, r.top + 0.04f * u, r.right - 0.03f * u, r.bottom - 0.02f * u), pink)
        c.restore()
    }

    private fun foot(c: Canvas, r: RectF) {
        c.drawOval(r, white)
        c.drawOval(r, shade)
    }

    private fun paw(c: Canvas, x: Float, y: Float, angle: Float, u: Float) {
        c.save()
        c.rotate(angle, x, y)
        val r = RectF(x - 0.14f * u, y - 0.06f * u, x + 0.14f * u, y + 0.06f * u)
        c.drawOval(r, white)
        c.drawOval(r, shade)
        c.restore()
    }
}
