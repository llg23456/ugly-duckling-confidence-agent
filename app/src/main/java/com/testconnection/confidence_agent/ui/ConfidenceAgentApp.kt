package com.testconnection.confidence_agent.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.testconnection.confidence_agent.R
import androidx.lifecycle.viewmodel.compose.viewModel
import com.testconnection.confidence_agent.ui.screens.GrowthScreen
import com.testconnection.confidence_agent.ui.screens.GrowthViewModel
import com.testconnection.confidence_agent.ui.screens.VideoStudioScreen
import com.testconnection.confidence_agent.ui.screens.WeeklyReportScreen
import com.testconnection.confidence_agent.ui.screens.AppCoverScreen
import com.testconnection.confidence_agent.ui.screens.HomeScreen
import com.testconnection.confidence_agent.ui.screens.HomeViewModel
import com.testconnection.confidence_agent.ui.screens.ProfileScreen
import com.testconnection.confidence_agent.ui.screens.RecordScreen
import com.testconnection.confidence_agent.ui.screens.CommunityScreen
import com.testconnection.confidence_agent.ui.screens.VoiceCallScreen
import com.testconnection.confidence_agent.data.preferences.VoicePreferencesStore
import com.testconnection.confidence_agent.data.preferences.OnboardingStore
import com.testconnection.confidence_agent.data.model.RecordMode
import com.testconnection.confidence_agent.ui.screens.MemoryCenterScreen
import com.testconnection.confidence_agent.ui.screens.SupportCircleScreen
import com.testconnection.confidence_agent.ui.screens.OnboardingScreen
import com.testconnection.confidence_agent.ui.screens.DataToolsScreen
import com.testconnection.confidence_agent.ui.theme.InkMuted
import com.testconnection.confidence_agent.ui.theme.SageDark
import com.testconnection.confidence_agent.ui.theme.SagePale
import kotlinx.coroutines.delay

private data class AppTab(val destination: AppDestination, val label: String, val iconRes: Int)

private val tabs = listOf(
    AppTab(AppDestination.HOME, "首页", R.drawable.ic_nav_home),
    AppTab(AppDestination.GROWTH, "成长", R.drawable.ic_nav_growth),
    AppTab(AppDestination.COMMUNITY, "社区", R.drawable.ic_nav_community),
    AppTab(AppDestination.RECORD, "记录", R.drawable.ic_nav_record),
    AppTab(AppDestination.PROFILE, "我的", R.drawable.ic_nav_profile),
)

