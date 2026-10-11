package io.github.duddmeister.lumen.data

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import io.github.duddmeister.lumen.BuildConfig
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.util.Locale
import java.util.concurrent.Executors

/** One GIF: a small looping preview for the grid, the file to send and its page online. */
class Gif(
    val id: String,
    val description: String,
    val previewUrl: String,
    val previewWidth: Int,
    val previewHeight: Int,
    val sendUrl: String,
    val pageUrl: String,
)

/** A page of results; [next] fetches the following page, null at the end. */
class GifPage(val gifs: List<Gif>, val next: String?)

/**
 * Where GIFs come from. Both services speak the v2 API that Tenor introduced
 * (Google shut Tenor's own API down in June 2026), so one client serves either.
 */
enum class GifService(val id: String, val title: String, val host: String, val keyPage: String, val builtInKey: String) {
    GIPHY("giphy", "GIPHY", "api.giphy.com", "https://developers.giphy.com/dashboard/", BuildConfig.GIPHY_API_KEY),
    KLIPY("klipy", "KLIPY", "api.klipy.com", "https://partner.klipy.com/", BuildConfig.KLIPY_API_KEY);

    companion object {
        fun of(id: String?): GifService? = entries.firstOrNull { it.id == id }

        /** GIPHY, unless this copy of Lumen was built with a key for KLIPY only. */
        val default: GifService
            get() = if (GIPHY.builtInKey.isEmpty() && KLIPY.builtInKey.isNotEmpty()) KLIPY else GIPHY
    }
}

/** A [GifService]'s search API. Calls block: run them off the main thread. */
class GifApi(
    private val service: GifService,
    private val apiKey: String,
    private val locale: Locale = Locale.getDefault(),
) {

    /** Trending GIFs. */
    fun featured(pos: String? = null): GifPage = get(url("featured", null, pos))

    fun search(query: String, pos: String? = null): GifPage = get(url("search", query, pos))

    internal fun url(endpoint: String, query: String?, pos: String?): String = buildString {
        append("https://").append(service.host).append("/v2/").append(endpoint)
        append("?key=").append(encode(apiKey))
        append("&client_key=lumen")
        append("&media_filter=tinygif,gif")
        append("&contentfilter=medium")
        append("&limit=").append(PAGE_SIZE)
        append("&locale=").append(encode(locale.language + if (locale.country.isEmpty()) "" else "_" + locale.country))
        if (locale.country.isNotEmpty()) append("&country=").append(encode(locale.country))
        if (query != null) append("&q=").append(encode(query))
        if (pos != null) append("&pos=").append(encode(pos))
    }

    private fun get(url: String): GifPage = parse(String(Http.get(url, MAX_RESPONSE_BYTES), Charsets.UTF_8))

    private fun encode(s: String): String = URLEncoder.encode(s, "UTF-8")

    companion object {
        const val PAGE_SIZE = 30
        private const val MAX_RESPONSE_BYTES = 2 shl 20

        /** Reads a search or featured response, skipping results without a usable preview. */
        fun parse(json: String): GifPage {
            val root = JSONObject(json)
            val gifs = ArrayList<Gif>()
            val results = root.optJSONArray("results")
            if (results != null) {
                for (i in 0 until results.length()) {
                    val r = results.optJSONObject(i) ?: continue
                    val formats = r.optJSONObject("media_formats") ?: continue
                    val preview = formats.optJSONObject("tinygif") ?: formats.optJSONObject("gif") ?: continue
                    val send = formats.optJSONObject("gif") ?: preview
                    val previewUrl = preview.optString("url")
                    val sendUrl = send.optString("url")
                    if (!previewUrl.startsWith("https://") || !sendUrl.startsWith("https://")) continue
                    val dims = preview.optJSONArray("dims") ?: send.optJSONArray("dims")
                    var w = dims?.optInt(0) ?: 0
                    var h = dims?.optInt(1) ?: 0
                    if (w <= 0 || h <= 0) {
                        // A GIF of unknown size gets a square tile.
                        w = 1
                        h = 1
                    }
                    val page = r.optString("itemurl").ifEmpty { r.optString("url") }.ifEmpty { sendUrl }
                    val description = r.optString("content_description").ifEmpty { r.optString("title") }
                    gifs.add(Gif(r.optString("id"), description, previewUrl, w, h, sendUrl, page))
                }
            }
            val next = root.optString("next").takeIf { it.isNotEmpty() && it != "0" }
            return GifPage(gifs, next)
        }
    }
}

