package ch.m4rm0tt.mynetworks.backup

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

class BackupException(val reason: Reason, cause: Throwable? = null) : IOException(reason.message, cause) {
    enum class Reason(val message: String) {
        NOT_A_BACKUP("Ce fichier n'est pas une sauvegarde My Networks."),
        UNSUPPORTED_VERSION("Cette sauvegarde vient d'une version plus récente de l'application."),
        WRONG_PASSWORD("Mot de passe incorrect."),
        CORRUPTED("La sauvegarde est corrompue ou incomplète."),
    }
}

/**
 * Conteneur chiffré des sauvegardes, construit uniquement avec des primitives standard de la JCA :
 *
 * - dérivation du mot de passe : PBKDF2-HMAC-SHA256 (sel aléatoire de 16 octets, 600 000 itérations
 *   par défaut, paramètres stockés dans l'en-tête) → 64 octets = clé AES-256 + clé de vérification ;
 * - chiffrement authentifié : AES-256-GCM par segments de 64 Kio selon la construction « STREAM »
 *   (nonce = préfixe aléatoire ‖ compteur ‖ drapeau « dernier segment »), comme Tink ou age, pour
 *   détecter toute modification, réordonnancement ou troncature sans tout charger en mémoire ;
 * - l'en-tête complet est authentifié (données associées de chaque segment) ;
 * - un vérificateur HMAC de 16 octets distingue « mauvais mot de passe » de « fichier corrompu ».
 *
 * Format v1 : "MYNETBK1" | version u16 | kdf u8 | itérations u32 | sel[16] | préfixe[7] | vérif[16]
 *             puis segments : longueur u32 | chiffré (texte ‖ tag 16).
 */
object BackupCrypto {
    private val MAGIC = "MYNETBK1".toByteArray(Charsets.US_ASCII)
    const val FORMAT_VERSION = 1
    private const val KDF_PBKDF2_SHA256 = 1
    const val DEFAULT_ITERATIONS = 600_000
    private const val MIN_ITERATIONS = 100_000
    private const val MAX_ITERATIONS = 10_000_000
    private const val SALT_LEN = 16
    private const val PREFIX_LEN = 7
    private const val CHECK_LEN = 16
    const val SEGMENT_SIZE = 64 * 1024
    private const val TAG_BITS = 128
    private const val HEADER_LEN = 8 + 2 + 1 + 4 + SALT_LEN + PREFIX_LEN + CHECK_LEN
    private val CHECK_INFO = "mynetworks-backup-check-v1".toByteArray(Charsets.US_ASCII)

    private class Keys(val enc: SecretKeySpec, val check: ByteArray)

    private fun derive(password: CharArray, salt: ByteArray, iterations: Int): Keys {
        val spec = PBEKeySpec(password, salt, iterations, 512)
        try {
            val raw = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            val keys = Keys(SecretKeySpec(raw.copyOfRange(0, 32), "AES"), raw.copyOfRange(32, 64))
            raw.fill(0)
            return keys
        } finally {
            spec.clearPassword()
        }
    }

