package ch.m4rm0tt.mynetworks.ui.sheets

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import ch.m4rm0tt.mynetworks.data.PhotoStore
import ch.m4rm0tt.mynetworks.domain.Contact
import ch.m4rm0tt.mynetworks.domain.EntityKind
import ch.m4rm0tt.mynetworks.domain.TagType
import ch.m4rm0tt.mynetworks.domain.TextNorm
import ch.m4rm0tt.mynetworks.ui.EditDraft
import ch.m4rm0tt.mynetworks.ui.components.Avatar
import ch.m4rm0tt.mynetworks.ui.components.Glyph
import ch.m4rm0tt.mynetworks.ui.components.GlyphIcon
import ch.m4rm0tt.mynetworks.ui.components.Pill
import ch.m4rm0tt.mynetworks.ui.components.TagChip
import ch.m4rm0tt.mynetworks.ui.theme.Mn

@Composable
fun mnFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = Mn.Text,
    unfocusedTextColor = Mn.Text,
    focusedBorderColor = Mn.Violet,
    unfocusedBorderColor = Mn.Rim,
    focusedLabelColor = Mn.Violet,
    unfocusedLabelColor = Mn.TextDim,
    cursorColor = Mn.Violet,
    focusedContainerColor = Mn.Black.copy(alpha = 0.35f),
    unfocusedContainerColor = Mn.Black.copy(alpha = 0.25f),
)

@Composable
fun MnField(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    keyboard: KeyboardType = KeyboardType.Text,
    capitalization: KeyboardCapitalization = KeyboardCapitalization.Sentences,
    singleLine: Boolean = true,
    imeAction: ImeAction = ImeAction.Next,
    onDone: (() -> Unit)? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = singleLine,
        minLines = if (singleLine) 1 else 3,
        keyboardOptions = KeyboardOptions(capitalization = capitalization, keyboardType = keyboard, imeAction = imeAction),
        keyboardActions = KeyboardActions(onDone = { onDone?.invoke() }),
        colors = mnFieldColors(),
        shape = RoundedCornerShape(16.dp),
        modifier = modifier.fillMaxWidth().padding(vertical = 4.dp),
    )
}

