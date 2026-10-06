package com.testconnection.confidence_agent.ui.screens

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.text.format.DateUtils
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.testconnection.confidence_agent.R
import com.testconnection.confidence_agent.data.model.CommunityDraft
import com.testconnection.confidence_agent.data.model.CommunityPost
import com.testconnection.confidence_agent.data.model.CommunityRecordImport
import com.testconnection.confidence_agent.data.model.RecordDraft
import com.testconnection.confidence_agent.data.model.RecordMode
import com.testconnection.confidence_agent.data.repository.CommunityPostStore
import com.testconnection.confidence_agent.data.repository.LocalRecordRepository
import com.testconnection.confidence_agent.ui.components.AppButtonShape
import com.testconnection.confidence_agent.ui.components.DuckArt
import com.testconnection.confidence_agent.ui.theme.Cream
import com.testconnection.confidence_agent.ui.theme.InkMuted
import com.testconnection.confidence_agent.ui.theme.SageDark
import com.testconnection.confidence_agent.ui.theme.SagePale
import com.testconnection.confidence_agent.ui.theme.TerracottaPale
import com.testconnection.confidence_agent.ui.theme.WarmOutline
import com.testconnection.confidence_agent.ui.theme.WarmWhite
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class CommunityFeedEntry(
    val post: CommunityPost,
    @DrawableRes val illustration: Int? = null,
)

private enum class CommunityMediaKind { AVATAR, POST_IMAGE }

private data class CommunityMediaTarget(
    val postId: String,
    val kind: CommunityMediaKind,
    val usesOwnProfileAvatar: Boolean = false,
)

private const val OWN_PROFILE_TARGET = "community-profile"

@Composable
fun CommunityScreen(
    contentPadding: PaddingValues,
    userName: String,
    onDetailVisibilityChanged: (Boolean) -> Unit = {},
) {
    val base = MaterialTheme.typography
    MaterialTheme(
        colorScheme = MaterialTheme.colorScheme,
        shapes = MaterialTheme.shapes,
        typography = base.copy(
            displaySmall = base.displaySmall.copy(fontFamily = FontFamily.Serif),
            headlineMedium = base.headlineMedium.copy(fontFamily = FontFamily.Serif),
            headlineSmall = base.headlineSmall.copy(fontFamily = FontFamily.Serif),
            titleLarge = base.titleLarge.copy(fontFamily = FontFamily.Serif),
            titleMedium = base.titleMedium.copy(fontFamily = FontFamily.Serif),
            bodyLarge = base.bodyLarge.copy(fontFamily = FontFamily.Serif),
            bodyMedium = base.bodyMedium.copy(fontFamily = FontFamily.Serif),
            bodySmall = base.bodySmall.copy(fontFamily = FontFamily.Serif),
            labelLarge = base.labelLarge.copy(fontFamily = FontFamily.Serif),
            labelMedium = base.labelMedium.copy(fontFamily = FontFamily.Serif),
            labelSmall = base.labelSmall.copy(fontFamily = FontFamily.Serif),
        ),
    ) {
        CommunityScreenContent(contentPadding, userName, onDetailVisibilityChanged)
    }
}

