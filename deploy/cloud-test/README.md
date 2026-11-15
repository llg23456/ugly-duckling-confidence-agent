# 受邀云测环境

2026-11-14 已完成公网 HTTPS 和 Android 真机独立联网验收。入口为 `https://api.yaligo-growth.top`，香港 ECS `47.83.165.140`，当前后端发布为 `20261115-hk06-c9ff668`。云测版首次输入一次邀请码，后续可直接联网使用，无需 WireGuard、USB、电脑后端或 SSH 隧道。

2026-11-14 23:38（Asia/Shanghai）已将本地合并提交 `3e7e5d1` 的后端部署到香港，发布前完成 SQLite 一致性备份，新镜像隔离运行后端测试 89 项通过。公网 HTTPS 压力趋势验收 11 项通过：无数据返回七天“暂无”、正常记录显示平稳、未触发提醒仍返回压力趋势、较强连续压力触发提醒、删除来源更新趋势、聊天纳入统计、删除全部归零，以及 401/403 认证隔离。测试身份的数据和凭据已清理/撤销，后台任务结束后再次确认无残留。详见 [本轮验收结果](stress-trend-verification.json)。本轮未重新进行手机界面验收。

本轮业务源提交为 `3e7e5d1`，镜像和备份 SHA-256 见验收结果。旧发布 `20261114-hk04` 和发布前备份保留。当前根 `.env` 仅固定 `APP_VERSION=20261114-hk05-3e7e5d1`，`deploy/cloud-test/.env` 继续链接服务器独立保存的 `config/runtime.env`；模型 Key 和现有用户数据库沿用。日常运维仍从 `current` 目录运行 Compose。

可复查的公网验收脚本为 [stress-trend-smoke.py](stress-trend-smoke.py)。在服务器当前发布目录运行 `docker compose -f deploy/cloud-test/compose.yaml exec -T api python - < deploy/cloud-test/stress-trend-smoke.py`；脚本会生成独立测试身份、调用真实记录和聊天接口，并在结束时删除该身份的数据和撤销凭据，可能产生少量模型调用费用。不要用于导入个人数据。

此前云测验收证据分别见 [域名及证书](domain-verification.json)、[公网认证接口](access-verification.json) 和 [真机验收](phone-verification.json)。后端测试 80 项、Android JVM 测试 18 项通过。此前新身份记忆提取未落库，以及并发多模态请求约 19–22 秒的问题仍待专项排查；本次单次公网聊天约 1.91 秒，不能据此认定并发性能问题已解决。

## 安装与邀请

本地交付文件：`artifacts/cloud-test/20261114/ugly-duckling-cloudtest.apk`。包名 `com.testconnection.confidence_agent.cloudtest`，名称“鸭力Go·云测”，与原应用独立保存数据。该文件是受邀测试用 Debug APK，不是应用商店正式发布包。可运行 `build-apk.ps1` 重新构建并运行 Android JVM 测试；默认 SDK 为 `D:\Android\Sdk`。

Android Studio 的普通 `Run app`、无参数 `assembleDebug` 与打包脚本已统一为云测入口；不再需要 `-PcloudTest` 或 `-PapiBaseUrl`，也不再提供本地版构建。请确认 IDE 打开的是本分支工作区，Sync 后选择 `app` / `debug` 即可。

### 电脑端批量邀请（推荐）

Windows 电脑双击本目录下的 `批量生成邀请码.cmd`，输入数量后回车；直接回车默认生成 20 个。无需打开 Workbench。电脑需有 Windows OpenSSH、部署用 SSH 私钥及已核验的主机记录；当前电脑已配置。新电脑需单独配置其管理员密钥，不要把本机私钥随 APK 或脚本分发。

也可以在项目根目录使用 PowerShell：

```powershell
.\deploy\cloud-test\generate-invites.ps1 -Count 20
# 示例：50 个，14 天内兑换，授权到期为生成后的第 45 天
.\deploy\cloud-test\generate-invites.ps1 -Count 50 -InviteDays 14 -AccessDays 45
```

默认 **7 天内兑换、授权到期为生成后的第 30 天**，两者都从生成时间计算；例如第 7 天兑换，约剩 23 天授权。数量范围 1–200，兑换期限 1–30 天，授权期限 1–90 天，兑换期限不能长于授权期限。每个邀请码只可兑换一次，仅用于新设备首次授权。

清单自动保存到项目的 `artifacts/cloud-test/invites/`（已被 Git 忽略），文件名含时间和批次标识；用 Excel 打开即可。字段为 `label`（编号）、`invite_code`（邀请码）、`redeem_before`（兑换截止）、`access_until`（授权到期）、`recipient`（领取人）、`note`（备注）。日期带 `+08:00`，为北京时间。电脑清单仅授予当前 Windows 用户访问权限；完整清单属于管理资料，每次只向一人发送一个邀请码。

服务器在 `/root/cloud-invites/` 保留 root 专用清单。若下载中断，按脚本输出的服务器路径用 SCP 重新下载，避免重复运行而生成另一批。服务器数据库只保存摘要，无法从数据库恢复邀请码明文；明文仅保留在这两份管理清单。

