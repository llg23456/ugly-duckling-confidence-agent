package com.testconnection.confidence_agent.data.video

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import com.testconnection.confidence_agent.R
import com.testconnection.confidence_agent.data.remote.VideoScene
import java.io.File
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class VideoRenderScene(
    val scene: VideoScene,
    val photoPath: String? = null,
    val annotation: String = "",
    val narrationPath: String? = null,
    val narrationDurationMs: Long = 0,
    val originalVoicePath: String? = null,
    val originalVoiceDurationMs: Long = 0,
    val durationMs: Long = 4_000,
    val isDemo: Boolean = false,
)

class GrowthVideoRenderer(private val context: Context) {
    suspend fun createFrames(scenes: List<VideoRenderScene>): List<File> {
        require(scenes.size in 3..7) { "请选择三到七个关键节点" }
        val frameDirectory = File(context.cacheDir, "video_frames").apply { mkdirs() }
        return withContext(Dispatchers.Default) {
            scenes.mapIndexed { index, item -> makeFrame(frameDirectory, item, index, scenes.size) }
        }
    }

    private fun makeFrame(directory: File, item: VideoRenderScene, index: Int, total: Int): File {
        val labels = mapOf(
            "beginning" to "开始状态", "difficulty" to "遇到困难",
            "small_step" to "迈出一步", "change" to "发生变化",
            "continuing" to "仍在继续", "help" to "获得帮助",
        )
        // 用 720×1280 的逻辑坐标排版，再缩放为更适合手机快速编码的 540×960。
        val bitmap = Bitmap.createBitmap(540, 960, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap).apply { scale(0.75f, 0.75f) }
        canvas.drawColor(0xFFFFFBF2.toInt())
        val photo = item.photoPath?.let(BitmapFactory::decodeFile)
        val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF202421.toInt() }
        val sage = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF607C6C.toInt() }
        val card = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        canvas.drawRoundRect(RectF(42f, 200f, 678f, 1085f), 42f, 42f, card)

        ink.typeface = Typeface.create("sans-serif", Typeface.BOLD)
        ink.textSize = 46f
        canvas.drawText("小丑鸭 · 成长小片", 52f, 105f, ink)

        sage.typeface = Typeface.create("sans-serif", Typeface.BOLD)
        sage.textSize = 27f
        val dateAndStage = listOfNotNull(
            displayDate(item.scene.date).takeIf(String::isNotBlank),
            labels[item.scene.stage] ?: "成长记录",
        ).joinToString("  ·  ")
        canvas.drawText(dateAndStage, 52f, 158f, sage)

        ink.typeface = Typeface.create("sans-serif", Typeface.BOLD)
        ink.textSize = 34f
        canvas.drawText(item.scene.title.ifBlank { "这一天的记录" }, 76f, 270f, ink)
        ink.typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        val caption = item.scene.text.trim().replace(Regex("\\s+"), " ")

        if (photo != null) {
            drawRoundedCenterCrop(canvas, photo, Rect(76, 310, 644, 715), 28f)
            photo.recycle()
            val lines = sequenceOf(32f, 29f, 26f, 24f)
                .map { size -> ink.textSize = size; size to wrap(caption, ink, 568f) }
                .firstOrNull { (_, wrapped) -> wrapped.size <= 6 }
                ?: (24f to wrap(caption, ink.apply { textSize = 24f }, 568f))
            ink.textSize = lines.first
            val lineHeight = ink.textSize * 1.35f
            lines.second.forEachIndexed { lineIndex, line ->
                canvas.drawText(line, 76f, 780f + lineIndex * lineHeight, ink)
            }
            if (item.annotation.isNotBlank()) {
                sage.typeface = Typeface.create("sans-serif", Typeface.NORMAL)
                sage.textSize = 23f
                val annotationY = 805f + lines.second.size * lineHeight
                wrap("照片批注：${item.annotation.trim()}", sage, 568f).take(2).forEachIndexed { lineIndex, line ->
                    canvas.drawText(line, 76f, annotationY + lineIndex * 30f, sage)
                }
            }
        } else {
            val illustration = BitmapFactory.decodeResource(context.resources, storyArt(caption))
            if (illustration != null) {
                canvas.drawBitmap(illustration, null, RectF(196f, 310f, 524f, 638f), Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
                illustration.recycle()
            }
            sage.textSize = 21f
            canvas.drawText("主题插画 · 装饰画面", 244f, 670f, sage)
            val lines = sequenceOf(32f, 29f, 26f, 24f)
                .map { size -> ink.textSize = size; size to wrap(caption, ink, 548f) }
                .firstOrNull { (_, wrapped) -> wrapped.size <= 8 }
                ?: (24f to wrap(caption, ink.apply { textSize = 24f }, 548f))
            ink.textSize = lines.first
            val lineHeight = ink.textSize * 1.45f
            val firstBaseline = 730f
            lines.second.forEachIndexed { lineIndex, line ->
                canvas.drawText(line, 86f, firstBaseline + lineIndex * lineHeight, ink)
            }
            val duck = BitmapFactory.decodeResource(context.resources, R.drawable.duck_step)
            if (duck != null) {
                canvas.drawBitmap(duck, null, RectF(510f, 1075f, 680f, 1245f), null)
                duck.recycle()
            }
        }

        sage.textSize = 28f
        canvas.drawText("${index + 1} / $total  ·  ${if (item.isDemo) "考研演示故事 · 示例记录" else "只讲真实发生过的事"}", 52f, 1210f, sage)
        val file = File(directory, "frame-${System.nanoTime()}-$index.png")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        return file
    }

    private fun storyArt(text: String): Int = when {
        listOf("户外", "公园", "散步", "湖边").any(text::contains) -> R.drawable.duck_story_outdoors
        listOf("运动", "慢跑", "健身", "操场").any(text::contains) -> R.drawable.duck_story_exercise
        listOf("师兄", "师姐", "老师", "请教", "提问", "求助", "沟通").any(text::contains) -> R.drawable.duck_story_guidance
        listOf("考研", "备考", "学习", "复习", "书", "做题").any(text::contains) -> R.drawable.duck_story_study
        else -> R.drawable.duck_step
    }

    private fun displayDate(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        return runCatching {
            val date = LocalDate.parse(raw)
            "${date.monthValue}月${date.dayOfMonth}日"
        }.getOrDefault(raw)
    }

    private fun drawRoundedCenterCrop(canvas: Canvas, source: Bitmap, destination: Rect, radius: Float) {
        val checkpoint = canvas.save()
        val clip = Path().apply {
            addRoundRect(RectF(destination), radius, radius, Path.Direction.CW)
        }
        canvas.clipPath(clip)
        drawCenterCrop(canvas, source, destination)
        canvas.restoreToCount(checkpoint)
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
