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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ch.m4rm0tt.mynetworks.data.PhotoStore
import ch.m4rm0tt.mynetworks.data.Settings
import ch.m4rm0tt.mynetworks.domain.CardField
import ch.m4rm0tt.mynetworks.domain.Contact
import ch.m4rm0tt.mynetworks.domain.EntityKind
import ch.m4rm0tt.mynetworks.domain.TagType
import ch.m4rm0tt.mynetworks.ui.components.Avatar
import ch.m4rm0tt.mynetworks.ui.components.GlassAction
import ch.m4rm0tt.mynetworks.ui.components.GlassRoundButton
import ch.m4rm0tt.mynetworks.ui.components.Glyph
import ch.m4rm0tt.mynetworks.ui.components.GlyphIcon
import ch.m4rm0tt.mynetworks.ui.components.TagChip
import ch.m4rm0tt.mynetworks.ui.theme.Mn

/** Actions vers d'autres applications : toujours déclenchées par un appui explicite. */
interface ExternalActions {
    fun call(phone: String)
    fun whatsapp(phone: String)
    fun email(address: String)
    fun openLink(url: String)
}

/** Valeur d'un champ de carte, ou null s'il est vide (aucune section vide n'est affichée). */
sealed interface FieldValue {
    data class Line(val text: String) : FieldValue
    data class Chips(val items: List<String>) : FieldValue
}

fun fieldValue(field: CardField, c: Contact, company: Contact?): FieldValue? = when (field) {
    CardField.PROFESSION -> c.tagsOf(TagType.PROFESSION).map { it.label }.takeIf { it.isNotEmpty() }?.let { FieldValue.Line(it.joinToString(", ")) }
    CardField.COMPANY -> company?.displayName?.let { FieldValue.Line(it) }
    CardField.SKILLS -> c.tagsOf(TagType.SKILL).map { it.label }.takeIf { it.isNotEmpty() }?.let { FieldValue.Chips(it) }
    CardField.LOCATION -> c.tagsOf(TagType.LOCATION).map { it.label }.takeIf { it.isNotEmpty() }?.let { FieldValue.Line(it.joinToString(", ")) }
    CardField.CATEGORIES -> c.tagsOf(TagType.CATEGORY).map { it.label }.takeIf { it.isNotEmpty() }?.let { FieldValue.Chips(it) }
    CardField.PHONE -> c.phone.takeIf { it.isNotBlank() }?.let { FieldValue.Line(it) }
    CardField.EMAIL -> c.email.takeIf { it.isNotBlank() }?.let { FieldValue.Line(it) }
    CardField.LINKS -> c.links.takeIf { it.isNotEmpty() }?.let { FieldValue.Line(it.joinToString("\n")) }
    CardField.NOTE -> c.note.takeIf { it.isNotBlank() }?.let { FieldValue.Line(it) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FieldBlock(field: CardField, value: FieldValue, maxLines: Int = Int.MAX_VALUE) {
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(field.label.uppercase(), style = MaterialTheme.typography.titleSmall, color = Mn.TextFaint)
        Spacer(Modifier.size(4.dp))
        when (value) {
            is FieldValue.Line -> Text(value.text, style = MaterialTheme.typography.bodyLarge, color = Mn.Text, maxLines = maxLines, overflow = TextOverflow.Ellipsis)
            is FieldValue.Chips -> FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                value.items.forEach { TagChip(it) }
            }
        }
    }
}

