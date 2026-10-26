package ch.m4rm0tt.mynetworks.backup

import android.content.ContentResolver
import android.net.Uri
import androidx.room.withTransaction
import ch.m4rm0tt.mynetworks.data.NetworkRepository
import ch.m4rm0tt.mynetworks.data.PhotoStore
import ch.m4rm0tt.mynetworks.data.SettingsStore
import ch.m4rm0tt.mynetworks.data.db.AppDatabase
import ch.m4rm0tt.mynetworks.data.db.EntityRow
import ch.m4rm0tt.mynetworks.data.db.EntityTagRow
import ch.m4rm0tt.mynetworks.data.db.ManualLinkRow
import ch.m4rm0tt.mynetworks.data.db.TagRow
import ch.m4rm0tt.mynetworks.domain.EntityKind
import ch.m4rm0tt.mynetworks.domain.TagType
import ch.m4rm0tt.mynetworks.domain.TextNorm
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

enum class ImportMode { MERGE, REPLACE }

data class ImportSummary(val imported: Int, val keptNewerLocal: Int, val photos: Int)

/**
 * Export/import manuel. Le fichier produit est entièrement chiffré (BackupCrypto) : aucune copie
 * en clair n'est écrite sur le disque. À l'import, tout le fichier est déchiffré et vérifié
 * (intégrité, version, mot de passe) AVANT de toucher la base ; l'écriture se fait ensuite dans
 * une seule transaction. Un échec laisse la base actuelle intacte.
 */