@Composable
private fun CommunityScreenContent(
    contentPadding: PaddingValues,
    userName: String,
    onDetailVisibilityChanged: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    val store = remember { CommunityPostStore(context) }
    var ownPosts by remember { mutableStateOf(store.loadOwnPosts()) }
    var avatarPath by remember { mutableStateOf(store.avatarPath()) }
    var query by remember { mutableStateOf("") }
    var showComposer by remember { mutableStateOf(false) }
    var selectedPost by remember { mutableStateOf<CommunityFeedEntry?>(null) }
    var pendingDelete by remember { mutableStateOf<CommunityPost?>(null) }
    var sourceDialogTarget by remember { mutableStateOf<CommunityMediaTarget?>(null) }
    var activeMediaTarget by remember { mutableStateOf<CommunityMediaTarget?>(null) }
    var pendingMediaUri by remember { mutableStateOf<Uri?>(null) }
    var pendingMediaFile by remember { mutableStateOf<File?>(null) }
    var mediaNotice by remember { mutableStateOf<String?>(null) }
    var likeVersion by remember { mutableIntStateOf(0) }
    var mediaVersion by remember { mutableIntStateOf(0) }
    val demoEntries = remember { demoCommunityPosts() }
    val scope = rememberCoroutineScope()

    fun acceptMedia(uri: Uri, deleteAfterRead: Boolean = false) {
        val target = activeMediaTarget ?: return
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    when {
                        target.kind == CommunityMediaKind.AVATAR && target.usesOwnProfileAvatar -> store.importAvatar(uri)
                        target.kind == CommunityMediaKind.AVATAR -> store.importCustomAvatar(target.postId, uri)
                        else -> store.importCustomPostImage(target.postId, uri)
                    }
                }
            }.onSuccess { path ->
                if (target.usesOwnProfileAvatar) avatarPath = path
                mediaVersion++
                mediaNotice = null
            }.onFailure { mediaNotice = it.message ?: "图片读取失败，请重新选择。" }
            if (deleteAfterRead) pendingMediaFile?.delete()
            pendingMediaFile = null
            pendingMediaUri = null
            activeMediaTarget = null
        }
    }

    val mediaPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) acceptMedia(uri) else activeMediaTarget = null
    }
    val takeMediaPhoto = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        val uri = pendingMediaUri
        if (success && uri != null) acceptMedia(uri, deleteAfterRead = true)
        else {
            pendingMediaFile?.delete()
            pendingMediaFile = null
            pendingMediaUri = null
            activeMediaTarget = null
        }
    }
    fun launchMediaCamera() {
        val target = activeMediaTarget ?: return
        val directory = File(context.cacheDir, "camera").apply { mkdirs() }
        val prefix = if (target.kind == CommunityMediaKind.AVATAR) "community-avatar-" else "community-post-"
        val file = File.createTempFile(prefix, ".jpg", directory)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        pendingMediaFile = file
        pendingMediaUri = uri
        takeMediaPhoto.launch(uri)
    }
    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) launchMediaCamera() else mediaNotice = "需要相机权限才能拍照，也可以从相册选择。"
    }
    fun requestMediaCamera() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            launchMediaCamera()
        } else {
            cameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    sourceDialogTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { sourceDialogTarget = null },
            title = { Text(if (target.kind == CommunityMediaKind.AVATAR) "更换头像" else "更换帖子配图") },
            text = { Text("修改只保存在本机。你可以拍一张新照片，或从相册选择。") },
            confirmButton = {
                TextButton(onClick = {
                    activeMediaTarget = target
                    sourceDialogTarget = null
                    requestMediaCamera()
                }) { Text("拍照", color = SageDark) }
            },
            dismissButton = {
                TextButton(onClick = {
                    activeMediaTarget = target
                    sourceDialogTarget = null
                    mediaPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }) { Text("从相册选择", color = SageDark) }
            },
        )
    }

    pendingDelete?.let { post ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除这条分享？") },
            text = { Text("只会删除社区中的副本，不会影响原始生活记录。") },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("取消") } },
            confirmButton = {
                TextButton(onClick = {
                    store.delete(post.id)
                    ownPosts = store.loadOwnPosts()
                    if (selectedPost?.post?.id == post.id) {
                        selectedPost = null
                        onDetailVisibilityChanged(false)
                    }
                    pendingDelete = null
                }) { Text("删除") }
            },
        )
    }

    selectedPost?.let { entry ->
        @Suppress("UNUSED_VARIABLE") val currentMediaVersion = mediaVersion
        CommunityPostDetailScreen(
            contentPadding = contentPadding,
            entry = entry,
            avatarPath = if (entry.post.isMine) avatarPath else store.customAvatarPath(entry.post.id),
            customImagePath = store.customPostImagePath(entry.post.id),
            liked = store.isLiked(entry.post.id),
            mediaNotice = mediaNotice,
            onBack = {
                selectedPost = null
                onDetailVisibilityChanged(false)
            },
            onAvatarClick = {
                sourceDialogTarget = CommunityMediaTarget(entry.post.id, CommunityMediaKind.AVATAR, entry.post.isMine)
            },
            onImageClick = { sourceDialogTarget = CommunityMediaTarget(entry.post.id, CommunityMediaKind.POST_IMAGE) },
            onLike = { store.toggleLike(entry.post.id); likeVersion++ },
            onShare = { shareCommunityPost(context, entry.post) },
            onDelete = if (entry.post.isMine) ({ pendingDelete = entry.post }) else null,
        )
        return
    }

    if (showComposer) {
        CommunityComposerScreen(
            contentPadding = contentPadding,
            userName = userName,
            store = store,
            avatarPath = avatarPath,
            onAvatarClick = {
                sourceDialogTarget = CommunityMediaTarget(OWN_PROFILE_TARGET, CommunityMediaKind.AVATAR, true)
            },
            onBack = { showComposer = false },
            onPublished = {
                ownPosts = store.loadOwnPosts()
                showComposer = false
            },
        )
        return
    }
    val normalizedQuery = query.trim()
    @Suppress("UNUSED_VARIABLE") val currentLikeVersion = likeVersion
    @Suppress("UNUSED_VARIABLE") val currentMediaVersion = mediaVersion
    val feed = (ownPosts.map { CommunityFeedEntry(it) } + demoEntries).filter { entry ->
        normalizedQuery.isBlank() || listOf(entry.post.authorName, entry.post.title, entry.post.content, entry.post.topic.orEmpty())
            .any { it.contains(normalizedQuery, ignoreCase = true) }
    }

    Box(
        Modifier.fillMaxSize()
            .padding(
                top = contentPadding.calculateTopPadding(),
                bottom = contentPadding.calculateBottomPadding(),
            )
            .background(Cream),
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 92.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item { CommunityHeader() }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        modifier = Modifier.weight(1f).height(50.dp),
                        singleLine = true,
                        leadingIcon = { Text("⌕", fontSize = 23.sp, lineHeight = 24.sp, color = InkMuted) },
                        placeholder = { Text("搜一搜社区内容", fontSize = 15.sp, lineHeight = 18.sp) },
                        shape = RoundedCornerShape(16.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            unfocusedBorderColor = Color.Transparent,
                            focusedBorderColor = SageDark,
                            unfocusedContainerColor = WarmWhite,
                            focusedContainerColor = WarmWhite,
                        ),
                    )
                    Button(
                        onClick = { showComposer = true },
                        modifier = Modifier.widthIn(min = 70.dp).height(42.dp),
                        shape = RoundedCornerShape(14.dp),
                        contentPadding = PaddingValues(horizontal = 14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = SageDark),
                        elevation = ButtonDefaults.buttonElevation(0.dp),
                    ) { Text("发布") }
                }
            }
            if (feed.isEmpty()) {
                item {
                    CommunityFlatCard {
                        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("没有找到相关分享", style = MaterialTheme.typography.titleLarge)
                            Text("换个关键词试试，或者写下你的第一条分享。", color = InkMuted)
                        }
                    }
                }
            } else {
                items(feed, key = { it.post.id }) { entry ->
                    CommunityPostCard(
                        entry = entry,
                        avatarPath = if (entry.post.isMine) avatarPath else store.customAvatarPath(entry.post.id),
                        customImagePath = store.customPostImagePath(entry.post.id),
                        liked = store.isLiked(entry.post.id),
                        onOpen = {
                            selectedPost = entry
                            onDetailVisibilityChanged(true)
                        },
                        onAvatarClick = {
                            sourceDialogTarget = CommunityMediaTarget(entry.post.id, CommunityMediaKind.AVATAR, entry.post.isMine)
                        },
                        onImageClick = { sourceDialogTarget = CommunityMediaTarget(entry.post.id, CommunityMediaKind.POST_IMAGE) },
                        onLike = { store.toggleLike(entry.post.id); likeVersion++ },
                        onComments = {
                            selectedPost = entry
                            onDetailVisibilityChanged(true)
                        },
                        onShare = { shareCommunityPost(context, entry.post) },
                    )
                }
            }
        }
        FloatingActionButton(
            onClick = { showComposer = true },
            modifier = Modifier.align(Alignment.BottomCenter)
                .padding(bottom = 16.dp),
            shape = CircleShape,
            containerColor = MaterialTheme.colorScheme.onSurface,
            contentColor = MaterialTheme.colorScheme.surface,
        ) { Text("＋", style = MaterialTheme.typography.headlineMedium) }
    }
}

