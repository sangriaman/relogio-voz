package pt.sangriaman.relogiovoz.skills

import android.content.Context

/**
 * Resposta de uma habilidade.
 * [speech] é dito em voz alta; [afterSpeech] corre depois de acabar de falar
 * (por exemplo, abrir o NewPipe para a música não tapar a voz).
 */
data class Answer(val speech: String, val afterSpeech: (() -> Unit)? = null)

interface Skill {
    /** [n] é o texto normalizado (minúsculas, sem acentos). */
    fun matches(n: String, t: List<String>): Boolean

    /** Chamado numa thread de fundo; pode fazer pedidos à internet. */
    suspend fun handle(ctx: Context, n: String, t: List<String>, raw: String): Answer
}

fun List<String>.hasAny(vararg w: String) = w.any { it in this }
