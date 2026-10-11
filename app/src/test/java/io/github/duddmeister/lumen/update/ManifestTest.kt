package io.github.duddmeister.lumen.update

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/** Permissions Android checks only on a phone, which tests on the JVM would never notice missing. */
class ManifestTest {
    private val declared: Set<String> by lazy {
        val manifest = listOfNotNull(
            System.getProperty("lumen.manifest")?.let(::File),
            File("src/main/AndroidManifest.xml"),
            File("app/src/main/AndroidManifest.xml"),
        ).firstOrNull { it.isFile }
            ?: error("Cannot find the manifest; run tests from the project or app directory")
        val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder().parse(manifest)
        val uses = doc.getElementsByTagName("uses-permission")
        (0 until uses.length).map { uses.item(it).attributes.getNamedItemNS(ANDROID, "name").nodeValue }.toSet()
    }

    /**
     * The daily update check is a job that waits for a connection. For an app targeting
     * Android 14 or later, Android refuses to schedule one (with an exception) unless both
     * of these are declared. Missing the second stopped the app and keyboard opening in
     * v1.0.54 to v1.0.60.
     */
    @Test fun theUpdateCheckMayWaitForANetwork() {
        for (p in listOf("android.permission.INTERNET", "android.permission.ACCESS_NETWORK_STATE")) {
            assertTrue("$p is declared", p in declared)
        }
    }

    /** A daily check that survives restarts needs this, or Android refuses to schedule it. */
    @Test fun theUpdateCheckSurvivesRestarts() {
        assertTrue("android.permission.RECEIVE_BOOT_COMPLETED" in declared)
    }

    private companion object {
        const val ANDROID = "http://schemas.android.com/apk/res/android"
    }
}
