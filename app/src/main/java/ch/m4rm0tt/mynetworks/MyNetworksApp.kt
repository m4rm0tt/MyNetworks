package ch.m4rm0tt.mynetworks

import android.app.Application
import ch.m4rm0tt.mynetworks.data.NetworkRepository
import ch.m4rm0tt.mynetworks.data.PhotoStore
import ch.m4rm0tt.mynetworks.data.SettingsStore
import ch.m4rm0tt.mynetworks.data.db.AppDatabase
import ch.m4rm0tt.mynetworks.security.KeyManager
import ch.m4rm0tt.mynetworks.security.KeyUnavailableException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

sealed interface Storage {
    class Ready(val db: AppDatabase, val repo: NetworkRepository, val photos: PhotoStore) : Storage
    class Unavailable(val message: String) : Storage
}

class MyNetworksApp : Application() {
    lateinit var settings: SettingsStore
        private set
    val keys by lazy { KeyManager(this) }

    private val mutex = Mutex()
    private var storage: Storage? = null

    override fun onCreate() {
        super.onCreate()
        settings = SettingsStore(this)
    }

    /** Ouvre (une seule fois) la base chiffrée et le stockage des photos. N'efface jamais rien. */
    suspend fun storage(): Storage = mutex.withLock {
        storage?.let { return it }
        val result = withContext(Dispatchers.IO) {
            try {
                val dbExists = getDatabasePath(AppDatabase.NAME).exists()
                val photosDir = File(filesDir, "photos")
                val photosExist = photosDir.listFiles()?.isNotEmpty() == true
                val db = AppDatabase.open(this@MyNetworksApp, keys.databaseSecret(dbExists))
                val repo = NetworkRepository(db)
                repo.ensureSelf() // force l'ouverture : une mauvaise clé échoue ici
                Storage.Ready(db, repo, PhotoStore(this@MyNetworksApp, keys.photoSecret(photosExist)))
            } catch (e: KeyUnavailableException) {
                Storage.Unavailable(e.message ?: "Clé indisponible")
            } catch (e: Exception) {
                Storage.Unavailable("La base locale ne peut pas être ouverte (${e.javaClass.simpleName}). Aucune donnée n'a été effacée.")
            }
        }
        storage = result
        result
    }

    /** Sur décision explicite : met les données illisibles de côté et repart d'une base vide. */
    suspend fun resetAfterUnreadable() = mutex.withLock {
        withContext(Dispatchers.IO) { keys.setAsideUnreadableData() }
        storage = null
    }
}
