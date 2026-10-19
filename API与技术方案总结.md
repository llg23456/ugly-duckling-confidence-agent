# 小丑鸭｜API 与技术方案总结

> 当前状态：2026-10-16。本文按仓库现有实现整理；规划中的能力会明确标为“暂未实现”。

## 1. 已确认技术结论

- Android：Kotlin、Jetpack Compose、Material 3，保持 `minSdk 26 / targetSdk 36 / compileSdk 36`。
- 服务端：FastAPI、Pydantic v2、SQLAlchemy、SQLite。
- 模型平台：阿里云百炼（DashScope）；模型密钥只保存在服务端环境变量中。
- 客户端只访问自有 FastAPI，不直接连接模型供应商，也不保存 API Key。
- 原始照片、录音、社区媒体与完整生活记录本地优先；服务端只接收聊天、检索、回望或生成所需内容。
- 已实现文字、图片、语音聊天，分层记忆，日周月回望，支持圈与反馈，主动关心，压力趋势提醒，成长小片，数据导出和删除闭环。
- 压力趋势是辅助提醒，不是疾病诊断，不输出疾病名称、诊断概率或医疗结论。

## 2. 模型分工

| 任务 | 当前模型或实现 |
|---|---|
| 文字、图片、音频聊天 | `qwen3.8-omni-flash` |
| 事件提取、记忆分级、周月总结、安全分类 | `qwen3.7-flash` + JSON Schema |
| 短语音理解与聊天转写 | 第一版由 `qwen3.8-omni-flash` 直接理解本地 WAV |
| 长录音或专业 ASR | 预留 `paraformer-v2`，暂未作为主链路 |
| 长期记忆检索 | `qwen3.7-text-embedding-flash`，固定 768 维；无云端时保留关键词召回 |
| 语音朗读 | `qwen3-tts-flash`；`Serena`、`Ethan`，失败时回退 Android 系统 TTS |
| 压力趋势 | 服务端确定性规则 `stress-rule-v1`，不调用诊断模型 |
| 成长小片 | Android 准备画面与媒体，服务端使用 FFmpeg 合成 H.264/AAC MP4 |

同一个百炼 API Key 可调用当前配置的模型。模型名、Base URL、超时与 FFmpeg 路径均从服务端配置读取。

## 3. 密钥与环境变量

真实值只写入 `server/.env`，该文件必须被 Git 忽略。仓库提交的 `.env.example` 为：

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
APP_ENV=development
DATABASE_URL=sqlite:///./confidence_agent.db
```

Android 不得把 Key 写入 `BuildConfig`、`local.properties`、APK、日志、截图或 GitHub。后端地址只把 `BuildConfig` 当作首次安装默认值；运行时可从“帮助与求助资源”自动检测或手动修改，验证 `/health` 的服务标识后保存到本机偏好，全部 API 客户端实时读取同一地址。

## 4. 核心数据流

### 4.1 对话、记忆与回望

```text
Android 文字 / 图片 / 语音
    ↓
FastAPI 校验输入并保存会话
    ↓
语音转写 / 图片理解 / 相关记忆与记录召回
    ↓
生成结构化陪伴回复
    ↓
独立执行 memory-v2 原子记忆提取
    ↓
规则计算置信度、价值、敏感度与确认状态
    ↓
写入成长事件和记忆，按需失效日周月缓存
    ↓
Android Room 先显示本机历史，再与服务端同步
```

自然回复、成长事件和长期记忆分开保存。助手给出的建议不能被写成用户已完成的事实；用户的否定、纠正、计划、忘记请求和高敏感内容分别处理。首页只展示模型回复实际使用的记忆或记录来源。

### 4.2 主动关心

```text
跨日同类受阻 / 紧张 / 低落，或用户明确要求之后追问
    ↓
创建 ProactiveCheckIn
    ↓
先显示一次“小鸭记住了”确认
    ↓
下次打开 App 时显示正式问候
    ↓
用户选择继续聊 / 好一点 / 暂时不说
    ↓
写入回应并进入对应冷却状态
```

明确危险表达不进入普通主动关心或压力趋势，而是在当前对话中立即使用安全响应。

### 4.3 压力趋势

```text
聊天或主动记录产生新的 GrowthEvent
    ↓
筛选最近 7 个本地日期的有效用户来源
    ↓
按天归类 steady / watch / high
    ↓
跨日阈值生成或更新 StressAlert
    ↓
