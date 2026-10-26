package ch.m4rm0tt.mynetworks.ui

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ch.m4rm0tt.mynetworks.domain.EntityKind
import ch.m4rm0tt.mynetworks.domain.GraphLayout
import ch.m4rm0tt.mynetworks.domain.TagType
import ch.m4rm0tt.mynetworks.ui.components.GlassRoundButton
import ch.m4rm0tt.mynetworks.ui.components.Glyph
import ch.m4rm0tt.mynetworks.ui.components.SheetHost
import ch.m4rm0tt.mynetworks.ui.graph.NetworkGraph
import ch.m4rm0tt.mynetworks.ui.sheets.AddChooserSheet
import ch.m4rm0tt.mynetworks.ui.sheets.BackupSheet
import ch.m4rm0tt.mynetworks.ui.sheets.CardSheet
import ch.m4rm0tt.mynetworks.ui.sheets.EditSheet
import ch.m4rm0tt.mynetworks.ui.sheets.ExternalActions
import ch.m4rm0tt.mynetworks.ui.sheets.InfoSheet
import ch.m4rm0tt.mynetworks.ui.sheets.LockAvailability
import ch.m4rm0tt.mynetworks.ui.sheets.SearchSheet
import ch.m4rm0tt.mynetworks.ui.sheets.SettingsSheet
import ch.m4rm0tt.mynetworks.ui.sheets.ValuesSheet
import ch.m4rm0tt.mynetworks.ui.theme.Mn

@Composable
fun MainScreen(
    vm: MainViewModel,
    actions: ExternalActions,
    lock: LockAvailability,
    systemReduceMotion: Boolean,
    onEnableLock: () -> Unit,
) {
    val status by vm.status.collectAsStateWithLifecycle()
    Box(Modifier.fillMaxSize().background(Mn.Black)) {
        when (val s = status) {
            StorageStatus.Loading -> Unit
            is StorageStatus.Unavailable -> UnreadableScreen(s.message, onRetry = vm::retryStorage, onReset = vm::resetUnreadable)
            StorageStatus.Ready -> ReadyScreen(vm, actions, lock, systemReduceMotion, onEnableLock)
        }
    }
}

