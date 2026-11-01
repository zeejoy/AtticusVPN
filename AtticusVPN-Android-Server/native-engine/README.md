# Android 原生数据面：从源码构建

作者：TianzeL。项目自有代码采用 Apache-2.0；第三方代码保留各自许可证，见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。

本目录使用官方 wireguard-go 和 gVisor 构建 Android AAR。标准 WireGuard UDP 在手机上接收流量，隧道内 IPv4 TCP/UDP 由用户态网关转发到手机当前网络。无需 Root；没有自研密码、流量混淆、网站伪装或规避检测机制。

开源仓库不包含已有的 AAR、APK、签名密钥、设备配置或开发缓存。先从这里生成 `../app/libs/atticusengine.aar`，再从仓库根目录构建 Android APK。

## 固定构建输入

| 输入 | 固定版本 |
|---|---|
| Go | 1.27.0 |
| JDK | 17；脚本检查 `javac` 主版本 |
| Android SDK Platform | 36 |
| Android NDK | 29.0.14206865（r29） |
| x/mobile | v0.0.0-20260821190718-4776eadac327 |
| wireguard-go | v0.0.0-20260522210424-ecfc5a8d5446 |
| gVisor | v0.0.0-20250503011706-39ed1f5ac29c |
| AAR 最低 Android API | 24 |
| ABI | arm64-v8a、armeabi-v7a、x86、x86_64 |

其他 Go 依赖及校验和以 `go.mod` / `go.sum` 为准。工具链需自行安装，许可证按各上游条款；本仓库不分发 SDK、NDK、JDK 或 Go 安装包。首次下载需要能访问 Go 模块代理和签名校验服务。

## 构建方法

使用 PowerShell 7（Windows 可使用 Windows PowerShell 5.1）。Windows、Linux 上按各系统安装 Go、JDK 17、Android SDK/NDK，设置：

- Go 加入 `PATH`，或使用 `-GoExe` 指定可执行文件。
- `JAVA_HOME`：JDK 17 目录。
- `ANDROID_HOME`：包含 `platforms/android-36/android.jar` 的 SDK 目录。
- NDK 默认从 `ANDROID_HOME/ndk/29.0.14206865` 查找；也可使用 `ANDROID_NDK_HOME` 或 `-AndroidNdk`。

从仓库根目录执行：

```powershell
./native-engine/build-aar.ps1
```

或者显式传入本机工具位置。下列变量由你事先设置为实际目录，不需要与作者的电脑目录相同：

```powershell
./native-engine/build-aar.ps1 `
  -GoExe $MyGoExecutable `
  -JavaHome $MyJdk17Directory `
  -AndroidSdk $MySdkDirectory `
  -AndroidNdk $MyNdkDirectory
```

默认输出 `app/libs/atticusengine.aar`。`-Output` 可指定另一个 `.aar` 文件；成功完成校验后才覆盖该输出。`-KeepWork` 保留此次构建的独立临时目录以便排错，默认删除自己的临时目录，不清理你的模块缓存或其他目录。

脚本在当前进程临时设置环境，并在结束时恢复。依赖下载保留 `GOSUMDB=sum.golang.org`，没有关闭证书校验或模块校验。代码来源校验、Go 测试和 `go vet` 失败都会中止构建。

若官方代理的 sumdb 路由超时，可加 `-SumDbDirect`，直接访问官方 `https://sum.golang.org`，仍使用同一官方签名公钥与一致性校验，不使用 `GOSUMDB=off`。隔离 GOPATH 时只复制已有的签名树检查点，其他缓存及源目录不改写。

## x/mobile 修改与构建路径隐私

脚本使用校验和固定的 x/mobile，并仅在独立临时副本中修改 `cmd/gomobile/bind_androidapp.go`、`cmd/gomobile/bind.go`。修改原因和范围：

1. 保留 `gobind` 子进程环境及 Go 的 `PATH`。
2. 明确使用 `javac -encoding UTF-8`。
3. 将生成的 `go.mod` 中本地绝对 `replace` 路径改为指向相同目录的相对路径。

第三项很重要：仅使用 Go `-trimpath` 并不能删除 build-info 中的绝对模块替换路径。脚本把源模块复制到自己的临时目录，确保替换目录和生成模块位于同一文件系统；不把开发者用户名、源码检出目录或原电脑位置写入该字段。上游版权及 BSD 声明不改变，下载缓存中的上游源码不修改。

此外，C/C++ 编译采用文件／调试路径前缀映射，四个 ELF 使用 NDK `llvm-strip --strip-debug` 移除调试节，不剥离 JNI 必需接口。内层 JAR 和外层 AAR 再经 `cmd/normalizeaar` 规范化顺序、时间与权限。最后逐个检查解压条目，包括 JAR 内的 class，而非只扫描压缩包；发现当前构建机目录前缀或用户目录形式的路径就拒绝输出。

这些检查面向构建目录隐私，不是任意代码、依赖或秘密的数学证明。发布自己的产物时仍应检查源码、构建信息和配置。公开仓库只交付源码；自行构建的缓存、日志及 AAR 不应直接提交。相同工具和输入下生成的压缩元数据规范化，不宣称跨所有操作系统、工具链或环境逐字节可复现。

## 自动验收范围

脚本运行：

```text
go mod download
go mod verify
go test -count=1 ./...
go vet ./...
```

随后检查四 ABI、ELF 架构、Android API 24 note、AAR manifest、所需 Java 桥接 API，以及构建路径隐私。独立脚本逻辑测试可运行：

```powershell
./native-engine/tests/build-script.Tests.ps1
```

`integration_test.go` 在本机使用另一个官方 wireguard-go Device，通过回环 UDP 建立标准握手，再验证 TCP、UDP、DNS 和 DNS TCP 回退；还有 IPv6 回环入口测试。这些不证明 Android 真机的前台服务、省电、网络切换、公网 IPv6 入站或运营商策略。

当前数据面仅转发 IPv4 TCP/UDP；不转发 IPv6 互联网流量、ICMP、任意 IP 协议、二层广播或入站端口映射。Endpoint 可使用手机公网 IPv6，但两端必须有 IPv6，路由器／运营商必须允许 UDP 入站。界面中的 IKEv2 能力检测不等于已集成 strongSwan 服务。
