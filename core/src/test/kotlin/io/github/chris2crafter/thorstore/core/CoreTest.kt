package io.github.chris2crafter.thorstore.core

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CoreTest {

    private fun fixture(name: String) = javaClass.getResource("/fixtures/$name")!!.readText()

    private fun app(apkRegex: String? = null, exclude: String? = null) =
        CatalogApp("x", "X", Categories.EMULATORS, "", repo = "a/b", apkRegex = apkRegex, excludeRegex = exclude)

    @Test
    fun parsesAssetsWithShaAndSize() {
        val assets = GithubHtml.assets(fixture("azahar_assets.html"))
        val vanilla = assets.first { it.name == "azahar-android-vanilla-2126.2.apk" }
        assertEquals("b511d1d38012c5ab0dfdb4479335059bb89ef17acfbf0ba45e79107ed4185ba3", vanilla.sha256)
        assertEquals("47.9 MB", vanilla.sizeText)
        assertEquals("https://github.com/azahar-emu/azahar/releases/download/2126.2/azahar-android-vanilla-2126.2.apk", vanilla.url)
        assertNotNull(vanilla.date)
        // source code archives are not release assets
        assertTrue(assets.none { "/archive/" in it.url })
    }

    @Test
    fun picksTheRightApkForTheThor() {
        val azahar = GithubHtml.assets(fixture("azahar_assets.html"))
        assertEquals("azahar-android-vanilla-2126.2.apk", ApkPicker.apks(azahar, app()).first().name)

        val armsx2 = GithubHtml.assets(fixture("armsx2_assets.html"))
        assertEquals("ARMSX2-2.9-a13-armv8.2-sdk33.apk", ApkPicker.apks(armsx2, app("a13")).single().name)
        assertTrue(ApkPicker.apks(armsx2, app()).last().name.contains("legacy"))

        val obtainium = GithubHtml.assets(fixture("obtainium_assets.html"))
        assertEquals("app-arm64-v8a-release.apk", ApkPicker.apks(obtainium, app("^app-arm64-v8a-release\\.apk$")).single().name)
        assertEquals("arm64", Regex("arm64").find(ApkPicker.apks(obtainium, app()).first().name)?.value)

        val gamenative = GithubHtml.assets(fixture("gamenative_assets.html"))
        assertEquals("gamenative-v1.3.0.apk", ApkPicker.apks(gamenative, app(exclude = "xr")).single().name)
    }

    @Test
    fun readsTagsAndNotes() {
        assertEquals("v1.2.3", GithubHtml.tagFromLocation("https://github.com/a/b/releases/tag/v1.2.3"))
        assertNull(GithubHtml.tagFromLocation("https://github.com/a/b/releases"))
        assertEquals("partyboard_0.2.0", GithubHtml.releaseTags(fixture("partyboard_releases.html"), "mariopartyrd/partyboard").first())
        val notes = GithubHtml.notes(fixture("mjolnir_tag.html"))!!
        assertTrue(notes.startsWith("Mjolnir v0.2.7a"), notes)
        assertTrue("• Fixed obscure gesture combinations." in notes, notes)
    }

    @Test
    fun listsOlderReleasesWithDates() {
        val list = GithubHtml.releaseList(fixture("k11_releases.html"), "K11MCH1/AdrenoToolsDrivers")
        assertEquals(10, list.size)
        val r7 = list.first { it.tag == "v26.0.0-rc07" }
        assertEquals("v26.0.0 - Revision 7", r7.title)
        assertEquals("2025-12-26T01:13:15Z", r7.date)
        assertFalse(r7.prerelease)
    }

    @Test
    fun comparesVersions() {
        assertEquals(listOf(5L, 0L, 1L), Versions.numbers("v5.0.1-android.3"))
        assertEquals(true, Versions.isNewer("v9.3.1-android.2", "9.3.0"))
        assertEquals(false, Versions.isNewer("v9.3.1", "9.3.1"))
        assertNull(Versions.isNewer("2026-04-27", "1.0"))
        assertNull(Versions.isNewer("weekly_2024w38", "alpha16"))
    }

    @Test
    fun decidesUpdates() {
        val remote = ApkMeta("a.b", 42, "1.2", 0)
        assertTrue(UpdateLogic.hasUpdate("a.b", 41, "1.1", remote, null, null, "v1.2"))
        assertFalse(UpdateLogic.hasUpdate("a.b", 42, "1.2", remote, "x", "y", "v1.3"))
        // no readable APK: fall back to the hash of the last store install
        assertTrue(UpdateLogic.hasUpdate("a.b", 1, "nightly", null, "aaa", "bbb", "continuous"))
        assertFalse(UpdateLogic.hasUpdate("a.b", 1, "nightly", null, null, null, "continuous"))
    }

    @Test
    fun jsonRoundTrip() {
        val apps = listOf(
            CatalogApp("soh", "Ship of Harkinian", Categories.RECOMPS, "Ocarina \"of\" Time\nZeile 2", repo = "linkzenic/Shipwright-Android", tags = listOf("needs-rom")),
            CatalogApp("ra", "RetroArch", Categories.EMULATORS, "", homepage = "https://www.retroarch.com"),
        )
        val back = Catalog.parse(Catalog.write(apps, "2026-10-27"))
        assertEquals(apps, back)
    }

    @Test
    fun englishTextsRoundTripAndShowInEnglishMode() {
        val app = CatalogApp(
            "soh", "Ship of Harkinian", Categories.RECOMPS, "Nativer Port", descriptionEn = "Native port",
            repo = "a/b", note = "Braucht dein ROM.", noteEn = "Needs your ROM.",
        )
        val back = Catalog.parse(Catalog.write(listOf(app, CatalogApp("x", "X", Categories.TOOLS, "Nur Deutsch"))))
        assertEquals(app, back[0])
        val before = I18n.english
        try {
            I18n.english = false
            assertEquals("Nativer Port", back[0].shownDescription)
            assertEquals("Braucht dein ROM.", back[0].shownNote)
            I18n.english = true
            assertEquals("Native port", back[0].shownDescription)
            assertEquals("Needs your ROM.", back[0].shownNote)
            // no English text: German stays
            assertEquals("Nur Deutsch", back[1].shownDescription)
            assertNull(back[1].shownNote)
            // the user's own description wins in both languages; keeping the English text is no edit
            val u = UserData()
            u.edit(app, app.name, "Native port", app.category)
            assertTrue(u.edits.isEmpty())
            u.edit(app, app.name, "Mein Text", app.category)
            assertEquals("Mein Text", u.apply(listOf(app)).single().shownDescription)
        } finally {
            I18n.english = before
        }
    }

    @Test
    fun readsSingleEntriesOutOfAZip() {
        val bytes = ByteArrayOutputStream().also { bos ->
            ZipOutputStream(bos).use { z ->
                z.putNextEntry(ZipEntry("stored.txt").apply {
                    method = ZipEntry.STORED
                    val data = "hallo".toByteArray()
                    size = data.size.toLong(); compressedSize = size
                    crc = java.util.zip.CRC32().apply { update(data) }.value
                })
                z.write("hallo".toByteArray())
                z.putNextEntry(ZipEntry("res/deflated.txt"))
                z.write("Thor ".repeat(2000).toByteArray())
                z.closeEntry()
            }
        }.toByteArray()
        val zip = RemoteZip(ByteArrayRangeSource(bytes))
        assertEquals(setOf("stored.txt", "res/deflated.txt"), zip.entries.keys)
        assertEquals("hallo", String(zip.read("stored.txt")!!))
        assertEquals("Thor ".repeat(2000), String(zip.read("res/deflated.txt")!!))
        assertNull(zip.read("fehlt"))
    }

    @Test
    fun recognisesGithubLinks() {
        assertEquals("azahar-emu/azahar", Links.repo("https://github.com/azahar-emu/azahar"))
        assertEquals("azahar-emu/azahar", Links.repo("Schau mal: https://github.com/azahar-emu/azahar/releases/tag/2123.1 cool"))
        assertEquals("Ethiquema/Lichii-Android", Links.repo("github.com/Ethiquema/Lichii-Android.git"))
        assertEquals("K11MCH1/AdrenoToolsDrivers", Links.repo("K11MCH1/AdrenoToolsDrivers"))
        assertNull(Links.repo("https://github.com/topics/emulator"))
        assertNull(Links.repo("einfach nur text"))
    }

    @Test
    fun readsRepoAbout() {
        val html = """<meta name="description" content="Get Android app updates straight from the source. Contribute to ImranR98/Obtainium development by creating an account on GitHub.">
            <script>{"sidebarAbout":{"description":"Get Android app updates \u0026 more.","formattedDescription":"x","website":"https://obtainium.imranr.dev","topics":[{"name":"android"},{"name":"apk-updater"}],"showInsights":true}}</script>"""
        val info = GithubHtml.repoInfo(html, "ImranR98/Obtainium")
        assertEquals("Get Android app updates & more.", info.description)
        assertEquals("https://obtainium.imranr.dev", info.website)
        assertEquals(listOf("android", "apk-updater"), info.topics)
        val metaOnly = GithubHtml.repoInfo("""<meta name="description" content="An open-source 3DS emulator. - azahar-emu/azahar">""", "azahar-emu/azahar")
        assertEquals("An open-source 3DS emulator.", metaOnly.description)
        assertTrue(metaOnly.topics.isEmpty())
    }

    @Test
    fun guessesCategories() {
        val cats = Categories.builtIn
        fun g(name: String, repo: String, d: String?, t: List<String> = emptyList(), c: List<Category> = cats) = Classifier.guess(name, repo, d, t, c)
        assertEquals(Categories.EMULATORS, g("Azahar", "azahar-emu/azahar", "An open-source 3DS emulator project based on Citra."))
        assertEquals(Categories.RECOMPS, g("Zelda64Recomp", "Zelda64Recomp/Zelda64Recomp", "Static recompilation of Majora's Mask for PC"))
        assertEquals(Categories.FRONTENDS, g("Mjolnir", "blacksheepmvp/mjolnir", "Home launcher for dual screen handhelds"))
        assertEquals(Categories.PC, g("Winlator", "brunodev85/winlator", "Run Windows applications with Wine and Box64"))
        assertEquals(Categories.TOOLS, g("Something", "a/b", null))
        val own = cats + Category("c-shooter", "Shooter")
        assertEquals("c-shooter", g("Doom Port X", "a/doom", "A shooter for Android", emptyList(), own.filter { it.id != Categories.PORTS }))
        assertEquals("Adreno Tools Drivers", AppAdder.prettyName("AdrenoToolsDrivers"))
        assertEquals("Lichii android", AppAdder.prettyName("lichii-android"))
    }

    @Test
    fun userLayerOverCatalog() {
        val base = listOf(
            CatalogApp("az", "Azahar", Categories.EMULATORS, "3DS", repo = "azahar-emu/azahar"),
            CatalogApp("k11", "K11", Categories.DRIVERS, "Treiber", repo = "K11MCH1/AdrenoToolsDrivers"),
        )
        val u = UserData()
        u.edit(base[0], "Azahar 3DS", "3DS", Categories.EMULATORS)
        val mine = u.addCategory("Meine Favoriten")
        u.addApp(CatalogApp("u-x", "X", mine.id, "", repo = "a/x"))
        u.moveCategory(mine.id, -100)
        u.moveCategory(mine.id, -1)
        assertEquals(mine.id, u.categories().first().id)
        u.deleteCategory(Categories.DRIVERS, mine.id)

        val back = UserData.parse(u.toJson())
        val apps = back.apply(base).associateBy { it.id }
        assertEquals("Azahar 3DS", apps["az"]!!.name)
        assertEquals(mine.id, apps["k11"]!!.category)
        // moving a driver source to another tab keeps it a driver
        assertTrue(apps["k11"]!!.isDriver)
        assertTrue(apps["u-x"]!!.user)
        assertFalse(apps["u-x"]!!.isDriver)
        assertTrue(back.categories().none { it.id == Categories.DRIVERS })
        assertEquals(1, back.edits.size)
        // an APK app in the driver tab stays an APK app after saving
        val moved = CatalogApp("u-y", "Y", Categories.DRIVERS, "", repo = "a/y", driver = false, user = true)
        assertFalse(CatalogApp.fromMap(moved.toMap()).isDriver)
        // the catalog later adds a repository the user already added: only the own entry stays
        val withDup = base + CatalogApp("ax", "X im Katalog", Categories.TOOLS, "", repo = "A/X")
        assertEquals(listOf("u-x"), back.apply(withDup).filter { it.repo.equals("a/x", true) }.map { it.id })
    }

}
