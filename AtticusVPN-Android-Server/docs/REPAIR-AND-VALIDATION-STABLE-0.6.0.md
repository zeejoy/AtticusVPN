# AtticusVPN Android Server 0.6.0 全面修复与回归验证报告

TianzeL · STABLE 0.6.0 发布准备 · 2026-11-05 · 保持私有

## 1. 审查对象、工作区及证据边界

实际读取了用户指定的未公开源码与完整旧审查报告。原仓库为 release/0.6.0，HEAD b8d3f98e30b67395b09bf8ee16f4dad14c4fc614，versionCode 20，base versionName 0.6.0。开始时原目录已经含此前 F01–F03 的未提交修复，未把它误称为未经修改的审查提交。

本轮在独立本地分支 repair/0.6.0-preopen 修改，隔离目录为 atticus-060-fullfix/repo/AtticusVPN-Android-Server。保留了开始时全部 362 个源文件的 SHA-256、pre-task-source.zip、既有未提交补丁；另从 b8d3f98 建立独立 control 目录重跑旧复现。结束核对原目录 362 个源文件全部未变。没有修改公开 0.5.8，没有 Commit、Push、创建 Release 或上传尚未公开的源码，也没有访问用户的正式签名私钥/密码。

本版本实际数据面是标准 WireGuard 与用户态 TCP/UDP/DNS 网关。本次没有将测试系列的其他协议整体迁入正式版。公开 v.0.5.8 的快照只用于数据格式与版本比较，未代替修复对象。

“独立回归”指原始审查提交与修复副本分离、重用此前失败的复现、额外从调用边界测试，以及最终产物复查；不声称获得了外部第三方审计或真机认证。

## 2. 验收表

| 编号 | 修复状态 | 回归测试/检查 | 是否仍有风险或未验证事项 |
|---|---|---|---|
| F01 | 已修复 | 60 项 API 24/26/28/35 存储测试及旧审查复现通过 | 模拟 Android AtomicFile + 测试用内存 Keystore；真实硬件 Keystore、断电与 OEM 文件系统未验证 |
| F02 | 已修复 | 全局/单 Peer/双栈配额、真实 512 连接、520 次单 Peer 尝试、失败拨号、停止/撤销、并发回收通过 | 低内存真机资源预算、长期高负载未验证；达到配额会明确拒绝新连接 |
| F03 | 已修复 | 原 response-after-fin 复现、双向 FIN、大响应、延迟持续响应、同时 FIN、RST、空闲和取消通过 | 真实 Android 网络/运营商长连接未验证 |
| F04 | 已修复 | Question/ID/opcode/type/class/数量、压缩名称、畸形报文、TC 不缓存、既有 UDP/TCP DNS 与 TTL 测试通过 | 上游真实性仍依赖既有 DNS/网络安全模型，不把绑定校验当作 DNSSEC |
| F05 | 已修复 | 原 Robolectric 复现及 API 24/28/35 复检、重复命令、语言切换、停止通过 | 厂商通知实现与真实后台行为未验证 |
| F06 | 已修复 | 真实 WireGuard 关闭端口、实际转发器受控 TCP 超时/UDP 拨号失败、DNS 失败、固定分类、脱敏及 Java 日志模型通过 | 日志仍是采样；进程在采样前崩溃可能丢失末尾事件，不能记录任意远端真实原因 |
| F07 | 已修复 | PowerShell 17 项检查及实际 Go 子进程环境测试通过；Linux 四 ABI AAR 构建通过 | Windows 主机实际构建未验证；Windows PATH 大小写测试为模拟语义检查 |
| F08 | 已修复 | 独立/嵌套 Git 占位文件、内容扫描、源码 ZIP 检查通过 | 忽略与模式扫描不能证明未知秘密绝不存在；发布时仍需限定源包范围 |
| F09 | 已修复 | PRIVACY 与 LOGGING、采集器、UI/导出实现人工逐项对照；日志与 13 语言资源测试通过 | 技术正文仍为中英双语；外部导出文件生命周期不受应用控制 |
| R01 | 部分处理 | 已升级兼容稳定修复版本；OSV、govulncheck 与导入边界检查完成 | x/crypto 0.58.0 仍命中未维护 OpenPGP 包版本告警；本构建无该包导入/可达符号，尚无对应稳定标签修复版本 |
| G01 | 未验证（正式签名/实机升级） | 未签名 Release、Debug、元数据、R8、资源缩减、ABI/对齐、签名脚本策略已验证 | 原签名、真实覆盖安装和数据保持必须由持有者本地完成；不能现在分发为已验收正式 APK |
| G02 | 已确认（本地交付材料） | 25 个 APK 许可资产逐字节对应；完整匹配 desugar 源码及说明随交付 | 未独立重建上游 desugar JAR；公开分发入口尚未创建，实际发布时必须一起提供；不作普遍法律保证 |

