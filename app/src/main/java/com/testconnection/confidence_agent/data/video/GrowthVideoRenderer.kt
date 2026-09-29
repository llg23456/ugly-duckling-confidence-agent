package com.testconnection.confidence_agent.data.video

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

data class VideoRenderScene(
    val scene: VideoScene,
    val photoPath: String? = null,
    val annotation: String = "",
    val audioPath: String? = null,
    val audioDurationMs: Long = 0,
    val durationMs: Long = 4_000,
)

class GrowthVideoRenderer(private val context: Context) {
    suspend fun render(scenes: List<VideoRenderScene>): File {
        require(scenes.size in 3..5) { "请选择三到五个关键节点" }
        val directory = File(context.filesDir, "generated_videos").apply { mkdirs() }
        val frameDirectory = File(context.cacheDir, "video_frames").apply { mkdirs() }
        val frames = withContext(Dispatchers.Default) {
            scenes.mapIndexed { index, item -> makeFrame(frameDirectory, item, index, scenes.size) }
        }
        val output = File(directory, "growth-${System.currentTimeMillis()}.mp4")
        return withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { continuation ->
                val videoItems = frames.mapIndexed { index, image ->
                    EditedMediaItem.Builder(
                        MediaItem.Builder()
                            .setUri(Uri.fromFile(image))
                            .setImageDurationMs(scenes[index].durationMs)
                            .build(),
                    ).setFrameRate(24).build()
                }
                val videoSequence = EditedMediaItemSequence.Builder(videoItems).build()
                val sequences = mutableListOf(videoSequence)
                if (scenes.any { !it.audioPath.isNullOrBlank() }) {
                    val audioBuilder = EditedMediaItemSequence.Builder()
                    scenes.forEach { item ->
                        val audio = item.audioPath?.let(::File)?.takeIf { it.exists() && it.length() > 0 }
                        if (audio == null) {
                            audioBuilder.addGap(item.durationMs * 1_000)
                        } else {
                            val mediaItem = MediaItem.Builder().setUri(Uri.fromFile(audio)).apply {
                                if (item.audioDurationMs > 0) {
                                    setClippingConfiguration(
                                        MediaItem.ClippingConfiguration.Builder()
                                            .setEndPositionMs(item.audioDurationMs)
                                            .build(),
                                    )
                                }
                            }.build()
                            audioBuilder.addItem(EditedMediaItem.Builder(mediaItem).build())
                            val remainingMs = item.durationMs - item.audioDurationMs
                            if (remainingMs > 50) audioBuilder.addGap(remainingMs * 1_000)
                        }
                    }
                    sequences.add(audioBuilder.build())
                }
                val composition = Composition.Builder(sequences)
                    .experimentalSetForceAudioTrack(sequences.size > 1)
                    .build()
                val transformer = Transformer.Builder(context)
                    .setVideoMimeType(MimeTypes.VIDEO_H264)
                    .setAudioMimeType(MimeTypes.AUDIO_AAC)
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

    private fun makeFrame(directory: File, item: VideoRenderScene, index: Int, total: Int): File {
        val labels = mapOf(
            "beginning" to "开始状态", "difficulty" to "遇到困难",
            "small_step" to "迈出一步", "change" to "发生变化",
            "continuing" to "仍在继续", "help" to "获得帮助",
        )
        val bitmap = Bitmap.createBitmap(720, 1280, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val photo = item.photoPath?.let(BitmapFactory::decodeFile)
        if (photo != null) {
            drawCenterCrop(canvas, photo, Rect(0, 0, 720, 1280))
            canvas.drawColor(0x55000000)
            photo.recycle()
        } else {
            val backgrounds = listOf(0xFFFFFBF2.toInt(), 0xFFEAF2EB.toInt(), 0xFFF8E9E0.toInt())
            canvas.drawColor(backgrounds[index % backgrounds.size])
        }
        val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF202421.toInt() }
        val sage = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF607C6C.toInt() }
        val card = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xEEFFFFFF.toInt() }
        canvas.drawRoundRect(RectF(48f, 330f, 672f, 965f), 42f, 42f, card)
        ink.typeface = Typeface.create("sans-serif", Typeface.BOLD)
        ink.textSize = 46f
        ink.color = if (photo != null) Color.WHITE else 0xFF202421.toInt()
        canvas.drawText("小丑鸭 · 成长小片", 52f, 118f, ink)
        sage.typeface = Typeface.create("sans-serif", Typeface.BOLD)
        sage.textSize = 38f
        canvas.drawText(labels[item.scene.stage] ?: "成长记录", 86f, 415f, sage)
        ink.typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        ink.color = 0xFF202421.toInt()
        val caption = item.scene.text.trim().replace(Regex("\\s+"), " ")
        val lines = sequenceOf(43f, 39f, 35f, 32f, 29f)
            .map { size -> ink.textSize = size; size to wrap(caption, ink, 530f) }
            .first { (_, wrapped) -> wrapped.size <= 8 }
        ink.textSize = lines.first
        val lineHeight = ink.textSize * 1.38f
        lines.second.forEachIndexed { lineIndex, line ->
            canvas.drawText(line, 86f, 505f + lineIndex * lineHeight, ink)
        }
        if (item.annotation.isNotBlank()) {
            sage.typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            sage.textSize = 25f
            wrap("照片批注：${item.annotation.trim()}", sage, 530f).take(2).forEachIndexed { lineIndex, line ->
                canvas.drawText(line, 86f, 885f + lineIndex * 32f, sage)
            }
        }
        if (photo == null) {
            val duck = BitmapFactory.decodeResource(context.resources, R.drawable.duck_step)
            if (duck != null) {
                canvas.drawBitmap(duck, null, RectF(430f, 975f, 655f, 1200f), null)
                duck.recycle()
            }
        }
        sage.textSize = 28f
        sage.color = if (photo != null) Color.WHITE else 0xFF607C6C.toInt()
        canvas.drawText("${index + 1} / $total  ·  只讲真实发生过的事", 52f, 1210f, sage)
        val file = File(directory, "frame-${System.nanoTime()}-$index.png")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        return file
    }

    private fun drawCenterCrop(canvas: Canvas, source: Bitmap, destination: Rect) {
        val sourceRatio = source.width.toFloat() / source.height
        val targetRatio = destination.width().toFloat() / destination.height()
        val sourceRect = if (sourceRatio > targetRatio) {
            val width = (source.height * targetRatio).toInt()
            val left = (source.width - width) / 2
            Rect(left, 0, left + width, source.height)
        } else {
            val height = (source.width / targetRatio).toInt()
            val top = (source.height - height) / 2
            Rect(0, top, source.width, top + height)
        }
        canvas.drawBitmap(source, sourceRect, destination, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
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
