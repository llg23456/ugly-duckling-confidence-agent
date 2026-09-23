package com.testconnection.confidence_agent.widget

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
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
import com.testconnection.confidence_agent.data.model.RecordMode
import com.testconnection.confidence_agent.data.repository.LocalRecordRepository

private val cream = ColorProvider(Color(0xFFFFFBF2))
private val ink = ColorProvider(Color(0xFF202421))
private val muted = ColorProvider(Color(0xFF747B76))
private val sage = ColorProvider(Color(0xFF607C6C))
private val sagePale = ColorProvider(Color(0xFFE4EEE7))
private val peach = ColorProvider(Color(0xFFF5E2D6))

object WidgetUpdater {
    suspend fun refreshGrowthWidgets(context: Context) {
        TodayGrowthWidget().updateAll(context)
        MonthlyFootprintWidget().updateAll(context)
    }
}

private fun destinationIntent(context: Context, tab: Int, mode: RecordMode = RecordMode.TEXT, camera: Boolean = false) =
    Intent(context, MainActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        putExtra(MainActivity.EXTRA_TARGET_TAB, tab)
        putExtra(MainActivity.EXTRA_RECORD_MODE, mode.name)
        putExtra(MainActivity.EXTRA_OPEN_CAMERA, camera)
    }

@Composable
private fun Title(text: String) = Text(text, style = TextStyle(color = ink, fontSize = 19.sp, fontWeight = FontWeight.Bold))

class TodayGrowthWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val latest = LocalRecordRepository(context).load().firstOrNull()
        provideContent {
            val appContext = LocalContext.current
            Row(
                modifier = GlanceModifier.fillMaxSize().background(cream).padding(16.dp)
                    .clickable(actionStartActivity(destinationIntent(appContext, 1))),
            ) {
                Column(modifier = GlanceModifier.defaultWeight()) {
                    Title("今天也看见自己的进步")
                    Spacer(GlanceModifier.height(8.dp))
                    Text(
                        latest?.let { it.photoComment.ifBlank { it.text }.ifBlank { "留下一张值得记住的照片" } }
                            ?: "今天还没有记录，慢慢来也可以。",
                        style = TextStyle(color = muted, fontSize = 14.sp),
                        maxLines = 3,
                    )
                    Spacer(GlanceModifier.height(8.dp))
                    Text("查看来源  ›", style = TextStyle(color = sage, fontSize = 15.sp, fontWeight = FontWeight.Bold))
                }
                Image(ImageProvider(R.drawable.duck_writing), "正在书写的小鸭", modifier = GlanceModifier.size(90.dp))
            }
        }
    }
}

class TodayGrowthWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = TodayGrowthWidget()
}

class MonthlyFootprintWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val count = LocalRecordRepository(context).load().count()
        provideContent {
            val appContext = LocalContext.current
            Column(
                modifier = GlanceModifier.fillMaxSize().background(cream).padding(16.dp)
                    .clickable(actionStartActivity(destinationIntent(appContext, 1))),
            ) {
                Title("本月的小小足迹")
                Spacer(GlanceModifier.height(6.dp))
                Row {
                    Image(ImageProvider(R.drawable.duck_step), "向前走的小鸭", modifier = GlanceModifier.size(70.dp))
                    Column(modifier = GlanceModifier.padding(start = 10.dp)) {
                        Text("$count 个真实记录", style = TextStyle(color = sage, fontSize = 18.sp, fontWeight = FontWeight.Bold))
                        Text("我的尝试会慢慢积累", style = TextStyle(color = muted, fontSize = 13.sp))
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
    Column(modifier = GlanceModifier.fillMaxSize().background(cream).padding(16.dp)) {
        Row(modifier = GlanceModifier.fillMaxWidth()) {
            Column(modifier = GlanceModifier.defaultWeight()) {
                Title("今天想留下一点什么？")
                Text("哪怕只是一句话，也很珍贵。", style = TextStyle(color = muted, fontSize = 13.sp))
            }
            Image(ImageProvider(R.drawable.duck_welcome), "陪伴记录的小鸭", modifier = GlanceModifier.size(64.dp))
        }
        Spacer(GlanceModifier.height(8.dp))
        Row(modifier = GlanceModifier.fillMaxWidth()) {
            QuickAction("● 说一句", sagePale, GlanceModifier.defaultWeight(), actionStartActivity(destinationIntent(context, 2, RecordMode.VOICE)))
            QuickAction("▤ 写一句", sagePale, GlanceModifier.defaultWeight(), actionStartActivity(destinationIntent(context, 2, RecordMode.TEXT)))
            QuickAction("▧ 拍一张", peach, GlanceModifier.defaultWeight(), actionStartActivity(destinationIntent(context, 2, RecordMode.PHOTO, camera = true)))
        }
    }
}

@Composable
private fun QuickAction(text: String, background: ColorProvider, modifier: GlanceModifier, action: androidx.glance.action.Action) {
    Text(
        text,
        modifier = modifier.padding(horizontal = 3.dp).background(background).padding(10.dp).clickable(action),
        style = TextStyle(color = ink, fontSize = 14.sp, fontWeight = FontWeight.Bold),
        maxLines = 1,
    )
}

class QuickRecordWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = QuickRecordWidget()
}
