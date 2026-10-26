package ch.m4rm0tt.mynetworks

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import ch.m4rm0tt.mynetworks.backup.BackupCrypto
import ch.m4rm0tt.mynetworks.backup.BackupException
import ch.m4rm0tt.mynetworks.backup.BackupManager
import ch.m4rm0tt.mynetworks.backup.ImportMode
import ch.m4rm0tt.mynetworks.data.NetworkRepository
import ch.m4rm0tt.mynetworks.data.PhotoStore
import ch.m4rm0tt.mynetworks.data.SaveRequest
import ch.m4rm0tt.mynetworks.data.SettingsStore
import ch.m4rm0tt.mynetworks.data.db.AppDatabase
import ch.m4rm0tt.mynetworks.domain.Contact
import ch.m4rm0tt.mynetworks.domain.EntityKind
import ch.m4rm0tt.mynetworks.domain.TagType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.SecureRandom

/** Persistance chiffrée, recherche, valeurs communes et sauvegarde, avec des données fictives. */
@RunWith(AndroidJUnit4::class)
class StorageInstrumentedTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val names = mutableListOf<String>()
    private val secret = ByteArray(32).also { SecureRandom().nextBytes(it) }

    private fun open(name: String, key: ByteArray = secret): AppDatabase {
        names += name
        return AppDatabase.open(ctx, key, name)
    }

    @Before fun clean() { names.clear() }

    @After
    fun tearDown() {
        names.forEach { ctx.deleteDatabase(it) }
    }

    private fun person(first: String, last: String = "", phone: String = "", fav: Boolean = false) =
        Contact(id = "", kind = EntityKind.PERSON, firstName = first, lastName = last, phone = phone, favorite = fav)

    @Test
    fun persistsAcrossReopenAndIsEncryptedAtRest() = runBlocking {
        val db = open("t-persist.db")
        val repo = NetworkRepository(db)
        repo.ensureSelf()
        val a = repo.save(SaveRequest(person("Hélène", "Dupré", "+41 79 000 00 01"), mapOf(TagType.SKILL to listOf("Graphisme"))))
        val b = repo.save(SaveRequest(person("Hélène", "Dupré"), emptyMap()))
        assertTrue("homonymes conservés séparément", a != b)
        db.close()

        val again = open("t-persist.db")
        val contacts = NetworkRepository(again).contacts.first()
        assertEquals(3, contacts.size) // moi + 2 homonymes
        assertEquals("Graphisme", contacts.first { it.id == a }.tagsOf(TagType.SKILL).single().label)
        again.close()

        // Le fichier ne contient pas le nom en clair.
        val bytes = ctx.getDatabasePath("t-persist.db").readBytes()
        assertFalse(String(bytes, Charsets.ISO_8859_1).contains("Dupr"))
        // Une mauvaise clé ne permet pas l'ouverture, et rien n'est effacé.
        val wrong = ByteArray(32).also { SecureRandom().nextBytes(it) }
        try {
            val bad = AppDatabase.open(ctx, wrong, "t-persist.db")
            bad.openHelper.writableDatabase
            fail("ouverture avec une mauvaise clé")
        } catch (e: Exception) { /* attendu */ }
        assertTrue(ctx.getDatabasePath("t-persist.db").length() > 0)
    }

    @Test
    fun searchIgnoresCaseAndAccentsAndUpdatesAfterEdit() = runBlocking {
        val db = open("t-search.db")
        val repo = NetworkRepository(db)
        val id = repo.save(SaveRequest(person("Hélène", "Müller"), mapOf(TagType.LOCATION to listOf("Riviera"))))
        repo.save(SaveRequest(person("Paul"), emptyMap()))
        assertEquals(listOf(id), repo.search("HELENE", false, 50, 0))
        assertEquals(listOf(id), repo.search("muller", false, 50, 0))
        assertEquals(listOf(id), repo.search("rivi", false, 50, 0))
        val c = NetworkRepository(db).contacts.first().first { it.id == id }
        repo.save(SaveRequest(c, mapOf(TagType.LOCATION to listOf("Vevey"))))
        assertTrue(repo.search("riviera", false, 50, 0).isEmpty())
        assertEquals(listOf(id), repo.search("vevey", false, 50, 0))
        assertTrue(repo.search("zzz", false, 50, 0).isEmpty())
    }

    @Test
    fun tagsAreReusedRenamedAndMerged() = runBlocking {
        val db = open("t-tags.db")
        val repo = NetworkRepository(db)
        repo.save(SaveRequest(person("A"), mapOf(TagType.CATEGORY to listOf("Musique"))))
        repo.save(SaveRequest(person("B"), mapOf(TagType.CATEGORY to listOf("  musique "))))
        repo.save(SaveRequest(person("C"), mapOf(TagType.CATEGORY to listOf("Son"))))
        var tags = db.dao().allTags()
        assertEquals(2, tags.size)
        val son = tags.first { it.label == "Son" }
        repo.renameTag(son.id, "MUSIQUE")
        tags = db.dao().allTags()
        assertEquals(1, tags.size) // réunies sur demande explicite
        assertEquals(3, db.dao().allEntityTags().size)
    }

    @Test
    fun companyIsLinkedNotDuplicatedAndDeletionKeepsPeople() = runBlocking {
        val db = open("t-company.db")
        val repo = NetworkRepository(db)
        val p1 = repo.save(SaveRequest(person("Léa"), emptyMap(), companyText = "Atelier Nord"))
        val p2 = repo.save(SaveRequest(person("Marc"), emptyMap(), companyText = "atelier  nord"))
        val all = db.dao().allEntities()
        val companies = all.filter { it.kind == EntityKind.COMPANY.code }
        assertEquals(1, companies.size)
        assertEquals(companies[0].id, all.first { it.id == p1 }.companyId)
        assertEquals(companies[0].id, all.first { it.id == p2 }.companyId)
        repo.delete(companies[0].id)
        assertNull(db.dao().entity(p1)!!.companyId)
        assertNotNull(db.dao().entity(p2))
    }

    private fun jpegUri(): Uri {
        val f = File(ctx.cacheDir, "test-photo.jpg")
        val bmp = Bitmap.createBitmap(300, 200, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.MAGENTA) }
        f.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        return Uri.fromFile(f)
    }

    @Test
    fun backupRoundTripWithPhotosWrongPasswordAndCorruption() = runBlocking {
        val photosKey = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val photos = PhotoStore(ctx, photosKey)
        val settings = SettingsStore(ctx)
        val src = open("t-bk-src.db")
        val srcRepo = NetworkRepository(src)
        srcRepo.ensureSelf()
        val photoId = photos.importFromUri(jpegUri())
        val co = srcRepo.save(SaveRequest(Contact(id = "", kind = EntityKind.COMPANY, companyName = "Studio Lac"), emptyMap()))
        val id = srcRepo.save(
            SaveRequest(
                person("Zoé", "Martin", "+41 79 000 00 02", fav = true).copy(photoId = photoId, companyId = co, links = listOf("example.org/zoe")),
                mapOf(TagType.SKILL to listOf("Vidéo", "Montage"), TagType.CATEGORY to listOf("Amis")),
            )
        )
        val file = File(ctx.cacheDir, "t.mnbk")
        val pw = "phrase de test assez longue"
        BackupManager(src, srcRepo, photos, settings, ctx.contentResolver).export(Uri.fromFile(file), pw.toCharArray())
        assertFalse(String(file.readBytes(), Charsets.ISO_8859_1).contains("Zo"))

        // Import complet dans une base vide.
        val dst = open("t-bk-dst.db")
        val dstRepo = NetworkRepository(dst)
        val mgr = BackupManager(dst, dstRepo, photos, settings, ctx.contentResolver)
        val summary = mgr.importFrom(Uri.fromFile(file), pw.toCharArray(), ImportMode.REPLACE)
        assertEquals(2, summary.imported)
        val z = dstRepo.contacts.first().first { it.id == id }
        assertTrue(z.favorite)
        assertEquals(co, z.companyId)
        assertEquals(setOf("Vidéo", "Montage"), z.tagsOf(TagType.SKILL).map { it.label }.toSet())
        assertNotNull(photos.thumb(photoId))
        assertEquals(listOf(id), dstRepo.search("zoe", false, 10, 0))

        // Mauvais mot de passe : rien ne change.
        val before = dst.dao().allEntities().size
        try {
            mgr.importFrom(Uri.fromFile(file), "mauvais mot de passe".toCharArray(), ImportMode.REPLACE); fail()
        } catch (e: BackupException) {
            assertEquals(BackupException.Reason.WRONG_PASSWORD, e.reason)
        }
        assertEquals(before, dst.dao().allEntities().size)

        // Fichier corrompu : rien ne change.
        val bad = File(ctx.cacheDir, "bad.mnbk")
        val bytes = file.readBytes(); bytes[bytes.size / 2] = (bytes[bytes.size / 2].toInt() xor 0x10).toByte()
        bad.writeBytes(bytes)
        try {
            mgr.importFrom(Uri.fromFile(bad), pw.toCharArray(), ImportMode.REPLACE); fail()
        } catch (e: BackupException) {
            assertEquals(BackupException.Reason.CORRUPTED, e.reason)
        }
        assertEquals(before, dst.dao().allEntities().size)

        // Fusion : une version locale plus récente est gardée.
        dstRepo.save(SaveRequest(z.copy(note = "modifiée ici"), emptyMap()))
        val merge = mgr.importFrom(Uri.fromFile(file), pw.toCharArray(), ImportMode.MERGE)
        assertTrue(merge.keptNewerLocal >= 1)
        assertEquals("modifiée ici", dst.dao().entity(id)!!.note)
        assertTrue(BackupCrypto.SEGMENT_SIZE > 0)
    }
}