@Composable
private fun CommunityHeader() {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("社区", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
            Text("在这里，和同路人一起变好", style = MaterialTheme.typography.bodyLarge, color = InkMuted)
        }
        Box(Modifier.width(184.dp).height(104.dp)) {
            Surface(
                modifier = Modifier.align(Alignment.CenterStart).width(118.dp),
                color = SagePale,
                shape = RoundedCornerShape(22.dp),
            ) {
                Text(
                    "分享 · 陪伴\n成长 · 更好的自己",
                    Modifier.padding(horizontal = 13.dp, vertical = 11.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = SageDark,
                )
            }
            Image(
                painter = painterResource(R.drawable.duck_community_header),
                contentDescription = "探出头陪伴分享的小鸭",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
            )
        }
    }
}

@Composable
private fun CommunityFlatCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    if (onClick == null) {
        Surface(
            modifier = modifier.fillMaxWidth(),
            color = WarmWhite,
            shape = RoundedCornerShape(18.dp),
            border = BorderStroke(1.dp, WarmOutline),
        ) { content() }
    } else {
        Surface(
            onClick = onClick,
            modifier = modifier.fillMaxWidth(),
            color = WarmWhite,
            shape = RoundedCornerShape(18.dp),
            border = BorderStroke(1.dp, WarmOutline),
        ) { content() }
    }
}

