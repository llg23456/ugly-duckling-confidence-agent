# 导师建议改进：真机验收记录

日期：2026-10-03。设备：REDMI K80 Ultra（25060RK16C），Android 16 / API 36。

## 环境与结果

- 独立安装 `com.testconnection.confidence_agent.p0test`，通过 USB 反向端口连接本机 8787。
- 使用独立 SQLite、Mock 后端及合成的测试记录；原应用的数据和后端配置不参与测试。
- 10 项不同的真机测试全部通过，包含 5 项界面流程和原有 5 项设备测试。
- 窄屏复查：临时调整到 960×2080、520 dpi（约 295 dp 宽），完成后恢复 1280×2772。
- 后端 40 项测试、Android 11 项 JVM 测试通过；普通 Debug 与隔离测试 APK 构建成功。

| 验收内容 | 结果 |
| --- | --- |
| 起步、尝试、成长三阶段图片及不同日期的依据 | 通过；使用测试记录验证，不冒充个人经历 |
| 离线画像、关键词、前后变化、完整画像及历史 | 通过 |
| 离线查看保存在本机的来源原文 | 通过 |
| 删除来源、来源内容修改、待同步删除不复活 | 通过 |
| 师兄师姐选项与真实 API 保存 | 通过 |
| 四周考研演示、日回望展开、周报告、主题搜索及脚本 | 通过 |
| 无配音小片生成、装饰插画标识、手机播放 | 通过；播放位置实际超过 500 ms |
| JSON 中的本地画像历史、全部删除后回到首次认识 | 通过 |
| 窄屏下的三阶段、离线原文和支持圈操作 | 通过 |

手机生成的视频为 3 个片段、14.188 秒、540×960，视频 H.264、音轨 AAC；无配音音轨为静音。
本地证据位于被 Git 忽略的 `server/tmp/device-acceptance/`：三阶段截图、离线原文、画像历史、日回望、支持圈、删除结果、视频预览，以及 `phone-growth.mp4`。

## 真机发现并修复

1. 演示记录曾计入个人成长阶段：现在排除 `source_type=demo`，演示仍可用于回望与视频。
2. 离线打开原文缺少服务器 ID 到本机 ID 的映射：记忆中心现在读取持久化映射。
3. 支持圈、周报告、视频页面贴近系统栏：补齐系统栏安全区域，视频输入适配键盘。
4. 配音文字点击无效：整行改为具有选中状态的单选控件。
5. 窄屏的阶段说明不自然换行：按宽度缩小阶段图片，保留文字空间。
6. 更新旧的演示日期、个人页入口说明。

## 复现

先启动独立测试服务；如 FFmpeg 不在 PATH，用 `--ffmpeg` 指定本机路径：

```powershell
.\server\.venv\Scripts\python.exe .\server\scripts\run_device_acceptance_server.py
```

另一个终端执行（多设备时为 adb 加上 `-s <序列号>`）：

```powershell
.\gradlew.bat -PisolatedP0Test=true -PapiBaseUrl=http://127.0.0.1:8787 :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb reverse tcp:8787 tcp:8787
adb shell am instrument -w com.testconnection.confidence_agent.p0test.test/androidx.test.runner.AndroidJUnitRunner
```

界面测试仅在 `.p0test` 安装中运行，自动跳过普通包。小米手机须允许 USB 安装；屏幕点击流程须开启 USB 调试安全设置。测试采用 shell 启动界面以兼容后台启动限制。

## 验证范围

真实云端画像提取、云端语音配音，以及麦克风、相机和系统分享目的地未在本轮验证。其配置与权限需单独联调。本轮没有发送测试内容给联系人。
