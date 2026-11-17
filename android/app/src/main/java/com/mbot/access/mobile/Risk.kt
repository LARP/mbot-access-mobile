package com.mbot.access.mobile

import java.text.Normalizer

/** Palabras (ES + EN) de botones que envian, pagan, compran, borran o publican. Idea tomada de CONFIRM_WORDS de Mika. */
object Risk {
    private val WORDS = setOf(
        // espanol
        "enviar", "envia", "pagar", "comprar", "eliminar", "borrar", "confirmar", "confirma", "publicar",
        "transferir", "suscribirse", "suscribir", "donar", "vaciar",
        // ingles
        "send", "pay", "buy", "purchase", "delete", "remove", "confirm", "post", "publish", "submit",
        "transfer", "subscribe", "checkout", "donate", "erase"
    )
    private val PHRASES = listOf(
        "realizar pedido", "hacer pedido", "finalizar compra", "comprar ahora", "aceptar y pagar",
        "place order", "pay now", "buy now", "complete purchase", "empty trash", "vaciar papelera"
    )

    private fun norm(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").lowercase()

    private fun tokens(s: String): List<String> = norm(s).split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }

    /** Devuelve la palabra de riesgo encontrada, o null. Los textos largos (parrafos, mensajes) se ignoran. */
    fun check(texts: List<String>, ids: List<String>): String? {
        for (t in texts) {
            if (t.isBlank()) continue
            val tk = tokens(t)
            if (tk.isEmpty() || tk.size > 5) continue
            val joined = tk.joinToString(" ")
            PHRASES.firstOrNull { joined.contains(it) }?.let { return it }
            tk.firstOrNull { it in WORDS }?.let { return it }
        }
        for (id in ids) {
            if (id.isBlank()) continue
            val name = id.substringAfter('/')
            val camel = name.replace(Regex("([a-z0-9])([A-Z])"), "$1_$2")
            val tk = tokens(camel)
            if (tk.isEmpty() || tk.size > 3) continue
            tk.firstOrNull { it in WORDS }?.let { return it }
        }
        return null
    }
}
