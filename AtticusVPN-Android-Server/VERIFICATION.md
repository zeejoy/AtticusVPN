# STABLE 0.6.0 正式版开发核验 · TianzeL

## 当前修订：P1-20261103

2026-11-03，在 STABLE 0.6.0 源码上修复 F01/F02/F03，versionCode 20 和配置格式不变。其余审查项未一并修复，不能把三个 P1 修复表述为全部开源前审查通过。

| 本轮独立执行 | 结果 |
|---|---|
| Java core / Android JVM | 47 + 272 = **319 项**，0 失败、错误、跳过 |
| 备份恢复新增回归 | 9 场景 × API 28/35 = 18 项通过；真实 AtomicFile/AES-GCM，测试 KeyStore provider |
| Go 常规 / race / vet | 通过；最终 race 连续执行 3 轮 |
| 实际 WireGuard TCP 回归 | 半关闭后响应完整；每 Peer 第 65 条连接拒绝；拨号失败释放、停止回收通过 |
| 并发/资源回归 | 双栈共享 64 配额、全局 512、并发准入、未知源拒绝、空闲/取消/RST、大响应通过 |
| 原生 AAR | 四 ABI 重建，go mod verify 和 Java API 核对通过 |
| Android / Lint | core:test、testDebugUnitTest、lintDebug、lintRelease、assembleDebug、assembleRelease 通过；--no-build-cache --rerun-tasks，111 个任务执行 |
| 云盘 TEST 1.3.2 | 原 ZIP 498 条目独立读取；生产源文件未改。F01 在 API 28/35 复现；TCP 配额及半关闭 race、实际 WireGuard 半关闭通过 |

本轮原生 AAR SHA-256：4575f330d1518676755595d3141ccb4199f70adf4b5caa8585c820f7373962c1。最终 APK/源码哈希和静态检查结果见同批 SHA256SUMS-STABLE-0.6.0.txt、validation-results-STABLE-0.6.0.json。修复实现及限制见 [P1 记录](docs/P1-FIX-2026-11-03.md)。

依然未验证：用户原签名/真机覆盖升级、真实断电/硬件 Keystore、公网 IPv6 与锁屏后台。正式包仍未签名。原审查 F04–F09、依赖维护及二进制分发合规事项仍待处理；其中 PowerShell 自检 F07 的旧断言本次没有修改，不能宣称该项通过。

## 以下为 P1 修订之前的历史记录

以下旧测试数和旧哈希仅用于追溯，不代表当前交付文件。

构建与测试：2026-11-02；交付整理：2026-11-03（香港时间）。以 GitHub 0.5.8 / 5af69e4 为正式基线。本轮与下方历史记录分开，不把历史结果当作重跑。

| 项目 | 实际结果 |
|---|---|
| Java core | 47 项，0 失败/错误/跳过 |
| Android JVM / Robolectric | 254 项，0 失败/错误/跳过 |
| Java 合计 | **301 项** |
| Lint debug | No issues found；未禁用严格检查，仅说明既有 annotation 固定版本 |
| Android 构建 | core:test、app:testDebugUnitTest、lintDebug、assembleDebug、assembleRelease 成功 |
| Go | go test ./...、go test -race ./...、go vet ./... 成功；native build 同时执行 go mod verify |
| 原生构建 | Go 1.27.0、NDK 29、API 24，四 ABI AAR 重新构建 |
| 安装包静态核验 | 包名/版本码/渠道正确；zipalign 16 KiB 对齐通过；两包各 4 个 JNI 与 AAR 经标准 strip 后逐字节一致 |
| 原签名工具 | 5 项策略单元测试通过；真实 Android 工具合成旧包/密钥测试结果随附 JSON，不使用用户密钥 |
| 玻璃来源 | 6 个核心组件与 TEST 1.3.0 / e934d48 逐字节相同，见 docs/GLASS-PROVENANCE.json |
| 语言 | 13 种，每种 285 项正式版可翻译界面字符串，包含旧正式版设备撤销错误提示 |

原生 AAR SHA-256：c2b2ad4311bf66f3110cfa0396d97db98fd8c5df0216590f448c81f0bef84ef5。

STABLE 待签名 APK：31,074,209 字节；SHA-256 fa612bc7dbf135a2b9e697f4b1b293906644993fe3da6059344546da6e339a50；包名 dev.lan.atticusvpn.server；versionCode 20；versionName 0.6.0-stable。该包未签名，不能直接安装。

TEST 调试 APK：34,277,481 字节；SHA-256 f1b1aa24278ae468ae92f75ace6eed3141bbad9b4d7a1f6104430222385afa13；包名 dev.lan.atticusvpn.server.debug；versionCode 20；versionName 0.6.0-test-debug。签名验证通过，不能替代原正式签名升级。

未读取用户 0.5.8 的签名密钥或 APK，尚未核对真实旧包证书。工具需要用户在保管原密钥的电脑上执行，签名不一致会拒绝输出。没有生成替代正式签名，没有更新 GitHub main/tag/release。

实际 Activity 测试覆盖三页位置、原配置表单迁移、主页计数、服务命令仍仅指向 WireGuard、13 种语言选择/保存、API 24、RTL、大字体、后台权限标准流程，以及原有玻璃滑块/触摸/取消手势与日志隐私/边界。预览由 Native Graphics 绘制实际组件，是主机渲染而非真机照片。

真实手机覆盖升级与配置保留、各品牌 Doze/锁屏运行、读屏/字体、GPU玻璃观感和耗电、多设备公网 IPv6 连通仍待验收。母语审校未完成；机器辅助长译文已保留格式参数和关键提示，但不能称为专业本地化验收。

---

## 原正式版历史验证记录

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


## 2026-11-05 本轮完整修复验证（Linux）

本轮的实际工具链、371 项 Java、Go/race/vet、两轮 fuzz、17 项原生脚本、12 项 Python、四 ABI AAR 与 APK 结果见 [完整报告](docs/REPAIR-AND-VALIDATION-STABLE-0.6.0.md)。此前章节属于历史记录，不能据其中 Windows/设备表述推导本轮完成同类验收。本轮 Windows 真构建、原签名覆盖安装与 Android 真机均未验证。未关闭现有安全检查；失败负向控制和一次新增测试断言修正记录均保留。
