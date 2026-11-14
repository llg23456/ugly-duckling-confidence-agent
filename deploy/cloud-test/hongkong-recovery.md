# 香港测试服务器恢复记录

本文件记录 `20261114-hk01` 初始恢复阶段，后续已升级至 `20261114-hk04` 并完成 HTTPS、受邀认证和真机独立联网验收。当前操作见 [云测说明](README.md)，下述“尚未完成”等描述属于恢复当时的状态。

日期：2026-11-14。目标实例：47.83.165.140，中国香港，Ubuntu 22.04，2 vCPU / 4 GiB，40 GiB 系统盘。

用户已释放成都实例，因此无法取得旧实例最新数据库和运行配置。本次使用本机 Windows DPAPI 加密备份恢复，备份时间为 2026-11-13 13:30（Asia/Shanghai）。此后的仅服务端新增内容不在本备份内；手机原有本地数据不被此次恢复清除。

恢复前后数据库 SHA-256 一致：`e430d55d476c0fc88fc6331abbf37d6d26c20c3ad5e8f29f4afe616ff62063e1`。SQLite `integrity_check` 为 `ok`，初始表行数：conversations 7、messages 18、growth_events 7、memories 1、records 4、reviews 2、video_scripts 1、support_people 0。验收产生的虚构数据可能使后续计数增加。

香港发布目录为 `/opt/ugly-duckling-cloudtest/releases/20261114-hk01`，`current` 指向该目录；镜像标签为 `20261114-hk01`。项目根 `.env` 和 `deploy/cloud-test/.env` 均指向 `/opt/ugly-duckling-cloudtest/config/runtime.env`；根 `.env` 提供 Compose 镜像版本，具体模型 Key 仅保存在服务器环境文件中。

由于原环境文件随成都实例释放，模型 Key 不在本机数据库备份中。需在香港 Workbench 重新运行 `bash /opt/ugly-duckling-cloudtest/current/deploy/cloud-test/configure-ai.sh`，以隐藏输入配置完整的项目 Key。继续使用北京百炼入口，并在该 Key 的 IP 白名单加入 `47.83.165.140`。

当前业务服务仅绑定 `127.0.0.1:8000`。域名、HTTPS、受邀设备认证及手机独立互联网访问尚未完成，不能将本记录视为多人公网评估环境已上线。原 `verification.json` 是成都阶段的历史验收结果，香港恢复后的检查另行记录。

恢复后已通过 71 项后端测试（1 项上游弃用警告，26.27 秒）。实际 HTTP 的 Mock 聊天、三日记录同步、周回望、视频脚本、FFmpeg H.264/AAC 合成与导出通过；容器重建后的数据保留、SQLite 在线备份及恢复检查通过。原云测手机的两条会话消息仍可通过香港接口读取。日常备份与重启策略已恢复。

香港真实模型 Key 已重新配置且通过请求验证。聊天、图片理解、TTS 与语音理解接口的真实调用通过，但本轮并发冒烟耗时约 19–22 秒；新增成长事件已保存，预期的新身份记忆未落库，记忆步骤未通过。响应耗时和身份记忆保留原因尚需排查。`hongkong-verification.json` 分别记录通过项和待解决项，不能将其解读为完整模型质量验收通过。
