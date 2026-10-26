package ch.m4rm0tt.mynetworks

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings as AndroidSettings
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import ch.m4rm0tt.mynetworks.domain.PhoneNumbers
import ch.m4rm0tt.mynetworks.domain.PhoneParse
import ch.m4rm0tt.mynetworks.ui.MainScreen
import ch.m4rm0tt.mynetworks.ui.MainViewModel
import ch.m4rm0tt.mynetworks.ui.components.GlyphIcon
import ch.m4rm0tt.mynetworks.ui.components.Glyph
import ch.m4rm0tt.mynetworks.ui.sheets.ExternalActions
import ch.m4rm0tt.mynetworks.ui.sheets.LockAvailability
import ch.m4rm0tt.mynetworks.ui.sheets.mnFieldColors
import ch.m4rm0tt.mynetworks.ui.theme.Mn
import ch.m4rm0tt.mynetworks.ui.theme.MyNetworksTheme

class MainActivity : FragmentActivity(), ExternalActions {
    private val vm: MainViewModel by viewModels()
    private val app get() = application as MyNetworksApp

    private var locked by mutableStateOf(false)
    private var lockMessage by mutableStateOf<String?>(null)
    private var promptShowing = false
    private var backgroundAt = 0L

    private var callRationaleFor by mutableStateOf<String?>(null)
    private var pendingCall: String? = null
    private var whatsappFix by mutableStateOf<String?>(null)

    private val callPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val number = pendingCall ?: return@registerForActivityResult
        pendingCall = null
        if (granted) placeCall(number) else openDialer(number)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        applyRecentsProtection()
        val s = app.settings.state.value
        locked = savedInstanceState?.getBoolean("locked") ?: (s.lockEnabled && lockAvailability().available)

        setContent {
            MyNetworksTheme {
                val reduce = systemReduceMotion()
                if (locked) {
                    LockScreen(lockMessage) { authenticate(forEnabling = false) }
                } else {
                    MainScreen(
                        vm = vm,
                        actions = this,
                        lock = lockAvailability(),
                        systemReduceMotion = reduce,
                        onEnableLock = { authenticate(forEnabling = true) },
                    )
                }
                callRationaleFor?.let { number -> CallRationale(number) }
                whatsappFix?.let { raw -> WhatsAppFix(raw) }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean("locked", locked)
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) backgroundAt = SystemClock.elapsedRealtime()
    }

    override fun onStart() {
        super.onStart()
        val s = app.settings.state.value
        if (s.lockEnabled && backgroundAt > 0L) {
            val elapsed = SystemClock.elapsedRealtime() - backgroundAt
            if (elapsed >= s.relockDelaySec * 1000L) locked = true
        }
        backgroundAt = 0L
        applyRecentsProtection()
    }

    override fun onResume() {
        super.onResume()
        if (locked && !promptShowing && lockAvailability().available) authenticate(forEnabling = false)
    }

