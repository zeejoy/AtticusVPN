# AtticusVPN Android Server

自托管的标准 WireGuard 安卓服务端。项目作者与公开署名：**TianzeL**。应用版本 0.5.8，最低 Android 7.0（API 24）。

本仓库只发布现有 Android Server 功能的源码：安卓界面、纯 Java 核心、Go 用户态网络引擎、测试、界面素材及构建工具。
不含 Windows/Android 客户端源码、Linux 服务端、私人签名、已有 APK/AAR、设备测试数据、Git 历史或未集成的 strongSwan 原生实验目录。
`dev.lan.*` 是保留的技术包名；公开作者统一使用 TianzeL。

## 功能与边界

- 标准 WireGuard，由成熟上游密码学和 wireguard-go 实现，不自创密码算法，不含流量伪装或反 DPI 机制。
- 公网 IPv6 入口，Android 用户态 IPv4 TCP/UDP 出口；普通运行不需要 Root。
- Peer 管理、标准配置/二维码导出、相册保存与敏感数据确认；导出页面禁止截屏。
- 多服务商 DDNS，按服务商显示字段；域名跟随健康公网 IPv6 更新并独立检查 AAAA 解析。
- 简体中文、繁體中文、English；深色/浅色/跟随系统，默认英语与跟随系统。
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
| Go | 1.27.0 |
| PowerShell | 7，推荐；脚本也保留 Windows PowerShell 兼容写法 |
| Android SDK | Platform 36 / Build Tools 36.0.0 |
| Android NDK | 29.0.14206865 |
| Gradle / AGP | Wrapper 9.3.1 / AGP 9.1.0 |
| Python | 3.10+，仅用于发布检查 |

设置 `JAVA_HOME`、`ANDROID_HOME` 或 `ANDROID_SDK_ROOT`，Go 加入 PATH。NDK 默认从 SDK 的 `ndk/29.0.14206865` 读取，也可设置 `ANDROID_NDK_HOME`。
第一次构建需要联网下载依赖：直接 Maven 依赖固定版本，Go 模块由 go.sum 校验，Gradle Wrapper 有发行包 SHA-256。仓库没有宣称全部 Maven 传递依赖已做 Gradle 校验元数据锁定。SDK/NDK 安装需接受其上游许可，仓库不分发这些工具。

在解压后的仓库根目录：

```powershell
python ./tools/verify-public.py
./native-engine/tests/build-script.Tests.ps1
./native-engine/build-aar.ps1
./gradlew.bat --no-daemon --max-workers=2 :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

macOS/Linux 安装 PowerShell 后也可以运行原生脚本，Gradle 改用 `./gradlew`（必要时 `chmod +x gradlew`）。本次发布以 Windows 源码构建验证为准，不把未实测平台称为已验证。
APK 在 `app/build/outputs/apk/debug/app-debug.apk`。调试签名由你自己的构建环境生成，**不保证覆盖另一签名的已有应用**。发布签名与密码必须自行保管，切勿提交。
可在原生引擎构建后用 Android Studio 打开根目录。

原生构建会修改经校验的临时 x/mobile 副本，适配子进程环境、UTF-8 和相对模块替换路径；不修改上游缓存。
它使用路径映射与调试段清理，并在输出前解压检查全部 AAR/JAR/ABI 文件，发现构建机路径则拒绝输出。详见 [原生构建说明](native-engine/README.md)。

## 使用

1. 安装自己构建的服务端，确认手机获得健康公网 IPv6；必要时允许路由器向该手机的 UDP 入站。
2. 连接设置默认 UDP 51820、MTU 1380。已有自定义端口不会自动覆盖。
3. 第三页可选择 DDNS 服务商并填写对应字段，说明位于输入框上方。切换服务商只改变表单；需要主动保存才能生效。
4. 启动服务器，在第二页添加 Peer，进入导出页面生成标准配置或二维码。
5. 在另一个有 IPv6 的网络上使用兼容的标准 WireGuard 客户端导入并连接，核验实际握手、DNS 和网站访问。

配置和二维码含客户端私钥，请勿公开。保存到相册可能被照片备份同步，应用会先提示，但无法控制其他应用的同步行为。
详细设置与安全说明见 [使用说明](docs/USAGE.md)、[安全边界](SECURITY.md)及[隐私说明](PRIVACY.md)。

## 许可与素材

项目自有代码、文档及授权界面素材采用 [Apache-2.0](LICENSE)。第三方依赖、Gradle Wrapper、Go/Android 运行库各自保留原许可，**不因仓库根许可证改变**。
完整归属见 [NOTICE](NOTICE)、[第三方声明](THIRD_PARTY_NOTICES.md)和 [LICENSES](LICENSES/README.md)。这是许可材料整理，不代表法律担保或上游项目背书。
新应用图标采用授权的完整盾牌/手机通道图片；主页仍使用原字标，通知图标仍是独立单色资源。
用户主动启用系统“主题图标”时使用配套的单色轮廓；普通彩色图标保留提供的原图。实现依据 [Android 自适应图标说明](https://developer.android.com/develop/ui/compose/system/icon_design_adaptive)。

## 提交 GitHub

先本地解压再上传文件；把 ZIP 当单个文件上传不会自动展开为源码仓库。不要上传自己的构建缓存、APK/AAR、日志、导出配置或签名。

用 Git 提交前，请在 GitHub 启用邮箱隐私，并使用该账号提供的 noreply 邮箱。不要依赖电脑上可能含真实姓名的全局 Git 配置：

```text
git init
git add .
git -c user.name=TianzeL -c user.email=YOUR_GITHUB_NOREPLY_EMAIL commit -m "Initial Android Server source release"
git branch -M main
git remote add origin YOUR_REPOSITORY_URL
git push -u origin main
```

账号公开资料、后续提交作者、截图和 Issue 内容仍由你控制；源码包的隐私检查不能替你隐藏 GitHub 账号身份。
本包不含 `.git`，没有携带既往提交作者/邮箱。

