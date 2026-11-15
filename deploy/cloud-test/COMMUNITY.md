# 受邀云测社区

社区面向已有邀请码授权的体验者，沿用设备凭据。社区昵称、预设头像和公开作者 UUID 独立于私人画像。更换手机或丢失设备授权后，本轮不提供社区账号找回。

## 客户端与接口

- 云端共享帖子，按时间分页（每页20条）、搜索标题/正文、话题过滤、我的帖子。
- 标题1—50字、正文最多2000字，正文或图片至少一项；最多3张图片。评论1—500字，单层展示。
- 发布前设置社区昵称并确认公开范围。记录导入只复制用户点选的文字/照片到可编辑草稿；请求中不含私人记录 ID。旧本机帖子保留，通过“替换草稿并编辑”手动重新发布。
- 点赞可重复请求且只保留一条，帖子/评论带客户端发布编号；发布超时后保留相同编号与内容重试。草稿不自动发送。首页缓存最近100条文字；离线图片需联网重新验证。
- 可删除自己的帖子/评论、举报、屏蔽与解除屏蔽。举报分骚扰、隐私、灌水、危险内容、其他；同一用户对同一内容去重，不按举报次数自动下架。
- 删除全部数据包含自己的社区身份、帖子、评论、点赞、举报、屏蔽和本机草稿/缓存。公开作者 UUID 留匿名墓碑以维护数据库引用。审计与35天内的匿名额度计数属于运维记录。社区内容不写入私人记忆、压力分析或短信链路。
- 数据导出含 `cloud_community` 和本机草稿；ZIP同时下载本人仍可见的社区图片。下载失败的图片附说明文件，避免静默缺项。
- 视频继续支持本机保存、系统分享；云社区不上传视频。

所有 `/api/v1/community/*` 请求均校验设备授权与请求设备编号。社区写操作独立计数，不消耗模型日额度：每设备每日发帖10、评论50、图片上传30次（北京时间），仍受原有每设备每分钟90次请求限制。删除社区身份不会重置当天额度。

## 图片与存储

`compose.yaml` 挂载宿主机 `/opt/ugly-duckling-cloudtest/community-media` 到容器 `/community-media`；首次部署先执行：

```bash
install -d -o 10001 -g 10001 -m 700 /opt/ugly-duckling-cloudtest/community-media
```

只接收有效 JPEG/PNG/WebP，每张最多5MB、2000万像素；服务端按 EXIF 方向校正、最长边1600像素、统一 JPEG 并清除 EXIF。网关对图片入口设置6MB请求上限（含multipart开销）。图片仅能随本人帖子绑定一次，未绑定图仅上传者可读，已删除/下架图对用户立即不可访问；管理员可检查下架图。默认总容量2GiB，包含待清理文件；满额返回507，文字发布继续可用。

每小时执行 `python -m app.community_admin cleanup-media`：未绑定或中断遗留文件24小时后清理，删除的媒体保留7天后物理清理，过期匿名额度计数35天后清理。清理任务使用容器的现有数据库及媒体挂载，不调用模型。

## 私有管理员入口

公网 Caddy 不开放 `/community-admin*`。后端另外检查本地 Host、私有连接地址、拒绝代理转发头；管理员使用独立密码、两小时 HttpOnly/SameSite 会话及 CSRF 校验。仅经 SSH 本地转发访问。

首次账号创建（不把密码放在命令行参数、Git或日志中）：

```bash
docker exec -it ugly-duckling-cloudtest-api-1 python -m app.community_admin create --username admin
```

Windows运行 `Open-CommunityAdmin.ps1`，自动创建仅监听127.0.0.1的SSH隧道并打开管理页。默认端口18010，需已有项目SSH密钥和校验过的known_hosts。脚本输出隧道PID，关闭时仅结束该PID。

可浏览举报、帖子与图片、评论、社区用户、操作记录；举报可点“查看被举报内容”后下架/恢复、限制作者发布，再标记已处理。限制只影响社区发帖/评论/上传图片，不影响私人陪伴。已被作者删除的内容不能恢复。每次管理操作必填原因，并记录管理员、对象、动作、时间。

## 成对备份与恢复

现有日备份命令不变：

```bash
docker exec ugly-duckling-cloudtest-api-1 python backup.py --output /backups
```

每份SQLite快照附同名 `.media.json`，记录当时仍存在的帖子图片 UUID、字节数及 SHA256。图片放在同一备份目录的 `community-media/` 中，只复制一次；保留最近7份数据库与清单，删除不再被保留清单引用的备份图片。备份失败不留下不成对的数据库。目录级文件锁阻止备份与轮换并发运行。

恢复需要停服：选择数据库及同名清单，校验清单中全部图片的 SHA256，将数据库恢复到 `data/confidence_agent.db`，将清单引用图片复制回媒体目录并恢复10001所有权；保留当前库/图片以便回退，检查 SQLite integrity/foreign_key，再启动对应版本。禁止直接覆盖运行中的SQLite数据库，也不要单独恢复媒体目录。备份可能含已删除的旧内容，仅供受控运维恢复。

