package com.testconnection.confidence_agent.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.testconnection.confidence_agent.R
import com.testconnection.confidence_agent.data.model.UserProfile
import com.testconnection.confidence_agent.ui.components.DuckArt
import com.testconnection.confidence_agent.ui.components.noRippleClickable
import com.testconnection.confidence_agent.ui.theme.Cream
import com.testconnection.confidence_agent.ui.theme.SageDark
import com.testconnection.confidence_agent.ui.theme.SagePale
import com.testconnection.confidence_agent.ui.theme.TerracottaPale

@Composable
fun MemoryCenterScreen(
    profile: UserProfile?,
    onBack: () -> Unit,
    onRestartOnboarding: () -> Unit,
) {
    val items = profile?.displayItems().orEmpty()
    var selected by remember { mutableStateOf<Pair<String, String>?>(null) }
    val colors = listOf(SagePale, TerracottaPale, Color(0xFFF3E7C9), Color(0xFFE6EDF2), Color(0xFFE9E0F0))

    selected?.let { item ->
        AlertDialog(
            onDismissRequest = { selected = null },
            containerColor = Cream,
            shape = RoundedCornerShape(30.dp),
            title = { Text(item.first) },
            text = { Text(item.second, style = MaterialTheme.typography.bodyLarge) },
            confirmButton = { TextButton(onClick = { selected = null }) { Text("收好") } },
        )
    }

    Column(
        modifier = Modifier.fillMaxSize().background(Cream).padding(horizontal = 20.dp, vertical = 22.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("‹", modifier = Modifier.noRippleClickable(onClick = onBack).padding(10.dp), style = MaterialTheme.typography.headlineLarge)
            Text("记忆中心", style = MaterialTheme.typography.displaySmall)
        }
        Text("这些是你亲口告诉小鸭的。左右翻一翻，点开可以放大查看。", style = MaterialTheme.typography.bodyLarge, color = SageDark)
        Spacer(Modifier.height(20.dp))
        DuckArt(R.drawable.duck_welcome, "记忆中心的小鸭", Modifier.size(130.dp).align(Alignment.CenterHorizontally))
        Spacer(Modifier.height(12.dp))
        if (items.isEmpty()) {
            Surface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(26.dp), color = SagePale) {
                Column(modifier = Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("还没有建立初步画像", style = MaterialTheme.typography.titleLarge)
                    Text("你可以重新和小鸭认识一下。", style = MaterialTheme.typography.bodyLarge)
                }
            }
        } else {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                itemsIndexed(items) { index, item ->
                    val size = if (index % 3 == 0) 176.dp else 150.dp
                    Box(
                        modifier = Modifier
                            .size(size)
                            .background(colors[index % colors.size], CircleShape)
                            .noRippleClickable { selected = item }
                            .padding(18.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(item.first, style = MaterialTheme.typography.titleMedium, color = SageDark)
                            Text(item.second, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center, maxLines = 4)
                        }
                    }
                }
            }
        }
        Spacer(Modifier.weight(1f))
        Button(onClick = onRestartOnboarding, modifier = Modifier.fillMaxWidth().height(52.dp)) {
            Text(if (items.isEmpty()) "现在认识一下" else "重新认识我")
        }
        Text(
            "重新认识不会自动公开任何内容；不愿回答的项目可以选择不透露。",
            modifier = Modifier.padding(top = 10.dp),
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
        )
    }
}