首页一次性预警 → 压力报告 → 自评 / 支持 / 回访
```

压力分析失败或后端离线不会阻断聊天缓存、记录保存和其他页面。

## 5. Android 结构与状态

```text
app/src/main/java/com/testconnection/confidence_agent/
├─ data/model
├─ data/preferences
├─ data/remote
├─ data/repository
├─ ui/components
├─ ui/screens
├─ ui/theme
├─ widget
└─ MainActivity.kt
```

- `ChatRepository` 管理 Room 历史、消息同步与多模态请求。
- 各功能 API 客户端统一从 `ServerEndpoint.current()` 读取运行时后端地址。
- 成长页采用本地缓存先显示、后台按需刷新；聊天、记录和求助反馈会标记相关缓存失效。
- 压力提醒由 `HomeViewModel` 在进入首页、同步历史、发送聊天或接收场景结果后刷新。
- 新压力提醒先显示朦胧红色蒙版与预警图标，约一秒后弹出“压力很大—压力报告”。
- 报告首页只保留三档仪表盘和带横纵坐标的近 7 天趋势；判断依据、快速自评、支持与回访分别进入独立页面。
- 用户打开报告时，客户端用 `alertId:createdAt` 记录“已查看”状态并移除同一提醒的首页卡片；返回首页或重启 App 不重复展示。该操作只控制本机首页入口，不等同于服务端 `dismissed`，新提醒仍可再次出现。
- 自评按钮未完成两题时给出提示，提交期间显示保存中，成功后显示调整后的建议等级，失败时就地提示后端连接问题。
- 界面装饰图位于 `app/src/main/res/drawable-nodpi/stress_*_art.png`，图表使用 Compose Canvas，没有引入图表依赖或持续闪烁动画。

## 6. 服务端结构

```text
server/
├─ app/api/routes
├─ app/core
├─ app/db
├─ app/schemas
├─ app/services
├─ tests
├─ .env.example
└─ requirements.txt
```

服务启动后统一使用 `/api/v1` 前缀；`GET /health` 例外。主要接口组如下：

| 接口组 | 已实现能力 |
|---|---|
| `/health` | 服务标识、运行环境、真实 AI 开关与模型状态 |
| `/api/v1/chat` | 文字聊天、结构化策略、来源证据、历史保存 |
| `/api/v1/multimodal/*` | 图片聊天、语音聊天、单独转写、TTS 音频代理 |
| `/api/v1/onboarding/*` | 首次画像字段、合并回答、缺项追问与画像刷新 |
| `/api/v1/events/*` | 成长事件提取、事件列表、日摘要 |
| `/api/v1/memories/*` | 记忆列表、编辑、确认和删除 |
| `/api/v1/records/*` | 主动记录同步、照片描述、查询和删除 |
| `/api/v1/reviews/*` | 日周月聚合回望与待处理日报 |
| `/api/v1/support*` | 支持建议、结果反馈、支持圈联系人 CRUD |
| `/api/v1/check-ins/*` | 待问候、到期提示和回应 |
| `/api/v1/videos/*` | 主题建议、脚本、编辑与 FFmpeg 渲染 |
| `/api/v1/data/*` | 服务端数据导出和删除全部数据 |
| `/api/v1/stress-alerts/*` | 压力提醒、场景、自评、回访和关闭 |

`/multimodal/speech` 只允许 `Serena` 和 `Ethan`。服务端使用百炼 Key 获取短时音频 URL，再下载并代理给 Android；Key 和供应商 URL 不会进入 APK。

## 7. 压力趋势方案

### 7.1 数据范围与排除规则

仅分析用户主动提交到 App 的聊天与记录，不读取：

- 其他应用内容；
- 定位信息；
- 手机使用时长；
- 可穿戴设备数据。

真实分析要求成长事件仍能追溯到 `source_user_message_id` 或 `source_record_id`。以下内容不进入趋势：

- 单日普通抱怨；
- 明确否定或明确好转；
- 仅描述未来可能发生的压力；
- 高敏感事件；
- 自伤、伤人等急性危险表达；
- 内部普通演示数据或来源已被删除的事件。

压力场景使用独立 `source_type=stress_scenario`，只在场景分析中读取，`memory_decision=ignore`，不参与真实压力提醒、个人画像或成长阶段。

### 7.2 七日判定规则

每个有效日期取当天最高等级：

- `steady`：没有压力信号；
- `watch`：存在一般压力、紧张、担忧、疲惫或不安信号；
- `high`：出现明确高压力，或已经影响睡眠、专注、饮食、学习、工作与日常生活。

跨日阈值：

- 两个不同日期达到 `high`，生成 `high` 提醒；
- 三个不同日期存在压力信号且最近一个信号日为 `high`，生成 `high` 提醒；
- 两个不同日期至少达到 `watch`，生成 `watch` 提醒；
- 其余情况不提醒。

同一真实提醒处于 `active / confirmed / follow_up_scheduled` 时会更新趋势与来源，不重复创建。用户主动关闭后 7 天内不会因同类旧内容立即重新生成。

### 7.3 两问自评

请求字段：

- `impact`：`none / some / significant`；
- `coping`：`manageable / strained / overwhelmed`。

调整规则：

- `significant`、`overwhelmed`，或 `some + strained`，调整为 `high`；
- `none + manageable`，调整为 `watch`；
- 其他组合保留原等级。

自评结果写入 `StressAlert.self_check`，调整后的等级写入 `confirmed_level`，状态变为 `confirmed`。

### 7.4 数据表

`StressAlert` 保存：

- `level / confirmed_level / status`；
- `source_event_ids / trend / factors`；
- `self_check / actions`；
- `is_scenario / follow_up_check_in_id`；
- 创建与更新时间。

`ProactiveCheckIn` 增加 `available_after`，压力回访使用 `reason=stress_follow_up`。真实回访只有到期后才可见；场景回访可以通过专用接口把到期时间推进到当前时间。

### 7.5 压力提醒接口

| 方法 | 路径 | 用途 |
|---|---|---|
| `GET` | `/api/v1/stress-alerts/active?device_id=...` | 获取当前活动提醒 |
| `GET` | `/api/v1/stress-alerts/{id}?device_id=...` | 获取报告详情与来源 |
| `POST` | `/api/v1/stress-alerts/prepare-scenario` | 清理上一批同类场景并准备新的跨日记录 |
| `DELETE` | `/api/v1/stress-alerts/scenario?device_id=...` | 清理压力场景、关联提醒和回访 |
| `POST` | `/api/v1/stress-alerts/{id}/self-check` | 提交两问自评 |
| `POST` | `/api/v1/stress-alerts/{id}/follow-up` | 安排次日回访 |
| `POST` | `/api/v1/stress-alerts/{id}/follow-up/ready` | 仅场景使用，推进回访到期时间 |
| `POST` | `/api/v1/stress-alerts/{id}/dismiss` | 关闭提醒并取消仍待处理的关联回访 |

所有详情和写接口都会校验 `device_id` 对应的会话归属，其他设备访问返回 404。

### 7.6 一致性与导出

- 聊天和主动记录产生新成长事件后，后台重新评估真实压力趋势。
- 删除来源消息或记录时，同步失效不再满足条件的提醒。
- 删除全部数据时，先删除压力提醒，再删除回访、成长事件和会话。
- 服务端导出增加 `stress_alerts` 与 `stress_alert_evidence`；后者包含每条提醒引用的完整成长事件。
- 场景准备会先删除旧场景提醒、旧场景事件和关联回访，重复执行不会叠加三套数据。

## 8. 已实现边界与暂未实现

### 已实现

- 关键词与 768 维向量混合记忆召回；无云端时保留关键词路径。
- 本机真实发布与持久化的社区内容，以及内置展示内容；不是联网公开社区。
- Android 画面准备、服务端 FFmpeg 视频合成、预览、保存、分享和社区发布。
- 三个 Glance 桌面组件及用户主动选择展示内容的隐私开关。
- JSON/ZIP 数据导出、单条来源删除和删除全部数据。
- 压力提醒真实触发与所有构建版本可用的快速场景入口。

### 暂未实现

- 真正全双工实时语音、打断检测与回声消除；
- 视频内容理解的 Android 入口；
- 登录、多设备账号同步和联网公开社区；
- 自动联系老师、同学、家人或朋友；
- 定位、手机使用时长或可穿戴设备分析；
- 医疗诊断、心理疾病判断和诊断概率。

## 9. 当前验证状态

截至 2026-10-16：

- 后端完整测试：`71 passed`；
- 压力功能定向测试：7 项通过，覆盖跨日阈值、排除规则、自评、设备归属、重复准备、回访、来源删除、导出与清理；
- Android JVM：17 项通过，其中压力接口解析与等级、回访映射 2 项；
- Android Debug/Release Kotlin 编译通过；
- 本轮未执行 APK 的 `assemble`、`bundle`、签名或安装；
- 2026-10-03 的既有真机验收不包含最新压力报告界面，最新视觉与完整点击流程仍需下一轮手机复查。

## 10. 官方资料

- 百炼 OpenAI 兼容接口：https://help.aliyun.com/zh/model-studio/qwen-api-via-openai-chat-completions
- 百炼结构化输出：https://help.aliyun.com/en/model-studio/qwen-structured-output
- Qwen Omni：https://help.aliyun.com/en/model-studio/qwen-omni
- Qwen 非实时语音合成：https://help.aliyun.com/zh/model-studio/non-realtime-tts-user-guide
- Qwen TTS 音色列表：https://help.aliyun.com/zh/model-studio/qwen-tts-voice-list
- 百炼 API Key：https://help.aliyun.com/zh/model-studio/get-api-key
- Android Compose BOM：https://developer.android.com/develop/ui/compose/bom
