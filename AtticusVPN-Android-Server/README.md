# AtticusVPN Android Server

自托管的标准 WireGuard 安卓服务端。项目作者与公开署名：**TianzeL**。STABLE 版本 0.6.0，最低 Android 7.0（API 24）。[Release](https://github.com/zeejoy/AtticusVPN/releases/tag/v.0.6.0) 提供源码和原证书签名的正式 APK；已完成有限范围的真机覆盖升级检查，公网连接和后台运行等完整验收仍待完成。

本仓库只发布现有 Android Server 功能的源码：安卓界面、纯 Java 核心、Go 用户态网络引擎、测试、界面素材及构建工具。
不含 Windows/Android 客户端源码、Linux 服务端、私人签名、已有 APK/AAR、设备测试数据或未集成的 strongSwan 原生实验目录。
`dev.lan.*` 是保留的技术包名；公开作者统一使用 TianzeL。


## 正式版与测试版

GitHub 原 0.5.8 是第一个正式版，本次 0.6.0 是第二个正式版开发交付。既有 1.x 构件均属于 TEST 测试版，不能通过数字大小判断正式版先后。正式包名继续为 dev.lan.atticusvpn.server，versionCode 从 19 增至 20。STABLE 与 TEST 是发布渠道标识。

0.6.0 复用测试版的新主页、设备页和 IP 信息页；液态玻璃固定取自 **TEST 1.3.0 / e934d48**。VPN 配置迁至设置，增加当前运行日志、13 种语言和锁屏后台运行权限引导。保留正式 0.5.8 的 WireGuard、撤销设备、密钥清理和配置格式；本次不迁入 Atticus Link 或 IKEv2 服务。

原签名由用户保管。[正式 APK](https://github.com/zeejoy/AtticusVPN/releases/download/v.0.6.0/AtticusVPN-Server-v0.6.0.apk) 与原 0.5.8 的签名证书一致，证书 SHA-256 为 `2ff3b123dbaa5350542e1ee3c2cc371dde487038db3553a35d5754126caeab32`。APK SHA-256 为 `074bc9e90bbdf0f4c40f365788b805c623625ff2ff50aef6ac9f3d6888dcc0cd`；非 Debug 包，通过 v2/v3 签名与 16 KiB 对齐校验。Release 同时保留源码、校验值与第三方许可/对应源码材料；源码标签与原源码附件不变。开发者自行构建的未签名 Release 与独立包名 Debug 均不能冒充正式安装包。签名校验见 [发布渠道](docs/RELEASE-CHANNELS.md)。

2026-11-07 在 MI MAX 3（Android 10）上安装原 0.5.8、创建测试 Peer，再保留数据覆盖安装 0.6.0；安装成功、界面可打开，应用 UID、首次安装时间和测试 Peer 的名称、地址、启用状态、可导出状态保留。此检查使用新建测试配置；尚未验证既有生产配置的完整保留、服务器身份、真实 DDNS 设置、WireGuard 握手/转发、服务启停及持续后台运行，不能视为完整无损升级或公网验收。

## 本次修复与验证

F01–F09 的修复与永久测试来自已核验的修复源码包；包括状态恢复、TCP 配额/半关闭、DNS 响应匹配、通知、网关日志、构建、源码保护与隐私说明。11 月 5 日的 [完整修复报告](docs/REPAIR-AND-VALIDATION-STABLE-0.6.0.md) 为历史记录。本轮实际执行结果与未运行项见 [源码发布验证](docs/SOURCE-RELEASE-0.6.0.md)。

## 功能与边界

- 标准 WireGuard，由成熟上游密码学和 wireguard-go 实现，不自创密码算法，不含流量伪装或反 DPI 机制。
- 公网 IPv6 入口，Android 用户态 IPv4 TCP/UDP 出口；普通运行不需要 Root。
- Peer 管理、标准配置/二维码导出、相册保存与敏感数据确认；导出页面禁止截屏。
- 多服务商 DDNS，按服务商显示字段；域名跟随健康公网 IPv6 更新并独立检查 AAAA 解析。
- 共 13 种语言：简体中文、繁體中文、English、西班牙语、法语、德语、巴西葡萄牙语、俄语、阿拉伯语、印地语、日语、韩语和印尼语；深色/浅色/跟随系统，默认英语与跟随系统。
- 手机出口检测并行请求 Cloudflare、Microsoft、百度、Bilibili 官网，严格 TLS，任一 HTTPS 2xx/3xx 通过即可确认出口；DNS 未确认是独立警告。仅固定 GET 并读取状态行，不携带用户配置、Cookie 或设备标识。
- Root/XFRM 是用户主动触发的只读能力检查。**IKEv2 服务尚未集成，不能宣称双协议可用。**

公网 IPv6、运营商/路由器允许 UDP 入站及客户端 IPv6 能力仍是必要条件。DDNS 成功和手机出口成功都不证明远端客户端能连接。
目前不是通用内核路由器：不支持 ICMP/任意 IP 协议互联网转发，不保证 IPv6 互联网全隧道。

## 源码构建

源码包故意不附带原生 AAR：从源码生成，避免发布构建机路径与不透明历史产物。
先安装如下工具，按平台的官方安装方式操作：

| 工具 | 固定要求 |
|---|---|
| JDK | 17 |
| Go | 1.27.3 |
| PowerShell | 7，推荐；脚本也保留 Windows PowerShell 兼容写法 |
| Android SDK | Platform 36 / Build Tools 36.0.0 |
| Android NDK | 29.0.14206865 |
| Gradle / AGP | Wrapper 9.3.1 / AGP 9.1.0 |
| Python | 3.10+，仅用于发布检查 |

设置 `JAVA_HOME`、`ANDROID_HOME` 或 `ANDROID_SDK_ROOT`，Go 加入 PATH。NDK 默认从 SDK 的 `ndk/29.0.14206865` 读取，也可设置 `ANDROID_NDK_HOME`。
若本机同时安装多个 Go 版本，请通过原生脚本的 `-GoExe` 参数选择实际的 Go 1.27.3 可执行文件。脚本会关闭自动工具链切换并验证所选二进制版本，旧版 Go 启动器不能代替这个检查。
第一次构建需要联网下载依赖：直接 Maven 依赖固定版本，Go 模块由 go.sum 校验，Gradle Wrapper 有发行包 SHA-256。仓库没有宣称全部 Maven 传递依赖已做 Gradle 校验元数据锁定。SDK/NDK 安装需接受其上游许可，仓库不分发这些工具。

在解压后的仓库根目录：

```powershell
python ./tools/verify-public.py
./native-engine/tests/build-script.Tests.ps1
./native-engine/build-aar.ps1
./gradlew.bat --no-daemon --max-workers=2 :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

macOS/Linux 安装 PowerShell 后也可以运行原生脚本，Gradle 改用 `./gradlew`（必要时 `chmod +x gradlew`）。各平台本轮实际验证范围见源码发布验证记录，不把未执行的构建或真机操作称为已通过。
APK 在 `app/build/outputs/apk/debug/app-debug.apk`。调试签名由你自己的构建环境生成，**不保证覆盖另一签名的已有应用**。发布签名与密码必须自行保管，切勿提交。
可在原生引擎构建后用 Android Studio 打开根目录。

原生构建会修改经校验的临时 x/mobile 副本，适配子进程环境、UTF-8 和相对模块替换路径；不修改上游缓存。
它使用路径映射与调试段清理，并在输出前解压检查全部 AAR/JAR/ABI 文件，发现构建机路径则拒绝输出。详见 [原生构建说明](native-engine/README.md)。

## 使用

1. 安装 Release 中的正式 APK 或自己构建的服务端，确认手机获得健康公网 IPv6；必要时允许路由器向该手机的 UDP 入站。覆盖升级时保留应用数据并核对签名，不要卸载或清除数据。
2. 连接设置默认 UDP 51820、MTU 1380。已有自定义端口不会自动覆盖。
3. 设置 → VPN 设置可选择 DDNS 服务商并填写对应字段，说明位于输入框上方。切换服务商只改变表单；需要主动保存才能生效。
4. 启动服务器，在第二页添加 Peer，进入导出页面生成标准配置或二维码。
5. 在另一个有 IPv6 的网络上使用兼容的标准 WireGuard 客户端导入并连接，核验实际握手、DNS 和网站访问。

配置和二维码含客户端私钥，请勿公开。保存到相册可能被照片备份同步，应用会先提示，但无法控制其他应用的同步行为。
详细设置与安全说明见 [使用说明](docs/USAGE.md)、[安全边界](SECURITY.md)及[隐私说明](PRIVACY.md)。

## 许可与素材

项目自有代码、文档及授权界面素材采用 [Apache-2.0](LICENSE)。第三方依赖、Gradle Wrapper、Go/Android 运行库各自保留原许可，**不因仓库根许可证改变**。
完整归属见 [NOTICE](NOTICE)、[第三方声明](THIRD_PARTY_NOTICES.md)和 [LICENSES](LICENSES/README.md)。这是许可材料整理，不代表法律担保或上游项目背书。
新应用图标采用授权的完整盾牌/手机通道图片；主页仍使用原字标，通知图标仍是独立单色资源。
用户主动启用系统“主题图标”时使用配套的单色轮廓；普通彩色图标保留提供的原图。实现依据 [Android 自适应图标说明](https://developer.android.com/develop/ui/compose/system/icon_design_adaptive)。

## 下载与贡献

源码和校验值见 [v.0.6.0 Source-only Release](https://github.com/zeejoy/AtticusVPN/releases/tag/v.0.6.0)。**没有正式 0.6.0 APK 下载。** 历史 0.5.8 及其已披露问题保留在原 Release。

现有仓库保留原 Git 历史。贡献前请执行扫描与测试，不提交构建缓存、APK/AAR、私有日志、配置、签名或密钥。应在公开分支或 Pull Request 之前完成秘密检查；.gitignore 不会清除已经进入提交的内容。

可额外运行 `python tools/check-go-dependency-boundary.py --go <Go可执行文件>` 与 `python -m unittest discover -s tools -p "test_*.py"`。x/crypto 的 OpenPGP 模块级告警仍需跟踪，不得引入其未维护包。公开二进制须同时提供 [desugar 对应源码](docs/CORRESPONDING-SOURCE-STABLE-0.6.0.md)。
