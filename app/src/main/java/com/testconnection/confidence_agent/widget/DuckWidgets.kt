package com.testconnection.confidence_agent.widget

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.ButtonDefaults
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.appwidget.components.FilledButton
import androidx.glance.background
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.testconnection.confidence_agent.MainActivity
import com.testconnection.confidence_agent.R
import com.testconnection.confidence_agent.ui.AppDestination
import com.testconnection.confidence_agent.data.model.RecordMode
import com.testconnection.confidence_agent.data.preferences.DeviceIdStore
import com.testconnection.confidence_agent.data.repository.LocalRecordRepository
import com.testconnection.confidence_agent.data.remote.GrowthEvent
import com.testconnection.confidence_agent.data.remote.ReviewApiClient

private val cream = ColorProvider(Color(0xFFFFFBF2))
private val ink = ColorProvider(Color(0xFF202421))
private val muted = ColorProvider(Color(0xFF747B76))
private val sage = ColorProvider(Color(0xFF607C6C))
private val sagePale = ColorProvider(Color(0xFFE4EEE7))
private val peach = ColorProvider(Color(0xFFF5E2D6))

object WidgetUpdater {
    suspend fun refreshGrowthWidgets(context: Context, events: List<GrowthEvent>? = null) {
        val store = WidgetPrivacyStore(context)
        if (store.isAllowed()) {
            if (events == null) {
                // 已选记录已同步写入本地快照，先刷新组件，避免网络慢时桌面一直空白。
                TodayGrowthWidget().updateAll(context)
                MonthlyFootprintWidget().updateAll(context)
                val fresh = runCatching { ReviewApiClient().events(DeviceIdStore(context).get()) }.getOrNull()
                if (fresh != null) store.update(fresh, LocalRecordRepository(context.applicationContext).load())
            } else {
                store.update(events, LocalRecordRepository(context.applicationContext).load())
            }
        }
        TodayGrowthWidget().updateAll(context)
        MonthlyFootprintWidget().updateAll(context)
    }
}

private fun destinationIntent(context: Context, destination: AppDestination, mode: RecordMode = RecordMode.TEXT, camera: Boolean = false) =
    Intent(context, MainActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        putExtra(MainActivity.EXTRA_TARGET_TAB, destination.legacyCode)
        putExtra(MainActivity.EXTRA_RECORD_MODE, mode.name)
        putExtra(MainActivity.EXTRA_OPEN_CAMERA, camera)
    }

/** 桌面 RemoteViews 有传输大小限制，照片先缩至组件实际需要的尺寸。 */
private fun loadWidgetBitmap(path: String?, maxDimension: Int): Bitmap? {
    if (path.isNullOrBlank()) return null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (bounds.outWidth / sample > maxDimension || bounds.outHeight / sample > maxDimension) sample *= 2
    return BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
}

@Composable
private fun Title(text: String) = Text(text, style = TextStyle(color = ink, fontSize = 19.sp, fontWeight = FontWeight.Bold))

class TodayGrowthWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val store = WidgetPrivacyStore(context)
        val snapshot = store.snapshot()
        provideContent {
            val appContext = LocalContext.current
            Row(
                modifier = GlanceModifier.fillMaxSize().background(cream).padding(16.dp)
                    .clickable(actionStartActivity(destinationIntent(appContext, AppDestination.GROWTH))),
            ) {
                Column(modifier = GlanceModifier.defaultWeight()) {
                    Title(if (snapshot.todaySelected) "我今天想留下的" else "今天也看见自己的进步")
                    Spacer(GlanceModifier.height(8.dp))
                    Text(
                        if (!snapshot.allowed) "桌面展示尚未开启，打开应用后可自行选择。"
                        else snapshot.todayText.ifBlank { "今天还没有适合公开展示的事件。" },
                        style = TextStyle(color = muted, fontSize = 14.sp),
                        maxLines = 3,
                    )
                    Spacer(GlanceModifier.height(8.dp))
                    Text("查看来源  ›", style = TextStyle(color = sage, fontSize = 15.sp, fontWeight = FontWeight.Bold))
                }
                val photo = loadWidgetBitmap(snapshot.todayPhotoPath, maxDimension = 240)
                if (photo != null) Image(ImageProvider(photo), "选择的照片记录", modifier = GlanceModifier.size(120.dp))
                else Image(ImageProvider(R.drawable.duck_writing), "正在书写的小鸭", modifier = GlanceModifier.size(90.dp))
            }
        }
    }
}

class TodayGrowthWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = TodayGrowthWidget()
}

class MonthlyFootprintWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val store = WidgetPrivacyStore(context)
        val snapshot = store.snapshot()
        provideContent {
            val appContext = LocalContext.current
            Column(
                modifier = GlanceModifier.fillMaxSize().background(cream).padding(16.dp)
                    .clickable(actionStartActivity(destinationIntent(appContext, AppDestination.GROWTH))),
            ) {
                Title(if (snapshot.monthSelected) "我想留下的一笔" else "本月的小小足迹")
                Spacer(GlanceModifier.height(6.dp))
                Row {
                    val photo = loadWidgetBitmap(snapshot.monthPhotoPath, maxDimension = 140)
                    if (photo != null) Image(ImageProvider(photo), "选择的照片记录", modifier = GlanceModifier.size(70.dp))
                    else Image(ImageProvider(R.drawable.duck_growth_try_v3), "向前走的小鸭", modifier = GlanceModifier.size(70.dp))
                    Column(modifier = GlanceModifier.padding(start = 10.dp)) {
                        Text(if (snapshot.monthSelected) snapshot.monthText.ifBlank { "这条记录" } else if (snapshot.allowed) "${snapshot.monthCount} 个可展示事件" else "桌面展示未开启", style = TextStyle(color = sage, fontSize = 18.sp, fontWeight = FontWeight.Bold), maxLines = 3)
                        Text(if (snapshot.monthSelected) "你亲自选择的记录" else if (snapshot.allowed) "只统计适合公开的真实经历" else "打开应用后可自行选择", style = TextStyle(color = muted, fontSize = 13.sp))
                    }
                }
            }
        }
    }
}

class MonthlyFootprintWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = MonthlyFootprintWidget()
}

class QuickRecordWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent { QuickRecordContent() }
    }
}

@Composable
private fun QuickRecordContent() {
    val context = LocalContext.current
    Column(modifier = GlanceModifier.fillMaxSize().background(cream).padding(horizontal = 18.dp, vertical = 20.dp)) {
        Row(modifier = GlanceModifier.fillMaxWidth()) {
            Column(modifier = GlanceModifier.defaultWeight()) {
                Title("今天想留下一点什么？")
                Text("哪怕只是一句话，也很珍贵。", style = TextStyle(color = muted, fontSize = 13.sp))
            }
            Image(ImageProvider(R.drawable.duck_welcome), "陪伴记录的小鸭", modifier = GlanceModifier.size(64.dp))
        }
        Spacer(GlanceModifier.height(12.dp))
        Row(modifier = GlanceModifier.fillMaxWidth()) {
            QuickAction("说一句", R.drawable.ic_record_voice, sagePale, GlanceModifier.defaultWeight(), actionStartActivity(destinationIntent(context, AppDestination.RECORD, RecordMode.VOICE)))
            QuickAction("写一句", R.drawable.ic_record_write, sagePale, GlanceModifier.defaultWeight(), actionStartActivity(destinationIntent(context, AppDestination.RECORD, RecordMode.TEXT)))
            QuickAction("拍一张", R.drawable.ic_record_photo, peach, GlanceModifier.defaultWeight(), actionStartActivity(destinationIntent(context, AppDestination.RECORD, RecordMode.PHOTO, camera = true)))
        }
    }
}

@Composable
private fun QuickAction(text: String, iconRes: Int, background: ColorProvider, modifier: GlanceModifier, action: androidx.glance.action.Action) {
    FilledButton(
        text = text,
        onClick = action,
        icon = ImageProvider(iconRes),
        modifier = modifier.padding(horizontal = 6.dp).height(46.dp),
        colors = ButtonDefaults.buttonColors(
            backgroundColor = background,
            contentColor = ink,
        ),
        maxLines = 1,
    )
}

class QuickRecordWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = QuickRecordWidget()
}
