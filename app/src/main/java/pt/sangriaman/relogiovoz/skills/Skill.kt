package pt.sangriaman.relogiovoz.skills

import android.content.Context

/**
 * Resposta de uma habilidade.
 * [speech] é dito em voz alta; [display] (se definido) aparece no balão do ecrã em vez de [speech];
 * [afterSpeech] corre depois de acabar de falar (por exemplo, iniciar áudio ou abrir uma app).
 */
data class Answer(
    val speech: String,
    val display: String? = null,
    val afterSpeech: (() -> Unit)? = null
)

interface Skill {
    /**
     * [n] é o texto normalizado (minúsculas, sem acentos); [t] são as palavras.
     * Devolve a confiança da correspondência de 0 (não corresponde) a 100.
     */
    fun score(n: String, t: List<String>): Int

    /** Chamado numa thread de fundo; pode fazer pedidos à internet. */
    suspend fun handle(ctx: Context, n: String, t: List<String>, raw: String): Answer
}

fun List<String>.hasAny(vararg w: String) = w.any { it in this }
