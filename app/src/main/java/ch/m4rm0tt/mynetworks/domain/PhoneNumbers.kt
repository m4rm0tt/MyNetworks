package ch.m4rm0tt.mynetworks.domain

sealed interface PhoneParse {
    /** Numéro international en chiffres seuls, sans « + » (ex. 41791234567). */
    data class International(val digits: String) : PhoneParse
    /** Numéro local dont le pays ne peut pas être déduit sans deviner. */
    data object Ambiguous : PhoneParse
    data object Invalid : PhoneParse
}

object PhoneNumbers {
    private val separators = Regex("[\\s.\\-/()]")

    /**
     * Normalise vers le format international lorsque c'est possible sans deviner :
     * « +41… », « 0041… » sont reconnus ; un numéro local « 079… » n'est converti
     * que si un indicatif par défaut a été explicitement réglé.
     */
    fun toInternational(raw: String, defaultCountryCode: String?): PhoneParse {
        val s = raw.trim().replace(separators, "")
        if (s.isEmpty()) return PhoneParse.Invalid
        val digits: String = when {
            s.startsWith("+") -> s.substring(1)
            s.startsWith("00") -> s.substring(2)
            s.startsWith("0") -> {
                val cc = defaultCountryCode?.trim()?.removePrefix("+")?.takeIf { it.isNotEmpty() && it.all(Char::isDigit) }
                    ?: return if (s.all(Char::isDigit)) PhoneParse.Ambiguous else PhoneParse.Invalid
                cc + s.substring(1)
            }
            else -> return if (s.all(Char::isDigit)) PhoneParse.Ambiguous else PhoneParse.Invalid
        }
        if (!digits.all(Char::isDigit)) return PhoneParse.Invalid
        if (digits.length !in 8..15 || digits.startsWith("0")) return PhoneParse.Invalid
        return PhoneParse.International(digits)
    }

    /** Valeur à passer à « tel: » : on garde le numéro tel que saisi, nettoyé. */
    fun dialable(raw: String): String = raw.trim().replace(Regex("[\\s.\\-/()]"), "")
}
