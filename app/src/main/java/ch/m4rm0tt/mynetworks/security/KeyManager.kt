package ch.m4rm0tt.mynetworks.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.io.IOException
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class KeyUnavailableException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Secrets locaux (phrase secrète SQLCipher, clé des photos) : 32 octets aléatoires chacun,
 * chiffrés (AES-256-GCM) par une clé maître non exportable d'Android Keystore, puis stockés
 * dans noBackupFilesDir. La clé maître n'exige pas d'authentification utilisateur, afin qu'un
 * changement d'empreinte ou de visage ne la détruise pas : le verrouillage biométrique est une
 * barrière d'interface, la protection au repos repose sur le chiffrement.
 *
 * Si la clé maître est perdue ou invalide, rien n'est effacé : une erreur est remontée.
 */
class KeyManager(private val context: Context) {
    private val dir: File get() = context.noBackupFilesDir
    private val random = SecureRandom()

    fun databaseSecret(dbExists: Boolean): ByteArray = secret("db.secret", mustExist = dbExists)
    fun photoSecret(photosExist: Boolean): ByteArray = secret("photos.secret", mustExist = photosExist)

    private fun secret(name: String, mustExist: Boolean): ByteArray {
        val file = File(dir, name)
        if (file.exists()) {
            return try {
                unwrap(file.readBytes())
            } catch (e: Exception) {
                throw KeyUnavailableException("La clé de chiffrement de cet appareil n'est plus utilisable.", e)
            }
        }
        if (mustExist) {
            throw KeyUnavailableException("Des données chiffrées existent mais leur clé est introuvable.")
        }
        val raw = ByteArray(32).also(random::nextBytes)
        val tmp = File(dir, "$name.tmp")
        tmp.writeBytes(wrap(raw))
        if (!tmp.renameTo(file)) throw IOException("Impossible d'enregistrer la clé")
        return raw
    }

    private fun masterKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setUserAuthenticationRequired(false)
                .build()
        )
        return gen.generateKey()
    }

    private fun wrap(raw: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, masterKey())
        val iv = c.iv
        val ct = c.doFinal(raw)
        return byteArrayOf(VERSION, iv.size.toByte()) + iv + ct
    }

    private fun unwrap(blob: ByteArray): ByteArray {
        require(blob.size > 2 && blob[0] == VERSION) { "format de clé inconnu" }
        val ivLen = blob[1].toInt()
        val iv = blob.copyOfRange(2, 2 + ivLen)
        val ct = blob.copyOfRange(2 + ivLen, blob.size)
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val key = ks.getKey(ALIAS, null) as? SecretKey ?: error("clé maître absente")
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        return c.doFinal(ct)
    }

    /** Met de côté (sans les supprimer) les fichiers illisibles, sur décision explicite de l'utilisateur. */
    fun setAsideUnreadableData(): File {
        val stamp = System.currentTimeMillis()
        val aside = File(context.filesDir, "illisible-$stamp").apply { mkdirs() }
        listOf(
            context.getDatabasePath(ch.m4rm0tt.mynetworks.data.db.AppDatabase.NAME),
            File(context.getDatabasePath(ch.m4rm0tt.mynetworks.data.db.AppDatabase.NAME).path + "-wal"),
            File(context.getDatabasePath(ch.m4rm0tt.mynetworks.data.db.AppDatabase.NAME).path + "-shm"),
            File(context.filesDir, "photos"),
            File(dir, "db.secret"),
            File(dir, "photos.secret"),
        ).filter { it.exists() }.forEach { it.renameTo(File(aside, it.name)) }
        return aside
    }

    companion object {
        private const val ALIAS = "mynetworks_master_v1"
        private const val VERSION: Byte = 1
    }
}