class BackupManager(
    private val db: AppDatabase,
    private val repo: NetworkRepository,
    private val photos: PhotoStore,
    private val settings: SettingsStore,
    private val resolver: ContentResolver,
) {
    private val dao = db.dao()

    suspend fun export(target: Uri, password: CharArray): Int = withContext(Dispatchers.IO) {
        val entities = dao.allEntities()
        val tags = dao.allTags()
        val entityTags = dao.allEntityTags()
        val links = dao.allLinks()
        val out = resolver.openOutputStream(target, "wt") ?: error("Destination inaccessible")
        BackupCrypto.encrypt(out, password).use { enc ->
            ZipOutputStream(enc).use { zip ->
                fun put(name: String, bytes: ByteArray) {
                    zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry()
                }
                put(
                    "manifest.json",
                    JSONObject()
                        .put("app", "mynetworks").put("format", 1)
                        .put("createdAt", System.currentTimeMillis())
                        .put("entities", entities.size)
                        .toString().toByteArray()
                )
                val data = JSONObject()
                    .put("entities", JSONArray().apply { entities.forEach { put(it.toJson()) } })
                    .put("tags", JSONArray().apply {
                        tags.forEach { put(JSONObject().put("id", it.id).put("type", it.type).put("label", it.label)) }
                    })
                    .put("entityTags", JSONArray().apply {
                        entityTags.forEach { put(JSONObject().put("entityId", it.entityId).put("tagId", it.tagId).put("position", it.position)) }
                    })
                    .put("links", JSONArray().apply {
                        links.forEach {
                            put(JSONObject().put("id", it.id).put("fromId", it.fromId).put("toId", it.toId).put("label", it.label).put("createdAt", it.createdAt))
                        }
                    })
                put("data.json", data.toString().toByteArray())
                put("settings.json", settings.toJson().toString().toByteArray())
                for (e in entities) {
                    val id = e.photoId ?: continue
                    val (full, thumb) = photos.exportBytes(id) ?: continue
                    put("photos/$id.p", full)
                    put("photos/$id.t", thumb)
                }
            }
        }
        entities.count { it.kind != EntityKind.SELF.code }
    }

    suspend fun importFrom(source: Uri, password: CharArray, mode: ImportMode): ImportSummary = withContext(Dispatchers.IO) {
        photos.clearStaging()
        try {
            val parsed = readAndVerify(source, password)
            val summary = commit(parsed, mode)
            summary
        } catch (e: Exception) {
            photos.clearStaging()
            throw e
        }
    }

    private class Parsed(val data: JSONObject, val settings: JSONObject?, val stagedPhotos: Set<String>)

    /** Lit tout le fichier : chaque segment est authentifié, la troncature est détectée à la fin. */
    private fun readAndVerify(source: Uri, password: CharArray): Parsed {
        val input = resolver.openInputStream(source) ?: throw BackupException(BackupException.Reason.NOT_A_BACKUP)
        input.use { raw ->
            val plain = BackupCrypto.decrypt(raw.buffered(), password)
            var manifest: JSONObject? = null
            var data: JSONObject? = null
            var settingsJson: JSONObject? = null
            val staged = HashSet<String>()
            val zip = ZipInputStream(plain)
            while (true) {
                val entry = zip.nextEntry ?: break
                val name = entry.name
                val bytes = zip.readLimited(MAX_ENTRY)
                when {
                    name == "manifest.json" -> manifest = JSONObject(String(bytes))
                    name == "data.json" -> data = JSONObject(String(bytes))
                    name == "settings.json" -> settingsJson = JSONObject(String(bytes))
                    name.startsWith("photos/") -> {
                        val file = name.removePrefix("photos/")
                        val id = file.substringBeforeLast('.')
                        val thumb = file.endsWith(".t")
                        if ((file.endsWith(".p") || thumb) && photos.stage(id, thumb, bytes)) staged.add(id)
                    }
                }
            }
            // Lit la fin du flux (répertoire central du zip) pour vérifier le dernier segment.
            val buf = ByteArray(8192)
            while (plain.read(buf) >= 0) { /* vérification d'intégrité jusqu'au bout */ }
            val m = manifest ?: throw BackupException(BackupException.Reason.CORRUPTED)
            if (m.optString("app") != "mynetworks") throw BackupException(BackupException.Reason.NOT_A_BACKUP)
            if (m.optInt("format", -1) != 1) throw BackupException(BackupException.Reason.UNSUPPORTED_VERSION)
            return Parsed(data ?: throw BackupException(BackupException.Reason.CORRUPTED), settingsJson, staged)
        }
    }

    private suspend fun commit(p: Parsed, mode: ImportMode): ImportSummary {
        val data = p.data
        val tagsJson = data.getJSONArray("tags")
        val entitiesJson = data.getJSONArray("entities")
        val etJson = data.getJSONArray("entityTags")
        val linksJson = data.optJSONArray("links") ?: JSONArray()

        // Validation complète avant toute écriture.
        val entities = (0 until entitiesJson.length()).map { entityFromJson(entitiesJson.getJSONObject(it)) }
        val backupTags = (0 until tagsJson.length()).map { i ->
            val o = tagsJson.getJSONObject(i)
            val type = TagType.of(o.getInt("type")) ?: throw BackupException(BackupException.Reason.CORRUPTED)
            Triple(o.getString("id"), type, TextNorm.clean(o.getString("label")))
        }.filter { it.third.isNotEmpty() }

        var imported = 0
        var kept = 0
        val importedIds = HashSet<String>()
        db.withTransaction {
            if (mode == ImportMode.REPLACE) {
                dao.deleteAllEntityTags(); dao.deleteAllLinks(); dao.deleteAllEntities(); dao.deleteAllTags()
            }
            // Valeurs communes : rattachement aux valeurs existantes équivalentes (casse/espaces).
            val tagMap = HashMap<String, String>()
            for ((id, type, label) in backupTags) {
                val key = TextNorm.tagKey(label)
                val existing = dao.findTag(type.code, key)
                if (existing != null) {
                    tagMap[id] = existing.id
                } else {
                    val newId = if (dao.tag(id) == null) id else UUID.randomUUID().toString()
                    dao.insertTag(TagRow(newId, type.code, label, key))
                    tagMap[id] = newId
                }
            }
            for (e in entities) {
                val local = dao.entity(e.id)
                if (mode == ImportMode.MERGE && local != null && local.updatedAt >= e.updatedAt) {
                    kept++
                    continue
                }
                dao.upsertEntity(e)
                importedIds.add(e.id)
                if (e.kind != EntityKind.SELF.code) imported++
            }
            val rows = (0 until etJson.length()).mapNotNull { i ->
                val o = etJson.getJSONObject(i)
                val eid = o.getString("entityId")
                val tid = tagMap[o.getString("tagId")] ?: return@mapNotNull null
                if (eid !in importedIds) null else EntityTagRow(eid, tid, o.optInt("position", 0))
            }
            for (id in importedIds) dao.clearTags(id)
            dao.insertEntityTags(rows)
            val existingIds = dao.allEntities().map { it.id }.toHashSet()
            val links = (0 until linksJson.length()).mapNotNull { i ->
                val o = linksJson.getJSONObject(i)
                val from = o.getString("fromId"); val to = o.getString("toId")
                if (from in existingIds && to in existingIds) ManualLinkRow(o.getString("id"), from, to, o.optString("label", ""), o.optLong("createdAt", 0L)) else null
            }
            dao.upsertLinks(links)
            dao.fixDanglingCompanies()
            if (dao.entity(ch.m4rm0tt.mynetworks.domain.GraphLayout.SELF_ID) == null) repo.ensureSelf()
            repo.rebuildAllSearchText()
        }

        // Photos : intégrées seulement après la réussite de la transaction.
        val photoIds = entities.filter { it.id in importedIds }.mapNotNull { it.photoId }.toSet()
        photos.commitStaged(photoIds.intersect(p.stagedPhotos))
        if (mode == ImportMode.REPLACE) photos.deleteUnreferenced(dao.referencedPhotos().toSet())
        if (mode == ImportMode.REPLACE && p.settings != null) settings.applyJson(p.settings)
        return ImportSummary(imported, kept, photoIds.size)
    }

    private fun entityFromJson(o: JSONObject): EntityRow {
        val id = o.getString("id")
        if (id.isBlank()) throw BackupException(BackupException.Reason.CORRUPTED)
        val kind = o.getInt("kind")
        if (kind !in 0..2) throw BackupException(BackupException.Reason.CORRUPTED)
        val linksArr = o.optJSONArray("links")
        val photo = if (o.isNull("photoId")) null else o.optString("photoId").takeIf { photos.isSafeId(it) }
        return EntityRow(
            id = id,
            kind = kind,
            firstName = o.optString("firstName", ""),
            lastName = o.optString("lastName", ""),
            companyName = o.optString("companyName", ""),
            fallbackLabel = o.optString("fallbackLabel", ""),
            photoId = photo,
            phone = o.optString("phone", ""),
            email = o.optString("email", ""),
            links = linksArr?.let { a -> (0 until a.length()).joinToString("\n") { a.getString(it) } } ?: "",
            companyId = if (o.isNull("companyId")) null else o.optString("companyId").ifBlank { null },
            note = o.optString("note", ""),
            favorite = o.optBoolean("favorite", false),
            createdAt = o.optLong("createdAt", 0L),
            updatedAt = o.optLong("updatedAt", 0L),
        )
    }

    private fun EntityRow.toJson(): JSONObject = JSONObject()
        .put("id", id).put("kind", kind)
        .put("firstName", firstName).put("lastName", lastName).put("companyName", companyName)
        .put("fallbackLabel", fallbackLabel)
        .put("photoId", photoId ?: JSONObject.NULL)
        .put("phone", phone).put("email", email)
        .put("links", JSONArray().apply { links.split('\n').filter { it.isNotBlank() }.forEach { put(it) } })
        .put("companyId", companyId ?: JSONObject.NULL)
        .put("note", note).put("favorite", favorite)
        .put("createdAt", createdAt).put("updatedAt", updatedAt)

    private fun InputStream.readLimited(limit: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        while (true) {
            val n = read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            if (out.size() > limit) throw BackupException(BackupException.Reason.CORRUPTED)
        }
        return out.toByteArray()
    }

    companion object {
        private const val MAX_ENTRY = 256 * 1024 * 1024
    }
}