表中“已修复/通过”均限下述实际自动化或人工验证范围，不等于已完整通过真实 Android 设备验收。

## 3. 各项修改、前后行为与永久测试

### F01

位置：`app/src/main/java/dev/lan/atticusvpn/server/storage/KeystoreBlob.java:52`；`app/src/main/java/dev/lan/atticusvpn/server/storage/KeystoreBlob.java:58`；`app/src/main/java/dev/lan/atticusvpn/server/storage/KeystoreBlob.java:95`；`app/src/main/java/dev/lan/atticusvpn/server/storage/EncryptedStateRepository.java:54`。

原行为：只检查主文件，备份可恢复时可能新建身份。修复后识别 base/.bak/.new，先验证旧备份再调用 AtomicFile 的恢复读取；损坏/缺钥/解密失败不回退为首次安装，也不覆盖恢复候选。仓库仅在恢复或首次提交成功后发布 state，清理必须经过 requireReady。补上 finishWrite 的提交读回核对，防止平台仅记录提交失败却让应用当作成功；已有密文缺钥时禁止重新生成 AES key。格式、AAD、别名及 schema 未改变。

永久测试：KeystoreBlobRecoveryTest（15 场景 × 4 API）、AuditAtomicRecoveryTest。覆盖首次/正常读取、备份独存、损坏主文件+有效备份、未完成 .new、写入前后中断、failWrite、缺钥/错钥、坏备份保留主文件、失败后再启动、首次提交失败禁止清理、静默提交失败。恢复用合成设备/密钥/设置，核对原密文字节不变；硬件密钥未参与。

### F02

位置：`native-engine/gateway_tcp.go:19`；`native-engine/gateway_tcp.go:92`；`native-engine/engine.go:97`；`native-engine/gateway.go:252`。

原行为：Forwarder 的 512 仅限制待处理请求，单 Peer 实际可持有 520 条连接。修复后在拨号前占用活动配额，全局 512、每 Peer 64，IPv4/IPv6 以成功 IpcGet 的有效 AllowedIPs 与公钥归属共享配额，不接受客户端自报身份。拨号最长 12 秒，空闲 15 分钟；成功、失败、取消、停止路径只释放一次。停止先关闭准入、取消拨号、关闭 socket 并等待工作协程。撤销沿用既有“持久化授权→停止/重建引擎→异步清理”流程。

数值依据与限制：512 与既有 256 UDP 会话上限共同约束用户态网关；64 为全局的 1/8，防止单 Peer 占满全局。每 TCP 双向复制缓冲约 64 KiB，512 条仅此项约 32 MiB。受控真实 WireGuard/gVisor 测试允许 8 Peer 各 64 条，再拒绝第九 Peer；普通测试测得含客户端与本地目标的 Go 堆增量约 52.4 MiB，停止后配额 0、goroutine 比基线少 38、FD 少 5。该测量不是 Android 设备总内存上界，race 构建也有额外开销。15 分钟/2 分钟采用空闲而非连接总时长，持续传输会续期。低内存手机仍需实测；本轮不宣称此默认值适合所有硬件。

永久测试：gateway_tcp_test.go、gateway_resources_test.go、gateway_tcp_integration_test.go、audit_quota_regression_test.go。原“520 全成功”观察测试已原样重新运行：修复前能保持 520，修复后在第 65 条被拒绝（此原观察的预期失败单独留档）；永久通过测试改为 520 次有界尝试只能保持 64。实际关闭端口/受控超时后配额可复用，撤销重启关闭旧出口并拒绝已移除 Peer，20 轮并发释放无持续增长。

