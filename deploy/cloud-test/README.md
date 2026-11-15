# 云测服务器：使用、连接与更新

## 当前在线发布（2026-11-15 16:47，北京时间）

本次已按用户授权完成实际 SSH 部署，在线发布为 `20261115-hk07-6f750ce`，业务源提交为 `6f750ce`（已包含 `83e7a21` 的问卷合并修复及同事的压力度量功能）。下方早前“队友部署、未验证SSH”等描述保留为历史记录，不能用于判断本次连接或上线状态。

- 发布前备份：`/opt/ugly-duckling-cloudtest/backups/pre-hk07/confidence-agent-20261115T084402249537Z.sqlite3`；上一版 `20261115-hk06-c9ff668` 保留，可用于回退镜像。
- 新 Linux 镜像257项后端测试通过、1项跳过（Android题库一致性在本地已通过，后端镜像不含Android源码）。测试使用隔离临时库、禁用网络和模型，不接触生产数据。
- 在生产备份的独立副本上执行自动升级，原18张表旧列的全部数据保持一致；六张压力相关表和 `messages.image_observation` 字段存在，SQLite完整性及外键检查通过。没有用本地数据库覆盖生产库。
- 公网受邀业务验收19项通过：自评保存、情绪不直接替代压力分数、真实模型证据、权重调整、异步记录评分、删除来源重算、7/30/365天趋势、问卷导出兼容、数据删除及401/403边界。另有公网5项基础检查通过。
- Compose显式固定 `NOTIFICATION_MODE=disabled`、`WELLBEING_MODEL_TIMEOUT=15`；云端手机短信启用和领取接口均返回409，通知没有发送。模型Key、数据库及授权沿用服务器现有配置。
- 验收仅使用独立合成身份，数据已删除、凭据已撤销；本次没有重新进行手机界面验收。健康接口和真实聊天模型均已分别验证，不能将单次响应耗时视作并发性能结论。

可复现脚本为 `deploy/cloud-test/wellbeing-smoke.py`；会产生少量真实模型调用费用，并在结束时清理其测试身份的数据。执行：`docker compose -f deploy/cloud-test/compose.yaml exec -T api python - < deploy/cloud-test/wellbeing-smoke.py`。

本次JSON报告位于本机外层 `../docs/maintenance/2026-11-15/wellbeing-hk07/`，服务器保留 `/backups/wellbeing-verification.json` 及当前发布目录中的升级验证结果。镜像仅发布后端；手机需要运行包含压力功能的云测 `debug` 版。

## 早前连接检查记录（2026-11-15）

- 公网入口：`https://api.yaligo-growth.top`；仓库记录的香港 ECS 地址：`47.83.165.140`。
- 本轮已验证：公网 5 项基本检查通过，SSH TCP 22 可达，本地 28 项基础后端测试通过。
- 健康接口报告 `production`、`ai_configured=true`、`mock=false`、`authentication_required=true`。
- 本轮按确认的分工只做连接与基础接口检查：你负责本地修改、验证和推送源码，队友负责服务器部署，不配置本机 SSH。
- **未验证且不属于本轮连接检查范围**：SSH 管理登录、服务器实际发布版本/容器/备份状态、带授权的真实模型调用及手机功能。
- 部署由队友完成，本机没有原部署私钥和已核验主机记录。历史记录中的 `20261114-hk05-3e7e5d1` 是上次记录的版本，不能视为本轮已核实的在线版本。

本轮原始检查结果在外层 `docs/maintenance/2026-11-15/`。旧恢复记录和验收 JSON 已移到外层 `docs/archive/2026-11-15-cleanup/deployment-history/`。

## 本次功能更新交接（2026-11-15）

这版同时更新 Android 和后端：压力自评与趋势、危险表达语义复核、按本轮求助需要选择联系人、两页行动卡、每日一句话回望、聊天与图片来源关联。运行素材、图标和许可文件已放在 `app/src` 与 `server/app/assets`；外层 `docs` 只存开发资料和测试报告，构建与运行不依赖该目录。

