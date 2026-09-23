# 小丑鸭｜API 与技术方案总结

## 1. 已确认结论

- 模型平台：阿里云百炼（DashScope）。
- 密钥：服务端环境变量 `DASHSCOPE_API_KEY`，Android 不保存任何模型密钥。
- Android：Kotlin、Jetpack Compose、Material 3，当前保持 `minSdk 26 / targetSdk 36 / compileSdk 36`。
- 服务端：FastAPI + Pydantic v2，比赛版先用 SQLite。
- 原始照片、录音与完整记录本地优先；服务端仅接收推理所需内容。

## 2. 模型分工

| 任务 | 模型 |
|---|---|
| 文字、图片、音频聊天 | `qwen3.8-omni-flash` |
| 事件提取、记忆分级、周月总结、安全分类 | `qwen3.7-flash` + JSON Schema |
| 短语音陪伴与转写 | 第一版由 `qwen3.8-omni-flash`直接理解本地 WAV |
| 长录音或专业 ASR | 后续使用 `paraformer-v2` |
| 长期记忆检索 | `qwen3.7-text-embedding-flash`，固定 768 维 |
| 语音朗读 | `qwen3-tts-flash`；`Serena` 温柔女声、`Ethan` 温暖男声，系统 TTS 回退 |

同一个百炼 API Key 可以调用上述模型。模型名称、Base URL 和超时均通过服务端配置读取，业务代码不写死厂商参数。

## 3. 密钥与环境变量

真实值只写入 `server/.env`，该文件必须被 Git 忽略。仓库只提交：

```env
ENABLE_LIVE_AI=false
DASHSCOPE_API_KEY=
DASHSCOPE_BASE_URL=https://dashscope.aliyuncs.com/compatible-mode/v1
DASHSCOPE_TTS_URL=https://dashscope.aliyuncs.com/api/v1/services/aigc/multimodal-generation/generation
CHAT_MODEL=qwen3.8-omni-flash
TTS_MODEL=qwen3-tts-flash
EXTRACTION_MODEL=qwen3.7-flash
EMBEDDING_MODEL=qwen3.7-text-embedding-flash
ASR_MODEL=paraformer-v2
```

Android 只访问自己的 FastAPI；不得把 Key 放进 `BuildConfig`、`local.properties`、APK、日志、截图或 GitHub。

## 4. 数据流

```text
Android 文字/图片/语音
    ↓
FastAPI 校验输入与隐私选项
    ↓
语音转写 / 图片理解 / 相关记忆检索
    ↓
生成自然陪伴回复
    ↓
独立进行结构化事件提取
    ↓
规则计算记忆价值、敏感度和是否需要确认
    ↓
返回回复与候选记忆，用户可查看、纠正或删除
```

自然回复与长期记忆写入必须分开：模型的一次理解不能直接变成永久记忆。

## 5. Android 结构

```text
app/src/main/java/.../
├─ data/model
├─ data/repository
├─ ui/components
├─ ui/screens
├─ ui/theme
└─ MainActivity.kt
```

静态展示内容继续由 `FakeConfidenceRepository` 提供；首页聊天已经通过独立 `ChatRepository` 接入后端，后续功能可以沿用同一分层方式扩展。

## 6. 服务端结构

```text
server/
├─ app/api/routes
├─ app/core
├─ app/schemas
├─ app/services
├─ tests
├─ .env.example
└─ requirements.txt
```

除聊天外的初始接口返回稳定的 Mock JSON；聊天接口在 `ENABLE_LIVE_AI=true` 且 Key 有效时调用百炼，否则安全回退到 Mock：

- `GET /health`
- `POST /api/v1/chat`
- `POST /api/v1/multimodal/image`
- `POST /api/v1/multimodal/audio`
- `POST /api/v1/multimodal/speech`：JSON `text` + `voice`，直接返回音频字节
- `POST /api/v1/multimodal/transcribe`：只转写语音，不生成聊天回复
- `GET /api/v1/onboarding/schema`：初始画像字段和开场文案
- `POST /api/v1/onboarding/analyze`：合并本轮回答、返回缺项和下一句追问
- `POST /api/v1/events/extract`
- `POST /api/v1/support/suggest`
- `GET /api/v1/reviews/{period}`

`/multimodal/speech` 只允许 `Serena` 和 `Ethan`，服务端使用同一百炼 Key 获取短时音频 URL，再下载并代理给 Android，Key 和供应商 URL 均不会进入 APK。Android 本地持久化音色、自动朗读和提示音偏好。

队友后续只替换 service 层或新增可选字段，不改变 Android 已使用的响应结构。主线落地契约见 `../小丑鸭_队友主干功能接手指南.md`。

## 7.1 初始画像边界

- 不通过声音或照片猜测性别、年龄和身份，只提取用户明确表达的信息。
- 每个字段都有状态：真实值、`unknown`、`prefer_not_to_say` 或 `none`。
- 后端模型负责提取，程序根据字段表判断是否完成；缺项才继续追问。
- Android 使用本地 JSON 保存当前画像，正式数据库由后续主干任务迁移。

## 7. 暂不实现

- 长期记忆算法、向量数据库。
- 真正全双工语音、打断检测与回声消除。
- 登录、多设备同步、公开社区。
- 自动联系老师、同学或朋友。
- 真实视频合成与公开分享。
- 医疗诊断或心理疾病判断。

## 8. 官方资料

- 百炼 OpenAI 兼容接口：https://help.aliyun.com/zh/model-studio/qwen-api-via-openai-chat-completions
- 百炼结构化输出：https://help.aliyun.com/en/model-studio/qwen-structured-output
- Qwen Omni：https://help.aliyun.com/en/model-studio/qwen-omni
- Qwen 非实时语音合成：https://help.aliyun.com/zh/model-studio/non-realtime-tts-user-guide
- Qwen TTS 音色列表：https://help.aliyun.com/zh/model-studio/qwen-tts-voice-list
- 百炼 API Key：https://help.aliyun.com/zh/model-studio/get-api-key
- Android Compose BOM：https://developer.android.com/develop/ui/compose/bom
