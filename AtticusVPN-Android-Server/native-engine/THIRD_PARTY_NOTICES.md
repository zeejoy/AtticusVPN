# Native engine third-party notices

本项目原生引擎的自有代码作者为 **TianzeL**，采用 [Apache-2.0](LICENSE)。以下依赖仍保留各自原许可证，不被本项目许可证重新授权。

| Go 模块 | 固定版本 | 范围／许可证 |
|---|---|---|
| `golang.zx2c4.com/wireguard` | `v0.0.0-20260522210424-ecfc5a8d5446` | 标准 WireGuard Device、UDP bind；MIT |
| `gvisor.dev/gvisor` | `v0.0.0-20250503011706-39ed1f5ac29c` | 用户态 TCP/IP 栈；Apache-2.0 与其 LICENSE 内的文件特定 MIT 声明 |
| `golang.org/x/crypto` | `v0.55.0` | 密码学支持／测试；BSD-3-Clause、PATENTS |
| `golang.org/x/net` | `v0.58.0` | 网络支持；BSD-3-Clause、PATENTS |
| `github.com/google/btree` | `v1.1.2` | gVisor 间接依赖；Apache-2.0 |
| `golang.org/x/mobile` | `v0.0.0-20260821190718-4776eadac327` | Android Java/JNI 桥接和构建工具；BSD-3-Clause、PATENTS |
| `golang.org/x/mod` | `v0.39.0` | 构建工具间接依赖；BSD-3-Clause、PATENTS |
| `golang.org/x/sync` | `v0.22.0` | 并发支持／构建依赖；BSD-3-Clause、PATENTS |
| `golang.org/x/sys` | `v0.47.0` | 系统调用支持；BSD-3-Clause、PATENTS |
| `golang.org/x/time` | `v0.7.0` | gVisor 间接依赖；BSD-3-Clause、PATENTS |
| `golang.org/x/tools` | `v0.49.0` | gomobile/gobind 构建工具依赖；BSD-3-Clause、PATENTS |
| `golang.zx2c4.com/wintun` | `v0.0.0-20230126152724-0fa3db229ce2` | wireguard-go 的 Windows 条件依赖；不链接进 Android ABI；MIT |

Go 标准库工具链固定为 `1.27.0`，保留 Go Authors 的 BSD-3-Clause 许可和 PATENTS。直接／间接模块版本以本目录 `go.mod` 为准，下载校验以 `go.sum` 为准。各 pinned x/* 模块的 LICENSE 和 PATENTS 经逐项核对为相同原文，随包只保留一份共享许可及专利声明，仍明确覆盖各模块。

原文在仓库根目录 [LICENSES/](../LICENSES/README.md)：

- `wireguard-go-MIT.txt`、`wintun-MIT.txt`。
- `gVisor-LICENSE.txt`：**完整保留 Apache 正文及上游 MIT 附录**，不裁剪、不改称项目许可证。
- `google-btree-Apache-2.0.txt`。
- `Go-BSD-3-Clause.txt`、`Go-PATENTS.txt`。
- `Go-standard-library-BSD-3-Clause.txt`、`Go-standard-library-PATENTS.txt`。

构建脚本为 Windows 兼容性对 x/mobile 的临时副本调整子进程环境和 UTF-8 编码；不会更改下载缓存。其修改由脚本明确标注，仍适用 x/mobile 的原许可。AAR/JNI 或其他二进制再分发时，应随包保留本说明及各组件的完整适用许可、版权、PATENTS 和 NOTICE；不能只保留项目 Apache 文件。

官方上游：

- [wireguard-go](https://git.zx2c4.com/wireguard-go/)、[wintun-go](https://git.zx2c4.com/wintun-go/)。
- [gVisor](https://github.com/google/gvisor)、[google/btree](https://github.com/google/btree)。
- [Go](https://go.dev/)、[Go 子仓库](https://go.googlesource.com/)。

该说明只描述组件归属和许可范围，不代表上游背书，也不构成法律意见。