- 从本次 `main` 提交发布，先备份生产数据库，再按下文创建新发布目录、构建镜像和启动容器。Git push 不会自动部署。
- 沿用服务器的 `config/runtime.env`，不复制开发电脑的 `.env` 或数据库。核对 `ENABLE_LIVE_AI=true`、已有百炼 Key 及模型访问权限；如果 Key 开启 IP 白名单，确认允许服务器的实际出口公网 IP。
- 新配置使用 `NOTIFICATION_MODE=disabled`、`WELLBEING_MODEL_TIMEOUT=15`；`COUNSELING_URL` 是可选的已核验机构 HTTPS 预约页，不配置则不显示入口。保持 Compose 中的 `APP_ENV=production` 和 `CLOUD_AUTH_REQUIRED=true`。
- 容器启动脚本会创建六张压力/联系人/通知表，并给 `messages` 增补 `image_observation` 字段。现有会话、记录和云测凭据继续使用原数据库；发布前仍需一致性备份，不能以本地库覆盖生产库。
- **真实手机 SIM 自动短信仍仅用于 `localDebug` + 开发后端。云测 App 和生产 API 都禁用此通道，本次发布不会开启云端短信。** 生产中可以保存手机号、使用联系人建议，但不能开启自动提醒；云端通知需要后续独立适配。
- 先更新后端，再用最新源码运行云测 `debug` 版 App（保留原应用数据和签名）。本地 `localDebug` 使用独立包名、数据库和设备身份，本地数据不会自动迁到云端。
- 更新后确认容器 healthy、`/health` 正常、未授权业务请求为 401；再由受邀测试设备验证压力自评、聊天后的联系人选择、日回望和原始记录。健康接口的 `ai_configured` 只表示配置存在，模型是否成功需要检查真实业务响应。

提交前已验证干净源码快照：后端 241 项测试通过，Android 两种配置编译通过、各 22 项单元测试通过；另以旧 main 的合成数据库验证自动升级及生产鉴权，新旧数据与凭据保留。本机未安装 Docker，本地 Python 依赖也与部署锁文件存在版本差异；这些结果不能替代 Linux 镜像验证。队友仍需执行下文的镜像构建及镜像内测试，成功后才替换生产容器。

## 服务器如何工作

```text
手机 App
  │ HTTPS + 设备授权令牌
  ▼
api.yaligo-growth.top → 香港 ECS（47.83.165.140）
  │ Caddy：HTTPS 证书、入口路由、请求大小限制
  ▼
127.0.0.1:8000 → Docker 中的 FastAPI
  ├─ SQLite：聊天、记录、记忆、邀请、授权和配额
  ├─ 百炼模型：对话、提取、语音等（Key 仅在后端）
  └─ FFmpeg：成长小片合成
```

服务器是一台持续开机的远程 Linux 电脑。Docker 将后端代码和依赖装成镜像，Compose 按配置启动容器；Caddy 对外提供 HTTPS，后端端口只绑定服务器本机。手机使用互联网直接请求服务器，你的开发电脑关机不会中断服务。

三种凭据用途不同：**SSH 私钥**用于管理服务器；**邀请码/设备令牌**用于 App 授权；**百炼 Key**用于后端调用模型。Git 仓库不保存这些凭据。

配置中约定的持久化位置如下，实际状态需登录后核对：

| 服务器路径 | 用途 |
| --- | --- |
| `/opt/ugly-duckling-cloudtest/current` | 指向当前发布目录的软链接 |
| `/opt/ugly-duckling-cloudtest/releases/` | 每次发布的独立源码目录 |
| `/opt/ugly-duckling-cloudtest/config/runtime.env` | 独立保存模型和运行配置 |
| `/opt/ugly-duckling-cloudtest/data/confidence_agent.db` | 持久化 SQLite 主库 |
| `/opt/ugly-duckling-cloudtest/backups/` | 数据库一致性备份 |
| `/opt/ugly-duckling-cloudtest/tools/cloud_invites.py` | 批量邀请管理工具 |

代码、配置、数据分开保存。因此常规更新容器不会自动清空用户数据；不要删除数据目录，也不要用本机数据库覆盖服务器。已有文档记录每天北京时间 03:00 定时备份，本轮尚未登录核实定时任务。

## 你怎样使用

手机端继续使用云测版，有授权就直接进入；首次安装需要独立邀请码。Android Studio Run 会更新手机应用，不会更新服务器。电脑无需启动本地 Python 服务。

电脑只做公网基本检查时，在项目根目录运行：

```powershell
.\deploy\cloud-test\check-public.ps1
```