### F03

位置：`native-engine/gateway_tcp.go:154`。

原行为：任一方向复制结束即关闭双向，response-after-fin 收到 0 字节。修复后正常 EOF 只向对端传播 CloseWrite，另一方向继续传输；异常、取消与超时才终止整体。两个复制协程都退出后释放配额，保留字节计数。FIN 后空闲期限缩短为 2 分钟，每次实际传输续期，避免静默连接永久占用。

永久测试：原 AuditTCPHalfClosePreservesResponse、真实 WireGuardTCPHalfClosePreservesResponse，及 TCPHalfClosePreservesEitherDirection、TCPIdleCancelResetAndActiveHalfClose、TCPSimultaneousFIN。覆盖双向先 FIN、约 144 KiB 反向响应、延迟持续传输、RST、同时结束、空闲和取消。

### F04

位置：`native-engine/dns_validation.go:30`；`native-engine/dns_cache.go:76`。

原行为：只匹配 transaction ID/QR，不同 Question 可进入原查询缓存。修复后校验长度、保留标志、请求/响应方向、ID、opcode、单 Question 数量，解析比较 QNAME/QTYPE/QCLASS。域名只按 ASCII 大小写不敏感比较；使用现有 dnsmessage 处理压缩指针和报文边界。非截断完整响应必须能完整解析；Question 有效的截断 UDP 可触发 TCP 重试，但绝不进入缓存。缓存插入再次验证，避免绕过上游调用路径。

永久测试：dns_validation_test.go、旧 AuditDNSRejectsMismatchedQuestion、既有 dns_cache_test.go/integration_test.go。覆盖大小写、压缩回答与指针越界/循环、数量/type/class/opcode/ID 不匹配、NXDOMAIN、AD/CD、截断与畸形长度、UDP/TCP 回退及 TTL 老化。新增 FuzzDNSResponseValidation，另保留原原生解析 fuzz。

### F05

位置：`app/src/main/java/dev/lan/atticusvpn/server/service/AtticusServerService.java:829`。

原行为：CHECK 直接写“准备中”，却未改变去重键，后续 RUNNING 被去重跳过。修复后所有通知写入（包括 startForeground 和语言刷新）经过同一受锁入口，去重键取实际生成通知的标题、正文和操作文字，成功展示后才更新；移除通知同步清空键。语言刷新与停止通过同一命令锁协调。

永久测试：AuditServiceNotificationTest 保留原复现，增加多次 CHECK、英文/中文/日文/阿拉伯文切换、主页状态和 STOP 后通知移除。API 24、28、35 通过；13 语言原有资源测试继续通过。

### F06

位置：`native-engine/gateway_telemetry.go:24`；`native-engine/gateway.go:306`；`native-engine/gateway.go:462`；`app/src/main/java/dev/lan/atticusvpn/server/logging/RunLogModel.java:49`。

原行为：WireGuard logger 有 Book，真实网关失败没有接入。修复后网关和引擎共享同一个有界 Book，覆盖 TCP/UDP 外连失败、拨号超时、端点创建、TCP/UDP 配额、DNS 全上游失败及异常转发 I/O。只记录固定类别、固定解释、次数和首次/末次时间；不传入目标、查询名、私钥、Token 或原始异常。正常 EOF、用户取消和设计内的空闲回收不报转发错误。UDP 复制也等待两方向结束后释放会话，并关闭停止/准入的 WaitGroup 竞态窗口。

测试注入点为未导出的 startWithDial；公开 Start 始终使用正常绑定网络的拨号器，gomobile 公共 API 不变。永久测试：旧 AuditFailedEgressMustRecordSafeReason、gateway_telemetry_test.go（真实转发器超时/UDP 失败与取消、DNS 失败、10000 次有界计数）、RuntimeLogCollectorGatewayTest（原生 JSON→Java 现有模型→显示文本，重复快照不重复累计且忽略原始 reason）。

### F07

