# 小丑鸭 FastAPI 服务

当前已接入“真实模型优先、Mock 自动兜底”的聊天主链路。未配置 Key、网络失败或模型调用失败时仍能返回模拟回复，方便前后端独立开发。

## 已实现接口

- `GET /health`：服务和模型配置状态。
- `POST /api/v1/chat`：文字聊天。
- `GET /api/v1/conversations/{device_id}/messages`：按时间顺序读取设备会话历史；不存在的设备返回空列表。
- `POST /api/v1/multimodal/image`：`multipart/form-data`，字段为 `file`、`prompt` 与 `device_id`；支持 JPEG、PNG、WebP，最大 8 MB。旧客户端可省略 `device_id`。
- `POST /api/v1/multimodal/audio`：`multipart/form-data`，字段为 `file` 与 `device_id`；支持 WAV、MP3、AAC、AMR、3GP，最大 6 MB。
- `POST /api/v1/multimodal/speech`：JSON 字段为 `text` 与 `voice`，`voice` 只允许 `Serena` 或 `Ethan`，成功时直接返回 WAV 音频。
- `POST /api/v1/multimodal/transcribe`：上传音频，只返回转写，不生成陪伴回复。
- `GET /api/v1/onboarding/schema`：首次认识字段表。
- `POST /api/v1/onboarding/analyze`：合并自然介绍与已有画像，返回缺项、追问和完成状态。
- `POST /api/v1/onboarding/refresh`：按 `device_id` 汇总最近用户对话和已保存主动记录，在保留旧画像未涉及字段的前提下返回更新画像。
- `POST /api/v1/events/extract`：成长事件提取；已配置真实模型时使用结构化提取，否则返回标记为 Mock 的结果。
- `GET /api/v1/events?device_id=...`：查看后台提取的成长事件及其原始消息 ID。
- `GET /api/v1/events/daily-summaries?device_id=...`：查看中等价值事件生成的当日摘要草稿。
- `GET /api/v1/memories?device_id=...`：查看长期记忆和待确认记忆。
- `PATCH /api/v1/memories/{id}`：提交 `device_id`、`content` 修改记忆。
- `POST /api/v1/memories/{id}/confirm?device_id=...`：确认敏感、矛盾或低置信度记忆。
- `DELETE /api/v1/memories/{id}?device_id=...`：删除记忆并从后续召回、上下文中排除旧内容。
- `GET/POST/PATCH/DELETE /api/v1/support-people`：按设备维护用户手填的支持对象、关系与适合场景。
- `POST /api/v1/support/suggest`：返回可信对象、理由、自己的小步骤、可编辑话术和更轻的备选方案；提供 `device_id` 时保存建议，供后续反馈引用。
- `POST /api/v1/support/feedback`：记录“帮到了 / 没帮到 / 没有联系”；确实联系后的反馈会进入成长事件。
- `GET /api/v1/support/feedback?device_id=...`：读取已保存的求助反馈。
- `POST /api/v1/records/sync`：按 `device_id` 和客户端记录 ID 幂等同步文字、转写、照片说明、自动画面描述和草稿状态；已保存记录生成成长事件并直接进入可检索记录库，原始媒体不上传。
- `POST /api/v1/records/describe-photo`：只接收当次照片进行客观画面描述，返回描述后不保存原图。
- `GET /api/v1/records?device_id=...` 与 `GET /api/v1/records/{id}?device_id=...`：读取记录的来源元数据。
- `DELETE /api/v1/records/client/{client_record_id}?device_id=...`：幂等删除单条记录，并清理派生事件、记忆、问候、视频脚本和相关回望。
- `GET /api/v1/data/export?device_id=...`：导出该设备的服务端结构化数据。
- `DELETE /api/v1/data?device_id=...`：删除该设备全部服务端数据。
- `POST /api/v1/reviews/generate`：按设备、周期及可选起止日期生成并保存回望；空数据不写入总结。响应保留旧版摘要字段，并新增结构化 `sections[{key,title,content}]` 与有来源约束的 `affirmation`。
- `POST /api/v1/reviews/overview`：一次返回所选日/周/月主报告，以及周内每日或月内每周的子摘要，供 Android 单请求加载完整页签。主报告可调用一次 AI 润色，子摘要只使用已有缓存或可靠模板。
- `POST /api/v1/reviews/generate-pending-daily`：补生成有真实事件的过去日期的日回望，重复调用不会产生重复总结。
- `GET /api/v1/reviews/{period}?device_id=...`：读取日、近七天或当月回望及来源 ID；不传 `device_id` 保留旧版 Mock 示例。
- `POST /api/v1/videos/keywords`：对 1～40 条同设备成长事件生成最多十组可选主题及其关联事件；在线 AI 可用时按语义归类，不可用或输出异常时自动退回程序规则，未被主题覆盖的事件统一归入“其他记录”。
- `POST /api/v1/videos/scripts`：提交 `device_id` 和 3～7 个同设备、非高敏感成长事件 ID，按时间生成一条素材对应一页的 3～7 页可编辑脚本，并保存每页日期与来源；中敏感内容仅在用户主动勾选后使用，高敏感内容始终拒绝。
- `GET /api/v1/videos/scripts/{id}?device_id=...`：读取该设备已保存的脚本。
- `PATCH /api/v1/videos/scripts/{id}`：保存删改后的 3～7 个页面，保持来源事件的时间顺序和唯一性。
- `POST /api/v1/videos/render/{script_id}`：校验设备归属后接收 3～7 组 PNG 画面、可选 AI 旁白和对应原声，由 FFmpeg 合成 H.264/AAC MP4；单段只要求时长为正，整片最长 5 分钟，上传素材总计不超过 64 MB。
- `GET /api/v1/check-ins/pending?device_id=...`：读取该设备尚未回应的一次主动问候；没有待问候时返回 `check_in: null`。
- `GET /api/v1/check-ins/notice?device_id=...`：消费一次“已建立待问候”的轻量提示，但不消费正式问候；同一任务只返回一次。
- `POST /api/v1/check-ins/{id}/respond`：提交 `device_id` 与 `talk / improved / not_now`，保存回应、冷却时间和可选的继续聊天话题。