@Composable
private fun CommunityAvatar(
    path: String?,
    authorName: String,
    size: Dp,
    onClick: (() -> Unit)? = null,
) {
    val bitmap = remember(path) { path?.let { BitmapFactory.decodeFile(it)?.asImageBitmap() } }
    val clickableModifier = if (onClick == null) Modifier else Modifier.clickable(onClick = onClick)
    Box(
        Modifier.size(size).clip(CircleShape).background(SagePale).then(clickableModifier),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(bitmap, "$authorName 的社区头像", Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            Image(
                painter = painterResource(R.drawable.duck_community_avatar),
                contentDescription = "$authorName 的默认小鸭头像",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        }
    }
}

@Composable
private fun CommunityPostCard(
    entry: CommunityFeedEntry,
    avatarPath: String?,
    customImagePath: String?,
    liked: Boolean,
    onOpen: () -> Unit,
    onAvatarClick: (() -> Unit)?,
    onImageClick: () -> Unit,
    onLike: () -> Unit,
    onComments: () -> Unit,
    onShare: () -> Unit,
) {
    val post = entry.post
    val displayedImagePath = customImagePath ?: post.imagePath
    val displayedIllustration = entry.illustration.takeIf { customImagePath == null }
    CommunityFlatCard(onClick = onOpen) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CommunityAvatar(avatarPath, post.authorName, 44.dp, onAvatarClick)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        Text(post.authorName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Surface(color = if (post.isMine) TerracottaPale else SagePale, shape = RoundedCornerShape(8.dp)) {
                            Text(post.authorBadge, Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                                style = MaterialTheme.typography.bodySmall, color = SageDark)
                        }
                    }
                    Text(relativeTime(post.createdAt), style = MaterialTheme.typography.bodySmall, color = InkMuted)
                }
            }
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                if (maxWidth < 250.dp && (displayedImagePath != null || displayedIllustration != null)) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        CommunityPostText(post)
                        CommunityPostImage(displayedImagePath, displayedIllustration,
                            Modifier.fillMaxWidth().height(142.dp), onImageClick)
                    }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                        Column(Modifier.weight(1f)) { CommunityPostText(post) }
                        CommunityPostImage(displayedImagePath, displayedIllustration, Modifier.size(98.dp), onImageClick)
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                post.topic?.let { topic ->
                    Surface(color = SagePale, shape = AppButtonShape) {
                        Text("# $topic", Modifier.padding(horizontal = 12.dp, vertical = 7.dp), color = SageDark,
                            style = MaterialTheme.typography.bodyMedium)
                    }
                }
                if (post.sourceRecordId != null) {
                    Spacer(Modifier.width(8.dp))
                    Text("来自我的记录", style = MaterialTheme.typography.bodySmall, color = InkMuted)
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                CommunityActionButton(
                    iconRes = R.drawable.ic_community_like,
                    contentDescription = if (liked) "取消点赞" else "点赞",
                    count = post.likeCount + if (liked) 1 else 0,
                    active = liked,
                    onClick = onLike,
                )
                CommunityActionButton(
                    iconRes = R.drawable.ic_community_comment,
                    contentDescription = "查看评论",
                    count = maxOf(post.commentCount, 3),
                    onClick = onComments,
                )
                CommunityActionButton(
                    iconRes = R.drawable.ic_community_share,
                    contentDescription = "分享",
                    count = post.shareCount,
                    onClick = onShare,
                )
            }
        }
    }
}

