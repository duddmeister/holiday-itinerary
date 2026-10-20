package io.github.duddmeister.lumen.settings

import io.github.duddmeister.lumen.engine.UserHistory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.SequenceInputStream
import java.io.StringWriter
import java.lang.reflect.Modifier

class BackupTest {

    private fun backup(settings: Map<String, Any>, words: UserHistory = UserHistory()): String =
        StringWriter().also { Backup.write(it, settings, words) }.toString()

    private fun read(text: String) = Backup.read(ByteArrayInputStream(text.toByteArray()))

    private fun history(vararg typed: String) = UserHistory().apply {
        var previous: String? = null
        for ((i, w) in typed.withIndex()) {
            record(w, previous, 1_000L + i)
            previous = w
        }
    }

    @Test fun settingsAndWordsComeBack() {
        val settings = mapOf(
            Prefs.THEME to "midnight",
            Prefs.HEIGHT to 115,
            Prefs.FLOAT_X to 0.25f,
            Prefs.DELETE_SWIPE to false,
            Prefs.SPELLING to "uk",
        )
        val words = history("Zorblax", "rocks", "Zorblax", "rocks")
        val back = read(backup(settings, words))
        assertEquals(settings, back.settings)
        assertEquals(2, back.words.count("zorblax"))
        assertEquals("Zorblax", back.words.entry("zorblax")!!.display)
        assertEquals(listOf("rocks" to 2), back.words.nextWords("zorblax", 3))
        assertTrue("a copy for a backup is not a save", words.dirty)
    }

    @Test fun keysAndCopiedTextStayOut() {
        val text = backup(
            mapOf(
                Prefs.THEME to "paper",
                Prefs.GIPHY_KEY to "my-secret-key",
                Prefs.ANNOUNCED_VERSION to 57,
                "clipboard_pinned" to "[\"a password\"]",
                "something_new" to true,
            ),
        )
        assertTrue(text.contains("paper"))
        for (left in listOf("my-secret-key", "57", "a password", "something_new")) assertFalse(left, text.contains(left))
    }

    @Test fun onlyWhatLumenKeepsComesBackAndInItsOwnType() {
        val text = listOf(
            "# Lumen backup v1",
            "setting\ttheme\tstring\tsakura",
            "setting\tgiphy_api_key\tstring\tsomeone-elses-key",
            "setting\thaptics\tstring\tyes",
            "setting\tkeyboard_height\tint\tlots",
            "setting\tfloat_y\tfloat\tNaN",
            "setting\tsound\tbool\ttrue",
            "setting\tfrom_a_newer_version\tbool\ttrue",
            // Glide typing, from an older version that had it.
            "setting\tglide_typing\tbool\ttrue",
            "setting\tbroken",
        ).joinToString("\n")
        assertEquals(mapOf(Prefs.THEME to "sakura", Prefs.SOUND to true), read(text).settings)
    }

    @Test fun oddCharactersSurvive() {
        val awkward = "tab\there\nnew line \\ back\\slash \\t not a tab"
        assertEquals(awkward, read(backup(mapOf(Prefs.THEME to awkward))).settings[Prefs.THEME])
    }

    @Test fun otherFilesAreNotBackups() {
        for (text in listOf("", "hello", "w\tword\t3\t1000", "{\"lumen\": 1}")) {
            try {
                read(text)
                throw AssertionError("read \"$text\" as a backup")
            } catch (e: Backup.NotABackup) {
                // As it should.
            }
        }
        // A newer version's backup is read for what this one understands; a byte order mark is fine.
        assertEquals(mapOf(Prefs.SOUND to true), read("\uFEFF# Lumen backup v2\nsetting\tsound\tbool\ttrue\n").settings)
    }

    @Test fun aHugeFileIsGivenUpOn() {
        val endless = object : InputStream() {
            override fun read() = 'a'.code
        }
        try {
            Backup.read(SequenceInputStream("# Lumen backup v1\n".byteInputStream(), endless))
            throw AssertionError("read forever")
        } catch (e: Backup.NotABackup) {
            // Stopped well short of running out of memory.
        }
    }

    /** A setting added later must be sorted into the backup or deliberately left out. */
    @Test fun everySettingIsAccountedFor() {
        val keys = Prefs::class.java.declaredFields
            .filter { Modifier.isStatic(it.modifiers) && it.type == String::class.java }
            .map { it.isAccessible = true; it.get(null) as String }
            .filter { it != Prefs.FILE }
        assertTrue(keys.size > 30)
        for (key in keys) assertTrue("$key is neither backed up nor left out", key in Backup.SETTINGS || key in Backup.LEFT_OUT)
        assertTrue(Backup.SETTINGS.keys.none { it in Backup.LEFT_OUT })
    }

    @Test fun restoringAddsWordsWithoutCountingThemTwice() {
        val here = history("hello", "there", "hello")
        val backup = history("hello", "Zorblax", "Zorblax", "Zorblax")
        here.merge(backup)
        assertEquals("the higher count, not the sum", 2, here.count("hello"))
        assertEquals(3, here.count("zorblax"))
        assertEquals(1, here.count("there"))
        assertEquals(listOf("Zorblax" to 1), here.nextWords("hello", 3).filter { it.first == "Zorblax" })
        // The same backup again changes nothing.
        here.merge(backup)
        assertEquals(2, here.count("hello"))
        assertEquals(3, here.count("zorblax"))
        assertTrue(here.dirty)
    }

    @Test fun theLaterSpellingOfAWordWins() {
        val here = UserHistory().apply { record("iphone", null, 1_000L) }
        here.merge(UserHistory().apply { record("iPhone", null, 2_000L) })
        assertEquals("iPhone", here.entry("iphone")!!.display)
        here.merge(UserHistory().apply { record("IPHONE", null, 500L) })
        assertEquals("iPhone", here.entry("iphone")!!.display)
    }
}