位置：`native-engine/tests/build-script.Tests.ps1:67`；`native-engine/tests/environment_test.go.in:16`。

原行为：自检只断言过时的 append(os.Environ()) 文本，实际正确补丁使用 cmd.Env 并由固定上游 environ 合并。修复断言并增加可执行验证：提取实际补丁与固定 x/mobile 的 environ 实现，运行 Go 子进程，检查父环境、自定义命令环境、SDK/NDK、CGO、Go 参数、显式 Go 路径/回退路径和含空格 PATH。测试 Windows PATH 大小写归一语义，保留重复补丁拒绝、UTF-8、相对模块替换、构建路径泄露、sumdb 检查。Linux PowerShell 自检 17 项通过，真实四 ABI 构建通过。

永久测试：native-engine/tests/build-script.Tests.ps1 与 environment_test.go.in。Windows 真实主机/NDK 工具链操作未执行，不能用语义模拟代替。

### F08

位置：`.gitignore:52`；`tools/verify-public.py:38`；`tools/test_source_protection.py:13`。

原行为：源码包只带内层较弱规则，独立 git init 后 6 个原报告敏感文件均不会被忽略；本轮负向控制再次复现。修复后内外层增加 WireGuard .conf、Atticus profile、签名属性、SSH 私钥、证书、加密状态及 .bak/.new、数据库/备份和私密目录规则，保留 Gradle 配置、源码、许可及显式公开示例。内容扫描同时拒绝敏感名称/类型，并测试普通文件名下的私钥块，未把 .gitignore 当作唯一防护。

永久测试：tools/test_source_protection.py。在独立平面与嵌套 Git 仓库创建合成占位文件，确认忽略和合法源码准入；最终源包再次解压、git init 后执行。最终源包内容扫描、CRC 和逐文件对照另有证据。

### F09

位置：`PRIVACY.md:23`；`docs/LOGGING.md:13`。

原行为：PRIVACY 主入口遗漏本次运行日志，专门 LOGGING 文档已有部分说明。已对照真实采集/渲染/复制/SAF 路径同步：实际字段、完整 IP 内存保留、部分遮盖只是显示策略、1024 设备/512 事件/每设备 16 端点/192 KiB 快照上限、进程结束丢失、停止 VPN 但进程存活仍保留、手动显示/复制/保存、60 秒待导出快照、外部提供商同步、无自动上传。

文档没有把遮盖说成匿名化。13 种语言控件资源逐字节保持，技术正文为中英双语；日志脱敏/保留/导出相关原有测试继续通过。本项采用人工内容对照，不能把文字审阅称为真机隐私认证。

## 4. R01 依赖维护、公告与实际可达性

| 组件 | 原版本 | 最终版本 | 依据与范围 |
|---|---|---|---|
| Go | 1.27.0 | 1.27.3 | 官方发行列表及 SHA-256 校验安装，覆盖包含 GO-2026-6809 在内的相应标准库补丁 |
| x/net | 0.58.0 | 0.62.0 | 覆盖 HTTP/2 与 GO-2026-6860、GO-2026-6866 等版本范围；当前 DNS 使用不等于 HTTP/HTML 漏洞可利用 |
| x/crypto | 0.55.0 | 0.58.0 | x/net 0.62.0 的稳定依赖要求；SSH 相关问题在 ≥0.56.0 修复，OpenPGP 见下文 |
| x/sys | 0.47.0 | 0.49.0 | x/net 升级所需的兼容依赖；未盲目升级其余全部模块 |
| x/mod | 0.39.0 | 0.40.0 | 覆盖 GO-2026-6179/6180 的 sumdb 相关修复；gomobile 实际构建及自检通过 |
| Guava | 33.6.0-jre | 33.7.2-jre | 测试依赖约束，修复 GHSA-xxph-c9ww-hj94；最终解析只出现在 debugUnitTestRuntimeClasspath |

Go 工具链升级后、x/net/x/crypto 组合升级后、x/mod 升级后分别运行实际 Go 测试；Guava 更新后执行 core/app 测试与依赖解析。所有 Go 模块仍由 go.sum 和 GOSUMDB 验证，未关闭校验。最终 OSV 查询 13 个 Go/标准库条目和 45 个 Maven 坐标，无请求错误；Maven 未发现此次查询已知命中。该结果不保证没有未知漏洞，也不证明全部构建供应链无风险。

