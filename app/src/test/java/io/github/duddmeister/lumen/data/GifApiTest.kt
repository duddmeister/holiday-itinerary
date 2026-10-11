package io.github.duddmeister.lumen.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class GifApiTest {
    private val response = """
        {"results":[
          {"id":"123","title":"","content_description":"Cat Typing GIF",
           "itemurl":"https://giphy.com/gifs/cat-typing-123","url":"https://gph.is/abc",
           "media_formats":{
             "gif":{"url":"https://media.giphy.com/media/123/giphy.gif","dims":[498,374],"duration":1.2,"size":123456},
             "tinygif":{"url":"https://media.giphy.com/media/123/200w.gif","dims":[220,165],"duration":1.2,"size":23456}}},
          {"id":"456","title":"video only","media_formats":{"mp4":{"url":"https://media.giphy.com/z/a.mp4","dims":[10,10]}}},
          {"id":"789","content_description":"","title":"Dog","itemurl":"","url":"https://klipy.com/dog",
           "media_formats":{"gif":{"url":"https://static.klipy.com/y/dog.gif","dims":[300,300]}}},
          {"id":"999","media_formats":{"tinygif":{"url":"http://insecure.example/a.gif","dims":[10,10]}}},
          {"id":"555","itemurl":"https://klipy.com/gifs/555","media_formats":{
             "gif":{"url":"https://static.klipy.com/555/full.gif"},"tinygif":{"url":"https://static.klipy.com/555/tiny.gif"}}}
        ],"next":"CAgQAA"}
    """.trimIndent()

    @Test
    fun readsGifsWithPreviewsAndLinks() {
        val page = GifApi.parse(response)
        assertEquals(listOf("123", "789", "555"), page.gifs.map { it.id })
        val cat = page.gifs[0]
        assertEquals("Cat Typing GIF", cat.description)
        assertEquals("https://media.giphy.com/media/123/200w.gif", cat.previewUrl)
        assertEquals(220, cat.previewWidth)
        assertEquals(165, cat.previewHeight)
        assertEquals("https://media.giphy.com/media/123/giphy.gif", cat.sendUrl)
        assertEquals("https://giphy.com/gifs/cat-typing-123", cat.pageUrl)
        // Without a small preview the full GIF stands in, and the title and short URL fill gaps.
        val dog = page.gifs[1]
        assertEquals("https://static.klipy.com/y/dog.gif", dog.previewUrl)
        assertEquals("Dog", dog.description)
        assertEquals("https://klipy.com/dog", dog.pageUrl)
        // A GIF of unknown size still shows, as a square tile.
        val square = page.gifs[2]
        assertEquals(square.previewWidth, square.previewHeight)
        assertTrue(square.previewWidth > 0)
        assertEquals("CAgQAA", page.next)
    }

    @Test
    fun endsWhenThereIsNoNextPage() {
        assertNull(GifApi.parse("""{"results":[],"next":""}""").next)
        assertTrue(GifApi.parse("""{"next":"0"}""").gifs.isEmpty())
    }

    @Test
    fun buildsRequestsForEachService() {
        val url = GifApi(GifService.GIPHY, "my key", Locale.UK).url("search", "funny cat", "CAgQAA")
        assertTrue(url, url.startsWith("https://api.giphy.com/v2/search?"))
        assertTrue(url, url.contains("key=my+key"))
        assertTrue(url, url.contains("q=funny+cat"))
        assertTrue(url, url.contains("pos=CAgQAA"))
        assertTrue(url, url.contains("locale=en_GB"))
        assertTrue(url, url.contains("country=GB"))
        assertTrue(url, url.contains("media_filter=tinygif,gif"))
        assertTrue(url, url.contains("limit=30"))
        val trending = GifApi(GifService.KLIPY, "k", Locale.UK).url("featured", null, null)
        assertTrue(trending, trending.startsWith("https://api.klipy.com/v2/featured?"))
        assertTrue(trending, !trending.contains("q=") && !trending.contains("pos="))
    }

    @Test
    fun knowsEachService() {
        for (service in GifService.entries) {
            assertEquals(service, GifService.of(service.id))
            assertTrue(service.keyPage, service.keyPage.startsWith("https://"))
        }
        assertNull(GifService.of("tenor"))
    }
}