@Composable
private fun CommunityActionButton(
    @DrawableRes iconRes: Int,
    contentDescription: String,
    count: Int,
    active: Boolean = false,
    onClick: () -> Unit,
) {
    TextButton(onClick = onClick, contentPadding = PaddingValues(horizontal = 7.dp, vertical = 5.dp)) {
        Image(
            painter = painterResource(iconRes),
            contentDescription = contentDescription,
            modifier = Modifier.size(22.dp),
            colorFilter = if (active) ColorFilter.tint(Color(0xFFE04753)) else null,
        )
        Spacer(Modifier.width(4.dp))
        Text(count.toString(), color = if (active) Color(0xFFE04753) else InkMuted)
    }
}

@Composable
private fun CommunityPostText(post: CommunityPost) {
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Text(post.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        if (post.content.isNotBlank()) Text(post.content, maxLines = 4, overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun CommunityPostImage(
    path: String?,
    @DrawableRes illustration: Int?,
    modifier: Modifier,
    onClick: (() -> Unit)? = null,
) {
    val bitmap = remember(path) { path?.let { BitmapFactory.decodeFile(it)?.asImageBitmap() } }
    val interactive = if (onClick == null) modifier else modifier.clickable(onClick = onClick)
    when {
        bitmap != null -> Image(bitmap, "分享中的照片", interactive.clip(RoundedCornerShape(18.dp)), contentScale = ContentScale.Crop)
        illustration != null -> Image(painterResource(illustration), "分享插画",
            interactive.clip(RoundedCornerShape(18.dp)).background(SagePale), contentScale = ContentScale.Crop)
    }
}

private data class CommunityComment(
    val author: String,
    val badge: String,
    val time: String,
    val content: String,
    val likes: Int,
    val color: Color,
)

@Composable
private fun CommunityPostDetailScreen(
    contentPadding: PaddingValues,
    entry: CommunityFeedEntry,
    avatarPath: String?,
    customImagePath: String?,
    liked: Boolean,
    mediaNotice: String?,
    onBack: () -> Unit,
    onAvatarClick: (() -> Unit)?,
    onImageClick: () -> Unit,
    onLike: () -> Unit,
    onShare: () -> Unit,
    onDelete: (() -> Unit)?,
) {
    BackHandler(onBack = onBack)
    val post = entry.post
    val displayedImagePath = customImagePath ?: post.imagePath
    val displayedIllustration = entry.illustration.takeIf { customImagePath == null }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val comments = remember(post.id) { demoComments(post) }
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize().background(Cream).statusBarsPadding().navigationBarsPadding(),
        contentPadding = PaddingValues(
            start = 20.dp,
            top = 12.dp + contentPadding.calculateTopPadding(),
            end = 20.dp,
            bottom = 28.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack, contentPadding = PaddingValues(horizontal = 2.dp, vertical = 8.dp)) {
                    Text("‹", style = MaterialTheme.typography.displaySmall, color = SageDark)
                }
                Spacer(Modifier.width(8.dp))
                CommunityAvatar(avatarPath, post.authorName, 48.dp, onAvatarClick)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(post.authorName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Surface(color = if (post.isMine) TerracottaPale else SagePale, shape = RoundedCornerShape(8.dp)) {
                        Text(
                            post.authorBadge,
                            Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = SageDark,
                        )
                    }
                }
                onDelete?.let { TextButton(onClick = it, contentPadding = PaddingValues(horizontal = 7.dp)) { Text("···", color = InkMuted) } }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("点击头像或配图可从拍照、相册更换", style = MaterialTheme.typography.bodySmall, color = InkMuted)
            }
            mediaNotice?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Color(0xFFC65B48)) }
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(13.dp)) {
                Text(post.title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Text("${relativeTime(post.createdAt)} · 来自小丑鸭社区", style = MaterialTheme.typography.bodyMedium, color = InkMuted)
                if (displayedImagePath != null || displayedIllustration != null) {
                    CommunityPostImage(
                        displayedImagePath,
                        displayedIllustration,
                        Modifier.fillMaxWidth().height(238.dp),
                        onImageClick,
                    )
                }
                if (post.content.isNotBlank()) {
                    Text(post.content, style = MaterialTheme.typography.bodyLarge)
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    post.topic?.let { topic ->
                        Surface(color = SagePale, shape = RoundedCornerShape(12.dp)) {
                            Text("# $topic", Modifier.padding(horizontal = 12.dp, vertical = 7.dp), color = SageDark)
                        }
                    }
                    if (post.sourceRecordId != null) Text("来自我的记录", style = MaterialTheme.typography.bodySmall, color = InkMuted)
                }
            }
        }

        item {
            HorizontalDivider(color = WarmOutline)
            Row(
                Modifier.fillMaxWidth().padding(top = 5.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CommunityActionButton(
                    iconRes = R.drawable.ic_community_like,
                    contentDescription = if (liked) "取消点赞" else "点赞",
                    count = post.likeCount + if (liked) 1 else 0,
                    active = liked,
                    onClick = onLike,
                )
                CommunityActionButton(
                    iconRes = R.drawable.ic_community_comment,
                    contentDescription = "查看评论",
                    count = maxOf(post.commentCount, comments.size),
                    onClick = { scope.launch { listState.animateScrollToItem(3) } },
                )
                CommunityActionButton(
                    iconRes = R.drawable.ic_community_share,
                    contentDescription = "分享",
                    count = post.shareCount,
                    onClick = onShare,
                )
            }
            HorizontalDivider(color = WarmOutline)
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(4.dp, 22.dp).background(SageDark, RoundedCornerShape(2.dp)))
                    Spacer(Modifier.width(9.dp))
                    Text("评论 (${maxOf(post.commentCount, comments.size)})", style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.weight(1f))
                    Text("本机演示讨论", style = MaterialTheme.typography.bodySmall, color = InkMuted)
                }
                comments.forEach { comment -> CommunityCommentRow(comment) }
            }
        }
    }
}