批量工具不调用 AI、不占用模型额度。它通过 SSH 在当前容器内执行运维目录的独立脚本，不需要重新部署应用。服务器首次安装或替换工具（仅组织者）可以从项目根目录执行以下命令；应沿用部署时的 `StrictHostKeyChecking=yes`、`UserKnownHostsFile` 和 `IdentityFile` SSH 选项：

```powershell
$sshOptions = @('-o','BatchMode=yes','-o','StrictHostKeyChecking=yes',
  '-o',"UserKnownHostsFile=$env:USERPROFILE\.ssh\ugly_duckling_cloudtest_known_hosts",
  '-o','IdentitiesOnly=yes','-i',"$env:USERPROFILE\.ssh\ugly_duckling_cloudtest")
ssh @sshOptions root@47.83.165.140 'install -d -m 700 /opt/ugly-duckling-cloudtest/tools'
scp @sshOptions .\server\app\cloud_invites.py root@47.83.165.140:/opt/ugly-duckling-cloudtest/tools/cloud_invites.py.next
ssh @sshOptions root@47.83.165.140 'chmod 600 /opt/ugly-duckling-cloudtest/tools/cloud_invites.py.next && mv /opt/ugly-duckling-cloudtest/tools/cloud_invites.py.next /opt/ugly-duckling-cloudtest/tools/cloud_invites.py'
```

脚本可通过 `-IdentityFile`、`-KnownHostsFile` 和 `-OutputDirectory` 指定本机路径。它严格验证服务器主机密钥，不自动接受未知密钥。保留的 `.cmd` 入口会在结束后暂停，便于查看结果。

批量工具已完成 89 项后端测试、Windows PowerShell 实际下载和公网一次性兑换验证，结果见 [批量邀请验证](invite-tool-verification.json)。独立安装工具时保留了服务器当时的应用发布及现有授权。

### 单人邀请和授权恢复

也可以在香港服务器 Workbench 或 SSH 终端给每名新试用者单独生成邀请码：

```bash
cd /opt/ugly-duckling-cloudtest/current
docker compose -f deploy/cloud-test/compose.yaml exec -T api python -m app.cloud_admin issue --label tester-01
```

邀请码仅显示这一次，24 小时内可兑换一次，授权有效期为邀请码创建后的 30 天；分享 APK 时，分别将各人的邀请码发给本人。不要将邀请码或凭据提交到仓库。安装后输入邀请码即可，服务地址已内置。

已有云测数据、清除应用数据或丢失授权的设备，需由组织者核对屏幕上的设备编号，再生成专用邀请码：

```bash
docker compose -f deploy/cloud-test/compose.yaml exec -T api python -m app.cloud_admin issue --label tester-recovery --device-id DEVICE_ID
```

普通邀请码不能通过猜测旧设备编号认领其云端数据。专用邀请码兑换后会替换该设备的旧令牌。重新安装导致设备编号改变时，需另行核对迁移，不要批量共用同一设备编号或令牌。

列出已授权设备或撤销某设备授权：

```bash
docker compose -f deploy/cloud-test/compose.yaml exec -T api python -m app.cloud_admin list
docker compose -f deploy/cloud-test/compose.yaml exec -T api python -m app.cloud_admin revoke --device-id DEVICE_ID
```

撤销还会使该设备尚未使用的专用邀请码失效。授权管理仅有 SSH CLI，没有公网管理接口。撤销不会删除该设备已存的数据。

## 访问边界与配额

- 后端启用 `CLOUD_AUTH_REQUIRED=true`。未授权/过期/撤销返回 401，携带他人设备编号返回 403。
- 服务端仅保存邀请码和高熵令牌的 SHA-256 摘要；Android 使用 Android Keystore 的 AES-GCM 密钥加密保存令牌，凭据文件排除在备份及设备迁移之外。凭据只发送给内置 HTTPS API 源，携带凭据的连接不自动跟随跳转。
- 每设备每 UTC 日最多 60 次写入或生成请求，全体每天合计 300 次；旧版生成报告的 GET 接口同样计数，普通消息/记录读取不计日配额。失败的已授权生成尝试也可能计数。该配额不是人民币费用硬上限，一个请求可能涉及多次模型调用，仍需关注百炼账单。
- 每设备每分钟最多 90 次业务请求；每来源地址每分钟最多 5 次激活尝试。视频合成仅允许 1 个并发任务。超出返回 429，日配额耗尽后仍可读取已存聊天和记录。
- 后端只绑定 `127.0.0.1:8000`，Caddy 对外提供 TCP 80/443；80 用于证书验证及跳转。公网文档及其他未配置路径不开放。健康检查不需要凭据。
- Caddy 限制激活请求 4 KB、普通 API 16 MB、视频上传 128 MB；应用还会校验媒体类型、文件数量和大小。