/** The server answered with an error status (401/403 usually mean a bad API key). */
class HttpStatusException(val code: Int) : IOException("HTTP $code")

internal object Http {
    /** Downloads [url] (blocking), refusing bodies larger than [maxBytes]. */
    fun get(url: String, maxBytes: Int): ByteArray {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 8_000
        c.readTimeout = 12_000
        c.setRequestProperty("User-Agent", "Lumen/" + BuildConfig.VERSION_NAME)
        try {
            val code = c.responseCode
            if (code !in 200..299) throw HttpStatusException(code)
            c.inputStream.use { input ->
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(16 * 1024)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    out.write(buffer, 0, n)
                    if (out.size() > maxBytes) throw IOException("Response too large")
                }
                return out.toByteArray()
            }
        } finally {
            c.disconnect()
        }
    }
}

/** Downloads and decodes GIF previews, keeping recent ones in memory. Callbacks run on the main thread. */
class GifImages {
    private val main = Handler(Looper.getMainLooper())
    private val workers = Executors.newFixedThreadPool(3) { r -> Thread(r, "lumen-gif").apply { isDaemon = true } }
    private val cache = LruCache<String, Drawable>(MAX_CACHED)
    private val loading = HashSet<String>()
    private val failed = HashSet<String>()

    /** The preview at [url] if it has loaded; otherwise starts loading it and calls [ready] once it arrives. */
    fun get(url: String, ready: () -> Unit): Drawable? {
        cache.get(url)?.let { return it }
        if (url in failed || !loading.add(url)) return null
        workers.execute {
            val drawable = try {
                decode(Http.get(url, MAX_PREVIEW_BYTES))
            } catch (e: IOException) {
                null
            } catch (e: RuntimeException) {
                null
            }
            main.post {
                loading.remove(url)
                if (drawable == null) {
                    failed.add(url)
                } else {
                    cache.put(url, drawable)
                    ready()
                }
            }
        }
        return null
    }

    fun shutdown() = workers.shutdownNow()

    private fun decode(bytes: ByteArray): Drawable? = if (Build.VERSION.SDK_INT >= 28) {
        ImageDecoder.decodeDrawable(ImageDecoder.createSource(ByteBuffer.wrap(bytes)))
    } else {
        // Before Android 9 there is no animated decoder: show the first frame.
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.let { BitmapDrawable(null, it) }
    }

    companion object {
        private const val MAX_CACHED = 90
        private const val MAX_PREVIEW_BYTES = 3 shl 20

        /** Starts or stops an animated preview; still images ignore it. */
        fun animate(d: Drawable, run: Boolean) {
            if (Build.VERSION.SDK_INT >= 28 && d is AnimatedImageDrawable) {
                if (run && !d.isRunning) d.start() else if (!run && d.isRunning) d.stop()
            }
        }
    }
}

/** GIFs saved for sending; [GifProvider] serves them to the app they are sent to. */
object GifFiles {
    private const val KEEP = 20
    private const val MAX_SEND_BYTES = 12 shl 20

    fun dir(context: Context) = File(context.cacheDir, "gifs")

    fun authority(context: Context) = context.packageName + ".gifs"

    /** Downloads [gif] (blocking) and returns the content URI to send. */
    fun download(context: Context, gif: Gif): Uri {
        val dir = dir(context).apply { mkdirs() }
        val name = gif.id.filter { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' }.ifEmpty { Integer.toHexString(gif.sendUrl.hashCode()) } + ".gif"
        val file = File(dir, name)
        if (!file.isFile || file.length() == 0L) {
            val part = File(dir, "$name.part")
            part.writeBytes(Http.get(gif.sendUrl, MAX_SEND_BYTES))
            if (!part.renameTo(file)) throw IOException("Could not save the GIF")
        }
        file.setLastModified(System.currentTimeMillis())
        dir.listFiles()?.filter { it.name.endsWith(".gif") }?.sortedByDescending { it.lastModified() }?.drop(KEEP)?.forEach { it.delete() }
        return Uri.Builder().scheme("content").authority(authority(context)).appendPath(name).build()
    }
}
