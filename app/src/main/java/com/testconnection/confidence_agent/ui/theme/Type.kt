package com.testconnection.confidence_agent.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.testconnection.confidence_agent.R

private val FriendlyArtFont = FontFamily(
    Font(R.font.zcool_kuaile_regular, weight = FontWeight.Normal),
)

val Typography = Typography(
    displaySmall = TextStyle(fontFamily = FriendlyArtFont, fontWeight = FontWeight.Normal, fontSize = 32.sp, lineHeight = 40.sp, letterSpacing = 0.4.sp),
    headlineMedium = TextStyle(fontFamily = FriendlyArtFont, fontWeight = FontWeight.Normal, fontSize = 24.sp, lineHeight = 32.sp, letterSpacing = 0.3.sp),
    titleLarge = TextStyle(fontFamily = FriendlyArtFont, fontWeight = FontWeight.Normal, fontSize = 20.sp, lineHeight = 27.sp, letterSpacing = 0.2.sp),
    titleMedium = TextStyle(fontFamily = FriendlyArtFont, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 23.sp, letterSpacing = 0.15.sp),
    bodyLarge = TextStyle(fontFamily = FriendlyArtFont, fontSize = 15.sp, lineHeight = 24.sp, letterSpacing = 0.1.sp),
    bodyMedium = TextStyle(fontFamily = FriendlyArtFont, fontSize = 13.sp, lineHeight = 20.sp, letterSpacing = 0.05.sp),
    labelLarge = TextStyle(fontFamily = FriendlyArtFont, fontWeight = FontWeight.Normal, fontSize = 13.sp, letterSpacing = 0.15.sp),
)
