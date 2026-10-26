package ch.m4rm0tt.mynetworks.domain

/** Type d'entité. Les codes sont stockés en base : ne jamais les renuméroter. */
enum class EntityKind(val code: Int) {
    SELF(0), PERSON(1), COMPANY(2);

    companion object {
        fun of(code: Int): EntityKind = entries.firstOrNull { it.code == code } ?: PERSON
    }
}

/** Valeurs communes partagées entre fiches. Codes stockés en base : ne jamais renuméroter. */
enum class TagType(val code: Int, val label: String, val plural: String) {
    CATEGORY(0, "Catégorie", "Catégories"),
    SKILL(1, "Compétence", "Compétences"),
    LOCATION(2, "Emplacement", "Emplacements"),
    PROFESSION(3, "Métier", "Métiers");

    companion object {
        fun of(code: Int): TagType? = entries.firstOrNull { it.code == code }
    }
}

/** Critère d'organisation du graphe. */
enum class GroupCriterion(val label: String) {
    NONE("Aucun regroupement"),
    CATEGORY("Catégorie"),
    SKILL("Compétence"),
    LOCATION("Emplacement"),
    PROFESSION("Métier"),
    COMPANY("Entreprise");

    val tagType: TagType?
        get() = when (this) {
            CATEGORY -> TagType.CATEGORY
            SKILL -> TagType.SKILL
            LOCATION -> TagType.LOCATION
            PROFESSION -> TagType.PROFESSION
            else -> null
        }
}

data class TagRef(val id: String, val type: TagType, val label: String)

/** Fiche complète telle qu'utilisée par l'interface. */
data class Contact(
    val id: String,
    val kind: EntityKind,
    val firstName: String = "",
    val lastName: String = "",
    val companyName: String = "",
    val fallbackLabel: String = "",
    val photoId: String? = null,
    val phone: String = "",
    val email: String = "",
    val links: List<String> = emptyList(),
    val companyId: String? = null,
    val note: String = "",
    val favorite: Boolean = false,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
    val tags: List<TagRef> = emptyList(),
) {
    val displayName: String
        get() {
            val main = when (kind) {
                EntityKind.COMPANY -> companyName.trim()
                else -> listOf(firstName.trim(), lastName.trim()).filter { it.isNotEmpty() }.joinToString(" ")
            }
            return main.ifEmpty { fallbackLabel.trim() }.ifEmpty { if (kind == EntityKind.SELF) "Moi" else "Sans nom" }
        }

    val initials: String get() = TextNorm.initials(displayName)

    fun tagsOf(type: TagType): List<TagRef> = tags.filter { it.type == type }

    /** Un libellé d'identification est obligatoire (sauf pour mon profil). */
    val hasIdentity: Boolean
        get() = when (kind) {
            EntityKind.COMPANY -> companyName.isNotBlank() || fallbackLabel.isNotBlank()
            EntityKind.PERSON -> firstName.isNotBlank() || lastName.isNotBlank() || fallbackLabel.isNotBlank()
            EntityKind.SELF -> true
        }
}

/** Champs affichables sur la carte de visite, dans un ordre choisi par l'utilisateur. */
enum class CardField(val key: String, val label: String) {
    PROFESSION("profession", "Métier"),
    COMPANY("company", "Entreprise"),
    SKILLS("skills", "Compétences"),
    LOCATION("location", "Emplacement"),
    CATEGORIES("categories", "Catégories"),
    PHONE("phone", "Téléphone"),
    EMAIL("email", "E-mail"),
    LINKS("links", "Réseaux et liens"),
    NOTE("note", "Note");

    companion object {
        fun of(key: String): CardField? = entries.firstOrNull { it.key == key }

        val defaultPersonOrder = listOf(PROFESSION, COMPANY, SKILLS, LOCATION, CATEGORIES, PHONE, EMAIL, LINKS, NOTE)
        val defaultCompanyOrder = listOf(SKILLS, LOCATION, CATEGORIES, PHONE, EMAIL, LINKS, NOTE)

        /** Champs qui n'ont pas de sens pour une entreprise. */
        val notForCompany = setOf(PROFESSION, COMPANY)
    }
}
