# 正式版与测试版 · TianzeL

## 当前公开发布：STABLE 0.6.0

GitHub `main` 已包含 0.6.0 源码与发布文档，`v.0.6.0` 标签及[正式 Release](https://github.com/zeejoy/AtticusVPN/releases/tag/v.0.6.0) 已公开。源码于 2026-11-07 发布，原证书签名的正式 APK 于 2026-11-08 加入同一 Release。该版本已提供安装包；完整真机功能与长期运行验收仍未完成。

| 构件 | 实际名称与用途 |
|---|---|
| 已发布正式 APK | [AtticusVPN-Server-v0.6.0.apk](https://github.com/zeejoy/AtticusVPN/releases/download/v.0.6.0/AtticusVPN-Server-v0.6.0.apk)，原证书签名的非 Debug Release 包 |
| 正式源码 | [AtticusVPN-Android-Server-STABLE-0.6.0-source.zip](https://github.com/zeejoy/AtticusVPN/releases/download/v.0.6.0/AtticusVPN-Android-Server-STABLE-0.6.0-source.zip)；标签源码见 [v.0.6.0](https://github.com/zeejoy/AtticusVPN/tree/v.0.6.0) |
| 第三方对应源码 | [desugar 2.1.5 完整归档与说明](CORRESPONDING-SOURCE-STABLE-0.6.0.md)，随正式 APK 在同一 Release 提供 |
| 开发用未签名包 | 本地 Gradle 输出 `app/build/outputs/apk/release/app-release-unsigned.apk`；历史交付曾使用 `AtticusVPN-Android-Server-STABLE-0.6.0-UNSIGNED.apk`，均不是已发布安装包 |
| 开发用调试包 | 本地 Gradle 输出 `app/build/outputs/apk/debug/app-debug.apk`；历史交付名 `AtticusVPN-Android-Server-TEST-0.6.0-debug.apk`，独立 `.debug` 包名、应用名称带 TEST，不是正式升级包 |
| 既有实验测试系列 | 1.0.0–1.3.x 为 TEST 系列，版本号不与 STABLE 比较；不重新签名或改名伪装正式版 |

0.5.8 是第一个正式版，0.6.0 是第二个正式版。STABLE 0.6.0 的实际数据面为标准 WireGuard；没有将 TEST 其他协议整体迁入，也未实现 IKEv2/IPsec 服务或 Atticus Link。[历史 0.5.8](https://github.com/zeejoy/AtticusVPN/releases/tag/v.0.5.8) 有 F01–F04 已知可靠性问题，不建议用于关键或长期运行环境。

## 已发布 APK 的身份与签名

- 包名：`dev.lan.atticusvpn.server`；versionCode：`20`；versionName：`0.6.0-stable`；最低 Android 7.0 / API 24。原 0.5.8 的 versionCode 为 19。
- APK SHA-256：`074bc9e90bbdf0f4c40f365788b805c623625ff2ff50aef6ac9f3d6888dcc0cd`，见[独立校验文件](https://github.com/zeejoy/AtticusVPN/releases/download/v.0.6.0/SHA256SUMS-APK-STABLE-0.6.0.txt)。
- 签名证书 SHA-256：`2ff3b123dbaa5350542e1ee3c2cc371dde487038db3553a35d5754126caeab32`，与已发布 0.5.8 一致；APK v2/v3 签名验证通过，非 debuggable，并通过 16 KiB ZIP 对齐校验。
- 正式 APK 从 `v.0.6.0` / `211f169e45776205e81050f51d2205157085d9c1` 源码构建。后续文档更新不改变该标签、固定源码归档或正式 APK。

普通 Debug 版本为 `dev.lan.atticusvpn.server.debug` / `0.6.0-test-debug`。专用 `.revocationtest`、历史 `.ipsectest` 等测试包也属于独立数据空间，不自动读取或迁移正式版私密配置。设备日志文件名包含其版本渠道；文件名不能代替包名与证书核验。

## 已确认的覆盖升级范围

Release 记录了 2026-11-07 在 MI MAX 3、Android 10 上的有限检查：在机主资料空间安装原 0.5.8，创建测试 Peer，再保留数据覆盖安装此 0.6.0 APK。没有卸载或清空数据；安装成功、界面可打开，应用 UID 与首次安装时间保留，测试 Peer 的名称、IPv4/IPv6 分配地址、启用状态和可导出状态保留。

此检查使用新建测试配置，不能视为已有生产配置全面无损升级。真实服务器身份连续性、生产配置完整保留、真实 DDNS 设置保留、WireGuard 握手、TCP/UDP 互联网转发、服务启停、长时间锁屏后台运行、其他 Android 设备兼容性，以及公网 IPv6/运营商网络均未完成验收。主机测试、DDNS 成功或手机自身出口检测也不能代替这些验收。

## 开发者使用原签名覆盖升级

用户原密钥与密码继续由用户本地保管，不上传源码仓库、聊天或云盘。已发布 APK 的证书核验使用公开证书，不需要公开私钥或密码。下列流程仅供构建和签名本地开发产物，不用于覆盖既有 Release 附件。

在保管原密钥的电脑安装 Python 3.10+、JDK 17 和 Android SDK Build Tools 36.0.0，准备原 0.5.8 安装包。运行（路径及别名替换为自己的，输出必须选择不存在的本地路径）：

```text
python tools/sign-stable.py --unsigned app/build/outputs/apk/release/app-release-unsigned.apk --previous-apk AtticusVPN-Server-v0.5.8.apk --keystore ORIGINAL_KEYSTORE --alias ORIGINAL_ALIAS --build-tools ANDROID_SDK/build-tools/36.0.0 --out AtticusVPN-Server-v0.6.0.apk
```

密码由本地隐藏输入提示读取，不写命令行参数。工具先验证旧 APK 签名、正式包名和版本递增，再对齐、签名并核对新旧签名证书完全一致。不同签名会拒绝输出；不支持未经单独评审的签名密钥轮换。`--out` 显式使用实际发布文件名；脚本的旧默认输出名不代表 GitHub 附件名称。

覆盖升级时保留旧应用及应用数据，不先卸载或清空数据。先核对证书并保存恢复材料，再检查原服务器身份、Peer、DDNS 与设置；静态签名/包名/版本检查不能代替真机升级与配置保留验收。