    /** Masque l'aperçu dans les applications récentes. */
    private fun applyRecentsProtection() {
        if (Build.VERSION.SDK_INT >= 33) {
            setRecentsScreenshotEnabled(false)
        } else if (app.settings.state.value.lockEnabled) {
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    private fun systemReduceMotion(): Boolean =
        AndroidSettings.Global.getFloat(contentResolver, AndroidSettings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f

    // --- Verrouillage biométrique ---

    private val authenticators = BIOMETRIC_WEAK or DEVICE_CREDENTIAL

    private fun lockAvailability(): LockAvailability {
        val bm = BiometricManager.from(this)
        val strong = bm.canAuthenticate(BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS
        val weak = bm.canAuthenticate(BIOMETRIC_WEAK) == BiometricManager.BIOMETRIC_SUCCESS
        return when (bm.canAuthenticate(authenticators)) {
            BiometricManager.BIOMETRIC_SUCCESS -> LockAvailability(
                true,
                when {
                    strong && weak -> "Empreinte ou visage selon ce qu'Android propose, avec repli sur le code de l'appareil."
                    weak -> "Biométrie disponible (classe 2, ex. visage), avec repli sur le code de l'appareil."
                    else -> "Pas de biométrie enregistrée : le code de verrouillage de l'appareil sera demandé."
                },
            )
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED ->
                LockAvailability(false, "Aucun verrouillage d'écran ni biométrie n'est configuré sur ce téléphone.")
            BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE, BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE ->
                LockAvailability(false, "Le matériel biométrique n'est pas disponible pour le moment.")
            BiometricManager.BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED ->
                LockAvailability(false, "Android demande une mise à jour de sécurité avant d'utiliser la biométrie.")
            else -> LockAvailability(false, "Le verrouillage n'est pas disponible sur cet appareil.")
        }
    }

    private fun authenticate(forEnabling: Boolean) {
        val availability = lockAvailability()
        if (!availability.available) {
            if (forEnabling) {
                Toast.makeText(this, availability.description, Toast.LENGTH_LONG).show()
            } else {
                // Le verrouillage de l'appareil a été retiré : impossible de vérifier, on n'enferme pas l'utilisateur.
                lockMessage = availability.description + " Le verrouillage de My Networks est désactivé."
                app.settings.update { it.copy(lockEnabled = false) }
                locked = false
            }
            return
        }
        if (promptShowing) return
        promptShowing = true
        val prompt = BiometricPrompt(this, ContextCompat.getMainExecutor(this), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                promptShowing = false
                lockMessage = null
                if (forEnabling) {
                    app.settings.update { it.copy(lockEnabled = true) }
                    applyRecentsProtection()
                } else {
                    locked = false
                }
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                promptShowing = false
                if (!forEnabling) lockMessage = errString.toString()
            }
        })
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(if (forEnabling) "Activer le verrouillage" else "Déverrouiller My Networks")
            .setSubtitle("Biométrie ou code de l'appareil")
            .setAllowedAuthenticators(authenticators)
            .build()
        prompt.authenticate(info)
    }

    // --- Actions externes (toujours sur appui explicite) ---

    override fun call(phone: String) {
        val number = PhoneNumbers.dialable(phone)
        if (number.isEmpty()) return
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)) {
            openDialer(number); return
        }
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED
        val s = app.settings.state.value
        when {
            granted -> placeCall(number)
            !s.callPermissionAsked || shouldShowRequestPermissionRationale(Manifest.permission.CALL_PHONE) -> callRationaleFor = number
            // Refus définitif : on ne redemande pas en boucle, on passe par le composeur.
            else -> openDialer(number)
        }
    }

    private fun placeCall(number: String) {
        try {
            startActivity(Intent(Intent.ACTION_CALL, Uri.parse("tel:" + Uri.encode(number))))
        } catch (e: Exception) {
            openDialer(number)
        }
    }

