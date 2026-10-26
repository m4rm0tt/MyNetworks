package ch.m4rm0tt.mynetworks.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.math.max
import kotlin.math.min

/**
 * Photos copiées dans l'espace privé de l'application, recadrées, réduites et chiffrées
 * (AES-256-GCM, clé protégée par Keystore). Aucune image n'est chargée depuis le réseau.
 */
class PhotoStore(context: Context, secret: ByteArray) {
    private val dir = File(context.filesDir, "photos").apply { mkdirs() }
    private val key = SecretKeySpec(secret, "AES")
    private val random = SecureRandom()
    private val resolver = context.contentResolver

    private val thumbCache = object : LruCache<String, ImageBitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ImageBitmap): Int = value.width * value.height * 4
    }

    private fun fullFile(id: String) = File(dir, "$id.p")
    private fun thumbFile(id: String) = File(dir, "$id.t")

    suspend fun importFromUri(uri: Uri): String = withContext(Dispatchers.IO) {
        val bitmap = decodeScaled(uri, FULL_SIZE)
        val full = squareCrop(bitmap, FULL_SIZE)
        val thumb = Bitmap.createScaledBitmap(full, THUMB_SIZE, THUMB_SIZE, true)
        val id = UUID.randomUUID().toString()
        write(fullFile(id), jpeg(full, 86))
        write(thumbFile(id), jpeg(thumb, 82))
        if (full !== bitmap) bitmap.recycle()
        id
    }

    fun cachedThumb(id: String): ImageBitmap? = thumbCache.get(id)

    suspend fun thumb(id: String): ImageBitmap? = withContext(Dispatchers.IO) {
        thumbCache.get(id) ?: read(thumbFile(id))?.let { bytes ->
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()?.also { thumbCache.put(id, it) }
        }
    }

    suspend fun full(id: String): ImageBitmap? = withContext(Dispatchers.IO) {
        read(fullFile(id))?.let { bytes -> BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap() }
    }

    fun delete(id: String) {
        thumbCache.remove(id)
        fullFile(id).delete()
        thumbFile(id).delete()
    }

    fun allIds(): List<String> = dir.listFiles()?.mapNotNull { f -> f.name.takeIf { it.endsWith(".p") }?.removeSuffix(".p") } ?: emptyList()

    /** Pour l'export : octets JPEG déchiffrés (photo et miniature). */
    fun exportBytes(id: String): Pair<ByteArray, ByteArray>? {
        val f = read(fullFile(id)) ?: return null
        val t = read(thumbFile(id)) ?: return null
        return f to t
    }

    // --- Import : zone de transit chiffrée, validée avant d'être intégrée ---
    private val staging = File(dir.parentFile, "photos-import")
    private val safeId = Regex("^[A-Za-z0-9-]{1,64}$")

    fun isSafeId(id: String) = safeId.matches(id)

    /** Rechiffre des octets JPEG importés avec la clé de cet appareil, en zone de transit. */
    fun stage(id: String, thumb: Boolean, bytes: ByteArray): Boolean {
        if (!isSafeId(id)) return false
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        if (opts.outWidth <= 0 || opts.outHeight <= 0) return false
        staging.mkdirs()
        write(File(staging, if (thumb) "$id.t" else "$id.p"), bytes)
        return true
    }

    /** Intègre les photos en transit pour les fiches effectivement importées. */
    fun commitStaged(ids: Set<String>) {
        for (id in ids) {
            if (!isSafeId(id)) continue
            val p = File(staging, "$id.p"); val t = File(staging, "$id.t")
            if (p.exists() && t.exists()) {
                p.renameTo(fullFile(id)); t.renameTo(thumbFile(id))
                thumbCache.remove(id)
            }
        }
        clearStaging()
    }

    fun clearStaging() {
        staging.listFiles()?.forEach { it.delete() }
        staging.delete()
    }

    /** Supprime les fichiers photo qui ne sont plus référencés par aucune fiche. */
    fun deleteUnreferenced(referenced: Set<String>) {
        for (id in allIds()) if (id !in referenced) delete(id)
    }

    private fun write(file: File, plain: ByteArray) {
        val iv = ByteArray(12).also(random::nextBytes)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
        val ct = c.doFinal(plain)
        val tmp = File(file.path + ".tmp")
        tmp.writeBytes(byteArrayOf(VERSION) + iv + ct)
        tmp.renameTo(file)
    }

    private fun read(file: File): ByteArray? {
        if (!file.exists()) return null
        return try {
            val blob = file.readBytes()
            if (blob.size < 29 || blob[0] != VERSION) return null
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, blob, 1, 12))
            c.doFinal(blob, 13, blob.size - 13)
        } catch (e: Exception) {
            null
        }
    }

    private fun decodeScaled(uri: Uri, target: Int): Bitmap {
        if (Build.VERSION.SDK_INT >= 28) {
            val src = ImageDecoder.createSource(resolver, uri)
            return ImageDecoder.decodeBitmap(src) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val w = info.size.width
                val h = info.size.height
                val scale = target.toFloat() / min(w, h)
                if (scale < 1f) decoder.setTargetSize(max(1, (w * scale).toInt()), max(1, (h * scale).toInt()))
            }
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (min(bounds.outWidth, bounds.outHeight) / (sample * 2) >= target) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            ?: error("Image illisible")
    }

    private fun squareCrop(src: Bitmap, size: Int): Bitmap {
        val side = min(src.width, src.height)
        val x = (src.width - side) / 2
        val y = (src.height - side) / 2
        val cropped = Bitmap.createBitmap(src, x, y, side, side)
        return if (side > size) Bitmap.createScaledBitmap(cropped, size, size, true) else cropped
    }

    private fun jpeg(b: Bitmap, quality: Int): ByteArray =
        ByteArrayOutputStream().use { out -> b.compress(Bitmap.CompressFormat.JPEG, quality, out); out.toByteArray() }

    companion object {
        private const val VERSION: Byte = 1
        const val FULL_SIZE = 640
        const val THUMB_SIZE = 160
    }
}
