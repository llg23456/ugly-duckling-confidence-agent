package com.testconnection.confidence_agent.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.viewmodel.compose.viewModel
import com.testconnection.confidence_agent.ui.screens.GrowthScreen
import com.testconnection.confidence_agent.ui.screens.HomeScreen
import com.testconnection.confidence_agent.ui.screens.HomeViewModel
import com.testconnection.confidence_agent.ui.screens.ProfileScreen
import com.testconnection.confidence_agent.ui.screens.RecordScreen
import com.testconnection.confidence_agent.ui.screens.VoiceCallScreen
import com.testconnection.confidence_agent.data.preferences.VoicePreferencesStore
import com.testconnection.confidence_agent.ui.theme.InkMuted
import com.testconnection.confidence_agent.ui.theme.SageDark
import com.testconnection.confidence_agent.ui.theme.SagePale

private data class AppTab(val label: String, val symbol: String)

private val tabs = listOf(
    AppTab("首页", "⌂"),
    AppTab("成长", "▥"),
    AppTab("记录", "▤"),
    AppTab("我的", "♙"),
)

@Composable
fun ConfidenceAgentApp() {
    val context = LocalContext.current
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    var showVoiceCall by rememberSaveable { mutableStateOf(false) }
    val voicePreferencesStore = androidx.compose.runtime.remember {
        VoicePreferencesStore(context.applicationContext)
    }
    var voicePreferences by androidx.compose.runtime.remember {
        mutableStateOf(voicePreferencesStore.load())
    }
    val homeViewModel: HomeViewModel = viewModel()
    val homeState by homeViewModel.uiState.collectAsState()

    if (showVoiceCall) {
        VoiceCallScreen(
            state = homeState,
            viewModel = homeViewModel,
            voicePreferences = voicePreferences,
            onClose = { showVoiceCall = false },
        )
        return
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            NavigationBar(
                modifier = Modifier.navigationBarsPadding(),
                containerColor = MaterialTheme.colorScheme.surface,
            ) {
                tabs.forEachIndexed { index, tab ->
                    NavigationBarItem(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        icon = {
                            Text(
                                text = tab.symbol,
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Normal,
                            )
                        },
                        label = { Text(tab.label) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = SageDark,
                            selectedTextColor = SageDark,
                            indicatorColor = SagePale,
                            unselectedIconColor = InkMuted,
                            unselectedTextColor = InkMuted,
                        ),
                    )
                }
            }
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize()) {
            when (selectedTab) {
                0 -> HomeScreen(
                    contentPadding = padding,
                    viewModel = homeViewModel,
                    onOpenVoice = { showVoiceCall = true },
                )
                1 -> GrowthScreen(contentPadding = padding)
                2 -> RecordScreen(contentPadding = padding)
                else -> ProfileScreen(
                    contentPadding = padding,
                    voicePreferences = voicePreferences,
                    onVoicePreferencesChange = {
                        voicePreferences = it
                        voicePreferencesStore.save(it)
                    },
                )
            }
        }
    }
}
