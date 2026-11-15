package com.testconnection.confidence_agent

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.platform.app.InstrumentationRegistry
import com.testconnection.confidence_agent.data.repository.CloudCommunityStore
import com.testconnection.confidence_agent.data.repository.CommunityImageCache
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Test

class CommunityImageCacheInstrumentedTest {
    private val context = object : ContextWrapper(InstrumentationRegistry.getInstrumentation().targetContext) {
        override fun getApplicationContext(): Context = this
        override fun getCacheDir() = File(super.getCacheDir(), "image-cache-acceptance").apply { mkdirs() }
        override fun getFilesDir() = File(super.getFilesDir(), "image-cache-acceptance").apply { mkdirs() }
        override fun getSharedPreferences(name: String, mode: Int) = super.getSharedPreferences("image-cache-acceptance-$name", mode)
    }
    private fun photo(color: Int = Color.GREEN): ByteArray {
        val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
        return ByteArrayOutputStream().use { output ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, output); bitmap.recycle(); output.toByteArray()
        }
    }
    @Before fun reset() { CommunityImageCache.clearAll(context) }
    @After fun cleanup() { CommunityImageCache.clearAll(context) }

    @Test fun repeatedReadAndRecreatedCacheDoNotDownloadAgain() = runBlocking {
        val calls = AtomicInteger(); val id = UUID.randomUUID().toString(); val bytes = photo()
        val cache = CommunityImageCache(context)
        suspend fun fetch(): ByteArray { calls.incrementAndGet(); return bytes }
        assertEquals(Color.GREEN, cache.load("server|reader", id, ::fetch).getPixel(0, 0))
        cache.load("server|reader", id, ::fetch)
        CommunityImageCache.trimMemory() // A new screen/process can still read the saved file.
        CommunityImageCache(context).load("server|reader", id, ::fetch)
        assertEquals(1, calls.get())
        assertEquals(bytes.size.toLong(), cache.diskBytes())
    }

    @Test fun simultaneousViewsShareOneDownload() = runBlocking {
        val cache = CommunityImageCache(context); val calls = AtomicInteger(); val id = UUID.randomUUID().toString(); val bytes = photo()
        coroutineScope {
            List(6) { async { cache.load("server|reader", id) { calls.incrementAndGet(); delay(80); bytes } } }.awaitAll()
        }
        assertEquals(1, calls.get())
    }

    @Test fun differentReadersAndServersHaveSeparateImageData() = runBlocking {
        val cache = CommunityImageCache(context); val id = UUID.randomUUID().toString()
        cache.load("server-a|reader-a", id) { photo(Color.RED) }
        cache.load("server-a|reader-b", id) { photo(Color.BLUE) }
        cache.load("server-b|reader-a", id) { photo(Color.YELLOW) }
        CommunityImageCache.trimMemory()
        assertEquals(Color.RED, cache.load("server-a|reader-a", id) { error("must use disk") }.getPixel(0, 0))
        assertEquals(Color.BLUE, cache.load("server-a|reader-b", id) { error("must use disk") }.getPixel(0, 0))
        assertEquals(Color.YELLOW, cache.load("server-b|reader-a", id) { error("must use disk") }.getPixel(0, 0))
    }

    @Test fun corruptDiskImageIsFetchedAgain() = runBlocking {
        val cache = CommunityImageCache(context); val id = UUID.randomUUID().toString(); val bytes = photo()
        cache.load("server|reader", id) { bytes }; CommunityImageCache.trimMemory()
        File(context.cacheDir, CommunityImageCache.DIRECTORY).walkTopDown().first { it.extension == "jpg" }.writeText("interrupted file")
        val calls = AtomicInteger()
        assertEquals(Color.GREEN, cache.load("server|reader", id) { calls.incrementAndGet(); bytes }.getPixel(0, 0))
        assertEquals(1, calls.get())
    }

    @Test fun diskBudgetEvictsLeastRecentlyUsedPicture() = runBlocking {
        val bytes = photo(); val clock = AtomicLong(System.currentTimeMillis())
        val cache = CommunityImageCache(context, maxDiskBytes = bytes.size.toLong() * 2, now = { clock.incrementAndGet() })
        val ids = List(3) { UUID.randomUUID().toString() }; val calls = AtomicInteger()
        suspend fun fetch(): ByteArray { calls.incrementAndGet(); return bytes }
        cache.load("s", ids[0], ::fetch); cache.load("s", ids[1], ::fetch)
        cache.load("s", ids[0], ::fetch) // Keep the first picture newer than the second.
        cache.load("s", ids[2], ::fetch); CommunityImageCache.trimMemory()
        assertTrue(cache.diskBytes() <= bytes.size.toLong() * 2)
        cache.load("s", ids[0], ::fetch); assertEquals(3, calls.get())
        cache.load("s", ids[1], ::fetch); assertEquals(4, calls.get())
    }

    @Test fun clearedOrRevokedPicturesCannotReturnFromMemoryOrLateDownloads() = runBlocking {
        val cache = CommunityImageCache(context); val bytes = photo(); val id = UUID.randomUUID().toString()
        cache.load("s", id) { bytes }; cache.evict("s", listOf(id))
        var fetched = false
        cache.load("s", id) { fetched = true; bytes }; assertTrue(fetched)
        val started = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val pending = async { cache.load("s", UUID.randomUUID().toString()) { started.complete(Unit); release.await(); bytes } }
        started.await(); CloudCommunityStore(context).clearAll(); release.complete(Unit)
        assertTrue(runCatching { pending.await() }.exceptionOrNull() is CancellationException)
        assertEquals(0L, cache.diskBytes())
    }

    @Test fun unusedPictureExpiresAfterSevenDays() = runBlocking {
        val clock = AtomicLong(System.currentTimeMillis()); val bytes = photo(); val id = UUID.randomUUID().toString()
        val cache = CommunityImageCache(context, now = { clock.get() }); var calls = 0
        cache.load("s", id) { calls++; bytes }
        clock.addAndGet(8L * 24 * 60 * 60 * 1000)
        cache.load("s", id) { calls++; bytes }
        assertEquals(2, calls)
    }
}
