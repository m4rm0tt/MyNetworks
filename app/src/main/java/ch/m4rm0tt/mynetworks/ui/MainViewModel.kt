package ch.m4rm0tt.mynetworks.ui

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import ch.m4rm0tt.mynetworks.MyNetworksApp
import ch.m4rm0tt.mynetworks.Storage
import ch.m4rm0tt.mynetworks.backup.BackupException
import ch.m4rm0tt.mynetworks.backup.BackupManager
import ch.m4rm0tt.mynetworks.backup.ImportMode
import ch.m4rm0tt.mynetworks.data.SaveRequest
import ch.m4rm0tt.mynetworks.data.Settings
import ch.m4rm0tt.mynetworks.data.db.TagUsage
import ch.m4rm0tt.mynetworks.domain.Contact
import ch.m4rm0tt.mynetworks.domain.EntityKind
import ch.m4rm0tt.mynetworks.domain.GraphLayout
import ch.m4rm0tt.mynetworks.domain.GraphLayoutResult
import ch.m4rm0tt.mynetworks.domain.GroupCriterion
import ch.m4rm0tt.mynetworks.domain.LayoutItem
import ch.m4rm0tt.mynetworks.domain.TagType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface StorageStatus {
    data object Loading : StorageStatus
    data object Ready : StorageStatus
    data class Unavailable(val message: String) : StorageStatus
}

/** Panneaux superposés au graphe. Le graphe reste la seule page. */
sealed interface Sheet {
    val key: String

    data class Card(val id: String) : Sheet { override val key get() = "card:$id" }
    data class Info(val id: String) : Sheet { override val key get() = "info:$id" }
    data class Edit(val nonce: Long) : Sheet { override val key get() = "edit:$nonce" }
    data object Search : Sheet { override val key get() = "search" }
    data object Settings : Sheet { override val key get() = "settings" }
    data object AddChooser : Sheet { override val key get() = "add" }
    data object Values : Sheet { override val key get() = "values" }
    data object Backup : Sheet { override val key get() = "backup" }
}

data class EditDraft(
    val id: String = "",
    val kind: EntityKind = EntityKind.PERSON,
    val firstName: String = "",
    val lastName: String = "",
    val companyName: String = "",
    val fallbackLabel: String = "",
    val photoId: String? = null,
    val phone: String = "",
    val email: String = "",
    val links: List<String> = emptyList(),
    val companyId: String? = null,
    val companyText: String = "",
    val note: String = "",
    val favorite: Boolean = false,
    val tags: Map<TagType, List<String>> = emptyMap(),
) {
    val isNew: Boolean get() = id.isEmpty()

    fun toContact() = Contact(
        id = id, kind = kind, firstName = firstName, lastName = lastName, companyName = companyName,
        fallbackLabel = fallbackLabel, photoId = photoId, phone = phone, email = email, links = links,
        companyId = companyId, note = note, favorite = favorite,
    )

    companion object {
        fun from(c: Contact) = EditDraft(
            id = c.id, kind = c.kind, firstName = c.firstName, lastName = c.lastName, companyName = c.companyName,
            fallbackLabel = c.fallbackLabel, photoId = c.photoId, phone = c.phone, email = c.email,
            links = c.links, companyId = c.companyId, note = c.note, favorite = c.favorite,
            tags = TagType.entries.associateWith { t -> c.tagsOf(t).map { it.label } }.filterValues { it.isNotEmpty() },
        )
    }
}

class CameraState {
    var scale by mutableFloatStateOf(1f)
    var offsetX by mutableFloatStateOf(0f)
    var offsetY by mutableFloatStateOf(0f)
    var initialized by mutableStateOf(false)
}

