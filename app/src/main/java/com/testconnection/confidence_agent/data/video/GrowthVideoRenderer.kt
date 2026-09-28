package com.testconnection.confidence_agent.data.video

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import com.testconnection.confidence_agent.R
import com.testconnection.confidence_agent.data.remote.VideoScene
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.max
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

class GrowthVideoRenderer(private val context: Context) {
    suspend fun render(scenes: List<VideoScene>): File {
        require(scenes.size in 2..5) { "请选择两到五个片段" }
        val directory = File(context.filesDir, "generated_videos").apply { mkdirs() }
        val frameDirectory = File(context.cacheDir, "video_frames").apply { mkdirs() }
        val frames = withContext(Dispatchers.Default) {
            scenes.mapIndexed { index, scene -> makeFrame(frameDirectory, scene, index, scenes.size) }
        }
        val durationMs = max(4_000L, (15_000L + scenes.size - 1) / scenes.size)
        val output = File(directory, "growth-${System.currentTimeMillis()}.mp4")
        return withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { continuation ->
                val media = frames.map { image ->
                    EditedMediaItem.Builder(MediaItem.Builder()
                        .setUri(Uri.fromFile(image))
                        .setImageDurationMs(durationMs)
                        .build())
                        .setFrameRate(24)
                        .build()
                }
                val composition = Composition.Builder(EditedMediaItemSequence(media)).build()
                val transformer = Transformer.Builder(context)
                    .setVideoMimeType(MimeTypes.VIDEO_H264)
                    .setPortraitEncodingEnabled(true)
                    .addListener(object : Transformer.Listener {
                        override fun onCompleted(composition: Composition, result: ExportResult) {
                            frames.forEach(File::delete)
                            if (!continuation.isActive) return
                            if (output.exists() && output.length() > 0) continuation.resume(output)
                            else continuation.resumeWithException(IllegalStateException("视频文件未生成"))
                        }

                        override fun onError(composition: Composition, result: ExportResult, exception: ExportException) {
                            frames.forEach(File::delete)
                            output.delete()
                            if (continuation.isActive) continuation.resumeWithException(exception)
                        }
                    }).build()
                continuation.invokeOnCancellation {
                    Handler(Looper.getMainLooper()).post { transformer.cancel() }
                    frames.forEach(File::delete)
                    output.delete()
                }
                runCatching { transformer.start(composition, output.absolutePath) }
                    .onFailure {
                        frames.forEach(File::delete)
                        output.delete()
                        if (continuation.isActive) continuation.resumeWithException(it)
                    }
            }
        }
    }

    private fun makeFrame(directory: File, scene: VideoScene, index: Int, total: Int): File {
        val labels = mapOf(
            "difficulty" to "那时的困难", "small_step" to "自己的一小步",
            "help" to "收到的帮助", "change" to "现在的变化", "continuing" to "仍在继续",
        )
        val bitmap = Bitmap.createBitmap(720, 1280, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val background = listOf(0xFFFFFBF2.toInt(), 0xFFEAF2EB.toInt(), 0xFFF8E9E0.toInt())
        canvas.drawColor(background[index % background.size])
        val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF202421.toInt() }
        val sage = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF607C6C.toInt() }
        val card = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        canvas.drawRoundRect(RectF(54f, 350f, 666f, 940f), 44f, 44f, card)
        ink.typeface = Typeface.create("sans-serif", Typeface.BOLD)
        ink.textSize = 48f
        canvas.drawText("小丑鸭 · 成长小片", 58f, 132f, ink)
        sage.typeface = Typeface.create("sans-serif", Typeface.BOLD)
        sage.textSize = 39f
        canvas.drawText(labels[scene.stage] ?: "成长记录", 93f, 430f, sage)
        ink.typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        val caption = scene.text.trim().replace(Regex("\\s+"), " ")
        val lines = sequenceOf(43f, 39f, 35f, 32f, 29f)
            .map { size -> ink.textSize = size; size to wrap(caption, ink, 520f) }
            .first { (_, wrapped) -> wrapped.size <= 9 }
        ink.textSize = lines.first
        val lineHeight = ink.textSize * 1.35f
        lines.second.forEachIndexed { lineIndex, line ->
            canvas.drawText(line, 93f, 515f + lineIndex * lineHeight, ink)
        }
        val duck = BitmapFactory.decodeResource(context.resources, R.drawable.duck_step)
        if (duck != null) {
            canvas.drawBitmap(duck, null, RectF(420f, 965f, 655f, 1200f), null)
            duck.recycle()
        }
        sage.textSize = 30f
        canvas.drawText("${index + 1} / $total  ·  只讲真实发生过的事", 58f, 1195f, sage)
        val file = File(directory, "frame-${System.nanoTime()}-$index.png")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        return file
    }

    private fun wrap(text: String, paint: Paint, width: Float): List<String> {
        val lines = mutableListOf<String>()
        val current = StringBuilder()
        for (character in text) {
            if (character == '\n' || paint.measureText(current.toString() + character) > width) {
                if (current.isNotEmpty()) lines.add(current.toString())
                current.clear()
                if (character == '\n') continue
            }
            current.append(character)
        }
        if (current.isNotEmpty()) lines.add(current.toString())
        return lines
    }
}