该脚本检查健康、站点入口、未授权请求 401 和公网 API 文档 404，不调用模型，不触碰已有数据。`/health` 的 AI 字段只反映配置；某次真实调用是否成功要检查该次响应的 `mock` / `mock_reason`。

## SSH 管理连接

推荐每位维护者使用自己的 SSH 密钥。私钥留在各自电脑，公钥由已有管理员添加到服务器授权列表；服务器主机指纹也应通过队友或已登录的云控制台核验。不要把私钥放进 Git，也不要用关闭主机校验的方法绕过缺少主机记录的问题。

现有脚本默认读取这两个本机文件：

```text
%USERPROFILE%\.ssh\ugly_duckling_cloudtest
%USERPROFILE%\.ssh\ugly_duckling_cloudtest_known_hosts
```

这两个文件不是每台电脑自动拥有的；新维护者需要先完成授权。配置完成后，在 PowerShell 设置连接参数：

```powershell
$sshOptions = @('-o','BatchMode=yes','-o','StrictHostKeyChecking=yes',
  '-o',"UserKnownHostsFile=$env:USERPROFILE\.ssh\ugly_duckling_cloudtest_known_hosts",
  '-o','IdentitiesOnly=yes','-o','ConnectTimeout=15',
  '-i',"$env:USERPROFILE\.ssh\ugly_duckling_cloudtest")
ssh @sshOptions root@47.83.165.140
```

登录后运行以下只读命令检查实际发布和容器（不要直接输出 `runtime.env` 或完整容器环境变量）：

```bash
cd /opt/ugly-duckling-cloudtest/current
readlink -f /opt/ugly-duckling-cloudtest/current
docker compose -f deploy/cloud-test/compose.yaml ps
curl --fail --silent http://127.0.0.1:8000/health
systemctl is-active caddy
```

查看最近日志与手动一致性备份：

```bash
docker compose -f deploy/cloud-test/compose.yaml logs --tail=80 api
docker compose -f deploy/cloud-test/compose.yaml exec -T api python backup.py
```

退出 SSH 输入 `exit`，不会停止后台服务。`open-tunnel.ps1` 只保留给管理员本地排查，手机正常使用不需要隧道。

## 邀请新测试者

SSH 配置好后，在电脑双击 `批量生成邀请码.cmd`，或在项目根目录运行：

```powershell
.\deploy\cloud-test\generate-invites.ps1 -Count 20
```

默认生成 20 个，7 天内兑换，授权到期为生成后第 30 天。每个邀请码兑换一次；清单保存在本机 `artifacts/cloud-test/invites/`，服务器在 `/root/cloud-invites/` 留有管理副本。清单已被 Git 忽略，每人只发自己的邀请码。

单人邀请也可在服务器 `current` 目录执行：

```bash
docker compose -f deploy/cloud-test/compose.yaml exec -T api python -m app.cloud_admin issue --label tester-01
```

单人命令默认 24 小时内兑换、授权到期为创建后第 30 天。已有设备丢失授权时，核实设备编号后加 `--device-id DEVICE_ID` 生成专用邀请；不要用普通邀请码认领旧数据。`list` 查看授权，`revoke --device-id DEVICE_ID` 撤销指定设备。撤销授权不会自动删除设备数据。

配额默认每设备每 UTC 日 60 次、全体 300 次写入或生成请求；这些是请求次数限制，不是模型费用的人民币上限。

## 修改后怎样提交

**Git 提交、推送 GitHub、更新服务器是三个独立动作。** 当前仓库未提供自动部署工作流，`git push` 不会自动重启或更新后端。

当前分工：你在本机修改和验证，确认后推送 GitHub；队友拉取指定提交、备份并发布服务器，再进行手机端云端联调。你不需要服务器管理密钥。

云测 `debug/release` 固定访问云端。本地联调请选择已加入的 `localDebug`，在“我的 → 本地测试 · 后端连接”连接电脑；本地测试通过不代表云端已经更新。准备云端验收时应切回新版云测 App。

在 `confidence_agent` 根目录：

```powershell
git status --short
git diff --stat
git diff
# 按本次修改选择文件；不要添加 .env、数据库、邀请码或私钥。
git add -- <本次修改的文件或目录>
git diff --cached
git commit -m "说明本次修改"
git push origin main
```

上面的 `<本次修改的文件或目录>` 是说明占位符，需替换后执行。与队友并行改动时更适合新建功能分支并通过 PR 合入 `main`。是否已部署以服务器实际发布目录及容器状态为准，不能由 Git 提交状态推断。

