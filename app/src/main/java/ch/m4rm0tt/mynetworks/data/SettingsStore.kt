package ch.m4rm0tt.mynetworks.data

import android.content.Context
import ch.m4rm0tt.mynetworks.domain.CardField
import ch.m4rm0tt.mynetworks.domain.GroupCriterion
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/** Préférences non sensibles (aucune donnée de contact ici). */
data class Settings(
    val criterion: GroupCriterion = GroupCriterion.CATEGORY,
    val favoritesOnly: Boolean = false,
    val showPersons: Boolean = true,
    val showCompanies: Boolean = true,
    val personOrder: List<CardField> = CardField.defaultPersonOrder,
    val companyOrder: List<CardField> = CardField.defaultCompanyOrder,
    val lockEnabled: Boolean = false,
    /** Délai avant re-verrouillage après passage en arrière-plan, en secondes. */
    val relockDelaySec: Int = 0,
    val idleMotion: Boolean = true,
    /** Indicatif utilisé pour convertir un numéro local (« 079… ») ; vide = ne jamais deviner. */
    val defaultCountryCode: String = "",
    val callPermissionAsked: Boolean = false,
)

class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(load())
    val state: StateFlow<Settings> = _state.asStateFlow()

    fun update(transform: (Settings) -> Settings) {
        val next = transform(_state.value)
        _state.value = next
        save(next)
    }

    private fun load(): Settings {
        val d = Settings()
        fun order(key: String, def: List<CardField>): List<CardField> {
            val raw = prefs.getString(key, null) ?: return def
            val parsed = raw.split(',').mapNotNull { CardField.of(it) }.distinct()
            // Les champs ajoutés dans une version ultérieure s'ajoutent à la fin.
            return parsed + def.filter { it !in parsed }
        }
        return Settings(
            criterion = runCatching { GroupCriterion.valueOf(prefs.getString("criterion", d.criterion.name)!!) }.getOrDefault(d.criterion),
            favoritesOnly = prefs.getBoolean("favoritesOnly", d.favoritesOnly),
            showPersons = prefs.getBoolean("showPersons", d.showPersons),
            showCompanies = prefs.getBoolean("showCompanies", d.showCompanies),
            personOrder = order("personOrder", d.personOrder),
            companyOrder = order("companyOrder", d.companyOrder).filter { it !in CardField.notForCompany },
            lockEnabled = prefs.getBoolean("lockEnabled", d.lockEnabled),
            relockDelaySec = prefs.getInt("relockDelaySec", d.relockDelaySec),
            idleMotion = prefs.getBoolean("idleMotion", d.idleMotion),
            defaultCountryCode = prefs.getString("defaultCountryCode", d.defaultCountryCode) ?: "",
            callPermissionAsked = prefs.getBoolean("callPermissionAsked", d.callPermissionAsked),
        )
    }

    private fun save(s: Settings) {
        prefs.edit()
            .putString("criterion", s.criterion.name)
            .putBoolean("favoritesOnly", s.favoritesOnly)
            .putBoolean("showPersons", s.showPersons)
            .putBoolean("showCompanies", s.showCompanies)
            .putString("personOrder", s.personOrder.joinToString(",") { it.key })
            .putString("companyOrder", s.companyOrder.joinToString(",") { it.key })
            .putBoolean("lockEnabled", s.lockEnabled)
            .putInt("relockDelaySec", s.relockDelaySec)
            .putBoolean("idleMotion", s.idleMotion)
            .putString("defaultCountryCode", s.defaultCountryCode)
            .putBoolean("callPermissionAsked", s.callPermissionAsked)
            .apply()
    }

    /** Préférences incluses dans la sauvegarde chiffrée (hors état technique). */
    fun toJson(): JSONObject {
        val s = _state.value
        return JSONObject()
            .put("criterion", s.criterion.name)
            .put("favoritesOnly", s.favoritesOnly)
            .put("showPersons", s.showPersons)
            .put("showCompanies", s.showCompanies)
            .put("personOrder", s.personOrder.joinToString(",") { it.key })
            .put("companyOrder", s.companyOrder.joinToString(",") { it.key })
            .put("lockEnabled", s.lockEnabled)
            .put("relockDelaySec", s.relockDelaySec)
            .put("idleMotion", s.idleMotion)
            .put("defaultCountryCode", s.defaultCountryCode)
    }

    fun applyJson(o: JSONObject) {
        update { s ->
            fun order(key: String, def: List<CardField>): List<CardField> {
                val raw = o.optString(key, "")
                if (raw.isEmpty()) return def
                val parsed = raw.split(',').mapNotNull { CardField.of(it) }.distinct()
                return parsed + def.filter { it !in parsed }
            }
            s.copy(
                criterion = runCatching { GroupCriterion.valueOf(o.optString("criterion", s.criterion.name)) }.getOrDefault(s.criterion),
                favoritesOnly = o.optBoolean("favoritesOnly", s.favoritesOnly),
                showPersons = o.optBoolean("showPersons", s.showPersons),
                showCompanies = o.optBoolean("showCompanies", s.showCompanies),
                personOrder = order("personOrder", s.personOrder),
                companyOrder = order("companyOrder", s.companyOrder).filter { it !in CardField.notForCompany },
                // Le verrouillage n'est jamais activé par un import : il dépend de cet appareil.
                relockDelaySec = o.optInt("relockDelaySec", s.relockDelaySec),
                idleMotion = o.optBoolean("idleMotion", s.idleMotion),
                defaultCountryCode = o.optString("defaultCountryCode", s.defaultCountryCode),
            )
        }
    }
}
