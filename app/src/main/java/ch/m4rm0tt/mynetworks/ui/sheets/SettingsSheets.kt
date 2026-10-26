package ch.m4rm0tt.mynetworks.ui.sheets

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import ch.m4rm0tt.mynetworks.BuildConfig
import ch.m4rm0tt.mynetworks.data.Settings
import ch.m4rm0tt.mynetworks.data.db.TagUsage
import ch.m4rm0tt.mynetworks.domain.CardField
import ch.m4rm0tt.mynetworks.domain.EntityKind
import ch.m4rm0tt.mynetworks.domain.GroupCriterion
import ch.m4rm0tt.mynetworks.domain.TagType
import ch.m4rm0tt.mynetworks.ui.components.GlassAction
import ch.m4rm0tt.mynetworks.ui.components.Glyph
import ch.m4rm0tt.mynetworks.ui.components.GlyphIcon
import ch.m4rm0tt.mynetworks.ui.components.Pill
import ch.m4rm0tt.mynetworks.ui.theme.Mn

@Composable
private fun Section(title: String) {
    Text(
        title.uppercase(), style = MaterialTheme.typography.titleSmall, color = Mn.Violet,
        modifier = Modifier.padding(top = 18.dp, bottom = 6.dp).semantics { heading() },
    )
}

@Composable
private fun SwitchRow(label: String, detail: String?, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clip(RoundedCornerShape(14.dp))
            .clickable(enabled = enabled, role = Role.Switch) { onChange(!checked) }
            .padding(horizontal = 4.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, color = if (enabled) Mn.Text else Mn.TextFaint, style = MaterialTheme.typography.bodyLarge)
            if (detail != null) Text(detail, color = Mn.TextDim, style = MaterialTheme.typography.bodySmall)
        }
        Switch(
            checked = checked, onCheckedChange = onChange, enabled = enabled,
            colors = SwitchDefaults.colors(checkedTrackColor = Mn.Violet.copy(alpha = 0.6f), checkedThumbColor = Mn.Text),
        )
    }
}

@Composable
private fun LinkRow(glyph: Glyph, label: String, detail: String?, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clip(RoundedCornerShape(14.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GlyphIcon(glyph, Mn.Violet, size = 20.dp)
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(label, color = Mn.Text, style = MaterialTheme.typography.bodyLarge)
            if (detail != null) Text(detail, color = Mn.TextDim, style = MaterialTheme.typography.bodySmall)
        }
    }
}

data class LockAvailability(val available: Boolean, val description: String)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsSheet(
    settings: Settings,
    lock: LockAvailability,
    onUpdate: ((Settings) -> Settings) -> Unit,
    onEnableLock: () -> Unit,
    onOpenValues: () -> Unit,
    onOpenBackup: () -> Unit,
    onEditMe: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
    ) {
        Text("Réglages", style = MaterialTheme.typography.titleLarge, color = Mn.Text, modifier = Modifier.semantics { heading() })
        LinkRow(Glyph.Person, "Ma carte", "Le nœud central : ton mini-CV, rien n'est inventé.", onEditMe)

        Section("Organiser le réseau par")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            GroupCriterion.entries.forEach { c ->
                Pill(c.label, settings.criterion == c, { onUpdate { it.copy(criterion = c) } })
            }
        }
        Text(
            "Un groupe apparaît dès que 2 fiches partagent la même valeur. Une fiche dans plusieurs groupes " +
                "est placée dans le plus grand et reliée aux autres par un trait discret. Aucun lien n'est créé entre personnes.",
            style = MaterialTheme.typography.bodySmall, color = Mn.TextDim, modifier = Modifier.padding(top = 8.dp),
        )

        Section("Afficher")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill("Personnes", settings.showPersons, { onUpdate { it.copy(showPersons = !it.showPersons) } }, leading = Glyph.Person)
            Pill("Entreprises", settings.showCompanies, { onUpdate { it.copy(showCompanies = !it.showCompanies) } }, leading = Glyph.Company)
            Pill("Favoris seulement", settings.favoritesOnly, { onUpdate { it.copy(favoritesOnly = !it.favoritesOnly) } }, leading = Glyph.Star)
        }

        Section("Carte de visite — ordre des informations")
        Text(
            "La carte montre les 3 premières informations renseignées dans cet ordre. Le panneau Informations montre tout.",
            style = MaterialTheme.typography.bodySmall, color = Mn.TextDim,
        )
        OrderEditor("Personne", settings.personOrder, CardField.defaultPersonOrder) { o -> onUpdate { it.copy(personOrder = o) } }
        OrderEditor("Entreprise", settings.companyOrder, CardField.defaultCompanyOrder) { o -> onUpdate { it.copy(companyOrder = o) } }

        Section("Valeurs communes")
        LinkRow(Glyph.Edit, "Corriger ou renommer", "Catégories, compétences, emplacements, métiers", onOpenValues)

        Section("Confidentialité")
        SwitchRow(
            "Verrouiller l'application", lock.description, settings.lockEnabled, enabled = lock.available || settings.lockEnabled,
        ) { on -> if (on) onEnableLock() else onUpdate { it.copy(lockEnabled = false) } }
        if (settings.lockEnabled) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(0 to "Immédiat", 60 to "Après 1 min", 300 to "Après 5 min").forEach { (sec, label) ->
                    Pill(label, settings.relockDelaySec == sec, { onUpdate { it.copy(relockDelaySec = sec) } })
                }
            }
        }
        Text(
            "Les données sont chiffrées sur le téléphone (SQLCipher + Android Keystore), même sans verrouillage. " +
                "L'aperçu dans les applications récentes est masqué.",
            style = MaterialTheme.typography.bodySmall, color = Mn.TextDim, modifier = Modifier.padding(top = 6.dp),
        )

        Section("Téléphone")
        var cc by remember(settings.defaultCountryCode) { mutableStateOf(settings.defaultCountryCode) }
        OutlinedTextField(
            value = cc,
            onValueChange = { v ->
                cc = v.filter { it.isDigit() || it == '+' }.take(5)
                onUpdate { s -> s.copy(defaultCountryCode = cc) }
            },
            label = { Text("Indicatif par défaut pour WhatsApp (ex. +41)") },
            supportingText = { Text("Vide = un numéro local (079…) n'est jamais converti au hasard.") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
            colors = mnFieldColors(),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth(),
        )

        Section("Animations")
        SwitchRow("Mouvement au repos", "Légère respiration des nœuds. Coupée en arrière-plan et si les animations du système sont désactivées.", settings.idleMotion) { v ->
            onUpdate { it.copy(idleMotion = v) }
        }

        Section("Sauvegarde")
        LinkRow(Glyph.Lock, "Exporter ou importer", "Fichier chiffré par mot de passe, photos et préférences incluses", onOpenBackup)

        Section("À propos")
        Text(
            "My Networks ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}). Aucun compte, aucun serveur, aucune " +
                "connexion internet : l'application ne possède pas la permission INTERNET.",
            style = MaterialTheme.typography.bodySmall, color = Mn.TextDim,
        )
        Spacer(Modifier.size(16.dp))
    }
}

