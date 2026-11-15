package com.testconnection.confidence_agent.ui.screens

import android.content.ClipData
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.testconnection.confidence_agent.data.model.CommunityRecordImport
import com.testconnection.confidence_agent.data.preferences.DeviceIdStore
import com.testconnection.confidence_agent.data.remote.CommunityApiClient
import com.testconnection.confidence_agent.data.remote.CommunityHttpException
import com.testconnection.confidence_agent.data.repository.*
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

private val communityTopics = listOf("成长分享", "学习交流", "生活片段", "想说的话", "今日记录")
private val communityAvatars = listOf("🐣", "🦆", "🌱", "🌻", "🌙", "☁️")
private data class ReportTarget(val kind: String, val id: String, val author: String, val mine: Boolean)

/** Shared, invited cloud community. The old local store is read only through the explicit import list. */
@Composable
@Suppress("UNUSED_PARAMETER")
fun CommunityScreen(
    contentPadding: PaddingValues,
    userName: String,
    onDetailVisibilityChanged: (Boolean) -> Unit = {},
    initialVideoPath: String? = null,
    onInitialVideoConsumed: () -> Unit = {},
) {
    val context = LocalContext.current
    val api = remember { CommunityApiClient(DeviceIdStore(context).get()) }
    val store = remember { CloudCommunityStore(context) }
    val localStore = remember { CommunityPostStore(context) }
    val scope = rememberCoroutineScope()
    var page by remember { mutableStateOf("feed") }
    var posts by remember { mutableStateOf(store.cache()) }
    var nextCursor by remember { mutableStateOf<String?>(null) }
    var myOnly by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var topic by remember { mutableStateOf("") }
    var cached by remember { mutableStateOf(posts.isNotEmpty()) }
    var profile by remember { mutableStateOf<JSONObject?>(null) }
    var selected by remember { mutableStateOf<JSONObject?>(null) }
    var detailReady by remember { mutableStateOf(false) }
    var comments by remember { mutableStateOf(emptyList<JSONObject>()) }
    var commentCursor by remember { mutableStateOf<String?>(null) }
    var comment by remember { mutableStateOf("") }
    var commentClientId by remember { mutableStateOf(UUID.randomUUID().toString()) }
    var pendingComment by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf(store.draft()) }
    var busy by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    var profileDialog by remember { mutableStateOf(false) }
    var nickname by remember { mutableStateOf("") }
    var avatar by remember { mutableIntStateOf(0) }
    var confirmPublish by remember { mutableStateOf(false) }
    var confirmDiscard by remember { mutableStateOf(false) }
    var importRecords by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf<ReportTarget?>(null) }
    var reporting by remember { mutableStateOf<ReportTarget?>(null) }
    var reportReason by remember { mutableStateOf("privacy") }
    var reportText by remember { mutableStateOf("") }
    var deleting by remember { mutableStateOf<ReportTarget?>(null) }
    var blocking by remember { mutableStateOf<ReportTarget?>(null) }
    var blockedUsers by remember { mutableStateOf(emptyList<JSONObject>()) }
    var videoNotice by remember { mutableStateOf<String?>(null) }

    fun run(action: suspend () -> Unit) {
        if (busy) return
        busy = true; notice = null
        scope.launch {
            try { action() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { notice = e.message ?: "连接暂时不可用，请重试；草稿仍在本机" }
            finally { busy = false }
        }
    }
    fun save(value: CloudCommunityDraft) { draft = value; store.save(value) }
    suspend fun loadFeed(append: Boolean = false) {
        val params = mutableMapOf("q" to query.trim(), "topic" to topic, "mine" to myOnly.toString())
        if (append) nextCursor?.let { params["cursor"] = it }
        val result = api.request("GET", "/posts", query = params)
        val rows = result.optJSONArray("posts").objects()
        posts = if (append) (posts + rows).distinctBy { it.getString("id") } else rows
        nextCursor = result.optString("next_cursor").takeIf { it.isNotBlank() && it != "null" }
        cached = false
        if (!myOnly && query.isBlank() && topic.isBlank()) store.cache(posts)
    }
    suspend fun loadComments(append: Boolean = false) {
        val current = selected ?: return
        if (current.optString("status") != "published") { comments = emptyList(); return }
        val params = if (append && commentCursor != null) mapOf("cursor" to commentCursor!!) else emptyMap()
        val result = api.request("GET", "/posts/${current.getString("id")}/comments", query = params)
        val rows = result.optJSONArray("comments").objects()
        comments = if (append) (comments + rows).distinctBy { it.getString("id") } else rows
        commentCursor = result.optString("next_cursor").takeIf { it.isNotBlank() && it != "null" }
    }
    fun updatePost(value: JSONObject) {
        posts = posts.map { if (it.optString("id") == value.optString("id")) value else it }
        if (selected?.optString("id") == value.optString("id")) selected = value
        store.clearFeed() // A changed or removed item must never survive as an older offline snapshot.
    }
    fun openProfile() {
        nickname = profile?.optString("nickname").orEmpty()
        avatar = profile?.optInt("avatar") ?: 0
        profileDialog = true
    }
    fun shareVideo(path: String) {
        runCatching {
            val file = File(path); require(file.isFile) { "视频文件已不存在" }
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
            context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type = "video/mp4"; putExtra(Intent.EXTRA_STREAM, uri)
                clipData = ClipData.newRawUri("成长小片", uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }, "分享成长小片"))
        }.onFailure { notice = it.message ?: "暂时无法分享" }
    }
    val videoSave = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("video/mp4")) { uri ->
        val source = videoNotice
        if (uri != null && source != null) run {
            withContext(Dispatchers.IO) {
                context.contentResolver.openOutputStream(uri)?.use { out -> File(source).inputStream().use { it.copyTo(out) } }
                    ?: error("无法保存到所选位置")
            }; notice = "视频已保存"
        }
    }
    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(3)) { uris ->
        if (uris.isNotEmpty()) run {
            val remaining = 3 - draft.pictures.size
            val paths = uris.take(remaining).map { store.importPicture(it) }
            save(draft.copy(pictures = draft.pictures + paths, uploaded = emptyList()))
            if (uris.size > remaining) notice = "每帖最多3张图片，已保留前${remaining}张"
        }
    }
    LaunchedEffect(Unit) {
        run { profile = api.request("GET", "/profile"); loadFeed() }
    }
    LaunchedEffect(initialVideoPath) {
        if (initialVideoPath != null) { videoNotice = initialVideoPath; onInitialVideoConsumed() }
    }
    LaunchedEffect(page, selected?.optString("id")) {
        onDetailVisibilityChanged(page != "feed")
        if (page == "detail") {
            detailReady = false
            comments = emptyList(); comment = ""; pendingComment = false
            commentClientId = UUID.randomUUID().toString()
            // Revalidate a cached detail before showing its image or accepting an interaction.
            snapshotFlow { busy }.first { !it }
            run {
                val id = selected?.getString("id") ?: return@run
                try { selected = api.request("GET", "/posts/$id"); detailReady = true; loadComments() }
                catch (e: CommunityHttpException) {
                    if (e.status == 404) { posts = posts.filterNot { it.getString("id") == id }; store.clearFeed(); page = "feed"; selected = null }
                    throw e
                }
            }
        }
    }
    DisposableEffect(Unit) { onDispose { onDetailVisibilityChanged(false) } }
    BackHandler(enabled = page != "feed") { page = "feed"; selected = null }

    if (profileDialog) AlertDialog(onDismissRequest = { if (!busy) profileDialog = false },
        title = { Text("设置社区名片") }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("社区使用独立昵称和预设头像。这里不会展示你的私人画像或联系方式。")
                OutlinedTextField(nickname, { if (it.length <= 20) nickname = it }, label = { Text("昵称（2—20字）") }, singleLine = true)
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    communityAvatars.forEachIndexed { index, emoji ->
                        TextButton(onClick = { avatar = index }) { Text(if (avatar == index) "[$emoji]" else emoji) }
                    }
                }
            }
        }, confirmButton = { TextButton(enabled = !busy && nickname.trim().length in 2..20, onClick = { run {
            profile = api.request("PATCH", "/profile", JSONObject().put("nickname", nickname.trim()).put("avatar", avatar))
            profileDialog = false; loadFeed()
        } }) { Text("保存名片") } }, dismissButton = { TextButton(onClick = { profileDialog = false }) { Text("取消") } })
    if (confirmPublish) AlertDialog(onDismissRequest = { confirmPublish = false }, title = { Text("确认公开分享？") },
        text = { Text("帖子和图片将对其他受邀体验者可见。请检查并移除姓名、联系方式和他人隐私；社区内容不会自动进入你的长期记忆或压力分析。") },
        confirmButton = { TextButton(onClick = {
            confirmPublish = false
            run {
                if (!draft.pending) save(draft.copy(pending = true))
                while (draft.uploaded.size < draft.pictures.size) {
                    val id = api.upload(draft.pictures[draft.uploaded.size])
                    save(draft.copy(uploaded = draft.uploaded + id))
                }
                val posted = api.request("POST", "/posts", draft.payload())
                store.clearDraft(); draft = CloudCommunityDraft(); selected = posted; page = "detail"
                loadFeed(); notice = "已发布，其他体验者现在可以看到"
            }
        }) { Text(if (draft.pending) "重试这次发布" else "确认发布") } },
        dismissButton = { TextButton(onClick = { confirmPublish = false }) { Text("再检查一下") } })
    if (confirmDiscard) AlertDialog(onDismissRequest = { confirmDiscard = false }, title = { Text("清空当前草稿？") },
        text = { Text("如果上次提交时断网，请先查看“我的帖子”确认是否已发布。清空草稿不会删除已发布的帖子。") },
        confirmButton = { TextButton(onClick = { store.clearDraft(); draft = CloudCommunityDraft(); confirmDiscard = false }) { Text("清空草稿") } },
        dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text("保留") } })
    if (videoNotice != null) AlertDialog(onDismissRequest = { videoNotice = null }, title = { Text("云端社区暂不支持视频") },
        text = { Text("可以保存或通过系统分享成长小片，也可以发布一段文字和最多3张图片。") },
        confirmButton = { TextButton(onClick = { videoNotice?.let(::shareVideo) }) { Text("系统分享") } },
        dismissButton = { Row {
            TextButton(onClick = { videoSave.launch("鸭力Go成长小片.mp4") }) { Text("保存视频") }
            TextButton(onClick = { videoNotice = null }) { Text("知道了") }
        } })
    if (importRecords) {
        val records = remember { LocalRecordRepository(context).load().mapNotNull { CommunityRecordImport.from(it) } }
        AlertDialog(onDismissRequest = { importRecords = false }, title = { Text("选择一条记录作为草稿") },
            text = { LazyColumn(Modifier.heightIn(max = 400.dp)) {
                item { Text("仅复制你选中的文字和照片。导入后可删改，确认发布前不会上传。选择后替换当前草稿。") }
                if (records.isEmpty()) item { Text("暂无可导入的已保存记录") }
                items(records) { record -> TextButton(onClick = {
                    run {
                        val path = record.sourcePhotoPath?.let { store.importPicture(Uri.fromFile(File(it))) }
                        save(CloudCommunityDraft(title = record.title.take(50), content = record.content.take(2000),
                            topic = record.topic ?: "成长分享", pictures = listOfNotNull(path)))
                        importRecords = false
                    }
                }) { Text(record.title) } }
            } }, confirmButton = { TextButton(onClick = { importRecords = false }) { Text("取消") } })
    }
    menu?.let { target -> AlertDialog(onDismissRequest = { menu = null }, title = { Text("内容操作") }, text = {
        Column {
            if (target.mine) TextButton(onClick = { deleting = target; menu = null }) { Text("删除我的内容") }
            else {
                TextButton(onClick = { reporting = target; reportReason = "privacy"; reportText = ""; menu = null }) { Text("举报此内容") }
                TextButton(onClick = { blocking = target; menu = null }) { Text("屏蔽此作者") }
            }
        }
    }, confirmButton = { TextButton(onClick = { menu = null }) { Text("关闭") } }) }
    deleting?.let { target -> AlertDialog(onDismissRequest = { deleting = null }, title = { Text("删除后无法恢复，确认删除？") },
        confirmButton = { TextButton(enabled = !busy, onClick = { run {
            api.request("DELETE", if (target.kind == "post") "/posts/${target.id}" else "/comments/${target.id}")
            deleting = null; store.clearFeed()
            if (target.kind == "post") { selected = null; page = "feed"; loadFeed() }
            else { loadComments(); selected?.let { updatePost(api.request("GET", "/posts/${it.getString("id")}")) } }
        } }) { Text("确认删除") } }, dismissButton = { TextButton(onClick = { deleting = null }) { Text("取消") } }) }
    blocking?.let { target -> AlertDialog(onDismissRequest = { blocking = null }, title = { Text("屏蔽此作者？") },
        text = { Text("不再显示此作者的帖子与评论。你可在“屏蔽管理”中解除。") },
        confirmButton = { TextButton(enabled = !busy, onClick = { run {
            api.request("PUT", "/blocks/${target.author}"); blocking = null; selected = null
            posts = emptyList(); store.clearFeed(); page = "feed"; loadFeed()
        } }) { Text("确认屏蔽") } }, dismissButton = { TextButton(onClick = { blocking = null }) { Text("取消") } }) }
    reporting?.let { target -> AlertDialog(onDismissRequest = { reporting = null }, title = { Text("举报内容") }, text = {
        Column {
            listOf("privacy" to "泄露隐私", "harassment" to "骚扰或攻击", "spam" to "广告灌水", "dangerous" to "危险内容", "other" to "其他").forEach { (key, label) ->
                TextButton(onClick = { reportReason = key }) { Text((if (reportReason == key) "● " else "○ ") + label) }
            }
            OutlinedTextField(reportText, { if (it.length <= 300) reportText = it }, label = { Text("补充说明（选填，最多300字）") })
            Text("举报将交由管理员查看，不会因举报次数自动下架。")
        }
    }, confirmButton = { TextButton(enabled = !busy, onClick = { run {
        api.request("POST", "/reports", JSONObject().put("kind", target.kind).put("target_id", target.id).put("reason", reportReason).put("detail", reportText))
        reporting = null; notice = "举报已收到，谢谢你的反馈"
    } }) { Text("提交举报") } }, dismissButton = { TextButton(onClick = { reporting = null }) { Text("取消") } }) }

    key(page) {
    LazyColumn(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(contentPadding).imePadding(),
        contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Column {
                if (page != "feed") TextButton(onClick = { page = "feed"; selected = null }) { Text("‹ 返回社区") }
                Text(when (page) { "compose" -> "写一篇分享"; "detail" -> "分享详情"; "legacy" -> "本机旧帖"; "blocks" -> "屏蔽管理"; else -> "一起成长" },
                    style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                if (page == "feed") Text("和其他体验者分享真实的小步，也给彼此一点支持。", style = MaterialTheme.typography.bodyMedium)
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
                notice?.let { Text(it, Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.primary) }
            }
        }
        if (page == "feed") {
            item {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Button(enabled = !busy, onClick = { page = "compose" }) { Text("写分享") }
                    OutlinedButton(enabled = !busy, onClick = { openProfile() }) { Text("社区名片") }
                    OutlinedButton(enabled = !busy, onClick = { page = "legacy" }) { Text("本机旧帖") }
                    OutlinedButton(enabled = !busy, onClick = { run { blockedUsers = api.request("GET", "/blocks").optJSONArray("profiles").objects(); page = "blocks" } }) { Text("屏蔽管理") }
                }
            }
            item {
                OutlinedTextField(query, { if (it.length <= 100) query = it }, Modifier.fillMaxWidth(), label = { Text("搜索标题或正文") }, singleLine = true)
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    TextButton(enabled = !busy, onClick = { run { myOnly = false; loadFeed() } }) { Text(if (!myOnly) "● 最新" else "最新") }
                    TextButton(enabled = !busy, onClick = { run { myOnly = true; loadFeed() } }) { Text(if (myOnly) "● 我的帖子" else "我的帖子") }
                    TextButton(enabled = !busy, onClick = { run { loadFeed() } }) { Text("搜索 / 刷新") }
                }
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    (listOf("") + communityTopics).forEach { value ->
                        FilterChip(selected = topic == value, enabled = !busy,
                            onClick = { run { topic = value; loadFeed() } }, label = { Text(value.ifBlank { "全部话题" }) })
                        Spacer(Modifier.width(6.dp))
                    }
                }
                if (cached) Text("显示上次加载的内容；联网刷新后更新。离线时不能发布或互动。", style = MaterialTheme.typography.bodySmall)
                if (profile?.optBoolean("restricted") == true) Text("社区发布功能已受限。你仍可阅读，私人陪伴功能不受影响。")
            }
            if (posts.isEmpty() && !busy) item { Text("这里暂时没有帖子。可以刷新，或分享你的第一小步。", Modifier.padding(vertical = 24.dp)) }
            items(posts, key = { "post-" + it.getString("id") }) { post ->
                CloudPostCard(post, api, cached, detail = false, onOpen = { if (!busy) { selected = post; page = "detail" } },
                    onLike = { run { updatePost(api.request(if (post.optBoolean("liked")) "DELETE" else "PUT", "/posts/${post.getString("id")}/like")) } },
                    onMenu = { menu = post.target("post") }, enabled = !busy && !cached)
            }
            if (nextCursor != null && !cached) item { OutlinedButton(enabled = !busy, onClick = { run { loadFeed(true) } }, modifier = Modifier.fillMaxWidth()) { Text("加载更多") } }
        } else if (page == "compose") {
            item {
                Text("草稿保存在这台手机。只会发布下面可见的文字和图片，最多3张；本轮不支持视频。")
                if (profile?.optBoolean("configured") != true) OutlinedButton(enabled = !busy, onClick = ::openProfile) { Text("先设置独立社区昵称") }
                if (draft.pending) Text("上次发布尚未确认。为避免重复或内容错位，草稿已锁定，请重试原请求；也可先在“我的帖子”查看。", color = MaterialTheme.colorScheme.primary)
                OutlinedTextField(draft.title, { if (it.length <= 50) save(draft.copy(title = it)) }, Modifier.fillMaxWidth(),
                    enabled = !busy && !draft.pending, label = { Text("标题 ${draft.title.length}/50") })
                OutlinedTextField(draft.content, { if (it.length <= 2000) save(draft.copy(content = it)) }, Modifier.fillMaxWidth().heightIn(min = 170.dp),
                    enabled = !busy && !draft.pending, label = { Text("想分享的经历 ${draft.content.length}/2000") }, minLines = 5)
                Row(Modifier.horizontalScroll(rememberScrollState())) { communityTopics.forEach { value ->
                    FilterChip(selected = draft.topic == value, enabled = !busy && !draft.pending,
                        onClick = { save(draft.copy(topic = value)) }, label = { Text(value) }); Spacer(Modifier.width(6.dp))
                } }
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    OutlinedButton(enabled = !busy && !draft.pending && draft.pictures.size < 3, onClick = { photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) { Text("选择照片（${draft.pictures.size}/3）") }
                    TextButton(enabled = !busy && !draft.pending, onClick = { importRecords = true }) { Text("从我的记录导入") }
                }
            }
            items(draft.pictures, key = { it }) { path ->
                Column {
                    LocalCommunityPicture(path)
                    TextButton(enabled = !busy && !draft.pending, onClick = { save(draft.copy(pictures = draft.pictures - path, uploaded = emptyList())) }) { Text("移除这张图片") }
                }
            }
            item {
                Button(enabled = !busy && profile?.optBoolean("configured") == true && profile?.optBoolean("restricted") != true &&
                    draft.title.isNotBlank() && (draft.content.isNotBlank() || draft.pictures.isNotEmpty()),
                    onClick = { confirmPublish = true }, modifier = Modifier.fillMaxWidth()) { Text(if (draft.pending) "重试原发布" else "检查并发布") }
                TextButton(enabled = !busy, onClick = { confirmDiscard = true }) { Text("清空草稿") }
                Text("返回不会丢失草稿，也不会自动发布。", style = MaterialTheme.typography.bodySmall)
            }
        } else if (page == "detail") {
            if (!detailReady && !busy) item { OutlinedButton(onClick = { run {
                selected?.let { selected = api.request("GET", "/posts/${it.getString("id")}"); detailReady = true; loadComments() }
            } }) { Text("重新加载详情") } }
            selected?.takeIf { detailReady }?.let { post ->
                item { CloudPostCard(post, api, cached = false, detail = true, onOpen = {}, enabled = !busy,
                    onLike = { run { updatePost(api.request(if (post.optBoolean("liked")) "DELETE" else "PUT", "/posts/${post.getString("id")}/like")) } },
                    onMenu = { menu = post.target("post") }) }
                if (post.optString("status") == "published") {
                    item { Text("评论", style = MaterialTheme.typography.titleLarge) }
                    if (comments.isEmpty() && !busy) item { Text("还没有评论，可以留下一句支持。") }
                    items(comments, key = { "comment-" + it.getString("id") }) { row ->
                        ElevatedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp)) {
                            CommunityAuthor(row.getJSONObject("author")); Text(row.optString("content"))
                            Row { Text(cloudTime(row.optString("created_at")), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                                TextButton(enabled = !busy, onClick = { menu = row.target("comment") }) { Text("操作") } }
                        } }
                    }
                    if (commentCursor != null) item { TextButton(enabled = !busy, onClick = { run { loadComments(true) } }) { Text("更多评论") } }
                    item {
                        OutlinedTextField(comment, { if (it.length <= 500) comment = it }, enabled = !busy && !pendingComment,
                            modifier = Modifier.fillMaxWidth(), label = { Text("友善地说一句（最多500字）") }, minLines = 2)
                        if (pendingComment) Text("评论尚未确认，可重试相同内容，避免重复。")
                        Button(enabled = !busy && comment.isNotBlank(), onClick = {
                            if (profile?.optBoolean("configured") != true) openProfile()
                            else run {
                                pendingComment = true
                                api.request("POST", "/posts/${post.getString("id")}/comments", JSONObject().put("client_id", commentClientId).put("content", comment.trim()))
                                comment = ""; pendingComment = false; commentClientId = UUID.randomUUID().toString()
                                loadComments(); updatePost(api.request("GET", "/posts/${post.getString("id")}"))
                            }
                        }) { Text(if (pendingComment) "重试评论" else "发送评论") }
                    }
                }
            }
        } else if (page == "legacy") {
            item { Text("以下仅来自这台手机，以前的点赞和评论数不会上传。点选后进入可编辑草稿，确认发布后才进入共享社区。") }
            val oldPosts = localStore.loadOwnPosts()
            if (oldPosts.isEmpty()) item { Text("暂无本机旧帖") }
            items(oldPosts, key = { "legacy-" + it.id }) { old ->
                ElevatedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) {
                    Text(old.title, style = MaterialTheme.typography.titleMedium); Text(old.content)
                    old.imagePath?.let { LocalCommunityPicture(it) }
                    OutlinedButton(enabled = !busy && !draft.pending, onClick = { run {
                        val path = old.imagePath?.let { store.importPicture(Uri.fromFile(File(it))) }
                        save(CloudCommunityDraft(title = old.title.take(50), content = old.content.take(2000),
                            topic = old.topic?.takeIf { it in communityTopics } ?: "成长分享", pictures = listOfNotNull(path)))
                        page = "compose"
                    } }) { Text("替换草稿并编辑") }
                    old.videoPath?.let { path -> TextButton(onClick = { videoNotice = path }) { Text("保存或分享旧帖视频") } }
                    if (draft.pending) Text("请先处理尚未确认的发布草稿。")
                } }
            }
        } else if (page == "blocks") {
            if (blockedUsers.isEmpty()) item { Text("你尚未屏蔽任何作者") }
            items(blockedUsers, key = { it.getString("id") }) { author ->
                Row(Modifier.fillMaxWidth()) {
                    Column(Modifier.weight(1f)) { CommunityAuthor(author) }
                    TextButton(enabled = !busy, onClick = { run {
                        api.request("DELETE", "/blocks/${author.getString("id")}")
                        blockedUsers = blockedUsers.filterNot { it.getString("id") == author.getString("id") }; store.clearFeed()
                    } }) { Text("解除屏蔽") }
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
    }
}

private fun JSONObject.target(kind: String) = ReportTarget(kind, getString("id"), getJSONObject("author").getString("id"), optBoolean("is_mine"))
private fun cloudTime(value: String): String = runCatching { Instant.parse(value).atZone(ZoneId.systemDefault())
    .format(DateTimeFormatter.ofPattern("MM-dd HH:mm")) }.getOrDefault(value.take(16))

@Composable
private fun CommunityAuthor(author: JSONObject) {
    Text("${communityAvatars.getOrElse(author.optInt("avatar")) { "🐣" }}  ${author.optString("nickname", "新朋友")}", fontWeight = FontWeight.Medium)
}

@Composable
private fun CloudPostCard(post: JSONObject, api: CommunityApiClient, cached: Boolean, detail: Boolean,
                          onOpen: () -> Unit, onLike: () -> Unit, onMenu: () -> Unit, enabled: Boolean) {
    ElevatedCard(onClick = onOpen, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            CommunityAuthor(post.getJSONObject("author"))
            Text(post.optString("title"), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(post.optString("content"), maxLines = if (detail) Int.MAX_VALUE else 5)
            val media = post.optJSONArray("media_ids").strings()
            (if (detail) media else media.take(1)).forEach { id -> CloudCommunityPicture(api, id, cached) }
            if (!detail && media.size > 1) Text("共${media.size}张图片，点开查看", style = MaterialTheme.typography.bodySmall)
            Text("${post.optString("topic")} · ${cloudTime(post.optString("created_at"))}", style = MaterialTheme.typography.bodySmall)
            if (post.optString("status") == "hidden") Text("此帖已下架：${post.optString("reason")}", color = MaterialTheme.colorScheme.error)
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                TextButton(enabled = enabled && post.optString("status") == "published", onClick = onLike) { Text("${if (post.optBoolean("liked")) "♥" else "♡"} ${post.optInt("like_count")}") }
                TextButton(onClick = onOpen) { Text("评论 ${post.optInt("comment_count")}") }
                TextButton(enabled = enabled, onClick = onMenu) { Text("操作") }
            }
        }
    }
}

@Composable
private fun CloudCommunityPicture(api: CommunityApiClient, id: String, offline: Boolean) {
    var bitmap by remember(id, offline) { mutableStateOf<android.graphics.Bitmap?>(null) }
    var failed by remember(id, offline) { mutableStateOf(false) }
    LaunchedEffect(id, offline) {
        if (offline) { failed = true; return@LaunchedEffect }
        try { val bytes = api.media(id); bitmap = withContext(Dispatchers.IO) { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }; failed = bitmap == null }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { failed = true }
    }
    val image = bitmap
    if (image != null) Image(image.asImageBitmap(), "社区分享图片", Modifier.fillMaxWidth().heightIn(max = 360.dp), contentScale = ContentScale.Fit)
    else Text(if (failed) "图片暂不可用，请联网刷新查看" else "图片加载中…", style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun LocalCommunityPicture(path: String) {
    val bitmap by produceState<android.graphics.Bitmap?>(null, path) {
        value = withContext(Dispatchers.IO) { BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = 2 }) }
    }
    bitmap?.let { Image(it.asImageBitmap(), "待分享的照片", Modifier.fillMaxWidth().heightIn(max = 240.dp), contentScale = ContentScale.Fit) }
}
