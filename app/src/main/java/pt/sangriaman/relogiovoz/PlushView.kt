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
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sin

/**
 * O Tricky: um peluche desenhado à mão (ursinho de pano com remendo e botões).
 * Estados: "idle" (pisca os olhos e respira), "listening" (orelhas atentas e ondas),
 * "thinking" (olha para cima, três pontinhos), "speaking" (mexe a boca).
 */
class PlushView(ctx: Context) : View(ctx) {

    var mode: String = "idle"
        set(value) {
            field = value
            invalidate()
        }

    private var t = 0f // tempo da animação, em segundos
    private val anim = ValueAnimator.ofFloat(0f, 1000f).apply {
        duration = 1_000_000L
        interpolator = LinearInterpolator()
        repeatCount = ValueAnimator.INFINITE
        addUpdateListener {
            t = it.animatedValue as Float
            invalidate()
        }
    }

    private val fur = paint(Color.rgb(196, 140, 92))
    private val furDark = paint(Color.rgb(150, 100, 62))
    private val muzzle = paint(Color.rgb(240, 214, 178))
    private val innerEar = paint(Color.rgb(236, 168, 160))
    private val black = paint(Color.rgb(30, 22, 18))
    private val white = paint(Color.WHITE)
    private val cheek = paint(Color.argb(90, 240, 110, 110))
    private val patch = paint(Color.rgb(110, 170, 200))
    private val glow = paint(Color.argb(120, 120, 200, 255))
    private val stitch = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(90, 60, 40)
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val wave = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(120, 200, 255)
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private fun paint(c: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = c }

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
        val s = min(width / 2f, height / 2.3f) // unidade de escala
        val cx = width / 2f
        val breathe = sin(t * 2f) * 0.015f * s
        val cy = height * 0.40f + breathe
        stitch.strokeWidth = s * 0.025f
        wave.strokeWidth = s * 0.03f

        val tilt = when (mode) {
            "listening" -> 8f
            "thinking" -> -6f + sin(t * 1.5f) * 2f
            else -> sin(t * 0.8f) * 2f
        }

        // ---------------- corpo
        val bodyTop = cy + s * 0.45f
        c.drawOval(RectF(cx - s * 0.62f, bodyTop, cx + s * 0.62f, bodyTop + s * 0.95f), fur)
        // barriga
        c.drawOval(RectF(cx - s * 0.38f, bodyTop + s * 0.18f, cx + s * 0.38f, bodyTop + s * 0.82f), muzzle)
        // remendo azul com pontos
        val pr = RectF(cx + s * 0.08f, bodyTop + s * 0.38f, cx + s * 0.30f, bodyTop + s * 0.60f)
        c.drawRoundRect(pr, s * 0.04f, s * 0.04f, patch)
        c.drawRoundRect(pr, s * 0.04f, s * 0.04f, stitch)
        // braços (acenam quando fala)
        val wavePaw = if (mode == "speaking") sin(t * 6f) * 10f else 0f
        c.save(); c.rotate(-25f + wavePaw, cx - s * 0.55f, bodyTop + s * 0.25f)
        c.drawOval(RectF(cx - s * 0.85f, bodyTop + s * 0.15f, cx - s * 0.45f, bodyTop + s * 0.42f), furDark)
        c.restore()
        c.save(); c.rotate(25f, cx + s * 0.55f, bodyTop + s * 0.25f)
        c.drawOval(RectF(cx + s * 0.45f, bodyTop + s * 0.15f, cx + s * 0.85f, bodyTop + s * 0.42f), furDark)
        c.restore()
        // pés
        c.drawOval(RectF(cx - s * 0.55f, bodyTop + s * 0.78f, cx - s * 0.15f, bodyTop + s * 1.02f), furDark)
        c.drawOval(RectF(cx + s * 0.15f, bodyTop + s * 0.78f, cx + s * 0.55f, bodyTop + s * 1.02f), furDark)
        c.drawCircle(cx - s * 0.35f, bodyTop + s * 0.90f, s * 0.07f, muzzle)
        c.drawCircle(cx + s * 0.35f, bodyTop + s * 0.90f, s * 0.07f, muzzle)

        // ---------------- cabeça (inclina conforme o estado)
        c.save()
        c.rotate(tilt, cx, cy + s * 0.3f)