/** Carte de visite adaptative, posée au-dessus du graphe. */
@Composable
fun CardSheet(
    c: Contact,
    company: Contact?,
    settings: Settings,
    photos: PhotoStore?,
    actions: ExternalActions,
    reduceMotion: Boolean,
    onInfo: () -> Unit,
    onEdit: () -> Unit,
    onToggleFavorite: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Avatar(c.photoId, c.initials, c.kind, photos, 76.dp, favorite = c.favorite, full = true)
            Column(Modifier.weight(1f).padding(start = 16.dp)) {
                Text(
                    c.displayName, style = MaterialTheme.typography.titleLarge, color = Mn.Text, maxLines = 2,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.semantics { heading() },
                )
                val subtitle = when (c.kind) {
                    EntityKind.SELF -> "Ma carte"
                    EntityKind.COMPANY -> "Entreprise"
                    EntityKind.PERSON -> null
                }
                if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = Mn.Violet)
                if (c.favorite) Text("★ Favori", style = MaterialTheme.typography.labelMedium, color = Mn.Legendary)
            }
            GlassRoundButton(Glyph.Info, "Toutes les informations", onInfo, size = 44.dp, reduceMotion = reduceMotion)
        }

        val order = if (c.kind == EntityKind.COMPANY) settings.companyOrder else settings.personOrder
        // Quelques informations utiles : les 3 premiers champs renseignés selon l'ordre choisi.
        val shown = order.asSequence()
            .filter { it != CardField.PHONE }
            .mapNotNull { f -> fieldValue(f, c, company)?.let { f to it } }
            .take(3).toList()
        Spacer(Modifier.size(12.dp))
        if (shown.isEmpty()) {
            Text(
                if (c.kind == EntityKind.SELF) "Ta carte est vide. Ajoute ce que tu veux y voir (métier, compétences…)."
                else "Pas encore de détails sur cette fiche.",
                style = MaterialTheme.typography.bodyMedium, color = Mn.TextDim, modifier = Modifier.padding(vertical = 8.dp),
            )
        } else {
            shown.forEach { (f, v) -> FieldBlock(f, v, maxLines = 3) }
        }

        Spacer(Modifier.size(14.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (c.phone.isNotBlank() && c.kind != EntityKind.SELF) {
                GlassAction(Glyph.Phone, "Appeler", { actions.call(c.phone) }, Modifier.weight(1f), reduceMotion = reduceMotion)
                GlassAction(Glyph.Chat, "WhatsApp", { actions.whatsapp(c.phone) }, Modifier.weight(1f), reduceMotion = reduceMotion)
            }
            if (c.kind != EntityKind.SELF) {
                GlassAction(
                    if (c.favorite) Glyph.Star else Glyph.StarOutline,
                    if (c.favorite) "Favori" else "Favori",
                    onToggleFavorite, Modifier.weight(1f),
                    tint = if (c.favorite) Mn.Legendary else Mn.Text,
                    accent = if (c.favorite) Mn.Legendary else Mn.Violet,
                    reduceMotion = reduceMotion,
                )
            }
            GlassAction(Glyph.Edit, "Modifier", onEdit, Modifier.weight(1f), reduceMotion = reduceMotion)
        }
        Spacer(Modifier.size(8.dp))
    }
}

/** Panneau « Informations » : tout ce qui est renseigné, dans un ordre lisible et fixe. */
@Composable
fun InfoSheet(
    c: Contact,
    company: Contact?,
    employees: List<Contact>,
    actions: ExternalActions,
    onOpen: (String) -> Unit,
    onDelete: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
    ) {
        Text("Informations", style = MaterialTheme.typography.titleSmall, color = Mn.Violet)
        Text(c.displayName, style = MaterialTheme.typography.titleLarge, color = Mn.Text, modifier = Modifier.semantics { heading() })
        if (c.kind == EntityKind.PERSON) {
            val names = listOf("Prénom" to c.firstName, "Nom" to c.lastName).filter { it.second.isNotBlank() }
            names.forEach { (label, v) -> SimpleRow(label, v) }
        }
        if (c.fallbackLabel.isNotBlank() && c.fallbackLabel != c.displayName) SimpleRow("Libellé", c.fallbackLabel)
        if (c.phone.isNotBlank()) ActionRow(Glyph.Phone, "Téléphone", c.phone) { actions.call(c.phone) }
        if (c.email.isNotBlank()) ActionRow(Glyph.Mail, "E-mail", c.email) { actions.email(c.email) }
        c.links.forEach { link -> ActionRow(Glyph.Link, "Lien", link) { actions.openLink(link) } }
        listOf(CardField.PROFESSION, CardField.COMPANY, CardField.SKILLS, CardField.CATEGORIES, CardField.LOCATION, CardField.NOTE)
            .forEach { f -> fieldValue(f, c, company)?.let { FieldBlock(f, it) } }
        if (company != null) {
            ActionRow(Glyph.Company, "Fiche entreprise", company.displayName) { onOpen(company.id) }
        }
        if (employees.isNotEmpty()) {
            Spacer(Modifier.size(8.dp))
            Text("PERSONNES RATTACHÉES", style = MaterialTheme.typography.titleSmall, color = Mn.TextFaint)
            employees.sortedBy { it.displayName.lowercase() }.forEach { e ->
                ActionRow(Glyph.Person, null, e.displayName) { onOpen(e.id) }
            }
        }
        if (c.kind != EntityKind.SELF) {
            Spacer(Modifier.size(20.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .clickable(role = Role.Button, onClick = onDelete)
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                GlyphIcon(Glyph.Trash, Mn.Danger, size = 20.dp)
                Spacer(Modifier.width(10.dp))
                Text("Supprimer cette fiche", color = Mn.Danger, style = MaterialTheme.typography.labelLarge)
            }
        }
        Spacer(Modifier.size(12.dp))
    }
}

@Composable
private fun SimpleRow(label: String, value: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(label.uppercase(), style = MaterialTheme.typography.titleSmall, color = Mn.TextFaint)
        Text(value, style = MaterialTheme.typography.bodyLarge, color = Mn.Text)
    }
}

@Composable
private fun ActionRow(glyph: Glyph, label: String?, value: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .clip(RoundedCornerShape(14.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = 6.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) { GlyphIcon(glyph, Mn.Violet, size = 20.dp) }
        Column(Modifier.padding(start = 8.dp).weight(1f)) {
            if (label != null) Text(label.uppercase(), style = MaterialTheme.typography.titleSmall, color = Mn.TextFaint)
            Text(value, style = MaterialTheme.typography.bodyLarge, color = Mn.Text, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}
