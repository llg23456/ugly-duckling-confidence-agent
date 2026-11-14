# 受邀云测环境

2026-11-14 已完成公网 HTTPS 和 Android 真机独立联网验收。入口为 `https://api.yaligo-growth.top`，香港 ECS `47.83.165.140`，当前后端发布为 `20261114-hk05-3e7e5d1`。云测版首次输入一次邀请码，后续可直接联网使用，无需 WireGuard、USB、电脑后端或 SSH 隧道。

2026-11-14 23:38（Asia/Shanghai）已将本地合并提交 `3e7e5d1` 的后端部署到香港，发布前完成 SQLite 一致性备份，新镜像隔离运行后端测试 89 项通过。公网 HTTPS 压力趋势验收 11 项通过：无数据返回七天“暂无”、正常记录显示平稳、未触发提醒仍返回压力趋势、较强连续压力触发提醒、删除来源更新趋势、聊天纳入统计、删除全部归零，以及 401/403 认证隔离。测试身份的数据和凭据已清理/撤销，后台任务结束后再次确认无残留。详见 [本轮验收结果](stress-trend-verification.json)。本轮未重新进行手机界面验收。

本轮业务源提交为 `3e7e5d1`，镜像和备份 SHA-256 见验收结果。旧发布 `20261114-hk04` 和发布前备份保留。当前根 `.env` 仅固定 `APP_VERSION=20261114-hk05-3e7e5d1`，`deploy/cloud-test/.env` 继续链接服务器独立保存的 `config/runtime.env`；模型 Key 和现有用户数据库沿用。日常运维仍从 `current` 目录运行 Compose。

可复查的公网验收脚本为 [stress-trend-smoke.py](stress-trend-smoke.py)。在服务器当前发布目录运行 `docker compose -f deploy/cloud-test/compose.yaml exec -T api python - < deploy/cloud-test/stress-trend-smoke.py`；脚本会生成独立测试身份、调用真实记录和聊天接口，并在结束时删除该身份的数据和撤销凭据，可能产生少量模型调用费用。不要用于导入个人数据。

此前云测验收证据分别见 [域名及证书](domain-verification.json)、[公网认证接口](access-verification.json) 和 [真机验收](phone-verification.json)。后端测试 80 项、Android JVM 测试 18 项通过。此前新身份记忆提取未落库，以及并发多模态请求约 19–22 秒的问题仍待专项排查；本次单次公网聊天约 1.91 秒，不能据此认定并发性能问题已解决。

## 安装与邀请

本地交付文件：`artifacts/cloud-test/20261114/ugly-duckling-cloudtest.apk`。包名 `com.testconnection.confidence_agent.cloudtest`，名称“鸭力Go·云测”，与原应用独立保存数据。该文件是受邀测试用 Debug APK，不是应用商店正式发布包。可运行 `build-apk.ps1` 重新构建并运行 Android JVM 测试；默认 SDK 为 `D:\Android\Sdk`。

Android Studio 的普通 `Run app`、无参数 `assembleDebug` 与打包脚本已统一为云测入口；不再需要 `-PcloudTest` 或 `-PapiBaseUrl`，也不再提供本地版构建。请确认 IDE 打开的是本分支工作区，Sync 后选择 `app` / `debug` 即可。

在香港服务器 Workbench 或 SSH 终端给每名新试用者单独生成邀请码：

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
