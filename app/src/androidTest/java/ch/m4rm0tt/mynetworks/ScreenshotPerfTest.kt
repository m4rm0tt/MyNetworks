package ch.m4rm0tt.mynetworks

import android.graphics.Bitmap
import android.os.SystemClock
import android.view.FrameMetrics
import android.view.Window
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import ch.m4rm0tt.mynetworks.data.db.EntityRow
import ch.m4rm0tt.mynetworks.data.db.EntityTagRow
import ch.m4rm0tt.mynetworks.data.db.TagRow
import ch.m4rm0tt.mynetworks.domain.EntityKind
import ch.m4rm0tt.mynetworks.domain.GroupCriterion
import ch.m4rm0tt.mynetworks.domain.TagType
import ch.m4rm0tt.mynetworks.domain.TextNorm
import ch.m4rm0tt.mynetworks.ui.MainViewModel
import ch.m4rm0tt.mynetworks.ui.Sheet
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Collections

/**
 * Captures réelles de l'application et mesures avec des données FICTIVES uniquement.
 * Les fichiers sont écrits dans Android/media/<paquet>/ci puis récupérés par la CI.
 * Les mesures sur émulateur (rendu logiciel) ne représentent pas la fluidité d'un Galaxy A36.
 */
@RunWith(AndroidJUnit4::class)
class ScreenshotPerfTest {
    private val app: MyNetworksApp = ApplicationProvider.getApplicationContext()
    @Suppress("DEPRECATION")
    private val outDir = File(app.externalMediaDirs.first(), "ci").apply { mkdirs() }
    private val firstNames = listOf("Léa", "Hugo", "Zoé", "Noah", "Inès", "Louis", "Chloé", "Gabriel", "Jade", "Arthur", "Emma", "Jules", "Mila", "Hélène", "Théo", "Nina")
    private val lastNames = listOf("Martin", "Bernard", "Dubois", "Moreau", "Lambert", "Fontaine", "Rochat", "Favre", "Müller", "Rey", "Bonvin", "Pittet")
    private val categories = listOf("Amis", "Musique", "Basket", "Événements", "Tech", "Famille fictive", "Clients test", "Sport")
    private val skills = listOf("Graphisme", "Vidéo", "Montage", "Photo", "Kotlin", "Django", "Mixage", "Coaching", "Compta", "Juridique", "Marketing", "Son", "Lumière", "Web", "3D", "Écriture", "Traduction", "Logistique", "Vente", "Droit")
    private val locations = listOf("Riviera", "Près de Lausanne", "Suisse romande", "Genève", "Valais", "Fribourg")

    private suspend fun ready(): Storage.Ready = app.storage() as Storage.Ready

    private suspend fun seed(n: Int) {
        val s = ready()
        val dao = s.db.dao()
        dao.deleteAllEntityTags(); dao.deleteAllLinks(); dao.deleteAllEntities(); dao.deleteAllTags()
        s.repo.ensureSelf()
        val now = System.currentTimeMillis()
        val tags = ArrayList<TagRow>()
        fun addTags(type: TagType, labels: List<String>) = labels.map { l ->
            TagRow("t-${type.code}-${TextNorm.tagKey(l)}", type.code, l, TextNorm.tagKey(l)).also { tags += it }
        }
        val cat = addTags(TagType.CATEGORY, categories)
        val sk = addTags(TagType.SKILL, skills)
        val loc = addTags(TagType.LOCATION, locations)
        val prof = addTags(TagType.PROFESSION, listOf("Designer", "Développeuse", "DJ", "Coach", "Photographe", "Avocate"))
        dao.upsertTags(tags)
        val rows = ArrayList<EntityRow>(n)
        val links = ArrayList<EntityTagRow>()
        val companies = (0 until maxOf(1, n / 50)).map { "co-$it" }
        companies.forEachIndexed { i, id ->
            rows += EntityRow(id = id, kind = EntityKind.COMPANY.code, companyName = "Studio fictif $i", createdAt = now + i, updatedAt = now)
            links += EntityTagRow(id, sk[i % sk.size].id, 0)
        }
        for (i in 0 until n - companies.size) {
            val id = "p-$i"
            rows += EntityRow(
                id = id, kind = EntityKind.PERSON.code,
                firstName = firstNames[i % firstNames.size], lastName = lastNames[(i / 3) % lastNames.size],
                phone = if (i % 4 == 0) "+41 79 000 ${(1000 + i % 9000)}" else "",
                companyId = if (i % 5 == 0) companies[i % companies.size] else null,
                favorite = i % 23 == 0, createdAt = now + 1000 + i, updatedAt = now,
            )
            links += EntityTagRow(id, cat[i % cat.size].id, 0)
            if (i % 2 == 0) links += EntityTagRow(id, cat[(i / 2 + 3) % cat.size].id, 1)
            links += EntityTagRow(id, sk[(i * 7) % sk.size].id, 0)
            links += EntityTagRow(id, loc[(i / 4) % loc.size].id, 0)
            links += EntityTagRow(id, prof[i % prof.size].id, 0)
        }
        rows.chunked(1000).forEach { dao.upsertEntities(it) }
        links.chunked(2000).forEach { dao.insertEntityTags(it) }
        s.repo.rebuildAllSearchText()
    }

