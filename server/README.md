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
- `POST /api/v1/support/suggest`：支持圈建议，当前仍为 Mock。
- `GET /api/v1/reviews/{period}`：周期回顾，当前仍为 Mock。

文字、图片和语音聊天现在共用按 `device_id` 区分的 SQLite 会话。生成回复前读取最近 12 条消息；成功后把用户消息和回复写入 `messages`，响应额外返回两个消息 ID。数据库在首次访问时自动创建于 `DATABASE_URL` 指定位置（默认 `server/confidence_agent.db`）。上传的原始图片、音频不写入服务端数据库，`media_ref` 仅保存 SHA-256 来源标识。

P1 在每轮回复保存后独立提取成长事件。程序按交接文档的五项权重和阈值裁决：高价值低敏感事件自动进入长期记忆，中等价值进入当日草稿，敏感、矛盾或低置信度事件等待用户确认。提取失败会记录服务端日志，不影响本轮回复；未启用真实模型时不自动写入事件。召回只使用带有效原始消息 ID 的已确认记忆，最多四条，`/chat` 和多模态响应中的 `evidence` 附有来源 ID、日期、类型和记忆 ID。旧 SQLite 数据库首次启动时自动补齐 P1 字段，现有消息保留。

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