`Caddyfile.bootstrap` 是只开放健康检查的初始维护配置；当前使用 `Caddyfile`。`setup-https.sh` 仅用于首次安装或校验初始配置，不用于覆盖已上线的业务网关。证书由 Caddy 自动管理，保存在 `/var/lib/caddy/.local/share/caddy`；保持域名解析、80/443 与持久化目录可用。无需另购 SSL 证书或 CDN。

## 服务、模型与备份

香港实例为 Ubuntu 22.04、2 vCPU / 4 GiB、40 GiB 系统盘；单个 Uvicorn worker。`current` 指向发布目录，配置独立保存在 `/opt/ugly-duckling-cloudtest/config/runtime.env`，仅 root 可读。模型 Key 由后端持有，不能写进 App、截图、聊天、Git 或发布包。

仅在更换 Key 时执行下列命令，并按隐藏输入提示操作；脚本会先进行一次小额真实请求验证：

```bash
bash /opt/ugly-duckling-cloudtest/current/deploy/cloud-test/configure-ai.sh
```

当前使用北京百炼入口，项目 Key 的白名单需包含香港实例 IP。ECS 试用额度与百炼费用分别计算。实例 IP 变化时还需同步域名解析与 Key 白名单。

数据库持久化在 `data/confidence_agent.db`，包含业务数据、邀请、授权与日配额。每天 Asia/Shanghai 03:00 在线备份到 `backups`，保留最近七份定时备份。容器以 UID 10001 运行，CPU/内存/进程数受限，日志轮转，开机及退出后自动重启；健康检查本身不会自动修复仍运行的异常进程。

```bash
cd /opt/ugly-duckling-cloudtest/current
docker compose -f deploy/cloud-test/compose.yaml ps
docker compose -f deploy/cloud-test/compose.yaml exec -T api python backup.py
```

升级先备份，再创建新的发布目录和镜像，通过检查后切换 `current`。撤回旧版本时同时考虑数据库和认证开关，不能回退成公网无认证服务。恢复备份必须先停服，复制到新的数据目录、验证 SQLite 完整性后再互换目录；保留原数据库及 WAL/SHM 以便撤回，不能覆盖运行中的主库。恢复旧备份也会恢复当时的授权状态，需复核并重新撤销备份之后已撤销的凭据。

历史材料：[香港初始恢复](hongkong-recovery.md)、[香港模型冒烟](hongkong-verification.json)、[成都历史验收](verification.json)。历史 USB/SSH 联调包与本次公网云测包不同，不能用旧包验收独立联网。

## 体验问卷与 Excel 导出

2026-11-15 已部署三步体验问卷，入口为“我的 → 评价与建议”。包含功能评价、主观变化、困难时刻帮助、负面体验及改进建议。必填项为使用时间、体验功能、总体满意度和当前身份（可选择“暂不透露”），其他选填；提交用途需主动勾选。困难时刻问卷并非心理诊断或疗效量表。

填写时可确认/修改身份，并选填测试代号、性别和年龄段，服务端保存当次快照，不随着日后画像变化。每次独立提交保留一份，同一次断网重试按提交编号去重。服务器接收时间是正式提交时间，Excel 同时提供北京时间和 UTC；设备编号关联的是设备，不保证一个设备只由一人使用。问卷不读取聊天或记录原文。

电脑上双击本目录 **导出评价问卷.cmd**，或在项目根运行：

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File deploy/cloud-test/export-feedback.ps1
```

默认将文件下载到 `artifacts/feedback/`（已忽略，不上传 Git）。Excel 包含“填写明细、每人最新一份、选项统计、文字建议、题目与口径”五个工作表。默认使用文件内参与编号关联，不导出设备编号；需要私下核对设备时加 `-IncludeDeviceIds`。不要将含身份及文字建议的整份工作簿直接作为公开材料。

统计按每设备、每问卷版本最新完整一份计算；跳过、不好判断和未经历等不进入判断分母，多选比例可能超过100%。自由文字作为普通文本写入，防止被 Excel 当作公式执行。用户在“数据导出”中可导出自己的问卷，通过“删除全部数据”可删除已提交问卷并清理本地草稿，即使没有聊天记录也能删除。

仅服务器运维人员可通过 SSH 执行导出，没有公网问卷管理接口。服务器导出方式：

```bash
cd /opt/ugly-duckling-cloudtest/current
docker compose -f deploy/cloud-test/compose.yaml exec -T api python -m app.feedback_export --output /backups/feedback/experience-feedback.xlsx
```

公网验收脚本为 [feedback-smoke.py](feedback-smoke.py)，结果见 [feedback-verification.json](feedback-verification.json)：12 项通过，测试身份和数据已清理。验收 Excel 为合成样例，不计入真实用户反馈。本轮本地后端115项、Android JVM19项通过，云端镜像114项通过、1项跳过（该项校验 Android 题库一致性，已在本地通过）。APK 构建和 Android 真机测试代码编译通过，未连接手机进行界面验收。

发布 `20261115-hk06-c9ff668` 对应业务源提交 `c9ff668`，数据库仅新增问卷表；发布前备份在 `/backups/pre-hk06/`，旧镜像及发布目录保留。验收脚本中无模型调用，部署保留已有用户、邀请码、授权和配额配置。
