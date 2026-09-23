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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.viewmodel.compose.viewModel
import com.testconnection.confidence_agent.ui.screens.GrowthScreen
import com.testconnection.confidence_agent.ui.screens.AppCoverScreen
import com.testconnection.confidence_agent.ui.screens.HomeScreen
import com.testconnection.confidence_agent.ui.screens.HomeViewModel
import com.testconnection.confidence_agent.ui.screens.ProfileScreen
import com.testconnection.confidence_agent.ui.screens.RecordScreen
import com.testconnection.confidence_agent.ui.screens.VoiceCallScreen
import com.testconnection.confidence_agent.data.preferences.VoicePreferencesStore
import com.testconnection.confidence_agent.data.preferences.OnboardingStore
import com.testconnection.confidence_agent.data.model.RecordMode
import com.testconnection.confidence_agent.ui.screens.MemoryCenterScreen
import com.testconnection.confidence_agent.ui.screens.OnboardingScreen
import com.testconnection.confidence_agent.ui.theme.InkMuted
import com.testconnection.confidence_agent.ui.theme.SageDark
import com.testconnection.confidence_agent.ui.theme.SagePale
import kotlinx.coroutines.delay

private data class AppTab(val label: String, val symbol: String)

private val tabs = listOf(
    AppTab("首页", "⌂"),
    AppTab("成长", "▥"),
    AppTab("记录", "▤"),
    AppTab("我的", "♙"),
)

@Composable
fun ConfidenceAgentApp(
    externalDestination: ExternalDestination? = null,
    onExternalDestinationConsumed: () -> Unit = {},
) {
    val context = LocalContext.current
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    var showCover by rememberSaveable { mutableStateOf(true) }
    var showVoiceCall by rememberSaveable { mutableStateOf(false) }
    var showMemoryCenter by rememberSaveable { mutableStateOf(false) }
    var requestedRecordMode by remember { mutableStateOf(RecordMode.TEXT) }
    var cameraLaunchToken by remember { mutableIntStateOf(0) }
    val voicePreferencesStore = androidx.compose.runtime.remember {
        VoicePreferencesStore(context.applicationContext)
    }
    var voicePreferences by androidx.compose.runtime.remember {
        mutableStateOf(voicePreferencesStore.load())
    }
    val onboardingStore = androidx.compose.runtime.remember { OnboardingStore(context.applicationContext) }
    var showOnboarding by androidx.compose.runtime.remember { mutableStateOf(onboardingStore.shouldShow()) }
    var userProfile by androidx.compose.runtime.remember { mutableStateOf(onboardingStore.loadProfile()) }
    val displayName = userProfile?.preferredName?.value
        ?.takeUnless { it == "unknown" || it == "prefer_not_to_say" || it.isBlank() }
        ?: "你"
    val homeViewModel: HomeViewModel = viewModel()
    val homeState by homeViewModel.uiState.collectAsState()

    LaunchedEffect(Unit) {
        delay(1_800)
        showCover = false
    }

    if (showCover) {
        AppCoverScreen()
        return
    }

    if (showOnboarding) {
        OnboardingScreen(
            voicePreferences = voicePreferences,
            initialProfile = userProfile ?: com.testconnection.confidence_agent.data.model.UserProfile(),
            onComplete = {
                userProfile = it
                onboardingStore.saveProfile(it, complete = true)
                showOnboarding = false
            },
            onSkip = {
                onboardingStore.skip()
                showOnboarding = false
            },
        )
        return
    }

    if (showMemoryCenter) {
        MemoryCenterScreen(
            profile = userProfile,
            onBack = { showMemoryCenter = false },
            onRestartOnboarding = {
                onboardingStore.reset()
                showMemoryCenter = false
                showOnboarding = true
            },
        )
        return
    }

    LaunchedEffect(externalDestination) {
        externalDestination?.let {
            selectedTab = it.tab.coerceIn(0, tabs.lastIndex)
            requestedRecordMode = it.recordMode
            if (it.openCamera) cameraLaunchToken += 1
            onExternalDestinationConsumed()
        }
    }

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
                    userName = displayName,
                    onOpenVoice = { showVoiceCall = true },
                )
                1 -> GrowthScreen(contentPadding = padding)
                2 -> RecordScreen(
                    contentPadding = padding,
                    requestedMode = requestedRecordMode,
                    cameraLaunchToken = cameraLaunchToken,
                )
                else -> ProfileScreen(
                    contentPadding = padding,
                    userName = displayName,
                    voicePreferences = voicePreferences,
                    onVoicePreferencesChange = {
                        voicePreferences = it
                        voicePreferencesStore.save(it)
                    },
                    onOpenMemoryCenter = { showMemoryCenter = true },
                )
            }
        }
    }
}
