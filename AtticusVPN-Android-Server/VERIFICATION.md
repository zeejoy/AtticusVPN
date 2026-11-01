# 发布核验

Copyright 2026 TianzeL. SPDX-License-Identifier: Apache-2.0.

核验日期：2026-10-17。公开副本基于 Android Server 0.5.8（versionCode 19）。
项目作者及三语言界面公开署名统一为 TianzeL；保留 `dev.lan.*` 技术标识。

## 发布范围与许可

- 仅包含 Android 服务端工程、Java core、原生引擎源码、测试、授权素材和构建说明。
- 不附带客户端工程、Linux 服务端、未集成的 strongSwan 原生实验目录、Git 历史、设备测试资料、日志、签名、APK/AAR 或构建缓存。
- 自有代码、文档和授权素材采用 Apache-2.0；根 LICENSE、native-engine/LICENSE 与项目 APK 的 Apache 许可正文采用核对过的官方原文。
- 26 份第三方许可/归属原文逐项通过 LICENSES/README.md 的 SHA-256 清单核验。第三方原版权和许可原样保留，不改为项目 Apache 许可。

## 从源码构建与自动测试

本轮实际使用 Windows、JDK 17、Go 1.27.0、SDK 36、NDK 29.0.14206865、Gradle Wrapper 9.3.1、AGP 9.1.0。

| 核验 | 实际结果 |
|---|---|
| 原生脚本逻辑测试 | 15 项通过 |
| Go 模块 | download、verify 成功，签名校验保持开启 |
| Go 测试与静态检查 | `go test -count=1 ./...`、`go vet ./...` 通过；含回环 WireGuard 集成测试 |
| 原生源码构建 | 四 ABI AAR 生成成功；ELF 架构、API 24 note、manifest、Java 桥接 API 验收通过 |
| 原生路径隐私 | 相对模块替换、路径映射、清理调试节及全部解压 AAR/JAR 条目扫描通过 |
| Java core | 13 个测试类、47 项测试，0 失败/错误/跳过 |
| Android app | 29 个测试类、176 项测试，0 失败/错误/跳过 |
| Android Lint | 0 个报告问题；保留 warningsAsErrors，无历史 baseline |
| Debug APK | assembleDebug 成功，APK v2 签名验证通过 |
| APK 元数据 | `dev.lan.atticusvpn.server.debug`，0.5.8-debug，minSdk 24、targetSdk 36，四 ABI |
| 图标 | APK 内 PNG 与授权的 1254×1254 原图逐字节相同；主页字标和通知图标保留 |
| APK 原生库 | 四个库与已通过隐私门禁的 AAR 逐字节相同 |

原生 AAR SHA-256：
`b9b387b47b49ebd8368d9acd1ab4088cbf8ddd8582e991d8c632bfb2da482f54`

本地核验 Debug APK SHA-256（不附入源码包，也不承诺不同调试签名环境产生相同 APK）：
`9909c22e6cc7110a2e9968717eb42fa3530ae957fb79900d7cbb581e47404bf4`

SDK 的新常量注解只列 API 29 的 LineBreaker，而最低系统为 API 24：终端保持使用 API 23 已提供的同值 Layout 常量，仅在一个单调用兼容方法中说明并抑制 WrongConstant 误报；未全局关闭该检查。
新系统主题图标另用单色盾牌/手机轮廓，仅影响主动启用主题图标的启动器；普通彩色图标仍用原图。

构建工具另提示 SDK XML 版本较新、部分 API 已弃用，以及原生库无法再次剥离。它们不属于 Lint 报告错误；原生库此前已由 NDK 工具清理调试节，且完成独立路径扫描。没有因此关闭依赖、TLS 或隐私检查。

## 档案隐私与交付校验

最终源 ZIP 采用独立筛选，不把构建后的整个目录直接压缩。条目与公开源文件逐字节核对，ZIP 时间、权限和注释元数据规范化；另附整个 ZIP 的 SHA-256 文件。

公开检查工具逐项检查源码、嵌套 Gradle Wrapper JAR 和 PNG 结构；发布前另有不随包分发的已知身份/设备标识检查。旧项目作者真实姓名变体不保留在本包中，三语言署名使用 TianzeL。
图片检查涵盖元数据、CRC 与文件尾部；不包含设备截图或照片。嵌入的测试 PKCS#12 在内存中解析，只含明确的 TEST ONLY CA/CLIENT 和 `.test` 标识，不是用户部署证书。

人工复核保留两类必要公开数据：离线测试的伪凭据/模拟 DDNS/IP 样本，以及上游原始版权和公开联系信息。不得把公开测试私钥用于部署。
扫描和人工复核未发现已知项目作者个人信息或实际部署秘密；这不构成对任意未知内容零泄漏的数学担保。后续修改必须重新审查。GitHub 账号资料和未来提交的姓名/邮箱需另按 README 配置。

发布者可在未构建的解压目录，或对源 ZIP 再执行：

```powershell
python ./tools/verify-public.py
python ./tools/verify-public.py PATH_TO_SOURCE_ZIP
```

## 未进行的验证

本轮没有安装/启动手机应用、操作 Root、做公网或两机连通测试，也没有上传 GitHub；未验证 release 混淆打包和 macOS/Linux 实际构建。
自动测试、APK 构建成功、DDNS 或手机出口检测均不能代替真实设备的公网 IPv6 入站和远端客户端联网验收。源码包不附带私人发布签名，不能保证覆盖其他签名的既有安装。