@Composable
private fun OrderEditor(title: String, order: List<CardField>, default: List<CardField>, onChange: (List<CardField>) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, color = Mn.Text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            if (order != default) TextButton(onClick = { onChange(default) }) { Text("Ordre proposé", color = Mn.Violet) }
        }
        order.forEachIndexed { i, f ->
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${i + 1}.", color = Mn.TextFaint, modifier = Modifier.padding(end = 8.dp))
                Text(f.label, color = Mn.Text, modifier = Modifier.weight(1f))
                MiniButton(Glyph.Up, "Monter ${f.label}", enabled = i > 0) {
                    onChange(order.toMutableList().apply { add(i - 1, removeAt(i)) })
                }
                MiniButton(Glyph.Down, "Descendre ${f.label}", enabled = i < order.lastIndex) {
                    onChange(order.toMutableList().apply { add(i + 1, removeAt(i)) })
                }
            }
        }
    }
}

@Composable
private fun MiniButton(glyph: Glyph, description: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(48.dp)
            .clip(CircleShape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) { GlyphIcon(glyph, if (enabled) Mn.TextDim else Mn.Surface3, size = 20.dp) }
}

@Composable
fun AddChooserSheet(onPick: (EntityKind) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
        Text("Ajouter", style = MaterialTheme.typography.titleLarge, color = Mn.Text, modifier = Modifier.semantics { heading() })
        Spacer(Modifier.size(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            GlassAction(Glyph.Person, "Une personne", { onPick(EntityKind.PERSON) }, Modifier.weight(1f).heightIn(min = 96.dp))
            GlassAction(Glyph.Company, "Une entreprise", { onPick(EntityKind.COMPANY) }, Modifier.weight(1f).heightIn(min = 96.dp))
        }
        Spacer(Modifier.size(12.dp))
    }
}

/** Corriger, renommer (et réunir) ou supprimer les valeurs communes. */
@Composable
fun ValuesSheet(usage: List<TagUsage>, onRename: (String, String) -> Unit, onDelete: (String) -> Unit) {
    var editing by remember { mutableStateOf<TagUsage?>(null) }
    var deleting by remember { mutableStateOf<TagUsage?>(null) }
    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
    ) {
        Text("Valeurs communes", style = MaterialTheme.typography.titleLarge, color = Mn.Text, modifier = Modifier.semantics { heading() })
        Text(
            "Renommer une valeur avec le nom d'une autre réunit les deux. Rien n'est fusionné automatiquement.",
            style = MaterialTheme.typography.bodySmall, color = Mn.TextDim,
        )
        TagType.entries.forEach { type ->
            val list = usage.filter { it.type == type.code }.sortedBy { it.label.lowercase() }
            if (list.isNotEmpty()) {
                Section(type.plural)
                list.forEach { u ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .clickable(role = Role.Button) { editing = u }
                            .padding(horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(u.label, color = Mn.Text, modifier = Modifier.weight(1f))
                        Text("${u.usage}", color = Mn.TextFaint, modifier = Modifier.padding(horizontal = 8.dp))
                        MiniButton(Glyph.Trash, "Supprimer ${u.label}", enabled = true) { deleting = u }
                    }
                }
            }
        }
        if (usage.isEmpty()) Text("Aucune valeur pour l'instant.", color = Mn.TextDim, modifier = Modifier.padding(vertical = 16.dp))
        Spacer(Modifier.size(12.dp))
    }

    editing?.let { u ->
        var text by remember(u.id) { mutableStateOf(u.label) }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text("Renommer") },
            text = {
                OutlinedTextField(text, { text = it }, singleLine = true, colors = mnFieldColors())
            },
            confirmButton = { TextButton(onClick = { onRename(u.id, text); editing = null }) { Text("Renommer", color = Mn.Violet) } },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("Annuler", color = Mn.TextDim) } },
            containerColor = Mn.Surface2,
        )
    }
    deleting?.let { u ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Supprimer « ${u.label} » ?") },
            text = { Text("La valeur sera retirée de ${u.usage} fiche(s). Les fiches elles-mêmes sont conservées.") },
            confirmButton = { TextButton(onClick = { onDelete(u.id); deleting = null }) { Text("Supprimer", color = Mn.Danger) } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Annuler", color = Mn.TextDim) } },
            containerColor = Mn.Surface2,
        )
    }
}