@Composable
private fun CommunityCommentRow(comment: CommunityComment) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Box(
            Modifier.size(42.dp).clip(CircleShape).background(comment.color),
            contentAlignment = Alignment.Center,
        ) {
            Text(comment.author.take(1), fontWeight = FontWeight.Bold, color = SageDark)
        }
        Spacer(Modifier.width(11.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                Text(comment.author, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Surface(color = SagePale, shape = RoundedCornerShape(7.dp)) {
                    Text(comment.badge, Modifier.padding(horizontal = 6.dp, vertical = 2.dp), style = MaterialTheme.typography.labelSmall, color = SageDark)
                }
            }
            Text(comment.time, style = MaterialTheme.typography.bodySmall, color = InkMuted)
            Text(comment.content, style = MaterialTheme.typography.bodyLarge)
            Text("♡ ${comment.likes}", style = MaterialTheme.typography.bodySmall, color = InkMuted)
            HorizontalDivider(Modifier.padding(top = 5.dp), color = WarmOutline)
        }
    }
}

private fun demoComments(post: CommunityPost): List<CommunityComment> {
    val topicReply = when {
        post.topic?.contains("运动") == true -> "学习之外还能照顾身体，这种节奏比一味硬撑更难得。今晚我也准备去走一走。"
        post.topic?.contains("学习") == true || post.title.contains("学习") -> "把问题整理清楚再去请教，这个方法很实用。我也准备列一张自己的问题清单。"
        post.sourceRecordId != null -> "从一条真实记录慢慢整理成分享，读起来很真诚。谢谢你愿意把这一刻留下来。"
        else -> "能把当时的感受说得这么具体，已经是在认真看见自己了。"
    }
    return listOf(
        CommunityComment("慢慢来", "同路人", "12分钟前", topicReply, 18, Color(0xFFE6EFE8)),
        CommunityComment("小路同学", "表达练习中", "26分钟前", "我也经历过类似的起伏。不是每天都状态很好，但愿意重新开始就已经在往前走。", 11, Color(0xFFF5E7DE)),
        CommunityComment("向前一点点", "行动派", "1小时前", "先完成一个很小的步骤，再决定下一步。这个思路让我轻松了不少，给你加油。", 9, Color(0xFFE8E7F3)),
    )
}

