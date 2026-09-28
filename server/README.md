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
- `POST /api/v1/records/sync`：按 `device_id` 和客户端记录 ID 幂等同步文字、转写、照片说明和草稿状态；已保存记录生成成长事件，原始媒体不上传。
- `GET /api/v1/records?device_id=...` 与 `GET /api/v1/records/{id}?device_id=...`：读取记录的来源元数据。
- `POST /api/v1/reviews/generate`：按设备、周期及可选起止日期生成并保存回望；空数据不写入总结。响应保留旧版摘要字段，并新增结构化 `sections[{key,title,content}]` 与有来源约束的 `affirmation`。
- `POST /api/v1/reviews/generate-pending-daily`：补生成有真实事件的过去日期的日回望，重复调用不会产生重复总结。
- `GET /api/v1/reviews/{period}?device_id=...`：读取日、近七天或当月回望及来源 ID；不传 `device_id` 保留旧版 Mock 示例。
- `POST /api/v1/videos/scripts`：提交 `device_id` 和 1～6 个同设备、非敏感成长事件 ID，生成五段可编辑脚本并保存来源。
- `GET /api/v1/videos/scripts/{id}?device_id=...`：读取该设备已保存的脚本。
- `PATCH /api/v1/videos/scripts/{id}`：保存删改后的 2～5 个片段，保留阶段顺序和来源限制。

文字、图片和语音聊天现在共用按 `device_id` 区分的 SQLite 会话。生成回复前读取最近 12 条消息；成功后把用户消息和回复写入 `messages`，响应额外返回两个消息 ID。数据库在首次访问时自动创建于 `DATABASE_URL` 指定位置（默认 `server/confidence_agent.db`）。上传的原始图片、音频不写入服务端数据库，`media_ref` 仅保存 SHA-256 来源标识。

P1 在每轮回复保存后独立提取成长事件。程序按交接文档的五项权重和阈值裁决：高价值低敏感事件自动进入长期记忆，中等价值进入当日草稿，敏感、矛盾或低置信度事件等待用户确认。提取失败会记录服务端日志，不影响本轮回复；未启用真实模型时不自动写入事件。召回只使用带有效原始消息 ID 的已确认记忆，最多四条，`/chat` 和多模态响应中的 `evidence` 附有来源 ID、日期、类型和记忆 ID。旧 SQLite 数据库首次启动时自动补齐 P1 字段，现有消息保留。

P2 在用户主动求助、持续受阻或面临高难任务时把聊天策略设为 `seek_support`。建议从用户自己维护的支持圈中选择对象；没有人选时只给泛称，不假定真实关系。Android 可编辑、复制或主动打开系统分享面板，服务端不会联系任何人。反馈与建议关联，只有用户确认实际得到帮助后才写入 `support_received`；自己的尝试单独写入 `own_effort`。成长页读取真实事件，支持反馈也能追溯到原始反馈。P1 数据库首次启动时自动增补 P2 字段和新表。

P3 在打开应用后同步本地记录并补生成跨日回望；生成失败可在成长页重试。日、周、月回望分别返回对应的结构化栏目；周报告覆盖完成事项、困难、解决过程、变化与仍在继续，月总结覆盖主要经历、反复困难、变化和下一步。程序先从真实事件生成可靠模板；开启云端 AI 且同一周期至少有两条事件时，模型只根据来源字段精简组织语言。模型超时、不可用、栏目顺序错误或输出越界时自动保留模板；来源没有变化的已保存报告直接复用。月故事从真实事件中选最多六个关键节点，优先保留受阻经历。每份非空回望存入 `reviews`，记录 `source_event_ids`；同一日期重复生成会复用并在内容变化时更新。Android 节点能打开本机原始文字、转写、照片或语音；数据库自动增补 P3 字段和表。

P4 脚本由真实成长事件生成，记录模型、提示版本、来源和用户修改状态。未启用真实模型时返回明确标记的可编辑模板；模型响应格式无效时返回错误且不写库。Android 使用 Media3 Transformer 1.5.1 在本机合成 15～20 秒、720×1280 的 H.264 MP4，分享必须由用户主动触发。桌面组件默认不公开事件，开启后仅显示低敏感且未命中人物和明显敏感信息过滤的内容；该过滤不能保证识别所有私人信息，开启后应检查预览。

音频接口先让 `qwen3.8-omni-flash`输出转写，再把转写送入统一的小鸭对话服务，因此 Android 可以把“用户转写 + 小鸭回复”写回同一会话。第一版是轮次式录音，不是 WebSocket 全双工电话。

回复朗读使用 `qwen3-tts-flash`。后端先获取有效期 24 小时的临时音频 URL，再立即下载并把音频字节代理给 Android；客户端不接触百炼 Key，也不依赖临时 URL 的有效期。

## 启动

```powershell
python -m venv .venv
.\.venv\Scripts\python.exe -m pip install -r requirements.txt
if (-not (Test-Path .env)) { Copy-Item .env.example .env }
    .\.venv\Scripts\python.exe -m uvicorn app.main:app --host 0.0.0.0 --port 8000
```

- 健康检查：`http://127.0.0.1:8000/health`
- Swagger：`http://127.0.0.1:8000/docs`
- Android 模拟器访问宿主机：`http://10.0.2.2:8000`
- USB 真机访问宿主机：执行 `D:\jdk-11.0.28\platform-tools\adb.exe reverse tcp:8000 tcp:8000`，客户端即可访问 `http://127.0.0.1:8000`

后端的端口检查、健康检测、安全关闭和 USB 转发完整命令见项目根目录 [README.md](../README.md#后端启动检查与关闭)。

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