/**
 * Saisie de valeurs communes : propose les valeurs déjà utilisées, autorise la création libre,
 * et ignore les doublons triviaux (casse, espaces).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TagInput(
    label: String,
    values: List<String>,
    suggestions: List<String>,
    single: Boolean,
    onChange: (List<String>) -> Unit,
) {
    var text by rememberSaveable(label) { mutableStateOf("") }
    fun add(v: String) {
        val clean = TextNorm.clean(v)
        if (clean.isEmpty()) return
        val key = TextNorm.tagKey(clean)
        // Si la valeur existe déjà (casse/espaces près), on réutilise son orthographe.
        val canonical = suggestions.firstOrNull { TextNorm.tagKey(it) == key } ?: clean
        val next = if (single) listOf(canonical)
        else if (values.any { TextNorm.tagKey(it) == key }) values else values + canonical
        onChange(next)
        text = ""
    }
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(label.uppercase(), style = MaterialTheme.typography.titleSmall, color = Mn.TextFaint)
        if (values.isNotEmpty()) {
            FlowRow(
                Modifier.padding(top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                values.forEach { v -> TagChip(v, onRemove = { onChange(values - v) }) }
            }
        }
        MnField(
            value = text,
            onChange = { text = it },
            label = if (single && values.isNotEmpty()) "Remplacer" else "Ajouter",
            imeAction = ImeAction.Done,
            onDone = { add(text) },
        )
        val folded = TextNorm.fold(text)
        val proposals = suggestions
            .filter { s -> values.none { TextNorm.tagKey(it) == TextNorm.tagKey(s) } }
            .filter { folded.isEmpty() || TextNorm.fold(it).contains(folded) }
            .take(8)
        if (proposals.isNotEmpty() || text.isNotBlank()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (text.isNotBlank() && proposals.none { TextNorm.tagKey(it) == TextNorm.tagKey(text) }) {
                    Pill("+ « ${TextNorm.clean(text)} »", selected = true, onClick = { add(text) })
                }
                proposals.forEach { s -> Pill(s, selected = false, onClick = { add(s) }) }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EditSheet(
    draft: EditDraft,
    canSave: Boolean,
    allContacts: List<Contact>,
    tagSuggestions: Map<TagType, List<String>>,
    photos: PhotoStore?,
    onChange: ((EditDraft) -> EditDraft) -> Unit,
    onPickPhoto: (android.net.Uri) -> Unit,
    onRemovePhoto: () -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
) {
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) onPickPhoto(uri)
    }
    val hasDetails = draft.tags.isNotEmpty() || draft.note.isNotBlank() || draft.links.isNotEmpty() ||
        draft.companyId != null || draft.companyText.isNotBlank() || draft.fallbackLabel.isNotBlank()
    var expanded by rememberSaveable { mutableStateOf(hasDetails || draft.kind == EntityKind.SELF) }
    val title = when {
        draft.kind == EntityKind.SELF -> "Ma carte"
        draft.isNew && draft.kind == EntityKind.COMPANY -> "Nouvelle entreprise"
        draft.isNew -> "Nouvelle personne"
        else -> "Modifier"
    }

    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.titleLarge, color = Mn.Text, modifier = Modifier.weight(1f).semantics { heading() })
            TextButton(onClick = onCancel) { Text("Annuler", color = Mn.TextDim) }
        }

        // Photo (sélecteur de photos Android : aucun accès général à la galerie).
        Row(Modifier.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            val c = draft.toContact()
            Avatar(draft.photoId, if (c.hasIdentity) c.initials else "+", draft.kind, photos, 64.dp)
            Column(Modifier.padding(start = 14.dp)) {
                TextButton(onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) {
                    Text(if (draft.photoId == null) (if (draft.kind == EntityKind.COMPANY) "Ajouter un logo" else "Ajouter une photo") else "Changer", color = Mn.Violet)
                }
                if (draft.photoId != null) TextButton(onClick = onRemovePhoto) { Text("Retirer", color = Mn.TextDim) }
            }
        }

        // Identité et coordonnées d'abord.
        if (draft.kind == EntityKind.COMPANY) {
            MnField(draft.companyName, { v -> onChange { it.copy(companyName = v) } }, "Nom de l'entreprise", capitalization = KeyboardCapitalization.Words)
        } else {
            MnField(draft.firstName, { v -> onChange { it.copy(firstName = v) } }, "Prénom", capitalization = KeyboardCapitalization.Words)
            MnField(draft.lastName, { v -> onChange { it.copy(lastName = v) } }, "Nom", capitalization = KeyboardCapitalization.Words)
        }
        MnField(draft.phone, { v -> onChange { it.copy(phone = v) } }, "Téléphone", keyboard = KeyboardType.Phone, capitalization = KeyboardCapitalization.None)
        MnField(draft.email, { v -> onChange { it.copy(email = v) } }, "E-mail", keyboard = KeyboardType.Email, capitalization = KeyboardCapitalization.None)

        if (draft.kind != EntityKind.SELF) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 52.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .clickable(role = Role.Switch) { onChange { it.copy(favorite = !it.favorite) } }
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                GlyphIcon(if (draft.favorite) Glyph.Star else Glyph.StarOutline, if (draft.favorite) Mn.Legendary else Mn.TextDim)
                Text("Favori (lien légendaire)", color = Mn.Text, modifier = Modifier.padding(start = 10.dp).weight(1f))
                Switch(
                    checked = draft.favorite,
                    onCheckedChange = { v -> onChange { it.copy(favorite = v) } },
                    colors = SwitchDefaults.colors(checkedTrackColor = Mn.Legendary.copy(alpha = 0.6f), checkedThumbColor = Mn.Legendary),
                )
            }
        }

        if (!expanded) {
            TextButton(onClick = { expanded = true }, modifier = Modifier.padding(top = 4.dp)) {
                Text("+ Métier, entreprise, compétences, catégories, emplacement, liens, note", color = Mn.Violet)
            }
        }
        AnimatedVisibility(expanded) {
            Column {
                if (draft.kind != EntityKind.COMPANY) {
                    TagInput("Métier", draft.tags[TagType.PROFESSION].orEmpty(), tagSuggestions[TagType.PROFESSION].orEmpty(), single = true) { v ->
                        onChange { it.copy(tags = it.tags + (TagType.PROFESSION to v)) }
                    }
                    CompanyInput(draft, allContacts, onChange)
                }
                TagInput(
                    if (draft.kind == EntityKind.COMPANY) "Compétences / activités" else "Compétences",
                    draft.tags[TagType.SKILL].orEmpty(), tagSuggestions[TagType.SKILL].orEmpty(), single = false,
                ) { v -> onChange { it.copy(tags = it.tags + (TagType.SKILL to v)) } }
                TagInput("Catégories", draft.tags[TagType.CATEGORY].orEmpty(), tagSuggestions[TagType.CATEGORY].orEmpty(), single = false) { v ->
                    onChange { it.copy(tags = it.tags + (TagType.CATEGORY to v)) }
                }
                TagInput("Emplacement (texte libre)", draft.tags[TagType.LOCATION].orEmpty(), tagSuggestions[TagType.LOCATION].orEmpty(), single = true) { v ->
                    onChange { it.copy(tags = it.tags + (TagType.LOCATION to v)) }
                }
                LinksInput(draft.links) { v -> onChange { it.copy(links = v) } }
                MnField(draft.note, { v -> onChange { it.copy(note = v) } }, "Note", singleLine = false, imeAction = ImeAction.Default)
                if (draft.kind != EntityKind.SELF) {
                    MnField(
                        draft.fallbackLabel, { v -> onChange { it.copy(fallbackLabel = v) } },
                        "Libellé de remplacement (si pas de nom)",
                    )
                }
            }
        }

        Spacer(Modifier.size(14.dp))
        if (!canSave) {
            Text(
                if (draft.kind == EntityKind.COMPANY) "Un nom d'entreprise ou un libellé suffit pour enregistrer."
                else "Un prénom, un nom ou un libellé suffit pour enregistrer.",
                color = Mn.TextDim, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 6.dp),
            )
        }
        Button(
            onClick = onSave,
            enabled = canSave,
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
            shape = RoundedCornerShape(18.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Mn.Violet, contentColor = Mn.Black, disabledContainerColor = Mn.Surface3),
        ) { Text("Enregistrer", style = MaterialTheme.typography.labelLarge) }
        Spacer(Modifier.size(12.dp))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CompanyInput(draft: EditDraft, all: List<Contact>, onChange: ((EditDraft) -> EditDraft) -> Unit) {
    val companies = remember(all) { all.filter { it.kind == EntityKind.COMPANY } }
    val linked = draft.companyId?.let { id -> companies.firstOrNull { it.id == id } }
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text("ENTREPRISE", style = MaterialTheme.typography.titleSmall, color = Mn.TextFaint)
        if (linked != null) {
            Row(Modifier.padding(top = 6.dp)) {
                TagChip(linked.displayName, onRemove = { onChange { it.copy(companyId = null, companyText = "") } })
            }
        } else {
            MnField(
                draft.companyText,
                { v -> onChange { it.copy(companyText = v, companyId = null) } },
                "Nom de l'entreprise",
                capitalization = KeyboardCapitalization.Words,
            )
            val folded = TextNorm.fold(draft.companyText)
            val matches = companies.filter { folded.isEmpty() || TextNorm.fold(it.displayName).contains(folded) }.take(8)
            if (matches.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    matches.forEach { co ->
                        Pill(co.displayName, selected = false, leading = Glyph.Company, onClick = {
                            onChange { it.copy(companyId = co.id, companyText = co.displayName) }
                        })
                    }
                }
            }
            if (draft.companyText.isNotBlank()) {
                val exact = companies.any { TextNorm.tagKey(it.companyName) == TextNorm.tagKey(draft.companyText) }
                Text(
                    if (exact) "Sera rattachée à l'entreprise existante du même nom." else "Une fiche entreprise sera créée.",
                    style = MaterialTheme.typography.bodySmall, color = Mn.TextDim, modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun LinksInput(links: List<String>, onChange: (List<String>) -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text("RÉSEAUX ET LIENS", style = MaterialTheme.typography.titleSmall, color = Mn.TextFaint)
        links.forEachIndexed { i, l ->
            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                GlyphIcon(Glyph.Link, Mn.Violet, size = 18.dp)
                Text(l, color = Mn.Text, modifier = Modifier.weight(1f).padding(horizontal = 8.dp), maxLines = 1)
                TextButton(onClick = { onChange(links.filterIndexed { j, _ -> j != i }) }) { Text("Retirer", color = Mn.TextDim) }
            }
        }
        MnField(
            text, { text = it }, "Ajouter un lien (Instagram, LinkedIn, site…)",
            keyboard = KeyboardType.Uri, capitalization = KeyboardCapitalization.None, imeAction = ImeAction.Done,
            onDone = {
                val v = text.trim()
                if (v.isNotEmpty()) { onChange(links + v); text = "" }
            },
        )
        if (text.isNotBlank()) {
            Row(
                Modifier
                    .clip(RoundedCornerShape(50))
                    .background(Mn.Violet.copy(alpha = 0.2f))
                    .clickable { onChange(links + text.trim()); text = "" }
                    .padding(horizontal = 14.dp, vertical = 8.dp)
            ) { Text("Ajouter ce lien", color = Mn.Text) }
        }
    }
}