官方 GO-2026-5932 明确说明 x/crypto/openpgp 未维护且不安全。查询时固定版本告警的修复界限为 0.58.1-0.20261009181718-2f1f834330b2，官方最新稳定标签仍为 0.58.0；本轮选择兼容稳定版本，没有为清空报告盲目改用预发行提交。232 条实际导入依赖（含测试）中没有 OpenPGP 包，最终 Linux 源码、Android arm64 源码与四 ABI 保留符号 AAR 扫描均未确认函数级可达命中。源码扫描仍保留该模块级告警，不声称“零漏洞”。永久增加 tools/check-go-dependency-boundary.py，后续引入 OpenPGP 会明确失败；应在有合适稳定标签后重新评估并升级。

官方证据：go.dev/dl 发行及校验值、vuln.go.dev/ID/GO-2026-5932.json、GO-2026-6809.json、GO-2026-6860.json、GO-2026-6179.json，以及 OSV 原始 JSON 均随验证证据保存。没有将未公开源码上传扫描服务，在线查询内容仅为公开依赖坐标。

## 5. G01 签名、构建和覆盖升级

最终正式包为 dev.lan.atticusvpn.server，versionCode 20，versionName 0.6.0-stable；公开 0.5.8 源码为 versionCode 19。Debug 为独立 dev.lan.atticusvpn.server.debug / 0.6.0-test-debug，使用环境 Debug 签名，仅供测试，不能冒充正式覆盖升级。

Release 为未签名 APK，apksigner verify 退出 1 是预期结果，不列为签名通过。Release 无 debuggable，R8 与资源缩减实际执行。Debug 签名验证通过。四 ABI 均为 arm64-v8a、armeabi-v7a、x86、x86_64。两 APK 的 zipalign -c -P 16 4 通过；64 位原生 ELF LOAD 对齐 0x4000（16 KiB），32 位为 0x1000（4 KiB，不宣称 32 位支持 16 KiB 页）。库无 .debug_/.zdebug_ 段；保留符号 AAR 经 NDK strip 后与 APK 的所有四 ABI 库逐字节一致。ZIP 条目扫描未见所检查的私钥块、Token、构建路径或意外调试文件；模式扫描不是任意秘密不存在的证明。

签名脚本增加摘要格式校验、非 debuggable/工具/参数检查和排他写入输出，防止签名期间出现同名文件时覆盖已有 APK；错误不回显签名工具的敏感文本。12 项 Python 测试（签名策略 9 + 源码保护 3）通过。没有读取用户真实 Release key，没有用 Debug key 签署 Release。

覆盖升级前应由用户在本地使用原签名，先验证旧 APK 与新签名证书摘要相同，再使用 adb install -r（不得卸载/清空数据）测试。核对原服务器公钥、Peer 名称/授权/Seal/导出能力、DDNS、端口/DNS/MTU/语言设置，检查重新启动及失败恢复。源码层 encode/decode 两方法与公开 0.5.8 逐字节相同，MAIN_ALIAS、SCHEMA 相同；AES-GCM 文件格式/AAD 未更改。这支持兼容性判断，但不能替代真实原签名覆盖安装。

## 6. G02 许可与对应源码