private fun shareCommunityPost(context: android.content.Context, post: CommunityPost) {
    val share = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, "${post.title}\n${post.content}\n——来自小丑鸭社区")
    }
    context.startActivity(Intent.createChooser(share, "分享这条内容"))
}

@Composable
private fun CommunityComposerScreen(
    contentPadding: PaddingValues,
    userName: String,
    store: CommunityPostStore,
    avatarPath: String?,
    onAvatarClick: () -> Unit,
    onBack: () -> Unit,
    onPublished: () -> Unit,
) {
    val context = LocalContext.current
    val records = remember { LocalRecordRepository(context).load().filter { it.status == "saved" } }
    var draft by remember { mutableStateOf(CommunityDraft()) }
    var showRecordPicker by remember { mutableStateOf(false) }
    var publishing by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    if (showRecordPicker) {
        RecordImportDialog(
            records = records,
            onDismiss = { showRecordPicker = false },
            onSelect = { record ->
                CommunityRecordImport.from(record)?.let { draft = it }
                showRecordPicker = false
            },
        )
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(Cream)
            .padding(
                top = contentPadding.calculateTopPadding(),
                bottom = contentPadding.calculateBottomPadding(),
            )
            .imePadding(),
        contentPadding = PaddingValues(start = 20.dp, top = 18.dp, end = 20.dp, bottom = 40.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text("‹ 返回") }
                Column(Modifier.weight(1f)) {
                    Text("发布分享", style = MaterialTheme.typography.headlineMedium)
                    Text("把真实经历整理成愿意表达的一小步", style = MaterialTheme.typography.bodyMedium, color = InkMuted)
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CommunityAvatar(avatarPath, userName, 48.dp, onAvatarClick)
                    Text("换头像", style = MaterialTheme.typography.labelSmall, color = SageDark)
                }
            }
        }
        item {
            CommunityFlatCard {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = { showRecordPicker = true }, modifier = Modifier.fillMaxWidth(), shape = AppButtonShape) {
                        Text("从我的记录导入")
                    }
                    Text("也可以直接在下面写。导入后仍可修改，只有确认发布才会出现在社区。",
                        style = MaterialTheme.typography.bodyMedium, color = InkMuted)
                    OutlinedTextField(
                        value = draft.title,
                        onValueChange = { draft = draft.copy(title = it) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("标题") },
                        placeholder = { Text("给这一刻起个名字") },
                        shape = RoundedCornerShape(20.dp),
                    )
                    OutlinedTextField(
                        value = draft.content,
                        onValueChange = { draft = draft.copy(content = it) },
                        modifier = Modifier.fillMaxWidth().height(190.dp),
                        label = { Text("想分享的话") },
                        placeholder = { Text("发生了什么？你看见了自己怎样的变化？") },
                        shape = RoundedCornerShape(20.dp),
                    )
                    draft.sourcePhotoPath?.let { path ->
                        val bitmap = remember(path) { BitmapFactory.decodeFile(path)?.asImageBitmap() }
                        if (bitmap != null) {
                            Image(bitmap, "准备发布的照片", Modifier.fillMaxWidth().height(210.dp).clip(RoundedCornerShape(20.dp)),
                                contentScale = ContentScale.Crop)
                            TextButton(onClick = { draft = draft.copy(sourcePhotoPath = null) }) { Text("不带照片发布") }
                        }
                    }
                    draft.sourceRecordId?.let {
                        Surface(color = SagePale, shape = RoundedCornerShape(14.dp)) {
                            Text("已导入一条本机记录；发布后会保存独立副本。",
                                Modifier.fillMaxWidth().padding(12.dp), style = MaterialTheme.typography.bodyMedium, color = SageDark)
                        }
                    }
                    notice?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    Button(
                        onClick = {
                            publishing = true
                            notice = null
                            scope.launch {
                                val result = withContext(Dispatchers.IO) { runCatching { store.publish(draft, userName) } }
                                publishing = false
                                result.onSuccess { onPublished() }
                                    .onFailure { notice = it.message ?: "暂时无法发布" }
                            }
                        },
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                        enabled = !publishing,
                        shape = AppButtonShape,
                        colors = ButtonDefaults.buttonColors(containerColor = SageDark),
                    ) {
                        if (publishing) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        else Text("确认发布", fontWeight = FontWeight.SemiBold)
                    }
                    Text("这是本机演示社区，不会自动上传到公开网络。", style = MaterialTheme.typography.bodySmall, color = InkMuted)
                }
            }
        }
    }
}

