package ch.m4rm0tt.mynetworks.ui.sheets

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import ch.m4rm0tt.mynetworks.backup.ImportMode
import ch.m4rm0tt.mynetworks.ui.components.Pill
import ch.m4rm0tt.mynetworks.ui.theme.Mn
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

const val MIN_BACKUP_PASSWORD = 10

@Composable
private fun PasswordField(value: String, onChange: (String) -> Unit, label: String) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        colors = mnFieldColors(),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    )
}

@Composable
fun BackupSheet(
    busy: Boolean,
    status: String?,
    onExport: (Uri, CharArray) -> Unit,
    onImport: (Uri, CharArray, ImportMode) -> Unit,
) {
    var pw by remember { mutableStateOf("") }
    var pw2 by remember { mutableStateOf("") }
    var importUri by remember { mutableStateOf<Uri?>(null) }
    var importPw by remember { mutableStateOf("") }
    var mode by remember { mutableStateOf(ImportMode.MERGE) }
    var confirmReplace by remember { mutableStateOf(false) }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null) {
            onExport(uri, pw.toCharArray())
            pw = ""; pw2 = ""
        }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> importUri = uri }

    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
    ) {
        Text("Sauvegarde", style = MaterialTheme.typography.titleLarge, color = Mn.Text, modifier = Modifier.semantics { heading() })
        Text(
            "Ton réseau ne quitte jamais le téléphone tout seul. Pour changer d'appareil, exporte un fichier chiffré " +
                "(AES-256-GCM, clé dérivée de ton mot de passe par PBKDF2) puis importe-le sur le nouveau téléphone. " +
                "Le fichier ne dépend pas de la clé matérielle de cet appareil : seul le mot de passe compte. " +
                "Sans lui, la sauvegarde est irrécupérable.",
            style = MaterialTheme.typography.bodySmall, color = Mn.TextDim,
        )
        if (busy) {
            Row(Modifier.padding(vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(color = Mn.Violet, modifier = Modifier.size(24.dp))
                Text("Chiffrement en cours…", color = Mn.Text, modifier = Modifier.padding(start = 12.dp))
            }
        }
        if (status != null) Text(status, color = Mn.Text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 10.dp))

        Text("EXPORTER", style = MaterialTheme.typography.titleSmall, color = Mn.Violet, modifier = Modifier.padding(top = 16.dp, bottom = 4.dp))
        PasswordField(pw, { pw = it }, "Mot de passe de la sauvegarde")
        PasswordField(pw2, { pw2 = it }, "Confirmer le mot de passe")
        val pwOk = pw.length >= MIN_BACKUP_PASSWORD && pw == pw2
        Text(
            when {
                pw.isEmpty() -> "Au moins $MIN_BACKUP_PASSWORD caractères. Une phrase de plusieurs mots est idéale. Il n'est stocké nulle part."
                pw.length < MIN_BACKUP_PASSWORD -> "Encore ${MIN_BACKUP_PASSWORD - pw.length} caractère(s)."
                pw != pw2 -> "Les deux mots de passe sont différents."
                else -> "Prêt. Choisis où enregistrer le fichier."
            },
            style = MaterialTheme.typography.bodySmall, color = if (pwOk) Mn.Violet else Mn.TextDim,
        )
        Button(
            onClick = {
                val day = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(Date())
                exportLauncher.launch("my-networks-$day.mnbk")
            },
            enabled = pwOk && !busy,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp).heightIn(min = 52.dp),
            shape = RoundedCornerShape(18.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Mn.Violet, contentColor = Mn.Black, disabledContainerColor = Mn.Surface3),
        ) { Text("Exporter…") }

        Text("IMPORTER", style = MaterialTheme.typography.titleSmall, color = Mn.Violet, modifier = Modifier.padding(top = 24.dp, bottom = 4.dp))
        TextButton(onClick = { importLauncher.launch(arrayOf("*/*")) }, enabled = !busy) {
            Text(if (importUri == null) "Choisir un fichier .mnbk" else "Fichier choisi ✓ (changer)", color = Mn.Violet)
        }
        if (importUri != null) {
            PasswordField(importPw, { importPw = it }, "Mot de passe de ce fichier")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 6.dp)) {
                Pill("Fusionner", mode == ImportMode.MERGE, { mode = ImportMode.MERGE })
                Pill("Tout remplacer", mode == ImportMode.REPLACE, { mode = ImportMode.REPLACE })
            }
            Text(
                if (mode == ImportMode.MERGE) "Ajoute les fiches du fichier. Si une fiche existe des deux côtés, la version la plus récente est gardée. Tes réglages ne changent pas."
                else "Remplace tout ton réseau actuel par le contenu du fichier (fiches, photos, valeurs, réglages). Irréversible.",
                style = MaterialTheme.typography.bodySmall, color = if (mode == ImportMode.REPLACE) Mn.Danger else Mn.TextDim,
            )
            Button(
                onClick = {
                    val uri = importUri ?: return@Button
                    if (mode == ImportMode.REPLACE) confirmReplace = true
                    else { onImport(uri, importPw.toCharArray(), mode); importPw = "" }
                },
                enabled = importPw.isNotEmpty() && !busy,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp).heightIn(min = 52.dp),
                shape = RoundedCornerShape(18.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Mn.Violet, contentColor = Mn.Black, disabledContainerColor = Mn.Surface3),
            ) { Text("Importer") }
        }
        Spacer(Modifier.size(16.dp))
    }

    if (confirmReplace) {
        AlertDialog(
            onDismissRequest = { confirmReplace = false },
            title = { Text("Tout remplacer ?") },
            text = { Text("Ton réseau actuel sera remplacé par la sauvegarde, une fois celle-ci entièrement vérifiée. Pense à exporter l'état actuel avant si tu as un doute.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmReplace = false
                    importUri?.let { onImport(it, importPw.toCharArray(), ImportMode.REPLACE) }
                    importPw = ""
                }) { Text("Remplacer", color = Mn.Danger) }
            },
            dismissButton = { TextButton(onClick = { confirmReplace = false }) { Text("Annuler", color = Mn.TextDim) } },
            containerColor = Mn.Surface2,
        )
    }
}