文字、图片和语音聊天现在共用按 `device_id` 区分的 SQLite 会话。生成回复前读取最近 12 条消息；`chat-v2` 结构化返回回复、意图、实际策略、主题和真正使用的记忆 ID，成功后把用户消息和回复写入 `messages`，并保存模型与 Prompt 版本。数据库在首次访问时自动创建于 `DATABASE_URL` 指定位置（默认 `server/confidence_agent.db`）。上传的原始图片、音频不写入服务端数据库，`media_ref` 仅保存 SHA-256 来源标识。

`memory-v2` 在每轮回复保存后独立提取一个兼容成长页的合并事件，以及最多五个原子记忆点。记忆点区分身份、偏好、目标、持续处境、支持关系和真实经历，并保存用户证据原文、来源消息、置信度、敏感度、时间属性和事实状态。计划、否定、明确纠正及“不记录/忘掉刚才”分别处理；重复事实合并，明确纠正替代旧值，普通冲突和敏感信息等待确认。模型字段轻微偏差会被规范化，完整契约校验失败时只修复一次，仍失败则不写库。

召回统一搜索已保存记录与聊天长期记忆，按关键词 35%、向量 35%、类型匹配 15%、重要度 10%、时间 5% 计算单一分数，再做近重复过滤、四条上限和 700 字预算；向量不可用时自动退回关键词。短指代会结合最近两条用户消息。只有模型实际写入回复并返回对应 ID 的证据才进入响应 `evidence`。旧 SQLite 首次启动只增加新字段和索引，原有行继续按 `experience` 兼容，不会清空数据。

数据导出的 `memory_assessments` 会把来源用户原话、置信度及 0.75 阈值、五项价值分、加权公式、总分、敏感度和最终记忆去向聚合展示。新事件会完整保存五项分数；升级前的历史事件无法反推原始分项，`score_components` 为 `null` 时仍保留其已有置信度、总分和最终判定。

P2 在用户主动求助、持续受阻或面临高难任务时把聊天策略设为 `seek_support`。只要用户的支持圈中已有联系人，就优先从这些真实保存对象中按场景、类型和偏好排序；支持圈为空时才使用泛称。Android 收到该策略后会在情绪回应下方自动加载求助卡，用户仍可编辑、复制或主动分享话术，服务端不会联系任何人。

P3 只在用户进入成长页时按需同步本地记录和生成回望，不再在 App 冷启动时预加载，也不再扫描并补生成全部历史日期。日、周、月回望分别返回对应的结构化栏目；周报告覆盖完成事项、困难、解决过程、变化与仍在继续，月总结覆盖主要经历、反复困难、变化和下一步。`/reviews/overview` 用一次请求返回完整页签，主报告最多进行一次 AI 润色，周内每日和月内每周子摘要不重复调用模型。模型超时、不可用、栏目顺序错误或输出越界时自动保留模板；来源没有变化的已保存报告直接复用。Android 先显示五分钟本地缓存，再按需后台刷新；聊天、记录和求助反馈变化会使缓存失效。月故事从真实事件中选最多六个关键节点，优先保留受阻经历。每份非空主回望存入 `reviews`，记录 `source_event_ids`；同一日期重复生成会复用并在内容变化时更新。Android 节点能打开本机原始文字、转写、照片或语音；数据库自动增补 P3 字段和表。

