package ch.m4rm0tt.mynetworks.data

import androidx.room.withTransaction
import ch.m4rm0tt.mynetworks.data.db.AppDatabase
import ch.m4rm0tt.mynetworks.data.db.EntityRow
import ch.m4rm0tt.mynetworks.data.db.EntityTagJoin
import ch.m4rm0tt.mynetworks.data.db.EntityTagRow
import ch.m4rm0tt.mynetworks.data.db.TagRow
import ch.m4rm0tt.mynetworks.data.db.TagUsage
import ch.m4rm0tt.mynetworks.domain.Contact
import ch.m4rm0tt.mynetworks.domain.EntityKind
import ch.m4rm0tt.mynetworks.domain.GraphLayout
import ch.m4rm0tt.mynetworks.domain.TagRef
import ch.m4rm0tt.mynetworks.domain.TagType
import ch.m4rm0tt.mynetworks.domain.TextNorm
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import java.util.UUID

/** Ce que le formulaire envoie à l'enregistrement. */
data class SaveRequest(
    val contact: Contact,
    /** Libellés saisis par type de valeur commune, dans l'ordre voulu. */
    val tagLabels: Map<TagType, List<String>>,
    /** Nom d'entreprise tapé librement (si aucune entreprise existante n'a été choisie). */
    val companyText: String = "",
)

class NetworkRepository(private val db: AppDatabase) {
    private val dao = db.dao()

    val contacts: Flow<List<Contact>> = combine(dao.observeEntities(), dao.observeEntityTags()) { rows, joins ->
        val byEntity = joins.groupBy { it.entityId }
        rows.map { it.toContact(byEntity[it.id].orEmpty()) }
    }

    val tagUsage: Flow<List<TagUsage>> = dao.observeTagUsage()

    suspend fun ensureSelf() {
        if (dao.entity(GraphLayout.SELF_ID) == null) {
            val now = System.currentTimeMillis()
            dao.upsertEntity(EntityRow(id = GraphLayout.SELF_ID, kind = EntityKind.SELF.code, createdAt = now, updatedAt = now))
        }
    }

    suspend fun save(req: SaveRequest): String = db.withTransaction {
        val now = System.currentTimeMillis()
        val c = req.contact
        val id = c.id.ifEmpty { UUID.randomUUID().toString() }
        val existing = dao.entity(id)

        // Entreprise : rattachement à une entreprise existante plutôt qu'une duplication.
        var companyId = c.companyId
        if (c.kind != EntityKind.COMPANY && companyId == null && req.companyText.isNotBlank()) {
            val key = TextNorm.tagKey(req.companyText)
            val match = dao.allEntities().firstOrNull { it.kind == EntityKind.COMPANY.code && TextNorm.tagKey(it.companyName) == key }
            companyId = match?.id ?: run {
                val newId = UUID.randomUUID().toString()
                dao.upsertEntity(
                    EntityRow(
                        id = newId, kind = EntityKind.COMPANY.code, companyName = TextNorm.clean(req.companyText),
                        createdAt = now, updatedAt = now,
                    )
                )
                refreshSearchText(listOf(newId))
                newId
            }
        }
        if (c.kind == EntityKind.COMPANY) companyId = null

        val row = EntityRow(
            id = id,
            kind = c.kind.code,
            firstName = TextNorm.clean(c.firstName),
            lastName = TextNorm.clean(c.lastName),
            companyName = TextNorm.clean(c.companyName),
            fallbackLabel = TextNorm.clean(c.fallbackLabel),
            photoId = c.photoId,
            phone = c.phone.trim(),
            email = c.email.trim(),
            links = c.links.map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n"),
            companyId = companyId,
            note = c.note.trim(),
            favorite = c.favorite,
            createdAt = existing?.createdAt ?: now,
            updatedAt = now,
        )
        dao.upsertEntity(row)

        // Valeurs communes : réutilise la valeur existante (casse et espaces ignorés), sinon la crée.
        dao.clearTags(id)
        val links = ArrayList<EntityTagRow>()
        for ((type, labels) in req.tagLabels) {
            labels.map { TextNorm.clean(it) }.filter { it.isNotEmpty() }.distinctBy { TextNorm.tagKey(it) }
                .forEachIndexed { pos, label ->
                    val tagId = findOrCreateTag(type, label)
                    links.add(EntityTagRow(id, tagId, pos))
                }
        }
        dao.insertEntityTags(links)

        val affected = mutableListOf(id)
        // Renommer une entreprise met à jour la recherche de ses employés.
        if (c.kind == EntityKind.COMPANY && existing != null && existing.companyName != row.companyName) {
            affected += dao.employeesOf(id).map { it.id }
        }
        refreshSearchText(affected)
        id
    }

