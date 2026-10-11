package io.github.duddmeister.lumen.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleasesTest {
    // Trimmed from GitHub's answer for a release made by CI with generated notes.
    private val latest = """
        {"html_url":"https://github.com/duddmeister/holiday-itinerary/releases/tag/v1.0.45",
         "tag_name":"v1.0.45","name":"Lumen 1.0.45","draft":false,"prerelease":false,
         "assets":[
           {"name":"notes.txt","size":12,"browser_download_url":"https://github.com/duddmeister/holiday-itinerary/releases/download/v1.0.45/notes.txt"},
           {"name":"lumen-1.0.45.apk","size":1234567,"content_type":"application/vnd.android.package-archive",
            "browser_download_url":"https://github.com/duddmeister/holiday-itinerary/releases/download/v1.0.45/lumen-1.0.45.apk"}],
         "body":"## What's Changed\r\n* Update Lumen from inside the app by @duddmeister in https://github.com/duddmeister/holiday-itinerary/pull/3\r\n* Fix the see-through keyboard by @duddmeister in https://github.com/duddmeister/holiday-itinerary/pull/4\r\n\r\n\r\n**Full Changelog**: https://github.com/duddmeister/holiday-itinerary/compare/v1.0.41...v1.0.45"}
    """.trimIndent()

    @Test
    fun readsTheVersionAndItsApk() {
        val release = Releases.parse(latest)
        assertNotNull(release)
        release!!
        assertEquals(45, release.versionCode)
        assertEquals("1.0.45", release.versionName)
        assertEquals("https://github.com/duddmeister/holiday-itinerary/releases/download/v1.0.45/lumen-1.0.45.apk", release.apkUrl)
        assertEquals(1234567L, release.apkSize)
        assertEquals(listOf("Update Lumen from inside the app", "Fix the see-through keyboard"), release.changes)
    }

    @Test
    fun skipsReleasesItCannotInstall() {
        assertNull("no APK", Releases.parse("""{"tag_name":"v1.0.9","assets":[]}"""))
        assertNull("no version code", Releases.parse("""{"tag_name":"latest","assets":[{"name":"a.apk","browser_download_url":"https://x/a.apk"}]}"""))
        assertNull("not https", Releases.parse("""{"tag_name":"v1.0.9","assets":[{"name":"a.apk","browser_download_url":"http://x/a.apk"}]}"""))
    }

    @Test
    fun keepsChangesShort() {
        val notes = (1..6).joinToString("\n") { "* Change $it by @someone in https://example.com/pull/$it" }
        assertEquals(listOf("Change 1", "Change 2", "Change 3"), Releases.changes(notes))
        assertTrue(Releases.changes("**Full Changelog**: https://example.com").isEmpty())
    }
}