    private fun shot(name: String) {
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        val bmp: Bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot() ?: return
        File(outDir, name).outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun pause(ms: Long) = SystemClock.sleep(ms)

    @Test
    fun capturesAndPerformance() {
        app.settings.update { it.copy(criterion = GroupCriterion.CATEGORY, idleMotion = true, lockEnabled = false, favoritesOnly = false, showCompanies = true, showPersons = true) }
        runBlocking { seed(0) }
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        pause(2500); shot("00-vide.png")

        runBlocking { seed(40) }
        scenario.onActivity { it.vm.camera.userMoved = false; it.vm.camera.initialized = false }
        pause(3500); shot("01-reseau-categories.png")

        val favId = "p-0"
        scenario.onActivity { it.vm.open(Sheet.Card(favId)) }
        pause(1500); shot("02-carte.png")
        scenario.onActivity { it.vm.open(Sheet.Info(favId)) }
        pause(1500); shot("03-informations.png")
        scenario.onActivity { it.vm.closeAll() }
        scenario.onActivity { it.vm.open(Sheet.Search); it.vm.setQuery("helene") }
        pause(1800); shot("04-recherche-accents.png")
        scenario.onActivity { it.vm.closeAll(); it.vm.startAdd(EntityKind.PERSON) }
        pause(1500); shot("05-ajout.png")
        scenario.onActivity { it.vm.closeAll(); it.vm.open(Sheet.Settings) }
        pause(1500); shot("06-reglages.png")
        scenario.onActivity { it.vm.closeAll() }
        app.settings.update { it.copy(criterion = GroupCriterion.SKILL) }
        pause(3500); shot("07-reseau-competences.png")
        app.settings.update { it.copy(criterion = GroupCriterion.COMPANY) }
        pause(3500); shot("08-reseau-entreprises.png")
        scenario.onActivity { it.vm.camera.scale = 1.4f }
        pause(1500); shot("09-zoom.png")

        // Mesures : 100, 1 000, 10 000 fiches fictives.
        app.settings.update { it.copy(criterion = GroupCriterion.CATEGORY, idleMotion = false) }
        val results = JSONArray()
        for (n in listOf(100, 1000, 10_000)) {
            val tSeed = SystemClock.elapsedRealtime()
            runBlocking { seed(n) }
            val seedMs = SystemClock.elapsedRealtime() - tSeed
            val contacts = runBlocking { ready().repo.contacts.first() }
            val t0 = SystemClock.elapsedRealtimeNanos()
            val layout = MainViewModel.buildLayout(contacts, app.settings.state.value, emptySet())
            val layoutMs = (SystemClock.elapsedRealtimeNanos() - t0) / 1e6
            val tq = SystemClock.elapsedRealtimeNanos()
            val hits = runBlocking { ready().repo.search("helene", false, 50, 0) }
            val searchMs = (SystemClock.elapsedRealtimeNanos() - tq) / 1e6

            scenario.onActivity { it.vm.camera.userMoved = false; it.vm.camera.initialized = false }
            pause(4000)
            shot("perf-$n-vue-ensemble.png")
            val frames = Collections.synchronizedList(ArrayList<Double>())
            val listener = Window.OnFrameMetricsAvailableListener { _, m, _ ->
                frames += m.getMetric(FrameMetrics.TOTAL_DURATION) / 1e6
            }
            val handler = android.os.Handler(android.os.Looper.getMainLooper())
            scenario.onActivity { it.window.addOnFrameMetricsAvailableListener(listener, handler) }
            // Déplacement + zoom programmés pendant ~3 s.
            for (step in 0 until 180) {
                scenario.onActivity { a ->
                    val c = a.vm.camera
                    c.offsetX += if (step < 90) 6f else -6f
                    c.scale = (c.scale * if (step % 60 < 30) 1.01f else 0.99f).coerceIn(0.03f, 4f)
                }
                pause(16)
            }
            pause(300)
            scenario.onActivity { it.window.removeOnFrameMetricsAvailableListener(listener) }
            shot("perf-$n-apres-geste.png")
            val sorted = synchronized(frames) { frames.sorted() }
            fun pct(p: Double) = if (sorted.isEmpty()) -1.0 else sorted[((sorted.size - 1) * p).toInt()]
            results.put(
                JSONObject()
                    .put("entites", n)
                    .put("noeuds", layout.nodes.size)
                    .put("liens", layout.edges.size)
                    .put("seed_ms", seedMs)
                    .put("layout_ms", layoutMs)
                    .put("recherche_ms", searchMs)
                    .put("recherche_resultats", hits.size)
                    .put("images_mesurees", sorted.size)
                    .put("image_p50_ms", pct(0.5))
                    .put("image_p90_ms", pct(0.9))
                    .put("image_p99_ms", pct(0.99))
                    .put("images_sup_16ms_pct", if (sorted.isEmpty()) -1.0 else 100.0 * sorted.count { it > 16.7 } / sorted.size)
            )
        }
        File(outDir, "perf.json").writeText(
            JSONObject()
                .put("appareil", "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} (émulateur CI, API ${android.os.Build.VERSION.SDK_INT})")
                .put("avertissement", "Émulateur sans GPU matériel : ces chiffres ne valent pas mesure sur Galaxy A36.")
                .put("mesures", results).toString(2)
        )
        runBlocking { seed(0) }
        scenario.close()
    }
}