@Composable
private fun ReadyScreen(
    vm: MainViewModel,
    actions: ExternalActions,
    lock: LockAvailability,
    systemReduceMotion: Boolean,
    onEnableLock: () -> Unit,
) {
    val context = LocalContext.current
    val settings by vm.settings.collectAsStateWithLifecycle()
    val layout by vm.layout.collectAsStateWithLifecycle()
    val contacts by vm.contacts.collectAsStateWithLifecycle()
    val byId by vm.contactsById.collectAsStateWithLifecycle()
    val tagUsage by vm.tagUsage.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()
    val favOnly by vm.searchFavoritesOnly.collectAsStateWithLifecycle()
    val results by vm.searchResults.collectAsStateWithLifecycle()
    val reduceMotion = systemReduceMotion
    val photos = vm.photoStore()
    val sheets = vm.sheets
    val topCard = (sheets.lastOrNull { it is Sheet.Card } as? Sheet.Card)?.id

    BackHandler(enabled = sheets.isNotEmpty()) { vm.back() }

    LaunchedEffect(vm.message) {
        vm.message?.let {
            Toast.makeText(context, it, Toast.LENGTH_LONG).show()
            vm.clearMessage()
        }
    }

    val suggestions = remember(tagUsage) {
        TagType.entries.associateWith { t ->
            tagUsage.filter { it.type == t.code }.sortedWith(compareByDescending<ch.m4rm0tt.mynetworks.data.db.TagUsage> { it.usage }.thenBy { it.label.lowercase() }).map { it.label }
        }
    }

    Box(Modifier.fillMaxSize()) {
        NetworkGraph(
            layout = layout,
            camera = vm.camera,
            focus = vm.focus,
            selectedId = topCard,
            photos = photos,
            idleMotion = settings.idleMotion,
            reduceMotion = reduceMotion,
            bottomInsetFraction = if (sheets.isNotEmpty()) 0.45f else 0f,
            onTapNode = vm::onNodeTapped,
            modifier = Modifier.fillMaxSize(),
        )

        val entityCount = contacts.count { it.kind != EntityKind.SELF }
        if (entityCount == 0 && sheets.isEmpty()) {
            Text(
                "Ton réseau commence ici.\nTouche + pour ajouter une personne ou une entreprise.",
                color = Mn.TextDim,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(bottom = 96.dp, start = 32.dp, end = 32.dp),
            )
        }

        // Boutons flottants permanents : Recherche + Réglages (haut droite), + (bas gauche).
        Row(
            Modifier
                .align(Alignment.TopEnd)
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            GlassRoundButton(Glyph.Search, "Rechercher", { vm.open(Sheet.Search) }, reduceMotion = reduceMotion)
            GlassRoundButton(Glyph.Settings, "Réglages", { vm.open(Sheet.Settings) }, reduceMotion = reduceMotion)
        }
        GlassRoundButton(
            Glyph.Plus, "Ajouter une personne ou une entreprise", { vm.open(Sheet.AddChooser) },
            modifier = Modifier
                .align(Alignment.BottomStart)
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(20.dp),
            size = 62.dp,
            reduceMotion = reduceMotion,
        )

        SheetHost(sheets = sheets.toList(), reduceMotion = reduceMotion, onDismissTop = vm::back) { sheet ->
            when (sheet) {
                is Sheet.Card -> {
                    val c = byId[sheet.id]
                    if (c == null) Missing() else CardSheet(
                        c = c,
                        company = c.companyId?.let { byId[it] },
                        settings = settings,
                        photos = photos,
                        actions = actions,
                        reduceMotion = reduceMotion,
                        onInfo = { vm.open(Sheet.Info(c.id)) },
                        onEdit = { vm.startEdit(c.id) },
                        onToggleFavorite = { vm.toggleFavorite(c.id) },
                    )
                }
                is Sheet.Info -> {
                    val c = byId[sheet.id]
                    if (c == null) Missing() else InfoSheet(
                        c = c,
                        company = c.companyId?.let { byId[it] },
                        employees = if (c.kind == EntityKind.COMPANY) contacts.filter { it.companyId == c.id } else emptyList(),
                        actions = actions,
                        onOpen = { id -> vm.selectSearchResult(id) },
                        onDelete = { vm.askDelete(c.id) },
                    )
                }
                is Sheet.Edit -> EditSheet(
                    draft = vm.draft,
                    canSave = vm.canSave(),
                    allContacts = contacts,
                    tagSuggestions = suggestions,
                    photos = photos,
                    onChange = vm::updateDraft,
                    onPickPhoto = vm::pickPhoto,
                    onRemovePhoto = vm::removePhoto,
                    onSave = vm::saveDraft,
                    onCancel = vm::back,
                )
                Sheet.Search -> SearchSheet(
                    query = query,
                    favoritesOnly = favOnly,
                    resultIds = results,
                    byId = byId,
                    pageSize = MainViewModel.PAGE,
                    photos = photos,
                    onQuery = vm::setQuery,
                    onFavoritesOnly = { vm.searchFavoritesOnly.value = it },
                    onSelect = vm::selectSearchResult,
                    onMore = vm::loadMoreResults,
                )
                Sheet.Settings -> SettingsSheet(
                    settings = settings,
                    lock = lock,
                    onUpdate = { vm.updateSettings(it) },
                    onEnableLock = onEnableLock,
                    onOpenValues = { vm.open(Sheet.Values) },
                    onOpenBackup = { vm.clearBackupStatus(); vm.open(Sheet.Backup) },
                    onEditMe = { vm.startEdit(GraphLayout.SELF_ID) },
                )
                Sheet.AddChooser -> AddChooserSheet(onPick = vm::startAdd)
                Sheet.Values -> ValuesSheet(tagUsage, onRename = vm::renameTag, onDelete = vm::deleteTag)
                Sheet.Backup -> BackupSheet(
                    busy = vm.backupBusy,
                    status = vm.backupStatus,
                    onExport = vm::exportBackup,
                    onImport = vm::importBackup,
                )
            }
        }

        if (vm.confirmDiscard) {
            AlertDialog(
                onDismissRequest = { vm.dismissDiscardDialog(false) },
                title = { Text("Abandonner les modifications ?") },
                text = { Text("Ce que tu as saisi n'est pas encore enregistré.") },
                confirmButton = { TextButton(onClick = { vm.dismissDiscardDialog(true) }) { Text("Abandonner", color = Mn.Danger) } },
                dismissButton = { TextButton(onClick = { vm.dismissDiscardDialog(false) }) { Text("Continuer la saisie", color = Mn.Violet) } },
                containerColor = Mn.Surface2,
            )
        }
        vm.confirmDeleteId?.let { id ->
            AlertDialog(
                onDismissRequest = { vm.confirmDelete(false) },
                title = { Text("Supprimer « ${byId[id]?.displayName ?: ""} » ?") },
                text = { Text("La fiche et sa photo seront supprimées de ce téléphone. Les personnes rattachées à une entreprise supprimée sont conservées.") },
                confirmButton = { TextButton(onClick = { vm.confirmDelete(true) }) { Text("Supprimer", color = Mn.Danger) } },
                dismissButton = { TextButton(onClick = { vm.confirmDelete(false) }) { Text("Annuler", color = Mn.Violet) } },
                containerColor = Mn.Surface2,
            )
        }
    }
}

@Composable
private fun Missing() {
    Text("Cette fiche n'existe plus.", color = Mn.TextDim, modifier = Modifier.padding(24.dp))
}

@Composable
private fun UnreadableScreen(message: String, onRetry: () -> Unit, onReset: () -> Unit) {
    var confirm by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(28.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Données inaccessibles", style = MaterialTheme.typography.headlineSmall, color = Mn.Text)
        Spacer(Modifier.size(12.dp))
        Text(message, color = Mn.TextDim, style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.size(12.dp))
        Text(
            "Rien n'a été effacé. Si la clé de chiffrement de cet appareil a été perdue (réinitialisation de la sécurité, " +
                "restauration système), les données locales ne peuvent pas être relues. Tu peux repartir d'une base vide " +
                "puis importer ta dernière sauvegarde chiffrée ; les anciens fichiers seront mis de côté, pas supprimés.",
            color = Mn.TextDim, style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.size(20.dp))
        Button(onClick = onRetry, colors = ButtonDefaults.buttonColors(containerColor = Mn.Violet, contentColor = Mn.Black), modifier = Modifier.fillMaxWidth()) {
            Text("Réessayer")
        }
        TextButton(onClick = { confirm = true }, modifier = Modifier.fillMaxWidth()) { Text("Repartir d'une base vide…", color = Mn.Danger) }
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Repartir d'une base vide ?") },
            text = { Text("Les fichiers illisibles seront déplacés dans un dossier à part (non supprimés). Tu pourras ensuite importer une sauvegarde.") },
            confirmButton = { TextButton(onClick = { confirm = false; onReset() }) { Text("Confirmer", color = Mn.Danger) } },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Annuler", color = Mn.Violet) } },
            containerColor = Mn.Surface2,
        )
    }
}