@Composable
fun ConfidenceAgentApp(
    externalDestination: ExternalDestination? = null,
    onExternalDestinationConsumed: () -> Unit = {},
) {
    val context = LocalContext.current
    var selectedDestination by rememberSaveable { mutableStateOf(AppDestination.HOME) }
    var communityDetailOpen by rememberSaveable { mutableStateOf(false) }
    var showCover by rememberSaveable { mutableStateOf(true) }
    var showVoiceCall by rememberSaveable { mutableStateOf(false) }
    var showMemoryCenter by rememberSaveable { mutableStateOf(false) }
    var showSupportCircle by rememberSaveable { mutableStateOf(false) }
    var showVideoStudio by rememberSaveable { mutableStateOf(false) }
    var showWeeklyReport by rememberSaveable { mutableStateOf(false) }
    var showDataTools by rememberSaveable { mutableStateOf(false) }
    var pendingCommunityVideoPath by rememberSaveable { mutableStateOf<String?>(null) }
    var sourceMessageId by rememberSaveable { mutableStateOf<Long?>(null) }
    var requestedRecordMode by remember { mutableStateOf(RecordMode.TEXT) }
    var requestedRecordDateEpochDay by rememberSaveable { mutableStateOf<Long?>(null) }
    var requestedEditRecord by remember { mutableStateOf<com.testconnection.confidence_agent.data.model.RecordDraft?>(null) }
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
    val growthViewModel: GrowthViewModel = viewModel()
    val homeState by homeViewModel.uiState.collectAsState()
    val growthState by growthViewModel.state.collectAsState()

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
            onOpenSource = { sourceId ->
                sourceMessageId = sourceId
                selectedDestination = AppDestination.HOME
                showMemoryCenter = false
            },
            onRestartOnboarding = {
                userProfile = onboardingStore.loadProfile()
                onboardingStore.reset()
                showMemoryCenter = false
                showOnboarding = true
            },
            onProfileUpdated = { updated ->
                userProfile = updated
                onboardingStore.saveProfile(updated, complete = true, reason = "画像更新")
            },
            onOpenFeedback = { showMemoryCenter = false; showSupportCircle = true },
            onEditRecord = { record ->
                requestedRecordMode = record.mode
                requestedRecordDateEpochDay = java.time.Instant.ofEpochMilli(record.createdAt)
                    .atZone(java.time.ZoneId.systemDefault()).toLocalDate().toEpochDay()
                requestedEditRecord = record
                selectedDestination = AppDestination.RECORD
                showMemoryCenter = false
            },
        )
        return
    }

    if (showSupportCircle) {
        SupportCircleScreen(onBack = { showSupportCircle = false })
        return
    }

    if (showVideoStudio) {
        val sourceIds = buildSet {
            addAll(growthState.review?.sourceEventIds.orEmpty())
            growthState.dailyReviews.forEach { day ->
                addAll(day.review?.sourceEventIds.orEmpty())
            }
        }
        VideoStudioScreen(
            events = growthState.events.filter { it.id in sourceIds },
            sourceEventIds = sourceIds,
            recordIds = growthState.recordIds,
            onBack = { showVideoStudio = false },
            onPublishToCommunity = { file ->
                pendingCommunityVideoPath = file.absolutePath
                showVideoStudio = false
                communityDetailOpen = false
                selectedDestination = AppDestination.COMMUNITY
            },
        )
        return
    }

    if (showWeeklyReport) {
        WeeklyReportScreen(
            review = growthState.review,
            dailyReviews = growthState.dailyReviews,
            events = growthState.events,
            onBack = { showWeeklyReport = false },
            onShare = { showVideoStudio = true },
        )
        return
    }

    if (showDataTools) {
        DataToolsScreen(
            state = growthState,
            onCreateExamWeekDemoData = {
                growthViewModel.createExamWeekDemoData { start ->
                    showDataTools = false
                    selectedDestination = AppDestination.GROWTH
                    growthViewModel.openWeek(start)
                }
            },
            onCreateFourWeekDemoData = growthViewModel::createDemoData,
            onClearDemoData = growthViewModel::clearDemoData,
            onAllDataDeleted = {
                onboardingStore.reset()
                userProfile = null
                showDataTools = false
                showOnboarding = true
                growthViewModel.forceRefresh()
            },
            onBack = { showDataTools = false },
        )
        return
    }

    LaunchedEffect(externalDestination) {
        externalDestination?.let {
            selectedDestination = it.destination
            communityDetailOpen = false
            requestedRecordMode = it.recordMode
            requestedRecordDateEpochDay = null
            requestedEditRecord = null
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
            if (!communityDetailOpen) Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surface,
                shadowElevation = 10.dp,
            ) {
                NavigationBar(
                    modifier = Modifier.fillMaxWidth(),
                    containerColor = MaterialTheme.colorScheme.surface,
                    tonalElevation = 0.dp,
                ) {
                    tabs.forEach { tab ->
                        NavigationBarItem(
                            selected = selectedDestination == tab.destination,
                            onClick = {
                                communityDetailOpen = false
                                if (tab.destination == AppDestination.RECORD) {
                                    requestedRecordMode = RecordMode.TEXT
                                    requestedRecordDateEpochDay = null
                                    requestedEditRecord = null
                                }
                                selectedDestination = tab.destination
                            },
                            icon = {
                                Image(
                                    painter = painterResource(tab.iconRes),
                                    contentDescription = tab.label,
                                    modifier = Modifier.size(26.dp),
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
            }
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize()) {
            when (selectedDestination) {
                AppDestination.HOME -> HomeScreen(
                    contentPadding = padding,
                    viewModel = homeViewModel,
                    userName = displayName,
                    onOpenVoice = { showVoiceCall = true },
                    onOpenRecordSource = { recordId ->
                        growthViewModel.recordForEdit(recordId) { record ->
                            if (record != null) {
                                requestedRecordMode = record.mode
                                requestedRecordDateEpochDay = java.time.Instant.ofEpochMilli(record.createdAt)
                                    .atZone(java.time.ZoneId.systemDefault()).toLocalDate().toEpochDay()
                                requestedEditRecord = record
                                selectedDestination = AppDestination.RECORD
                            }
                        }
                    },
                    sourceMessageId = sourceMessageId,
                    onSourceLocated = { sourceMessageId = null },
                )
                AppDestination.GROWTH -> GrowthScreen(
                    contentPadding = padding,
                    onOpenSource = { sourceMessageId = it; selectedDestination = AppDestination.HOME },
                    onOpenFeedback = { showSupportCircle = true },
                    growthViewModel = growthViewModel,
                    onOpenWeeklyReport = { showWeeklyReport = true },
                    onAddRecord = { date ->
                        requestedRecordMode = RecordMode.TEXT
                        requestedRecordDateEpochDay = date.toEpochDay()
                        requestedEditRecord = null
                        selectedDestination = AppDestination.RECORD
                    },
                    onEditRecord = { record ->
                        requestedRecordMode = record.mode
                        requestedRecordDateEpochDay = java.time.Instant.ofEpochMilli(record.createdAt)
                            .atZone(java.time.ZoneId.systemDefault()).toLocalDate().toEpochDay()
                        requestedEditRecord = record
                        selectedDestination = AppDestination.RECORD
                    },
                )
                AppDestination.COMMUNITY -> CommunityScreen(
                    contentPadding = padding,
                    userName = displayName,
                    onDetailVisibilityChanged = { communityDetailOpen = it },
                    initialVideoPath = pendingCommunityVideoPath,
                    onInitialVideoConsumed = { pendingCommunityVideoPath = null },
                )
                AppDestination.RECORD -> RecordScreen(
                    contentPadding = padding,
                    requestedMode = requestedRecordMode,
                    cameraLaunchToken = cameraLaunchToken,
                    requestedDateEpochDay = requestedRecordDateEpochDay,
                    requestedEditRecord = requestedEditRecord,
                )
                AppDestination.PROFILE -> ProfileScreen(
                    contentPadding = padding,
                    userName = displayName,
                    voicePreferences = voicePreferences,
                    onVoicePreferencesChange = {
                        voicePreferences = it
                        voicePreferencesStore.save(it)
                    },
                    onOpenMemoryCenter = { userProfile = onboardingStore.loadProfile(); showMemoryCenter = true },
                    onOpenSupportCircle = { showSupportCircle = true },
                    onOpenDataTools = { showDataTools = true },
                    onServerEndpointChanged = {
                        homeViewModel.refreshHistory()
                        if (selectedDestination == AppDestination.GROWTH) growthViewModel.forceRefresh()
                    },
                )
            }
        }
    }
}