    private fun checkValue(checkKey: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(checkKey, "HmacSHA256"))
        return mac.doFinal(CHECK_INFO).copyOf(CHECK_LEN)
    }

    private fun nonce(prefix: ByteArray, counter: Int, last: Boolean): ByteArray =
        ByteBuffer.allocate(12).put(prefix).putInt(counter).put(if (last) 1 else 0).array()

    fun encrypt(
        out: OutputStream,
        password: CharArray,
        iterations: Int = DEFAULT_ITERATIONS,
        random: SecureRandom = SecureRandom(),
    ): OutputStream {
        val salt = ByteArray(SALT_LEN).also(random::nextBytes)
        val prefix = ByteArray(PREFIX_LEN).also(random::nextBytes)
        val keys = derive(password, salt, iterations)
        val header = ByteBuffer.allocate(HEADER_LEN)
            .put(MAGIC).putShort(FORMAT_VERSION.toShort()).put(KDF_PBKDF2_SHA256.toByte())
            .putInt(iterations).put(salt).put(prefix).put(checkValue(keys.check)).array()
        keys.check.fill(0)
        out.write(header)
        return EncryptingStream(out, keys.enc, prefix, header)
    }

    fun decrypt(input: InputStream, password: CharArray): InputStream {
        val din = DataInputStream(input)
        val header = ByteArray(HEADER_LEN)
        try {
            din.readFully(header)
        } catch (e: EOFException) {
            throw BackupException(BackupException.Reason.NOT_A_BACKUP, e)
        }
        val buf = ByteBuffer.wrap(header)
        val magic = ByteArray(MAGIC.size).also { buf.get(it) }
        if (!MessageDigest.isEqual(magic, MAGIC)) throw BackupException(BackupException.Reason.NOT_A_BACKUP)
        val version = buf.short.toInt()
        if (version != FORMAT_VERSION) throw BackupException(BackupException.Reason.UNSUPPORTED_VERSION)
        val kdf = buf.get().toInt()
        val iterations = buf.int
        if (kdf != KDF_PBKDF2_SHA256 || iterations !in MIN_ITERATIONS..MAX_ITERATIONS) {
            throw BackupException(BackupException.Reason.CORRUPTED)
        }
        val salt = ByteArray(SALT_LEN).also { buf.get(it) }
        val prefix = ByteArray(PREFIX_LEN).also { buf.get(it) }
        val check = ByteArray(CHECK_LEN).also { buf.get(it) }
        val keys = derive(password, salt, iterations)
        val ok = MessageDigest.isEqual(checkValue(keys.check), check)
        keys.check.fill(0)
        if (!ok) throw BackupException(BackupException.Reason.WRONG_PASSWORD)
        return DecryptingStream(din, keys.enc, prefix, header)
    }

    private class EncryptingStream(
        private val out: OutputStream,
        private val key: SecretKeySpec,
        private val prefix: ByteArray,
        private val aad: ByteArray,
    ) : OutputStream() {
        private val buffer = ByteArray(SEGMENT_SIZE)
        private var filled = 0
        private var counter = 0
        private var closed = false

        override fun write(b: Int) {
            write(byteArrayOf(b.toByte()), 0, 1)
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            check(!closed) { "flux fermé" }
            var o = off
            var remaining = len
            while (remaining > 0) {
                if (filled == SEGMENT_SIZE) emit(last = false)
                val n = minOf(remaining, SEGMENT_SIZE - filled)
                System.arraycopy(b, o, buffer, filled, n)
                filled += n; o += n; remaining -= n
            }
        }

        private fun emit(last: Boolean) {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, nonce(prefix, counter, last)))
            c.updateAAD(aad)
            val ct = c.doFinal(buffer, 0, filled)
            out.write(ByteBuffer.allocate(4).putInt(ct.size).array())
            out.write(ct)
            counter++
            filled = 0
        }

        override fun flush() = out.flush()

        override fun close() {
            if (closed) return
            emit(last = true)
            closed = true
            buffer.fill(0)
            out.flush()
            out.close()
        }
    }

    private class DecryptingStream(
        private val input: DataInputStream,
        private val key: SecretKeySpec,
        private val prefix: ByteArray,
        private val aad: ByteArray,
    ) : InputStream() {
        private var plain = ByteArray(0)
        private var pos = 0
        private var counter = 0
        private var finished = false
        private var nextLen: Int = readLen() ?: throw BackupException(BackupException.Reason.CORRUPTED)

        private fun readLen(): Int? {
            val b = ByteArray(4)
            var read = 0
            while (read < 4) {
                val n = input.read(b, read, 4 - read)
                if (n < 0) {
                    if (read == 0) return null
                    throw BackupException(BackupException.Reason.CORRUPTED)
                }
                read += n
            }
            val len = ByteBuffer.wrap(b).int
            if (len < 16 || len > SEGMENT_SIZE + 16) throw BackupException(BackupException.Reason.CORRUPTED)
            return len
        }

        private fun nextSegment(): Boolean {
            if (finished) return false
            val ct = ByteArray(nextLen)
            try {
                input.readFully(ct)
            } catch (e: EOFException) {
                throw BackupException(BackupException.Reason.CORRUPTED, e)
            }
            val following = readLen()
            val last = following == null
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, nonce(prefix, counter, last)))
            c.updateAAD(aad)
            plain = try {
                c.doFinal(ct)
            } catch (e: GeneralSecurityException) {
                throw BackupException(BackupException.Reason.CORRUPTED, e)
            }
            pos = 0
            counter++
            if (last) finished = true else nextLen = following!!
            return true
        }

        override fun read(): Int {
            val one = ByteArray(1)
            val n = read(one, 0, 1)
            return if (n <= 0) -1 else one[0].toInt() and 0xff
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            while (pos >= plain.size) {
                if (!nextSegment()) return -1
            }
            val n = minOf(len, plain.size - pos)
            System.arraycopy(plain, pos, b, off, n)
            pos += n
            return n
        }

        override fun close() = input.close()
    }

    /** Utilitaire de test : chiffre un tableau d'octets en mémoire. */
    internal fun encryptBytes(data: ByteArray, password: CharArray, iterations: Int): ByteArray {
        val bos = ByteArrayOutputStream()
        encrypt(bos, password, iterations).use { it.write(data) }
        return bos.toByteArray()
    }
}
