package ch.m4rm0tt.mynetworks.domain

import java.text.Normalizer
import java.util.Locale

object TextNorm {
    private val combiningMarks = Regex("\\p{Mn}+")
    private val spaces = Regex("\\s+")

    /** Texte d'affichage : espaces superflus retirés, casse et accents conservés. */
    fun clean(s: String): String = s.trim().replace(spaces, " ")

    /** Clé de comparaison des valeurs communes : insensible à la casse et aux espaces superflus. */
    fun tagKey(s: String): String = clean(s).lowercase(Locale.ROOT)

    /** Forme de recherche : minuscules, sans accents (é → e, ß → ss, œ → oe…). */
    fun fold(s: String): String {
        val lower = s.lowercase(Locale.ROOT)
            .replace("ß", "ss").replace("œ", "oe").replace("æ", "ae")
            .replace("ø", "o").replace("đ", "d").replace("ł", "l")
        return Normalizer.normalize(lower, Normalizer.Form.NFD).replace(combiningMarks, "")
    }

    /** Requête de recherche pliée et protégée pour LIKE (échappement '\'). */
    fun likePattern(query: String): String {
        val folded = clean(fold(query))
        val escaped = folded.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
        return "%$escaped%"
    }

    fun initials(name: String): String {
        val words = clean(name).split(' ').filter { it.isNotEmpty() }
        val letters = words.take(2).mapNotNull { w -> w.firstOrNull { it.isLetterOrDigit() } }
        return if (letters.isEmpty()) "?" else letters.joinToString("").uppercase(Locale.ROOT)
    }
}
