package com.testconnection.confidence_agent.data.repository

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.AtomicFile
import android.util.LruCache
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/** App-owned image data, never an unauthenticated HTTP cache.
 * Callers must have just obtained this media ID from an authenticated, visible post.
 * An offline text snapshot alone does not grant permission to display an image.
 */
class CommunityImageCache(
    context: Context,
    private val maxDiskBytes: Long = 64L * 1024 * 1024,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val root = File(context.applicationContext.cacheDir, DIRECTORY)
    private val downloads = Semaphore(2)
    private val flights = ConcurrentHashMap<String, Mutex>()

    suspend fun load(scope: String, mediaId: String, fetch: suspend () -> ByteArray): Bitmap = withContext(Dispatchers.IO) {
        val target = file(scope, mediaId)
        val key = target.absolutePath
        flights.getOrPut(key) { Mutex() }.withLock {
            val stamp = synchronized(storageLock) {
                prune()
                memory.get(key)?.takeUnless { it.isRecycled }?.let {
                    target.setLastModified(now())
                    return@withContext it
                }
                if (target.isFile) {
                    val decoded = BitmapFactory.decodeFile(target.absolutePath)
                    if (decoded != null) {
                        target.setLastModified(now()); memory.put(key, decoded)
                        return@withContext decoded
                    }
                    target.delete() // A corrupt or interrupted cache entry must be fetched again.
                }
                generation to (versions[key] ?: 0L)
            }
            val bytes = downloads.withPermit { fetch() }
            currentCoroutineContext().ensureActive()
            val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: throw IOException("图片数据不完整，请重试")
            synchronized(storageLock) {
                if (stamp != (generation to (versions[key] ?: 0L))) {
                    decoded.recycle()
                    throw CancellationException("Image cache was cleared")
                }
                // Disk-full errors must not prevent displaying the successfully downloaded image.
                if (bytes.size <= maxDiskBytes) {
                    runCatching {
                        check(target.parentFile!!.isDirectory || target.parentFile!!.mkdirs())
                        val atomic = AtomicFile(target)
                        val stream = atomic.startWrite()
                        try { stream.write(bytes); atomic.finishWrite(stream) }
                        catch (error: Exception) { atomic.failWrite(stream); throw error }
                        target.setLastModified(now())
                        prune()
                    }
                }
                memory.put(key, decoded)
            }
            decoded
        }
    }

    fun evict(scope: String, mediaIds: Collection<String>) = synchronized(storageLock) {
        for (id in mediaIds) {
            val target = file(scope, id)
            val key = target.absolutePath
            versions[key] = (versions[key] ?: 0L) + 1L
            memory.remove(key)
            AtomicFile(target).delete()
        }
    }

    fun diskBytes(): Long = synchronized(storageLock) { entries().sumOf { it.length() } }

    private fun file(scope: String, mediaId: String): File {
        val namespace = MessageDigest.getInstance("SHA-256").digest(scope.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return File(File(root, namespace), "${UUID.fromString(mediaId)}.jpg")
    }

    private fun entries(): List<File> = root.listFiles().orEmpty().filter { it.isDirectory }
        .flatMap { directory -> directory.listFiles().orEmpty().filter { it.isFile } }

    /** Called on an IO dispatcher under storageLock. Files older than seven unused days expire. */
    private fun prune() {
        val cutoff = now() - 7L * 24 * 60 * 60 * 1000
        val all = entries().sortedBy { it.lastModified() }.toMutableList()
        var used = all.sumOf { it.length() }
        for (entry in all) {
            if (entry.lastModified() < cutoff || used > maxDiskBytes) {
                val size = entry.length()
                if (entry.delete()) { used -= size; memory.remove(entry.absolutePath) }
            }
        }
    }

    companion object {
        const val DIRECTORY = "community_image_cache"
        private val storageLock = Any()
        private var generation = 0L
        private val versions = mutableMapOf<String, Long>()
        private val memory = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
            override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
        }

        fun trimMemory() = synchronized(storageLock) { memory.evictAll() }

        fun clearAll(context: Context) = synchronized(storageLock) {
            generation += 1
            versions.clear()
            memory.evictAll()
            File(context.applicationContext.cacheDir, DIRECTORY).deleteRecursively()
        }
    }
}
