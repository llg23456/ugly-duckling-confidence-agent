package com.testconnection.confidence_agent.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.testconnection.confidence_agent.R
import com.testconnection.confidence_agent.ui.theme.Cream
import com.testconnection.confidence_agent.ui.theme.Ink
import com.testconnection.confidence_agent.ui.theme.SageDark

@Composable
fun AppCoverScreen() {
    Box(modifier = Modifier.fillMaxSize().background(Cream)) {
        Image(
            painter = painterResource(R.drawable.app_cover_v1),
            contentDescription = "小丑鸭勇敢迈出一步",
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
        )
        Column(
            modifier = Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 28.dp, vertical = 46.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "小丑鸭",
                    style = MaterialTheme.typography.displaySmall.copy(fontSize = 42.sp),
                    color = Ink,
                )
                Text(
                    text = "慢慢长成自己的样子",
                    modifier = Modifier.padding(top = 8.dp),
                    style = MaterialTheme.typography.titleMedium,
                    color = SageDark,
                    textAlign = TextAlign.Center,
                    fontWeight = FontWeight.Normal,
                )
            }
            Text(
                text = "每一小步，都值得被看见",
                style = MaterialTheme.typography.bodyLarge,
                color = SageDark,
                textAlign = TextAlign.Center,
            )
        }
    }
}
