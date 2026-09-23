# 小丑鸭 Confidence Agent

一款面向缺乏自信用户的温情 AI 成长陪伴 Android 应用。当前版本完成了四个主界面、文字聊天、图片理解、轮次式语音对话和可选云端音色；长期记忆、小组件与视频生成功能将在后续接入。

## 当前完成内容

### Android 静态前端

- **首页**：小丑鸭陪伴区、对话气泡、相关记忆、低压力行动建议、图片入口和语音电话入口。
- **成长**：日/周/月回望入口、成长时间线、个人努力与他人支持的双线记录、成长小片入口。
- **记录**：文字记录编辑区、语音/图片入口、保存区域和最近记录。
- **我的**：小丑鸭设置、记忆中心、隐私权限、数据导出、求助资源等设置入口。
- 多套透明背景的小丑鸭动作素材，避免所有页面使用同一姿态。
- 全局使用站酷快乐体，强化温暖、轻松的艺术字观感；字体依照 SIL Open Font License 1.1 随应用分发。

当前首页文字、图片和语音对话已接入后端，其余业务按钮仍用于展示布局。

### 后端骨架

- FastAPI 分层目录与配置管理。
- 健康检查、聊天、成长事件提取、一起向前支持建议、周期回顾接口。
- 聊天接口已支持真实百炼模型，未主动启用或调用失败时自动返回模拟数据。
- 图片接口支持 JPEG、PNG、WebP；语音页录制 16 kHz 单声道 WAV，由模型转写并回复。
- 回复默认由 `qwen3-tts-flash` 朗读，可在“我的—调整陪伴方式”选择 `Serena` 温柔女声、`Ethan` 温暖男声或系统声，并控制自动朗读和提示音；云端 TTS 失败自动回退到系统声。
- `一起向前`只生成联系谁、如何开口等建议，不替用户自动发送消息。

接口文档和启动方法见 [server/README.md](server/README.md)，模型与 API 决策见 [API与技术方案总结.md](API与技术方案总结.md)，当前状态、主干任务和具体实现顺序见 [开发交接_当前状态与待实现.md](开发交接_当前状态与待实现.md)。

## Android 运行

1. 使用 Android Studio 打开本目录。
2. 等待 Gradle Sync 完成。
3. 手机开启开发者选项和 USB 调试并连接电脑。
4. 在设备列表选中手机，点击 **Run app**。

## 后端启动、检查与关闭

以下命令均在 PowerShell 中执行。

### 1. 检查 8000 端口是否已有后端

```powershell
$listener = Get-NetTCPConnection -LocalPort 8000 -State Listen -ErrorAction SilentlyContinue | Select-Object -First 1
if ($listener) {
    Get-CimInstance Win32_Process -Filter "ProcessId = $($listener.OwningProcess)" |
        Select-Object ProcessId, Name, CommandLine
} else {
    Write-Output "8000 端口空闲，可以启动后端"
}
```

### 2. 启动后端

```powershell
cd D:\python\AI_creative\confidence_agent\server
python -m uvicorn app.main:app --host 0.0.0.0 --port 8000
```

该终端需要保持运行。另开一个终端检查服务：

```powershell
Invoke-RestMethod http://127.0.0.1:8000/health |
    Select-Object status, mock, ai_configured, chat_model
```

真实模型正常时应看到 `status=ok`、`mock=False`、`ai_configured=True`。

### 3. USB 真机端口转发

```powershell
D:\jdk-11.0.28\platform-tools\adb.exe reverse --remove-all
D:\jdk-11.0.28\platform-tools\adb.exe reverse tcp:8000 tcp:8000
D:\jdk-11.0.28\platform-tools\adb.exe reverse --list
```

不要直接输入 `adb`，本机 PATH 中优先找到的是雷电模拟器版本。

### 4. 安全关闭占用 8000 的后端

先检查占用进程，只有命令行中包含 `uvicorn app.main:app` 时才结束：

```powershell
$listener = Get-NetTCPConnection -LocalPort 8000 -State Listen -ErrorAction SilentlyContinue | Select-Object -First 1
if (-not $listener) {
    Write-Output "8000 端口没有正在运行的后端"
} else {
    $backendProcess = Get-CimInstance Win32_Process -Filter "ProcessId = $($listener.OwningProcess)"
    if ($backendProcess.CommandLine -match "uvicorn\s+app\.main:app") {
        Stop-Process -Id $backendProcess.ProcessId
        Write-Output "后端已关闭"
    } else {
        Write-Warning "8000 端口由其他程序占用，未自动关闭：$($backendProcess.CommandLine)"
    }
}
```

如果后端正在前台终端运行，也可以直接在该终端按 `Ctrl+C` 关闭。

也可以直接安装调试包：

```text
app/build/outputs/apk/debug/app-debug.apk
```

## 手机验收清单

1. **启动**：应用能正常启动，无闪退、白屏或明显卡顿。
2. **底部导航**：首页、成长、记录、我的四个页面可来回切换，选中状态正确。
3. **首页**：小丑鸭和卡片没有被裁切；长文本不溢出；输入框可以输入中文。
4. **成长**：时间线、双线成长卡和成长小片入口显示完整，页面能顺畅上下滚动。
5. **记录**：编辑区可以输入多行文字；最近记录能完整展示。
6. **我的**：所有设置项均可看到，滚动到底部无重叠。
7. **语音音色**：在“我的—调整陪伴方式”切换女声、男声和系统声；关闭自动朗读后可在语音页用播放按钮手动朗读。
8. **适配**：重点观察状态栏、底部系统导航栏、圆角屏和不同字体大小下是否遮挡内容。

除首页文字、图片和语音入口外，其余业务按钮点击后没有反应属于预期；请重点反馈页面裁切、间距、字号、颜色、图片姿态和滚动体验。

## 已确认的正式技术方案

- Android：Kotlin、Jetpack Compose、Material 3，最低 Android 8.0（API 26）。
- 后端：Python、FastAPI、Pydantic Settings。
- 云端模型：阿里云百炼 DashScope，由后端持有 `DASHSCOPE_API_KEY`。
- 多模态聊天：`qwen3.8-omni-flash`。
- 结构化提取与总结：`qwen3.7-flash`。
- 语音对话与转写：第一版使用 `qwen3.8-omni-flash`直接理解 WAV；后续需要专业长录音转写时切换 `paraformer-v2`。
- 向量嵌入：`qwen3.7-text-embedding-flash`，768 维。
- 语音播报：`qwen3-tts-flash`（`Serena` / `Ethan`），Android 系统 TTS 作为离线/失败回退。

API Key 不写入 Android APK，也不提交到 GitHub；只放在服务端 `.env` 中。

## 暂未实现

- 真正全双工的实时语音、打断检测、回声消除和独立安全分类。
- 视频理解的 Android 入口。
- 分层长期记忆、日/周/月自动总结。
- 成长小片视频生成与分享。
- 桌面小组件。
- 登录、数据库、云同步和正式隐私流程。
