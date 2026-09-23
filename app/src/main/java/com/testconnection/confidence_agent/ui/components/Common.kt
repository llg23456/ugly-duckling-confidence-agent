package com.testconnection.confidence_agent.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.testconnection.confidence_agent.ui.theme.WarmOutline
import com.testconnection.confidence_agent.ui.theme.WarmWhite

val AppCardShape = RoundedCornerShape(26.dp)
val AppButtonShape = RoundedCornerShape(50)

@Composable
fun WarmCard(
    modifier: Modifier = Modifier,
    containerColor: Color = WarmWhite,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = AppCardShape,
        colors = CardDefaults.cardColors(containerColor = containerColor),
        border = BorderStroke(1.dp, WarmOutline),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        content = content,
    )
}

@Composable
fun DuckArt(
    @DrawableRes drawable: Int,
    description: String,
    modifier: Modifier = Modifier,
) {
    Image(
        painter = painterResource(drawable),
        contentDescription = description,
        modifier = modifier,
        contentScale = ContentScale.Fit,
    )
}

@Composable
fun SectionHeading(title: String, subtitle: String? = null) {
    Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
    if (subtitle != null) {
        Text(
            subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