    private fun openDialer(number: String) {
        try {
            startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(number))))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "Aucune application de téléphone n'est disponible.", Toast.LENGTH_LONG).show()
        }
    }

    @Composable
    private fun CallRationale(number: String) {
        AlertDialog(
            onDismissRequest = { callRationaleFor = null },
            title = { Text("Appel direct") },
            text = {
                Text(
                    "Pour lancer l'appel directement depuis la fiche, Android demande l'autorisation « Téléphone ». " +
                        "My Networks ne l'utilise que lorsque tu touches « Appeler ». Sans elle, le numéro s'ouvre dans ton composeur."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    callRationaleFor = null
                    app.settings.update { it.copy(callPermissionAsked = true) }
                    pendingCall = number
                    callPermission.launch(Manifest.permission.CALL_PHONE)
                }) { Text("Continuer", color = Mn.Violet) }
            },
            dismissButton = {
                TextButton(onClick = { callRationaleFor = null; openDialer(number) }) { Text("Utiliser le composeur", color = Mn.TextDim) }
            },
            containerColor = Mn.Surface2,
        )
    }

    override fun whatsapp(phone: String) {
        when (val p = PhoneNumbers.toInternational(phone, app.settings.state.value.defaultCountryCode)) {
            is PhoneParse.International -> openWhatsApp(p.digits)
            else -> whatsappFix = phone
        }
    }

    private fun whatsappPackage(): String? = listOf("com.whatsapp", "com.whatsapp.w4b").firstOrNull { pkg ->
        try {
            packageManager.getPackageInfo(pkg, 0); true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }

    private fun openWhatsApp(digits: String) {
        val pkg = whatsappPackage()
        if (pkg == null) {
            Toast.makeText(this, "WhatsApp n'est pas installé sur ce téléphone.", Toast.LENGTH_LONG).show()
            return
        }
        try {
            // Ouvre la conversation, sans envoyer de message.
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/$digits")).setPackage(pkg))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "WhatsApp n'a pas pu être ouvert.", Toast.LENGTH_LONG).show()
        }
    }

    @Composable
    private fun WhatsAppFix(raw: String) {
        var text by androidx.compose.runtime.remember(raw) { mutableStateOf(raw) }
        val parsed = PhoneNumbers.toInternational(text, null)
        AlertDialog(
            onDismissRequest = { whatsappFix = null },
            title = { Text("Numéro international ?") },
            text = {
                Column {
                    Text(
                        "WhatsApp a besoin du numéro au format international. Le pays de ce numéro ne peut pas être deviné : " +
                            "corrige-le (ex. +41 79 123 45 67), ou règle un indicatif par défaut dans les Réglages.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.size(10.dp))
                    OutlinedTextField(text, { text = it }, singleLine = true, colors = mnFieldColors())
                    if (parsed !is PhoneParse.International) {
                        Text("Commence par + suivi de l'indicatif du pays.", color = Mn.TextDim, style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = parsed is PhoneParse.International,
                    onClick = {
                        whatsappFix = null
                        (parsed as? PhoneParse.International)?.let { openWhatsApp(it.digits) }
                    },
                ) { Text("Ouvrir WhatsApp", color = Mn.Violet) }
            },
            dismissButton = { TextButton(onClick = { whatsappFix = null }) { Text("Annuler", color = Mn.TextDim) } },
            containerColor = Mn.Surface2,
        )
    }

    override fun email(address: String) {
        try {
            startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:" + Uri.encode(address.trim()))))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "Aucune application e-mail n'est disponible.", Toast.LENGTH_LONG).show()
        }
    }

    override fun openLink(url: String) {
        val u = url.trim().let { if (Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:").containsMatchIn(it)) it else "https://$it" }
        val uri = Uri.parse(u)
        if (uri.scheme !in setOf("http", "https", "mailto", "tel")) {
            Toast.makeText(this, "Ce lien ne peut pas être ouvert.", Toast.LENGTH_LONG).show()
            return
        }
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "Aucune application ne peut ouvrir ce lien.", Toast.LENGTH_LONG).show()
        }
    }
}

@Composable
private fun LockScreen(message: String?, onUnlock: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .background(Mn.Black)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        GlyphIcon(Glyph.Lock, Mn.Violet, size = 48.dp)
        Spacer(Modifier.size(16.dp))
        Text("My Networks est verrouillé", style = MaterialTheme.typography.titleLarge, color = Mn.Text, textAlign = TextAlign.Center)
        if (message != null) {
            Spacer(Modifier.size(8.dp))
            Text(message, color = Mn.TextDim, textAlign = TextAlign.Center)
        }
        Spacer(Modifier.size(24.dp))
        Button(
            onClick = onUnlock,
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
            shape = RoundedCornerShape(18.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Mn.Violet, contentColor = Mn.Black),
        ) { Text("Déverrouiller") }
    }
}
