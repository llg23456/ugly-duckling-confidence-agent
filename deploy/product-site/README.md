# 鸭力Go 官网与 APK 下载

官网为 `https://yaligo-growth.top/`，`www.yaligo-growth.top` 保留路径跳转到主域名。现有 `api.yaligo-growth.top` 继续服务 App。页面直接由 Caddy 托管，不启动新常驻服务，不使用第三方字体、统计或广告。

源码位于 `web/product-site/`；小鸭素材来自应用，经过 WebP 编码优化，来源记录在 `assets-provenance.json`。二维码固定指向官网，不包含设备编号或授权信息。页面素材和二维码已提交，日常发布不需要重新生成图片或安装依赖。

## 发布或更换 APK

先完成 APK 验收并核对签名、包名与版本，随后在仓库根目录执行（Python 3.10+，需已配置的 Windows OpenSSH 部署私钥与主机记录）：

```powershell
python deploy/product-site/publish.py --apk artifacts/cloud-test/20261116-open/yaligo-cloudtest-open.apk --expected-sha256 a2d0959a225ba792eaf4b80076645ca62b6867a03b3ca111b1209cc2af0863b6 --version-name 1.0-cloudtest
```

`--expected-sha256` 必须来自已验收文件，脚本不构建或重新签名 APK。可通过 `--identity`、`--known-hosts` 指定运维 SSH 路径；`--package-only` 只生成发布包。它只打包页面、图片、版本元数据、站点配置与指定 APK，不打包 `.env`、数据库或应用源码。

生成物位于 `artifacts/product-site/<发布号>/`，已被 Git 忽略；APK 不提交至 GitHub。上传后再次核对包和全部文件哈希，先准备 APK，再切换官网与版本信息、平滑重载 Caddy。版本元数据中的 `published_at` 为官网发布时间，不代表应用构建时间。

## 服务器结构与接口

- `/opt/ugly-duckling-site/releases/<发布号>/`：页面、版本信息、校验清单和当前版本下载配置。
- `/opt/ugly-duckling-site/current`：当前页面发布软链接；历史发布保留。
- `/opt/ugly-duckling-site/downloads/`：带日期及哈希的安装包，历史包保留。
- `/etc/caddy/Caddyfile`：保留实际在线 API 规则，只追加官网配置的 `import`。

公网 `GET` / `HEAD /download/android` 返回 302，指向带版本的 APK。`GET /downloads/latest.json` 返回版本名称、包名、文件大小、发布时间、SHA-256、下载路径、最低 Android 版本、云测渠道及是否需要邀请码。元数据禁止缓存，安装包长期缓存并支持标准 HTTP Range；没有目录浏览。页面从元数据加载版本信息，失败时仍提供固定下载入口。

主域名和 `www` 的 A 记录指向 `47.83.165.140`，TTL 600 秒；`api` 记录不改变。Caddy 自动申请和续期 HTTPS 证书，无需另外购买 SSL。

## 验证

```powershell
node deploy/product-site/check-download-ui.mjs
python deploy/product-site/verify.py --expected-sha256 a2d0959a225ba792eaf4b80076645ca62b6867a03b3ca111b1209cc2af0863b6
```

第二条会通过公网完整下载一次 APK（约 65.7 MB）并校验哈希，同时检查 HTTPS、主域名跳转、GET/HEAD 下载入口、首尾分段续传、资源、敏感路径隔离与 API 健康。生成的 `verification.json` 记录结果。页面布局另外在浏览器桌面与移动视口中检查。

## 回退

在服务器读取本次发布输出的上一发布号，确认对应目录存在后，将 `current` 切回该目录，执行 `caddy validate --config /etc/caddy/Caddyfile --adapter caddyfile`，通过后 `systemctl reload caddy`。历史 APK 无需删除或覆盖，固定下载入口与版本信息随页面一起回退。

首次上线若需撤销官网，可恢复 `/opt/ugly-duckling-site/Caddyfile.before-<首次发布号>` 到 `/etc/caddy/Caddyfile` 并验证、重载；不要用仓库中较旧的 API Caddyfile 覆盖在线配置。正常发布不重启 API 容器，不改数据库或设备凭据。