        // ondas de som quando está a ouvir
        if (mode == "listening") {
            for (i in 0..2) {
                val phase = ((t * 1.5f + i / 3f) % 1f)
                wave.alpha = (255 * (1f - phase)).toInt()
                val r = s * (0.9f + phase * 0.5f)
                c.drawArc(RectF(cx - r, cy - r, cx + r, cy + r), -60f, -60f, false, wave)
                c.drawArc(RectF(cx - r, cy - r, cx + r, cy + r), -120f, 60f, false, wave)
            }
            c.drawCircle(cx, cy, s * 0.82f, glow)
        }

        // orelhas (levantam quando ouve)
        val earLift = if (mode == "listening") s * 0.08f else 0f
        for (side in listOf(-1f, 1f)) {
            val ex = cx + side * s * 0.52f
            val ey = cy - s * 0.48f - earLift
            c.drawCircle(ex, ey, s * 0.24f, fur)
            c.drawCircle(ex, ey, s * 0.14f, innerEar)
        }
        // cabeça
        c.drawOval(RectF(cx - s * 0.72f, cy - s * 0.62f, cx + s * 0.72f, cy + s * 0.58f), fur)
        // costura no topo da cabeça
        val seam = Path().apply {
            moveTo(cx, cy - s * 0.62f)
            lineTo(cx, cy - s * 0.30f)
        }
        c.drawPath(seam, stitch)
        for (k in 0..3) {
            val yy = cy - s * 0.58f + k * s * 0.08f
            c.drawLine(cx - s * 0.03f, yy, cx + s * 0.03f, yy, stitch)
        }
        // bochechas
        c.drawCircle(cx - s * 0.45f, cy + s * 0.15f, s * 0.11f, cheek)
        c.drawCircle(cx + s * 0.45f, cy + s * 0.15f, s * 0.11f, cheek)
        // focinho
        c.drawOval(RectF(cx - s * 0.30f, cy + s * 0.0f, cx + s * 0.30f, cy + s * 0.42f), muzzle)

        // olhos de botão (piscam de vez em quando; olham para cima a pensar)
        val blink = (t % 4.3f) < 0.15f && mode != "thinking"
        val lookY = if (mode == "thinking") -s * 0.06f else 0f
        for (side in listOf(-1f, 1f)) {
            val ex = cx + side * s * 0.27f
            val ey = cy - s * 0.12f
            if (blink) {
                c.drawLine(ex - s * 0.09f, ey, ex + s * 0.09f, ey, stitch.apply { strokeWidth = s * 0.035f })
                stitch.strokeWidth = s * 0.025f
            } else {
                c.drawCircle(ex, ey, s * 0.11f, black)
                // furos do botão
                c.drawCircle(ex - s * 0.035f, ey + lookY * 0.3f - s * 0.02f, s * 0.018f, furDark)
                c.drawCircle(ex + s * 0.035f, ey + lookY * 0.3f - s * 0.02f, s * 0.018f, furDark)
                c.drawCircle(ex - s * 0.04f, ey - s * 0.05f + lookY, s * 0.03f, white)
            }
        }

        // nariz
        c.drawOval(RectF(cx - s * 0.09f, cy + s * 0.07f, cx + s * 0.09f, cy + s * 0.17f), black)
        c.drawLine(cx, cy + s * 0.17f, cx, cy + s * 0.25f, stitch)

        // boca
        if (mode == "speaking") {
            val open = abs(sin(t * 11f)) * s * 0.09f + s * 0.02f
            c.drawOval(RectF(cx - s * 0.08f, cy + s * 0.25f, cx + s * 0.08f, cy + s * 0.25f + open), black)
        } else {
            val mouth = Path().apply {
                moveTo(cx - s * 0.12f, cy + s * 0.26f)
                quadTo(cx - s * 0.06f, cy + s * 0.33f, cx, cy + s * 0.25f)
                quadTo(cx + s * 0.06f, cy + s * 0.33f, cx + s * 0.12f, cy + s * 0.26f)
            }
            c.drawPath(mouth, stitch)
        }
        c.restore()

        // pontinhos a pensar
        if (mode == "thinking") {
            for (i in 0..2) {
                val up = sin(t * 5f - i * 0.8f) * s * 0.04f
                c.drawCircle(cx + s * (0.55f + i * 0.16f), cy - s * 0.80f + up, s * 0.05f, white)
            }
        }
    }

    @Suppress("unused")
    private fun deg(rad: Float) = (rad * 180f / PI).toFloat()
}