data class FocusRequest(val nodeId: String, val nonce: Long)

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val appRef = app as MyNetworksApp
    private val settingsStore = appRef.settings
    val settings: StateFlow<Settings> = settingsStore.state

    private val storage = MutableStateFlow<Storage?>(null)
    val status: StateFlow<StorageStatus> = storage.map { s ->
        when (s) {
            null -> StorageStatus.Loading
            is Storage.Ready -> StorageStatus.Ready
            is Storage.Unavailable -> StorageStatus.Unavailable(s.message)
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, StorageStatus.Loading)

    private val ready: Storage.Ready? get() = storage.value as? Storage.Ready

    val contacts: StateFlow<List<Contact>> = storage.flatMapLatest { s ->
        if (s is Storage.Ready) s.repo.contacts else flowOf(emptyList())
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val contactsById: StateFlow<Map<String, Contact>> = contacts.map { list -> list.associateBy { it.id } }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    val tagUsage: StateFlow<List<TagUsage>> = storage.flatMapLatest { s ->
        if (s is Storage.Ready) s.repo.tagUsage else flowOf(emptyList())
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Fiches révélées temporairement malgré les filtres (après une recherche). */
    private val reveal = MutableStateFlow<Set<String>>(emptySet())

    val layout: StateFlow<GraphLayoutResult> = combine(contacts, settings, reveal) { list, s, rev ->
        buildLayout(list, s, rev)
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, GraphLayoutResult.EMPTY)

    val camera = CameraState()
    var focus by mutableStateOf<FocusRequest?>(null)
        private set

    val sheets = mutableStateListOf<Sheet>()
    var draft by mutableStateOf(EditDraft())
        private set
    private var draftOriginal = EditDraft()
    var confirmDiscard by mutableStateOf(false)
        private set
    var confirmDeleteId by mutableStateOf<String?>(null)
        private set
    var message by mutableStateOf<String?>(null)
        private set

    // --- Recherche ---
    val query = MutableStateFlow("")
    val searchFavoritesOnly = MutableStateFlow(false)
    private val searchLimit = MutableStateFlow(PAGE)
    val searchResults: StateFlow<List<String>?> = combine(query, searchFavoritesOnly, searchLimit, storage, contacts) { q, fav, limit, s, _ ->
        Triple(q, fav, limit) to s
    }.debounce(120).mapLatest { (params, s) ->
        val (q, fav, limit) = params
        val repo = (s as? Storage.Ready)?.repo
        val result: List<String>? = if (repo == null || (q.isBlank() && !fav)) null else repo.search(q, fav, limit, 0)
        result
    }.flowOn(Dispatchers.IO).stateIn(viewModelScope, SharingStarted.Eagerly, null)

    init {
        viewModelScope.launch { storage.value = appRef.storage() }
    }

    fun retryStorage() {
        viewModelScope.launch { storage.value = appRef.storage() }
    }

    fun resetUnreadable() {
        viewModelScope.launch {
            appRef.resetAfterUnreadable()
            storage.value = null
            storage.value = appRef.storage()
        }
    }

    // --- Panneaux ---
    fun open(sheet: Sheet) {
        if (sheets.lastOrNull()?.key == sheet.key) return
        sheets.add(sheet)
    }

    /** Retour Android / fermeture du panneau du dessus. */
    fun back() {
        val top = sheets.lastOrNull() ?: return
        if (top is Sheet.Edit && isDraftDirty()) {
            confirmDiscard = true
            return
        }
        if (top is Sheet.Edit) discardDraftFiles()
        sheets.removeAt(sheets.lastIndex)
        if (top is Sheet.Search) reveal.value = emptySet()
    }

    fun closeAll() {
        if (sheets.any { it is Sheet.Edit }) discardDraftFiles()
        sheets.clear()
    }

    fun dismissDiscardDialog(discard: Boolean) {
        confirmDiscard = false
        if (discard) {
            discardDraftFiles()
            draft = draftOriginal
            sheets.removeAt(sheets.lastIndex)
        }
    }

    fun clearMessage() { message = null }
    fun showMessage(m: String) { message = m }

    // --- Sélection et recherche ---
    fun onNodeTapped(nodeId: String) {
        if (nodeId.startsWith("g:")) return
        open(Sheet.Card(nodeId))
    }

    fun selectSearchResult(id: String) {
        val s = settings.value
        val c = contactsById.value[id] ?: return
        val hidden = (s.favoritesOnly && !c.favorite) ||
            (c.kind == EntityKind.PERSON && !s.showPersons) || (c.kind == EntityKind.COMPANY && !s.showCompanies)
        if (hidden) reveal.value = reveal.value + id
        focus = FocusRequest(id, System.nanoTime())
        open(Sheet.Card(id))
    }

    fun loadMoreResults() { searchLimit.value += PAGE }
    fun setQuery(q: String) { query.value = q; searchLimit.value = PAGE }

    // --- Édition ---
    fun startAdd(kind: EntityKind) {
        draftOriginal = EditDraft(kind = kind)
        draft = draftOriginal
        if (sheets.lastOrNull() is Sheet.AddChooser) sheets.removeAt(sheets.lastIndex)
        open(Sheet.Edit(System.nanoTime()))
    }

    fun startEdit(id: String) {
        val c = contactsById.value[id] ?: return
        draftOriginal = EditDraft.from(c).let { d ->
            if (c.companyId != null) d.copy(companyText = contactsById.value[c.companyId]?.displayName ?: "") else d
        }
        draft = draftOriginal
        open(Sheet.Edit(System.nanoTime()))
    }

    fun updateDraft(transform: (EditDraft) -> EditDraft) { draft = transform(draft) }

    fun isDraftDirty(): Boolean = draft != draftOriginal

    fun canSave(): Boolean = draft.toContact().hasIdentity

    private fun discardDraftFiles() {
        val photos = ready?.photos ?: return
        val newPhoto = draft.photoId
        if (newPhoto != null && newPhoto != draftOriginal.photoId) photos.delete(newPhoto)
    }

    fun pickPhoto(uri: Uri) {
        val photos = ready?.photos ?: return
        viewModelScope.launch {
            try {
                val old = draft.photoId
                val id = photos.importFromUri(uri)
                if (old != null && old != draftOriginal.photoId) photos.delete(old)
                draft = draft.copy(photoId = id)
            } catch (e: Exception) {
                message = "Cette image n'a pas pu être importée."
            }
        }
    }

    fun removePhoto() {
        val photos = ready?.photos
        val cur = draft.photoId
        if (photos != null && cur != null && cur != draftOriginal.photoId) photos.delete(cur)
        draft = draft.copy(photoId = null)
    }

    fun saveDraft() {
        val r = ready ?: return
        if (!canSave()) {
            message = "Ajoute au moins un nom ou un libellé."
            return
        }
        val d = draft
        val original = draftOriginal
        viewModelScope.launch {
            try {
                val id = r.repo.save(
                    SaveRequest(
                        contact = d.toContact(),
                        tagLabels = d.tags,
                        companyText = if (d.companyId == null) d.companyText else "",
                    )
                )
                val oldPhoto = original.photoId
                if (oldPhoto != null && oldPhoto != d.photoId) r.photos.delete(oldPhoto)
                draftOriginal = d
                val top = sheets.lastOrNull()
                if (top is Sheet.Edit) sheets.removeAt(sheets.lastIndex)
                if (d.isNew) {
                    focus = FocusRequest(id, System.nanoTime())
                    open(Sheet.Card(id))
                }
            } catch (e: Exception) {
                message = "Enregistrement impossible. Rien n'a été modifié."
            }
        }
    }

    fun toggleFavorite(id: String) {
        val r = ready ?: return
        val c = contactsById.value[id] ?: return
        viewModelScope.launch { r.repo.setFavorite(id, !c.favorite) }
    }

    fun askDelete(id: String) { confirmDeleteId = id }

    fun confirmDelete(confirm: Boolean) {
        val id = confirmDeleteId
        confirmDeleteId = null
        if (!confirm || id == null) return
        val r = ready ?: return
        viewModelScope.launch {
            val photo = r.repo.delete(id)
            photo?.let { r.photos.delete(it) }
            draftOriginal = draft // la fiche n'existe plus : pas de demande d'abandon
            sheets.removeAll { (it is Sheet.Card && it.id == id) || (it is Sheet.Info && it.id == id) || it is Sheet.Edit }
        }
    }

    // --- Valeurs communes ---
    fun renameTag(id: String, label: String) {
        val r = ready ?: return
        viewModelScope.launch { r.repo.renameTag(id, label) }
    }

    fun deleteTag(id: String) {
        val r = ready ?: return
        viewModelScope.launch { r.repo.deleteTag(id) }
    }

    // --- Sauvegarde ---
    var backupBusy by mutableStateOf(false)
        private set
    var backupStatus by mutableStateOf<String?>(null)
        private set

    private fun backupManager(r: Storage.Ready) =
        BackupManager(r.db, r.repo, r.photos, settingsStore, getApplication<Application>().contentResolver)

    fun exportBackup(uri: Uri, password: CharArray) {
        val r = ready ?: return
        viewModelScope.launch {
            backupBusy = true
            backupStatus = null
            backupStatus = try {
                val n = backupManager(r).export(uri, password)
                "Export terminé : $n fiche(s), chiffré. Garde ce fichier et son mot de passe en lieu sûr."
            } catch (e: Exception) {
                "Échec de l'export : ce fichier est incomplet, ne t'y fie pas."
            } finally {
                password.fill('\u0000')
                backupBusy = false
            }
        }
    }

    fun importBackup(uri: Uri, password: CharArray, mode: ImportMode) {
        val r = ready ?: return
        viewModelScope.launch {
            backupBusy = true
            backupStatus = null
            backupStatus = try {
                val s = backupManager(r).importFrom(uri, password, mode)
                buildString {
                    append("Import terminé : ${s.imported} fiche(s) importée(s)")
                    if (s.keptNewerLocal > 0) append(", ${s.keptNewerLocal} gardée(s) car plus récentes ici")
                    append(".")
                }
            } catch (e: BackupException) {
                e.reason.message + " Ton réseau actuel n'a pas été modifié."
            } catch (e: Exception) {
                "Import impossible (fichier illisible ou incomplet). Ton réseau actuel n'a pas été modifié."
            } finally {
                password.fill('\u0000')
                backupBusy = false
            }
        }
    }

    fun clearBackupStatus() { backupStatus = null }

    // --- Réglages ---
    fun updateSettings(transform: (Settings) -> Settings) = settingsStore.update(transform)

    fun photoStore() = ready?.photos
    fun repository() = ready?.repo
    fun database() = ready?.db

    companion object {
        const val PAGE = 50

        fun buildLayout(list: List<Contact>, s: Settings, reveal: Set<String>): GraphLayoutResult {
            val self = list.firstOrNull { it.kind == EntityKind.SELF }
            val byId = list.associateBy { it.id }
            val labels = HashMap<String, String>()
            val type = s.criterion.tagType
            val items = ArrayList<LayoutItem>(list.size)
            for (c in list) {
                if (c.kind == EntityKind.SELF) continue
                val visible = (c.id in reveal) || (
                    (!s.favoritesOnly || c.favorite) &&
                        (c.kind != EntityKind.PERSON || s.showPersons) &&
                        (c.kind != EntityKind.COMPANY || s.showCompanies)
                    )
                if (!visible) continue
                val keys: List<String> = when {
                    type != null -> c.tagsOf(type).map { t -> labels[t.id] = t.label; t.id }
                    s.criterion == GroupCriterion.COMPANY -> {
                        val companyId = if (c.kind == EntityKind.COMPANY) c.id else c.companyId
                        if (companyId != null) {
                            labels[companyId] = byId[companyId]?.displayName ?: "Entreprise"
                            listOf(companyId)
                        } else emptyList()
                    }
                    else -> emptyList()
                }
                items.add(LayoutItem(c.id, c.kind, c.displayName, c.initials, c.favorite, c.photoId, keys, c.createdAt))
            }
            return GraphLayout.compute(
                selfLabel = self?.displayName ?: "Moi",
                selfInitials = self?.initials ?: "M",
                selfPhotoId = self?.photoId,
                items = items,
                groupLabels = labels,
            )
        }
    }
}
