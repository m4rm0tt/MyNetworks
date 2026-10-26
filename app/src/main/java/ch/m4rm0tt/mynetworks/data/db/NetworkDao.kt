package ch.m4rm0tt.mynetworks.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface NetworkDao {
    // --- Fiches ---
    @Query("SELECT * FROM entities")
    fun observeEntities(): Flow<List<EntityRow>>

    @Query("SELECT * FROM entities")
    suspend fun allEntities(): List<EntityRow>

    @Query("SELECT * FROM entities WHERE id = :id")
    suspend fun entity(id: String): EntityRow?

    @Query("SELECT * FROM entities WHERE id IN (:ids)")
    suspend fun entities(ids: List<String>): List<EntityRow>

    @Query("SELECT * FROM entities WHERE companyId = :companyId")
    suspend fun employeesOf(companyId: String): List<EntityRow>

    @Upsert
    suspend fun upsertEntity(row: EntityRow)

    @Upsert
    suspend fun upsertEntities(rows: List<EntityRow>)

    @Query("DELETE FROM entities WHERE id = :id")
    suspend fun deleteEntity(id: String)

    @Query("UPDATE entities SET companyId = NULL WHERE companyId = :companyId")
    suspend fun detachEmployees(companyId: String)

    @Query("UPDATE entities SET favorite = :favorite, updatedAt = :now WHERE id = :id")
    suspend fun setFavorite(id: String, favorite: Boolean, now: Long)

    @Query("UPDATE entities SET searchText = :text WHERE id = :id")
    suspend fun setSearchText(id: String, text: String)

    @Query("DELETE FROM entities")
    suspend fun deleteAllEntities()

    @Query("UPDATE entities SET companyId = NULL WHERE companyId IS NOT NULL AND companyId NOT IN (SELECT id FROM entities)")
    suspend fun fixDanglingCompanies()

    @Query("SELECT photoId FROM entities WHERE photoId IS NOT NULL")
    suspend fun referencedPhotos(): List<String>

    @Query("SELECT COUNT(*) FROM entities WHERE kind != 0")
    suspend fun countEntities(): Int

    /** Recherche paginée sur le texte plié (index : searchText est précalculé). */
    @Query(
        "SELECT id FROM entities WHERE kind != 0 AND searchText LIKE :pattern ESCAPE '\\' " +
            "AND (:favoritesOnly = 0 OR favorite = 1) " +
            "ORDER BY favorite DESC, updatedAt DESC LIMIT :limit OFFSET :offset"
    )
    suspend fun search(pattern: String, favoritesOnly: Boolean, limit: Int, offset: Int): List<String>

    // --- Valeurs communes ---
    @Query("SELECT * FROM tags")
    fun observeTags(): Flow<List<TagRow>>

    @Query("SELECT * FROM tags")
    suspend fun allTags(): List<TagRow>

    @Query(
        "SELECT t.id, t.type, t.label, t.normKey, COUNT(et.entityId) AS usage FROM tags t " +
            "LEFT JOIN entity_tags et ON et.tagId = t.id GROUP BY t.id"
    )
    fun observeTagUsage(): Flow<List<TagUsage>>

    @Query("SELECT * FROM tags WHERE type = :type AND normKey = :normKey LIMIT 1")
    suspend fun findTag(type: Int, normKey: String): TagRow?

    @Query("SELECT * FROM tags WHERE id = :id")
    suspend fun tag(id: String): TagRow?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertTag(row: TagRow)

    @Upsert
    suspend fun upsertTags(rows: List<TagRow>)

    @Query("UPDATE tags SET label = :label, normKey = :normKey WHERE id = :id")
    suspend fun renameTag(id: String, label: String, normKey: String)

    @Query("DELETE FROM tags WHERE id = :id")
    suspend fun deleteTag(id: String)

    @Query("DELETE FROM tags")
    suspend fun deleteAllTags()

    // --- Associations ---
    @Query(
        "SELECT et.entityId AS entityId, et.tagId AS tagId, t.type AS type, t.label AS label, et.position AS position " +
            "FROM entity_tags et JOIN tags t ON t.id = et.tagId"
    )
    fun observeEntityTags(): Flow<List<EntityTagJoin>>

    @Query(
        "SELECT et.entityId AS entityId, et.tagId AS tagId, t.type AS type, t.label AS label, et.position AS position " +
            "FROM entity_tags et JOIN tags t ON t.id = et.tagId WHERE et.entityId = :entityId"
    )
    suspend fun tagsOf(entityId: String): List<EntityTagJoin>

    @Query(
        "SELECT et.entityId AS entityId, et.tagId AS tagId, t.type AS type, t.label AS label, et.position AS position " +
            "FROM entity_tags et JOIN tags t ON t.id = et.tagId"
    )
    suspend fun allEntityTags(): List<EntityTagJoin>

    @Query("SELECT entityId FROM entity_tags WHERE tagId = :tagId")
    suspend fun entitiesWithTag(tagId: String): List<String>

    @Query("DELETE FROM entity_tags WHERE entityId = :entityId")
    suspend fun clearTags(entityId: String)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertEntityTags(rows: List<EntityTagRow>)

    @Query("UPDATE OR IGNORE entity_tags SET tagId = :to WHERE tagId = :from")
    suspend fun moveTagUsages(from: String, to: String)

    @Query("DELETE FROM entity_tags")
    suspend fun deleteAllEntityTags()

    // --- Liens manuels ---
    @Query("SELECT * FROM manual_links")
    suspend fun allLinks(): List<ManualLinkRow>

    @Upsert
    suspend fun upsertLinks(rows: List<ManualLinkRow>)

    @Query("DELETE FROM manual_links")
    suspend fun deleteAllLinks()
}