| 修改内容 | 测试时需要做什么 |
| --- | --- |
| `app/` 界面或客户端逻辑 | Android Studio Run 更新手机 |
| `server/app/` 后端逻辑 | 本地测试后重新发布服务器；接口兼容时手机无需重装 |
| 前后端接口同时改变 | 协调后端发布与手机更新，避免旧 App 调用不兼容接口 |
| 外层 `docs/`、PPT 或历史材料 | 不影响服务器；这些文件不在本 Git 仓库内 |
| 模型 Key/运行配置 | 在服务器更新独立配置并重建容器，不提交 Key |

## 后端发布步骤（操作指引，本轮未执行）

已有管理员完成发布即可，手机测试者不必拥有服务器权限。先提交并核对代码，再从确定的 Git 提交打包后端源码。下面不会生成 Android APK。

Windows 项目根目录，沿用上面的 `$sshOptions`：

```powershell
$revision = git rev-parse --short HEAD
$release = (Get-Date -Format 'yyyyMMdd-HHmmss') + '-' + $revision
$bundle = Join-Path $env:TEMP "$release.tar"
git archive --format=tar --output="$bundle" HEAD server deploy/cloud-test .dockerignore
if ($LASTEXITCODE -ne 0) { throw '源码归档失败' }
scp @sshOptions $bundle "root@47.83.165.140:/tmp/$release.tar"
if ($LASTEXITCODE -ne 0) { throw '上传失败' }
Write-Output "服务器下一步使用的 release：$release"
```

归档只包含 **已提交的 HEAD**，未提交改动不会进入发布。进入服务器后，将下面 `release` 替换为上一步输出的实际值。先确认当前有可用备份；这个流程面向兼容现有数据库结构的改动，涉及数据库迁移时应单独设计回滚。

```bash
set -eu
base=/opt/ugly-duckling-cloudtest
release=替换为上一步输出的发布名称
previous=$(readlink -f "$base/current")
cd "$previous"
docker compose -f deploy/cloud-test/compose.yaml exec -T api python backup.py

test ! -e "$base/releases/$release"
mkdir "$base/releases/$release"
tar -xf "/tmp/$release.tar" -C "$base/releases/$release"
cd "$base/releases/$release"
ln -s "$base/config/runtime.env" deploy/cloud-test/.env
printf 'APP_VERSION=%s\n' "$release" > .env
docker compose -f deploy/cloud-test/compose.yaml build api

# 新镜像隔离测试，不挂载生产数据库，不传模型 Key。
docker run --rm --entrypoint python \
  -e ENABLE_LIVE_AI=false -e CLOUD_AUTH_REQUIRED=false \
  "ugly-duckling-cloudtest:$release" -m pytest -q -p no:cacheprovider

# 此步会替换运行中的后端，选择测试者空闲时执行。
docker compose -f deploy/cloud-test/compose.yaml up -d --no-build api
# 等待约 30 秒后检查 healthy；未健康时不要切换 current。
docker compose -f deploy/cloud-test/compose.yaml ps
curl --fail --silent https://api.yaligo-growth.top/health
```

只有容器显示 `healthy` 且公网检查通过后，再将 `current` 切换到新目录：

```bash
ln -sfn "$base/releases/$release" "$base/current"
```

随后运行电脑端 `check-public.ps1` 并进行一次受邀设备业务测试。保留旧发布目录、镜像和备份。新容器异常时，在同一个 SSH 会话中执行以下命令恢复旧镜像（前提是新旧版本数据库兼容）：

```bash
cd "$previous"
docker compose -f deploy/cloud-test/compose.yaml up -d --no-build api
ln -sfn "$previous" "$base/current"
```

恢复数据库备份需要停服并验证备份完整性，不能直接覆盖运行中的 SQLite 主库；旧备份还会恢复旧授权状态，需要单独核查。

`stress-trend-smoke.py` 保留为可选的管理员专项检查，会创建独立测试身份、调用少量真实模型，并在结束时清理测试数据/撤销凭据。本轮不运行该专项功能测试。

`setup-https.sh` 和 `Caddyfile.bootstrap` 是初次安装工具，不应覆盖已上线的 `Caddyfile`。Caddy 负责证书续期；日常业务代码更新不需要重新安装 HTTPS。
