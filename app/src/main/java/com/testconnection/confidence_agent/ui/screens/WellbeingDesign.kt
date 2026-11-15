package com.testconnection.confidence_agent.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.testconnection.confidence_agent.R
import com.testconnection.confidence_agent.ui.theme.*

internal val WellbeingBackground = Color(0xFFF2F3EF)
internal val WellbeingOutline = Color(0xFFE0E5DE)

@Composable
internal fun WellbeingPage(title: String, onBack: () -> Unit, action: @Composable RowScope.() -> Unit = {},
                          content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().background(WellbeingBackground).statusBarsPadding().navigationBarsPadding().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("‹ 返回") }
            Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            action()
        }
        key(title) {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp), content = content)
        }
    }
}

@Composable
internal fun WellbeingCard(color: Color = Color.White, content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier = Modifier.fillMaxWidth(), color = color, shape = RoundedCornerShape(24.dp),
        border = BorderStroke(1.dp, WellbeingOutline), shadowElevation = 1.dp) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}

@Composable
internal fun WellbeingIcon(icon: Int, tint: Color = Color.Unspecified, background: Color = SagePale) {
    Surface(color = background, shape = RoundedCornerShape(14.dp)) {
        Icon(painterResource(icon), null, tint = tint, modifier = Modifier.padding(12.dp).size(24.dp))
    }
}

@Composable
internal fun WellbeingEntry(icon: Int, title: String, subtitle: String, onClick: () -> Unit) {
    Surface(onClick = onClick, modifier = Modifier.fillMaxWidth(), color = Color.White,
        shape = RoundedCornerShape(22.dp), border = BorderStroke(1.dp, WellbeingOutline)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            WellbeingIcon(icon)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = InkMuted)
            }
            Icon(painterResource(R.drawable.ic_wellbeing_chevron_right), null, tint = Color.Unspecified, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
internal fun WellbeingChoice(title: String, selected: Boolean, onClick: () -> Unit, subtitle: String? = null) {
    Surface(onClick = onClick, modifier = Modifier.fillMaxWidth(),
        color = if (selected) SagePale else Color.White, shape = RoundedCornerShape(16.dp),
        border = BorderStroke(if (selected) 1.5.dp else 1.dp, if (selected) SageDark else WellbeingOutline)) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = InkMuted) }
            }
            Text(if (selected) "✓" else "○", color = if (selected) SageDark else InkMuted)
        }
    }
}