## 验证入口

- 后端：`python -m pytest tests/test_community.py tests/test_community_backup.py -q`，覆盖两个身份、并发重试、额度、图片、审核、删除和成对备份。
- `community-smoke.py`：在新容器内运行，使用真实公网 HTTPS、两个临时受邀身份及本机私有管理口，结束后删除临时内容并撤销凭据；不调用模型、不修改真实用户记录、不读取正常管理员密码。
- Android：`CloudCommunityInstrumentedTest` 仅在 `.local` 安装运行，对接独立临时 FastAPI 数据库；`CloudCommunityStoreInstrumentedTest` 使用独立偏好/目录验证发布编号跨实例恢复。
- 真机使用 `localDebug` 的仪器测试时临时设置 `android.testBuildType = 'localDebug'`，正式云测仍用默认 `debug`。构建分别执行并使用单编译进程，避免本机Kapt多个变体的服务注册冲突。

本地仪器测试构建命令（不会切换正常云测构建的配置）：

```powershell
.\gradlew.bat -I deploy/cloud-test/community-test.init.gradle :app:assembleLocalDebug :app:assembleLocalDebugAndroidTest --no-parallel --max-workers=1 "-Pkotlin.compiler.execution.strategy=out-of-process"
```

测试端点默认为手机本机 `http://127.0.0.1:8769`，通过 `adb reverse tcp:8769 tcp:8769` 连接电脑上的独立FastAPI实例。该实例必须设置临时SQLite路径、`CLOUD_AUTH_REQUIRED=false`、`ENABLE_LIVE_AI=false` 和独立媒体目录。安装生成的两个localDebug APK后，仅运行 `CloudCommunityInstrumentedTest`、`CloudCommunityStoreInstrumentedTest`；它们会清理本地测试安装与临时后端中本次身份的数据，禁止指向真实用户库。

验证输出、截图和部署记录放在本机仓库外的维护文档目录，不提交用户数据、凭据或生成媒体。

## 2026-11-15 实施与验收

- 分支 `codex/cloud-community`；云端后端发布 `20261115-hk08-6eefebf`，镜像源提交 `6eefebf96ff6075e498d12eb5db9021e64395f77`。后续客户端修复不改变已部署后端的业务代码。
- Windows后端全套268项通过；Linux新镜像隔离测试267项通过、1项跳过（容器不包含Android源文件，跳过客户端问卷选项对照检查；该项在Windows仓库内通过）；新增10项社区/备份测试包含并发请求去重、删除后额度保持、图片容量与权限、审核及备份轮换。
- 生产库副本升级：原有24张表的行数和值指纹均保持不变，增加11张社区表，integrity/foreign_key检查通过。上线前备份在 `/backups/pre-hk08`，上线后成对备份已生成；未修改真实用户记录。
- 公网双受邀身份HTTP验收16项通过，涵盖授权绑定、上传与EXIF清理、发布/评论重试、点赞、作者权限、屏蔽/解除、审核与图片访问、限制发布、本人导出、删除及公网管理入口关闭；临时身份撤销，临时内容删除。正常管理员账号保留。
- Android云测/本地两个APK构建通过，22项JVM测试通过。Redmi真机7项社区流程在正常屏幕和约280dp窄屏均通过；核验帖子/评论通过实际API写入，未使用演示帖子。测试使用独立本地包和临时数据库，云测安装只覆盖更新、不清空数据。
- 真机发现旧Compose 1.7.0的失活节点测量崩溃，BOM更新为 `2024.10.01`（Compose 1.7.5、Material3 1.3.1），并明确列表页面/区块身份；修复后完整发布、输入及图片流程通过。上游修复说明：[Compose Foundation 1.7.4](https://developer.android.com/jetpack/androidx/releases/compose-foundation#1.7.4)。
- Android 16复核曾出现旧Espresso等待布局时的重入异常；测试依赖更新为Espresso 3.7.0 / AndroidX JUnit 1.3.0后，完整7项重新通过。这些依赖仅用于测试；本地测试宿主也仅编入localDebug，云测安装不包含测试宿主。
- 正式云测安装已读取香港共享信息流，首次发布要求独立昵称，编辑页系统返回键回到社区并恢复底部导航；未代替用户发布帖子。SSH隧道私有管理页面HTTP200验证通过，临时验证隧道已关闭。
- 本轮仍不包含视频上传、多端账户找回、私信或关注。这些入口不以虚构数据替代。社区人工审核由管理员负责，页面在处理前即时可见。

完整日志与截图：本机 `E:\docs\maintenance\2026-11-15\community-hk08`。初始管理员密码保存在本机仅当前用户可读的 `.codex_tmp/community-admin-credentials.json`，以及服务器root私有文件 `/root/yaligo-community-admin.json`；密码未写入Git或验收日志。