P4 脚本由真实成长事件生成，记录模型、提示版本、来源和用户修改状态。未启用真实模型时返回明确标记的可编辑模板；模型响应格式无效时返回错误且不写库。Android 将本机照片、批注和字幕绘制进 540×960 PNG 页面，再上传页面、AI 旁白和对应原声；后端通过 FFmpeg 按实际配音时长合成 H.264/AAC MP4。每段旁白播放完后可接该段最多 5 秒的原声，整片最长 5 分钟。云端 TTS 返回流式 WAV 时，后端会按实际下载字节数修正 RIFF/data 长度，避免 Android 将十余秒音频误判为数小时，也避免 FFmpeg 按虚假长度持续补音。分享必须由用户主动触发。桌面组件默认不公开事件，开启后仅显示低敏感且未命中人物和明显敏感信息过滤的内容；该过滤不能保证识别所有私人信息，开启后应检查预览。

主动关心由成长事件触发，不直接读取或上传本地媒体。单次普通情绪不会创建任务；同类受阻、紧张或低落需在不同日期至少出现两次，明确提出“之后再问我”可单次触发且无需等待 AI 事件提取。任务建立后只返回一次轻量确认提示，正式问候仍保留到下次打开 App。危险表达不进入延迟任务，仍由聊天当轮立即处理。每次回应后按选择写入冷却时间，避免重复追问；设备归属在读取和回应接口中都会校验。

音频接口先让 `qwen3.8-omni-flash`输出转写，再把转写送入统一的小鸭对话服务，因此 Android 可以把“用户转写 + 小鸭回复”写回同一会话。第一版是轮次式录音，不是 WebSocket 全双工电话。

回复朗读使用 `qwen3-tts-flash`。后端先获取有效期 24 小时的临时音频 URL，再立即下载并把音频字节代理给 Android；客户端不接触百炼 Key，也不依赖临时 URL 的有效期。

## 启动

```powershell
python -m venv .venv
.\.venv\Scripts\python.exe -m pip install -r requirements.txt
if (-not (Test-Path .env)) { Copy-Item .env.example .env }
.\.venv\Scripts\python.exe -m uvicorn app.main:app --host 0.0.0.0 --port 8000
```

成长小片依赖 FFmpeg，并按片段情绪自动选用 `app/assets/bgm_reflective_chillax.m4a` 或 `app/assets/bgm_bright_side.m4a`；连续同类片段保持音乐连续播放。启动后端前先确认以下命令可用：

```powershell
where.exe ffmpeg
ffmpeg -version
where.exe ffprobe
```

若没有加入 `PATH`，可在 `.env` 中配置 `FFMPEG_PATH=C:\ffmpeg\bin\ffmpeg.exe`；修改 `.env` 后需要重启 Uvicorn。

- 健康检查：`http://127.0.0.1:8000/health`
- Swagger：`http://127.0.0.1:8000/docs`
- Android 模拟器访问宿主机：`http://10.0.2.2:8000`
- Wi-Fi 真机访问：电脑和手机连同一 Wi-Fi 后，在 App 的“我的—帮助与求助资源”点击“一键检测当前 Wi-Fi”。也可用 `ipconfig` 查询 WLAN IPv4，在同一弹窗手动输入 `http://电脑IPv4:8000` 并检测保存；不需要重新安装 App。

后端的端口检查、健康检测、安全关闭和同一 Wi-Fi 配置见项目根目录 [README.md](../README.md#后端启动检查与关闭)。

真实 `DASHSCOPE_API_KEY` 只写入 `.env`，不得提交 Git。

## 开启真实对话

1. 复制 `.env.example` 为 `.env`。
2. 将百炼控制台创建的 Key 填入 `DASHSCOPE_API_KEY`，并将 `ENABLE_LIVE_AI` 改为 `true`。
3. 重启 Uvicorn，访问 `/health`，确认 `ai_configured` 为 `true`、`mock` 为 `false`。
4. 请求 `/api/v1/chat`。响应中的 `mock=false` 表示这次内容来自真实模型；若为 `true`，查看 `mock_reason` 排查配置或网络问题。

语音合成快速测试：

```powershell
$body = @{ text = '你好，我会陪你慢慢向前。'; voice = 'Serena' } | ConvertTo-Json
Invoke-WebRequest http://127.0.0.1:8000/api/v1/multimodal/speech `
    -Method Post -ContentType 'application/json' -Body $body -OutFile .\tts-test.wav
```

生成 `tts-test.wav` 即表示云端 TTS 链路可用。男声测试将 `voice` 改为 `Ethan`。

不要把完整 Key 发到聊天、截图或提交到 Git 仓库。