    private suspend fun findOrCreateTag(type: TagType, label: String): String {
        val key = TextNorm.tagKey(label)
        dao.findTag(type.code, key)?.let { return it.id }
        val tag = TagRow(UUID.randomUUID().toString(), type.code, label, key)
        dao.insertTag(tag)
        return tag.id
    }

    suspend fun setFavorite(id: String, favorite: Boolean) = dao.setFavorite(id, favorite, System.currentTimeMillis())

    /** Supprime une fiche ; renvoie l'id de sa photo pour suppression du fichier. */
    suspend fun delete(id: String): String? = db.withTransaction {
        val row = dao.entity(id) ?: return@withTransaction null
        if (row.kind == EntityKind.SELF.code) return@withTransaction null
        val employees = dao.employeesOf(id).map { it.id }
        dao.detachEmployees(id)
        dao.deleteEntity(id)
        refreshSearchText(employees)
        row.photoId
    }

    suspend fun search(query: String, favoritesOnly: Boolean, limit: Int, offset: Int): List<String> =
        dao.search(TextNorm.likePattern(query), favoritesOnly, limit, offset)

    /** Renomme une valeur commune ; si le nouveau nom existe déjà, les deux sont réunies. */
    suspend fun renameTag(tagId: String, newLabel: String) = db.withTransaction {
        val tag = dao.tag(tagId) ?: return@withTransaction
        val label = TextNorm.clean(newLabel)
        if (label.isEmpty()) return@withTransaction
        val key = TextNorm.tagKey(label)
        val affected = dao.entitiesWithTag(tagId)
        val other = dao.findTag(tag.type, key)
        if (other != null && other.id != tagId) {
            dao.moveTagUsages(tagId, other.id)
            dao.deleteTag(tagId)
        } else {
            dao.renameTag(tagId, label, key)
        }
        refreshSearchText(affected)
    }

    suspend fun deleteTag(tagId: String) = db.withTransaction {
        val affected = dao.entitiesWithTag(tagId)
        dao.deleteTag(tagId)
        refreshSearchText(affected)
    }

    private suspend fun refreshSearchText(ids: List<String>) {
        if (ids.isEmpty()) return
        for (chunk in ids.distinct().chunked(500)) {
            val rows = dao.entities(chunk)
            val companyNames = HashMap<String, String>()
            for (r in rows) {
                val company = r.companyId?.let { cid -> companyNames.getOrPut(cid) { dao.entity(cid)?.companyName ?: "" } } ?: ""
                dao.setSearchText(r.id, buildSearchText(r, dao.tagsOf(r.id), company))
            }
        }
    }

    suspend fun rebuildAllSearchText() = db.withTransaction {
        refreshSearchText(dao.allEntities().map { it.id })
    }

    companion object {
        fun buildSearchText(r: EntityRow, tags: List<EntityTagJoin>, companyName: String): String =
            TextNorm.fold(
                listOf(
                    r.firstName, r.lastName, r.companyName, r.fallbackLabel, companyName,
                    r.phone, r.phone.filter { it.isDigit() || it == '+' }, r.email, r.links, r.note,
                ).plus(tags.map { it.label }).filter { it.isNotBlank() }.joinToString(" \u0001 ")
            )
    }
}

fun EntityRow.toContact(tags: List<EntityTagJoin>): Contact = Contact(
    id = id,
    kind = EntityKind.of(kind),
    firstName = firstName,
    lastName = lastName,
    companyName = companyName,
    fallbackLabel = fallbackLabel,
    photoId = photoId,
    phone = phone,
    email = email,
    links = links.split('\n').filter { it.isNotBlank() },
    companyId = companyId,
    note = note,
    favorite = favorite,
    createdAt = createdAt,
    updatedAt = updatedAt,
    tags = tags.sortedWith(compareBy({ it.type }, { it.position }))
        .mapNotNull { j -> TagType.of(j.type)?.let { TagRef(j.tagId, it, j.label) } },
)