保留 Apache-2.0、原 NOTICE 及 WireGuard、gVisor、Go、ZXing、AndroidX/Kotlin、Gradle Wrapper 等声明。没有改变项目整体许可证，也没有移除第三方版权。APK 中 25 个 assets/licenses 文件与源码资产逐字节相同。升级 Go/x/* 后同步原生组件版本声明；测试期 Robolectric/Guava 与 APK 运行依赖分开记录。

完整 desugar_jdk_libs 2.1.5 对应源码作为单独 ZIP 随交付：上游 commit 092407c51c3eaaaa9e46f7b7e436dc642f614064，10162 条 ZIP 条目，包含 jdk11 源码、BUILD、WORKSPACE、repos.bzl、setup.bzl 和 build_maven_artifact.py。VERSION_JDK11.txt 的有效版本行为 2.1.5；三份上游核心许可与既有项目原文逐字节相同。归档未修改上游代码，SHA-256 见交付清单和 docs/CORRESPONDING-SOURCE-STABLE-0.6.0.md，说明同样进入 APK。

已补齐本地材料与明确分发步骤，未独立重建上游 Maven JAR，未创建公开下载入口。实际发布 APK 时必须在同一位置提供项目源码、该对应源码归档与许可/构建说明；不能仅挂项目 Apache LICENSE。未确认存在侵权或许可违规；也不把本轮材料核对表述为所有司法辖区或未来分发方式的普遍法律保证。原素材权属声明保留，原始授权凭证未独立验证。

## 7. 实际执行的验证与原始结果

| 命令/检查 | 实际结果与证据 |
|---|---|
| go test -count=1 ./... | 38 个顶层测试、61 项含子测试通过；final-go-test.jsonl |
| go test -race -count=1 ./... | 同上全部通过，无 race 报告；final-go-race.jsonl |
| go vet ./... | 退出 0，final-go-vet.log |
| govulncheck 源码（Linux + Android arm64） | 退出 0；保留 1 项 OpenPGP 模块级告警，无导入/符号命中；原始 JSON 单独交付 |
| govulncheck 保留符号 AAR（4 ABI） | 各退出 0；原始 JSON 单独交付。没有把剥离符号库的保守推断当作真实可达证明 |
| Go 模块验证 | AAR 脚本实际 go mod verify，全部通过 |
| 原生脚本自检 | 17 项通过，包含真实 Go 子进程环境；final-build-script.log |
| 原生 AAR | Go 1.27.3、NDK 29.0.14206865、API 24，四 ABI 成功；native-build-final.log；公共 gomobile API 未变 |
| :core:test / :app:testDebugUnitTest | 47 + 324 = 371，失败/错误/跳过均 0；完整 JUnit XML 已留存 |
| :app:lintDebug / :app:lintRelease | 通过，abortOnError 与 warningsAsErrors 保持；Lint 原始报告留存 |
| :app:assembleDebug / :app:assembleRelease | 成功；最终源代码完整回归 111 个任务重跑，更新最终 AAR 后再执行相同任务，随后 clean 重新打包；均有原始日志 |
| DNS fuzz | 20 秒目标、实际约 21 秒，206969 次执行，无崩溃；fuzz-dns.log |
| 原生 parser fuzz | 20 秒、467193 次执行，无崩溃；fuzz-native.log |
| Python 签名/源码保护 | 12 项通过；final-python-tests.log |
| 源码/档案/APK | 源包逐条内容扫描与 CRC/字节比较；实际解压/git init 保护测试；APK/ELF/许可/哈希证据 |

Go 的两个 cmd 子包本身没有测试文件，工具输出为 [no test files]；没有跳过已存在的失败测试。短时 fuzz 不证明所有输入无漏洞或无限期稳定。

负向控制：在 b8d3f98 的隔离副本运行旧审查，AtomicFile 的 API 28/35 和通知复现共 3 项失败；Go 的 FIN、DNS 和日志复现失败，520 条观察成功；旧构建自检失败；6 项忽略规则缺失再次复现。相同缺陷的修复版回归通过。负向控制的失败是证据，未混入“全部测试通过”的最终正向结果。

过程失败记录也保留：首次完整 Android 回归有 2 项新日志测试把单次事件错误期待为 count=1，而既有 UI 仅次数大于 1 才显示计数；按真实输出修正为固定码且只出现一次后，目标及完整套件均通过。没有删除断言、跳过测试或关闭检查。增量 Debug APK 曾有约 7.9 MB 旧打包空隙，已保存观察记录并 clean 重建；最终 APK 无该大空隙，Release 字节未受影响。

## 8. 未验证事项与本地验收步骤

1. 原 Release 签名证书、私钥使用及 0.5.8→0.6.0 真机覆盖安装。仅由持有者本地操作，不发送私钥/口令。
2. API 24–28 和新系统的真实 AndroidKeyStore、断电/异常终止、空间不足/文件系统失败恢复；只能在受控测试设备操作，不破坏现有用户数据。
3. 真实公网 WireGuard 握手、IPv6 重编号、移动网络/Wi-Fi 切换、DDNS 实际提供商、弱网与运营商 NAT。
4. 真机长时间锁屏/Doze/OEM 电池管理、前台通知和权限引导；后台政策不能靠主机测试保证。
5. 低内存/发热手机的配额预算、持续高负载和耗电；当前资源测试仅本地受控主机，未对公网压测。
6. Windows 的 JDK/Go/SDK/NDK/PowerShell 实际 AAR 构建及本地原密钥签名。现有 Windows PATH 测试只验证分支语义。
7. desugar 上游 Maven JAR 的独立可重现构建；公开发布时对应源码实际可下载性；全部 AGP/Gradle 传递供应链与原素材授权凭证的完整独立证明。
8. 测试结果不等于已发现并排除所有潜在缺陷；后续依赖公告需继续监控，特别是 OpenPGP 版本范围告警。

建议用户 Windows 端按 README 的固定工具链先执行 native-engine/tests/build-script.Tests.ps1，再执行 build-aar.ps1 与 Gradle；正式签名运行 tools/sign-stable.py，使用旧版 APK 作签名与升级元数据基线。任何签名或身份不匹配都应停止，不用卸载重装冒充覆盖成功。

## 9. 五个独立结论

A. 公开 0.6.0 源代码：**可发布，限定本次核验的源码 ZIP 范围**。F01–F09 已按本报告修复并验证，忽略与内容保护补齐，未发现本次已检查范围内的真实凭据泄漏；仍须保留依赖风险/验证边界说明。此判断不是自动公开授权，本轮保持私有。

B. 发布正式 Android APK：**证据不足，暂不能确认/不建议立即正式分发**。代码与构建检查通过，但原签名、真实覆盖升级、硬件恢复及网络/后台验收尚未完成。只交付明确标识的 Debug 测试包和 UNSIGNED Release。

C. 遗留 P0/P1 或必须发布前解决事项：**本轮未确认仍有 F01–F03 或新增 P0/P1；仍有发布门槛**。需完成 G01 真正签名/升级、关键真机回归，并在实际分发时落实 G02 对应源码入口。R01 OpenPGP 版本告警有不可达证据与导入守卫，不能称为完全消除。

D. 旧版密钥、Peer、设置：**源码与模拟证据支持保留，真实升级证据不足**。编码/解码、别名/schema 兼容，恢复测试保持原状态，且禁止错误新建/清理；实际保留依赖同包名、原签名、未清数据与完好 Keystore。

E. 用户 Windows/真机后续：**未验证，必须继续本地验收**。按第 8 节完成 Windows 工具链、原密钥签名、覆盖安装、硬件存储恢复、真实 IPv6/切网/DDNS 和长时间锁屏/低内存测试。未完成前不标注“正式用户验收通过”。

## 10. 交付及追溯

交付包括完整源码 ZIP、全部永久回归测试、Debug APK、未签名 Release APK、四 ABI AAR、desugar 对应源码、相对 b8d3f98 的完整修复补丁、逐文件变化清单、原始测试/静态分析/构建证据 ZIP、机器可读验证结果及 SHA256SUMS。源码 ZIP 不含 .git、构建缓存、签名或原始测试日志；日志属于单独的私有验收证据，不应直接随源码公开。

源代码版本保持 0.6.0 / 20，不修改正式包名；原项目与历史测试版均保留。最终文件名、字节数与 SHA-256 以交付目录的 BUILD-ARTIFACTS-STABLE-0.6.0.json 和 SHA256SUMS-STABLE-0.6.0.txt 为准。报告不把自身哈希嵌入自身，避免循环校验。

源包扫描过程也记录了一次已处理的发布保护提示：完整报告新增的官方公告域名 vuln.go.dev 原先不在既有公开域名表中。已按本轮实际读取的官方公告精确加入该域名，保留对其余未知域名的拒绝规则，没有禁用扫描或放开通配域名。
