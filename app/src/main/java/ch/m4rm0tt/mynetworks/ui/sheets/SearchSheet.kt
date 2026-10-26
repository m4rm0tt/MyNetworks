package ch.m4rm0tt.mynetworks.ui.sheets

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ch.m4rm0tt.mynetworks.data.PhotoStore
import ch.m4rm0tt.mynetworks.domain.Contact
import ch.m4rm0tt.mynetworks.domain.EntityKind
import ch.m4rm0tt.mynetworks.domain.TagType
import ch.m4rm0tt.mynetworks.ui.components.Avatar
import ch.m4rm0tt.mynetworks.ui.components.Glyph
import ch.m4rm0tt.mynetworks.ui.components.GlyphIcon
import ch.m4rm0tt.mynetworks.ui.components.Pill
import ch.m4rm0tt.mynetworks.ui.theme.Mn

/** Une ligne de contexte : pourquoi cette fiche (métier, entreprise, emplacement). */
fun subtitleOf(c: Contact, byId: Map<String, Contact>): String {
    val parts = mutableListOf<String>()
    if (c.kind == EntityKind.COMPANY) parts += "Entreprise"
    c.tagsOf(TagType.PROFESSION).firstOrNull()?.let { parts += it.label }
    c.companyId?.let { byId[it]?.displayName }?.let { parts += it }
    c.tagsOf(TagType.LOCATION).firstOrNull()?.let { parts += it.label }
    if (parts.size < 2) c.tagsOf(TagType.SKILL).take(2).forEach { parts += it.label }
    return parts.joinToString(" · ")
}

@Composable
fun SearchSheet(
    query: String,
    favoritesOnly: Boolean,
    resultIds: List<String>?,
    byId: Map<String, Contact>,
    pageSize: Int,
    photos: PhotoStore?,
    onQuery: (String) -> Unit,
    onFavoritesOnly: (Boolean) -> Unit,
    onSelect: (String) -> Unit,
    onMore: () -> Unit,
) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
        OutlinedTextField(
            value = query,
            onValueChange = onQuery,
            placeholder = { Text("Nom, métier, compétence, lieu, téléphone…", color = Mn.TextFaint) },
            leadingIcon = { GlyphIcon(Glyph.Search, Mn.TextDim, size = 20.dp) },
            trailingIcon = if (query.isNotEmpty()) {
                { TextButton(onClick = { onQuery("") }) { Text("Effacer", color = Mn.TextDim) } }
            } else null,
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            colors = mnFieldColors(),
            shape = RoundedCornerShape(18.dp),
            modifier = Modifier.fillMaxWidth().focusRequester(focus),
        )
        Row(Modifier.padding(vertical = 10.dp)) {
            Pill("Favoris seulement", favoritesOnly, { onFavoritesOnly(!favoritesOnly) }, leading = Glyph.Star)
        }
        val ids = resultIds
        when {
            ids == null -> Text(
                "La recherche ignore les majuscules et les accents (« helene » trouve « Hélène »).",
                style = MaterialTheme.typography.bodyMedium, color = Mn.TextDim, modifier = Modifier.padding(vertical = 12.dp),
            )
            ids.isEmpty() -> Text(
                "Aucun résultat.", style = MaterialTheme.typography.bodyLarge, color = Mn.TextDim,
                modifier = Modifier.padding(vertical = 20.dp),
            )
            else -> LazyColumn(Modifier.fillMaxWidth().heightIn(max = 520.dp)) {
                items(ids, key = { it }) { id ->
                    val c = byId[id]
                    if (c != null) ResultRow(c, subtitleOf(c, byId), photos) { onSelect(id) }
                }
                if (ids.size >= pageSize) {
                    item {
                        TextButton(onClick = onMore, modifier = Modifier.fillMaxWidth()) { Text("Plus de résultats", color = Mn.Violet) }
                    }
                }
            }
        }
        Spacer(Modifier.size(4.dp))
    }
}

@Composable
private fun ResultRow(c: Contact, subtitle: String, photos: PhotoStore?, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 60.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = 6.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(c.photoId, c.initials, c.kind, photos, 44.dp, favorite = c.favorite)
        Column(Modifier.padding(start = 12.dp).weight(1f)) {
            Text(c.displayName, style = MaterialTheme.typography.titleMedium, color = Mn.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle.isNotEmpty()) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Mn.TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