@Composable
private fun RecordImportDialog(
    records: List<RecordDraft>,
    onDismiss: () -> Unit,
    onSelect: (RecordDraft) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择一条记录") },
        text = {
            if (records.isEmpty()) {
                Text("还没有可发布的已保存记录。先到记录页写一句、说一句或拍一张吧。")
            } else {
                LazyColumn(Modifier.heightIn(max = 430.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(records, key = { it.id }) { record ->
                        val content = record.photoComment.ifBlank { record.text }.ifBlank { record.aiDescription }.ifBlank { "一张照片记录" }
                        Surface(onClick = { onSelect(record) }, color = SagePale, shape = RoundedCornerShape(16.dp)) {
                            Column(Modifier.fillMaxWidth().padding(13.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(when (record.mode) { RecordMode.TEXT -> "文字记录"; RecordMode.VOICE -> "语音转写"; RecordMode.PHOTO -> "照片记录" },
                                    style = MaterialTheme.typography.labelLarge, color = SageDark)
                                Text(content, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

private fun relativeTime(createdAt: Long): String =
    DateUtils.getRelativeTimeSpanString(createdAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()

private fun demoCommunityPosts(): List<CommunityFeedEntry> {
    val now = System.currentTimeMillis()
    return listOf(
        CommunityFeedEntry(
            CommunityPost("demo-1", "木淮南", "坚持者", "学习能力和愿意求助，可以一起成长",
                "今天终于把目标院校的问题整理清楚，准备向师兄师姐请教。把问题说具体以后，焦虑也没有那么大了。",
                topic = "学习与求助", createdAt = now - 2 * 60 * 60 * 1000, likeCount = 24, commentCount = 12, shareCount = 3),
            R.drawable.duck_story_guidance,
        ),
        CommunityFeedEntry(
            CommunityPost("demo-2", "五月雨", "治愈系", "我又愿意翻开书了",
                "没有逼自己完成整张计划表，只学了二十分钟。小鸭说，重新开始也算向前。",
                topic = "今日记录", createdAt = now - 5 * 60 * 60 * 1000, likeCount = 18, commentCount = 8, shareCount = 2),
            R.drawable.duck_story_study,
        ),
        CommunityFeedEntry(
            CommunityPost("demo-3", "慢慢来", "新同学", "不只比较学习时长",
                "听了老师的建议，我开始记录真正弄懂的问题。今天没有学很久，但能说清自己的薄弱点了。",
                topic = "备考调整", createdAt = now - 26 * 60 * 60 * 1000, likeCount = 32, commentCount = 15, shareCount = 4),
        ),
        CommunityFeedEntry(
            CommunityPost("demo-4", "向前一点点", "行动派", "给生活留一点空间",
                "傍晚和同学慢跑十分钟，又在公园走了一会儿。休息不是放弃，是为了更有力气继续。",
                topic = "运动与生活", createdAt = now - 2 * 24 * 60 * 60 * 1000, likeCount = 29, commentCount = 10, shareCount = 5),
            R.drawable.duck_story_exercise,
        ),
        CommunityFeedEntry(
            CommunityPost("demo-5", "小路同学", "表达练习中", "第一次把压力告诉家人",
                "他们没有催我给出结果，只问我最近有没有好好吃饭。原来表达脆弱，也可以是一种勇敢。",
                topic = "愿意表达", createdAt = now - 3 * 24 * 60 * 60 * 1000, likeCount = 41, commentCount = 19, shareCount = 6),
            R.drawable.duck_story_outdoors,
        ),
    )
}
