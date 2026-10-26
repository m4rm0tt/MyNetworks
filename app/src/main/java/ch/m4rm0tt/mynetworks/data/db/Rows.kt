package ch.m4rm0tt.mynetworks.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "entities",
    indices = [Index("kind"), Index("companyId"), Index("favorite")],
)
data class EntityRow(
    @PrimaryKey val id: String,
    val kind: Int,
    val firstName: String = "",
    val lastName: String = "",
    val companyName: String = "",
    val fallbackLabel: String = "",
    val photoId: String? = null,
    val phone: String = "",
    val email: String = "",
    /** Un lien par ligne. */
    val links: String = "",
    val companyId: String? = null,
    val note: String = "",
    val favorite: Boolean = false,
    val createdAt: Long,
    val updatedAt: Long,
    /** Texte de recherche plié (minuscules, sans accents), recalculé à chaque enregistrement. */
    @ColumnInfo(defaultValue = "") val searchText: String = "",
)

@Entity(
    tableName = "tags",
    indices = [Index(value = ["type", "normKey"], unique = true)],
)
data class TagRow(
    @PrimaryKey val id: String,
    val type: Int,
    val label: String,
    val normKey: String,
)

@Entity(
    tableName = "entity_tags",
    primaryKeys = ["entityId", "tagId"],
    indices = [Index("tagId")],
    foreignKeys = [
        ForeignKey(entity = EntityRow::class, parentColumns = ["id"], childColumns = ["entityId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = TagRow::class, parentColumns = ["id"], childColumns = ["tagId"], onDelete = ForeignKey.CASCADE),
    ],
)
data class EntityTagRow(
    val entityId: String,
    val tagId: String,
    val position: Int,
)

/** Liens manuels facultatifs entre deux fiches (modèle prêt, pas encore d'interface). */
@Entity(
    tableName = "manual_links",
    indices = [Index("fromId"), Index("toId")],
    foreignKeys = [
        ForeignKey(entity = EntityRow::class, parentColumns = ["id"], childColumns = ["fromId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = EntityRow::class, parentColumns = ["id"], childColumns = ["toId"], onDelete = ForeignKey.CASCADE),
    ],
)
data class ManualLinkRow(
    @PrimaryKey val id: String,
    val fromId: String,
    val toId: String,
    val label: String = "",
    val createdAt: Long,
)

/** Résultat de jointure fiche ↔ valeur commune. */
data class EntityTagJoin(
    val entityId: String,
    val tagId: String,
    val type: Int,
    val label: String,
    val position: Int,
)

data class TagUsage(
    val id: String,
    val type: Int,
    val label: String,
    val normKey: String,
    val usage: Int,
)
