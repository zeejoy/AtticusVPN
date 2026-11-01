# License provenance / 许可原文来源

项目自有代码作者为 TianzeL，采用根目录 `LICENSE` 的 Apache-2.0。此目录的第三方原文不替换、不重新授权第三方组件，也不意味着这里列举的全部工具都被打入 APK。

## 原文核对方法

2026-10-06 核对 Apache 官网原文：11,358 字节，SHA-256 为 `cfc7749b96f63bd31c3c42b5c471bf756814053e847c10f3eb003417bc523d30`。它与锁定 google/btree 模块的 LICENSE 逐字节相同；根 `LICENSE`、`native-engine/LICENSE` 和项目 APK 许可文件采用这一原文。

Go 原文来自 `go.mod` 固定版本下载包；在当前发布准备环境执行 `go mod verify` 成功。各 x/* 固定版本的 LICENSE／PATENTS 逐项比对相同，保留共享文件。没有把本机模块缓存路径写入公开文件。

AndroidX、JUnit、Hamcrest 与 Gradle Wrapper 原文从固定版本构件内原样提取。其他原文通过官方固定标签或 commit 下载；未调整拼写、换行、原始作者或联系信息。官方公开版权、地址或电子邮件属于第三方原始许可归属，不属于项目作者身份。

## 文件与适用范围

| 文件 | 原始来源与适用范围 |
|---|---|
| `Apache-2.0.txt` | [Apache 官方原文](https://www.apache.org/licenses/LICENSE-2.0.txt)，项目自有代码 |
| `WireGuard-Android-COPYING.txt` | [wireguard-android 标签 1.0.20260102](https://github.com/WireGuard/wireguard-android/blob/1.0.20260102/COPYING)，tunnel POM 与 Java 源码核对为 Apache-2.0 |
| `wireguard-go-MIT.txt` | `golang.zx2c4.com/wireguard` 固定模块的完整 LICENSE；原版权另保留于根 NOTICE |
| `wintun-MIT.txt` | `golang.zx2c4.com/wintun` 固定模块 LICENSE；Windows 条件依赖，不链接入 Android |
| `gVisor-LICENSE.txt` | gVisor commit `39ed1f5ac29c` 的完整 LICENSE，包含原 Apache-2.0 正文及文件特定 MIT 附录 |
| `google-btree-Apache-2.0.txt` | google/btree `v1.1.2` 模块 LICENSE |
| `Go-BSD-3-Clause.txt`、`Go-PATENTS.txt` | x/mobile commit `4776eadac327` 原文；逐字节核对也适用于 go.mod 固定的 x/crypto、x/net、x/mod、x/sync、x/sys、x/time、x/tools |
| `Go-standard-library-BSD-3-Clause.txt`、`Go-standard-library-PATENTS.txt` | Go `1.27.0` 工具链根 LICENSE／PATENTS，适用于链接的 Go 标准库 |
| `ZXing-LICENSE.txt`、`ZXing-NOTICE.txt` | [ZXing 标签 zxing-3.5.4](https://github.com/zxing/zxing/tree/zxing-3.5.4) 的完整原始 LICENSE／NOTICE；保留其中附加归属，不裁剪为仅 Apache 文本 |
| `AndroidX-annotation-LICENSE.txt` | `annotation-jvm:1.9.1` JAR 的 `META-INF/androidx/annotation/annotation/LICENSE.txt` |
| `AndroidX-collection-LICENSE.txt` | `collection-jvm:1.5.0` JAR 的 `META-INF/androidx/collection/collection/LICENSE.txt` |
| `Kotlin-LICENSE.txt`、`Kotlin-COPYRIGHT.txt` | [Kotlin v2.2.10 的 license 目录](https://github.com/JetBrains/kotlin/tree/v2.2.10/license)，适用于 stdlib 的原许可与版权 |
| `Kotlin-NOTICE.txt` | 同一 Kotlin 标签的原始 NOTICE。该原文描述上游 compiler distribution，保留为上游资料；本应用不捆绑 Kotlin 编译器 |
| `JetBrains-annotations-Apache-2.0.txt` | `annotations:13.0` 官方 POM 声明的标准 Apache-2.0；其固定版本 source JAR 的原 `NotNull.java` 版权为 `Copyright 2000-2012 JetBrains s.r.o.`，已保留在根 NOTICE |
| `desugar-LICENSE.txt`、`desugar-ADDITIONAL_LICENSE_INFO.txt`、`desugar-ASSEMBLY_EXCEPTION.txt` | [官方 commit 092407c51c3eaaaa9e46f7b7e436dc642f614064](https://github.com/google/desugar_jdk_libs/tree/092407c51c3eaaaa9e46f7b7e436dc642f614064)；GPLv2、Classpath Exception 和适用的额外声明，不受项目 Apache 替换 |
| `desugar-VERSION_JDK11.txt` | 同一 commit 的版本说明为 `2.1.5`；官方该版本 POM 指定上述上游 LICENSE |
| `Gradle-Wrapper-Apache-2.0.txt` | 仓库 wrapper JAR 的 `META-INF/LICENSE`；JAR 原样保留 |
| `JUnit-EPL-1.0.txt` | JUnit `4.13.2` JAR 的 `LICENSE-junit.txt`，仅测试 |
| `Hamcrest-BSD-3-Clause.txt` | Hamcrest core `1.3` JAR 的 `LICENSE.txt`，仅测试 |
| `JSON-java-Public-Domain.txt` | [JSON-java 标签 20250517 的 LICENSE](https://github.com/stleary/JSON-java/blob/20250517/LICENSE)，仅测试 |

上游完整仓库可能包含不在本项目构件中的额外组件；保留完整上游许可文件不表示本项目引入了它们。第三方源码修改或二进制分发时，仍需重新核对实际使用范围与适用义务。

## 原文 SHA-256

以下校验值针对原文字节，不是法律范围判定。README 本身不进入自校验。

```text
809fa1ed21450f59827d1e9aec720bbc4b687434fa22283c6cb5dd82a47ab9c0  AndroidX-annotation-LICENSE.txt
809fa1ed21450f59827d1e9aec720bbc4b687434fa22283c6cb5dd82a47ab9c0  AndroidX-collection-LICENSE.txt
cfc7749b96f63bd31c3c42b5c471bf756814053e847c10f3eb003417bc523d30  Apache-2.0.txt
a69bce275ba7a3570af6579cb0f55682cd75fedfcd49e0e8e9022270c447c916  desugar-ADDITIONAL_LICENSE_INFO.txt
a44eb7b5caf5534c6ef536b21edb40b4d6babf91bf97d9d45596868618b2c6fb  desugar-ASSEMBLY_EXCEPTION.txt
4b9abebc4338048a7c2dc184e9f800deb349366bdf28eb23c2677a77b4c87726  desugar-LICENSE.txt
b7a8eda0fbec0ddb0559f399347247da67dc8d36424c946e355b758813563cfc  desugar-VERSION_JDK11.txt
911f8f5782931320f5b8d1160a76365b83aea6447ee6c04fa6d5591467db9dad  Go-BSD-3-Clause.txt
96f408bfae65bf137fc2525d3ecb030271c50c1e90799f87abf8846d8dd505cc  Go-PATENTS.txt
911f8f5782931320f5b8d1160a76365b83aea6447ee6c04fa6d5591467db9dad  Go-standard-library-BSD-3-Clause.txt
96f408bfae65bf137fc2525d3ecb030271c50c1e90799f87abf8846d8dd505cc  Go-standard-library-PATENTS.txt
cfc7749b96f63bd31c3c42b5c471bf756814053e847c10f3eb003417bc523d30  google-btree-Apache-2.0.txt
0d542e0c8804e39aa7f37eb00da5a762149dc682d7829451287e11b938e94594  Gradle-Wrapper-Apache-2.0.txt
5af94223052169198cfa2195455b7d87209d9aa0fff4705822f328b4b272facb  gVisor-LICENSE.txt
1cde867cab5c8e842929da5391cef98b4017314822270e934e8e2eef3767c666  Hamcrest-BSD-3-Clause.txt
cfc7749b96f63bd31c3c42b5c471bf756814053e847c10f3eb003417bc523d30  JetBrains-annotations-Apache-2.0.txt
86cc84f1773e3318ba26fd61b3ae508a167df38b361695e105f6e499e7a2736f  JSON-java-Public-Domain.txt
9648bb2891b9813970bddb68d4be8a5e6ec8280d0180a53dfb29236b579c55bb  JUnit-EPL-1.0.txt
8f1a1700adb5490604c6094fb137bf73f6c28d3ee554b58f3ff8ef975a79672d  Kotlin-COPYRIGHT.txt
cfc7749b96f63bd31c3c42b5c471bf756814053e847c10f3eb003417bc523d30  Kotlin-LICENSE.txt
0b09a83d3ef7795c7dec65e815866597c066c53898f2852a76599b8f5552941a  Kotlin-NOTICE.txt
91276db973f25602d1aa43491f59cbc84cb88e6f151e1d0cc82a755563ce0195  wintun-MIT.txt
cfc7749b96f63bd31c3c42b5c471bf756814053e847c10f3eb003417bc523d30  WireGuard-Android-COPYING.txt
91276db973f25602d1aa43491f59cbc84cb88e6f151e1d0cc82a755563ce0195  wireguard-go-MIT.txt
3f62881f0566227a24b12e5a754cc79f39aaa94883038e95c94812e1f50af42f  ZXing-LICENSE.txt
4db4b60aace29ec6c5c4f13ca9cac1930d3c0c54bcc67dceead0c10771120e66  ZXing-NOTICE.txt
```
