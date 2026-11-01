# Third-party notices / 第三方组件声明

项目自有源码与文档版权归 **TianzeL**，采用 [Apache-2.0](LICENSE)。第三方组件保留各自许可、原始版权和商标；本项目的 Apache-2.0 **不会替换 MIT、BSD、GPL 或其他上游许可**。本说明不代表上游认可，也不构成法律意见。

## Android 运行依赖

下表按当前构建声明、实际已解析依赖以及对应版本的 POM／源码／发行物核对，不将 WireGuard Android 整个应用误作仅供本项目使用的 tunnel 库。

| 组件 | 版本 | 用途与许可 | 随包原文 |
|---|---|---|---|
| `com.wireguard.android:tunnel` | `1.0.20260102` | 标准 `Key`／`KeyPair` 与配置解析；该构件 POM、Java `Key`／`Curve25519` 源码均为 Apache-2.0。保留 WireGuard LLC 和 Southern Storm Software 原版权 | `LICENSES/WireGuard-Android-COPYING.txt`、`NOTICE` |
| `com.google.zxing:core` | `3.5.4` | 本地二维码编码；Apache-2.0 与完整上游 LICENSE 中的附加许可声明。未引入上游工具或在线服务 | `LICENSES/ZXing-LICENSE.txt`、`LICENSES/ZXing-NOTICE.txt` |
| `androidx.annotation:annotation-jvm` | `1.9.1` | tunnel 的依赖；Apache-2.0 | `LICENSES/AndroidX-annotation-LICENSE.txt` |
| `androidx.collection:collection-jvm` | `1.5.0` | tunnel 的依赖；Apache-2.0 | `LICENSES/AndroidX-collection-LICENSE.txt` |
| `org.jetbrains.kotlin:kotlin-stdlib` | `2.2.10` | 当前解析的 Java/Kotlin 标准库支持；Apache-2.0 | `LICENSES/Kotlin-LICENSE.txt`、`LICENSES/Kotlin-COPYRIGHT.txt` |
| `org.jetbrains:annotations` | `13.0` | 当前解析的类型注解支持；Apache-2.0 | `LICENSES/JetBrains-annotations-Apache-2.0.txt`、`NOTICE` |
| `com.android.tools:desugar_jdk_libs` | `2.1.5` | API 24 的标准库兼容；**GPLv2 with Classpath Exception**，以及适用的 assembly exception 和附加声明 | `LICENSES/desugar-LICENSE.txt`、`desugar-ADDITIONAL_LICENSE_INFO.txt`、`desugar-ASSEMBLY_EXCEPTION.txt` |

项目原生引擎和标准 WireGuard／TCP/IP 依赖详见 [native-engine/THIRD_PARTY_NOTICES.md](native-engine/THIRD_PARTY_NOTICES.md)。`app/src/main/assets/licenses/` 保留应用相关许可原文和声明，使开发者自行构建的 APK 也携带它们。Gradle 不全局删除依赖自带的 `META-INF/LICENSE`／`NOTICE`。

Android Server 只复用 tunnel 的 Java 类型，构建明确排除该客户端库自带的 `libwg-go.so`、`libwg.so`、`libwg-quick.so`。服务端数据面来自本项目的 native-engine，并非将官方客户端实现改称服务端。

## 构建与测试工具

| 组件 | 版本 | 范围／许可原文 |
|---|---|---|
| Gradle Wrapper | `9.3.1` | 构建工具，JAR 原样保留 Apache-2.0；`LICENSES/Gradle-Wrapper-Apache-2.0.txt` 从其内置 `META-INF/LICENSE` 提取 |
| Android Gradle Plugin | `9.1.0` | 从官方仓库下载的构建工具，不是本包附带的运行组件；依照其原始发行物许可 |
| Android SDK Platform／NDK | `36`／`29.0.14206865` | 本包不捆绑工具链；分别按下载时的原始组件许可使用 |
| JUnit | `4.13.2` | 仅单元测试，EPL-1.0；`LICENSES/JUnit-EPL-1.0.txt` 从固定版本 JAR 提取 |
| Hamcrest core | `1.3` | JUnit 测试依赖，BSD-3-Clause；`LICENSES/Hamcrest-BSD-3-Clause.txt` 从固定版本 JAR 提取 |
| JSON-java | `20250517` | 仅单元测试，Public Domain；`LICENSES/JSON-java-Public-Domain.txt` 来自固定上游标签 |

本仓库源码包不包含这些 Maven 依赖发行物或最终 APK。下载依赖、修改第三方组件或另行分发 APK/AAR 时，应保留对应许可、版权和 NOTICE，并满足各许可的适用要求。特别是包含 desugar GPL 代码的另行二进制分发，不能仅提供项目 Apache-2.0 文件便声称完成全部义务；对应源码等要求须按其 GPL 和例外条款处理。

## 核对依据

- [tunnel 1.0.20260102 官方 Maven POM](https://repo1.maven.org/maven2/com/wireguard/android/tunnel/1.0.20260102/tunnel-1.0.20260102.pom) 与 [对应源代码](https://github.com/WireGuard/wireguard-android/tree/1.0.20260102/tunnel)。
- [desugar 2.1.5 官方 Google Maven POM](https://dl.google.com/dl/android/maven2/com/android/tools/desugar_jdk_libs/2.1.5/desugar_jdk_libs-2.1.5.pom)。其指定的上游许可资料按官方 commit `092407c51c3eaaaa9e46f7b7e436dc642f614064` 保存，该 commit 的 `VERSION_JDK11.txt` 为 `2.1.5`。
- [Kotlin stdlib 2.2.10 官方 POM](https://repo1.maven.org/maven2/org/jetbrains/kotlin/kotlin-stdlib/2.2.10/kotlin-stdlib-2.2.10.pom)、[annotations 13.0 官方 POM](https://repo1.maven.org/maven2/org/jetbrains/annotations/13.0/annotations-13.0.pom)。
- [Apache 官方许可原文](https://www.apache.org/licenses/LICENSE-2.0.txt)、[许可 FAQ](https://www.apache.org/foundation/license-faq.html)。
- 各原文的版本来源、范围和校验值见 [LICENSES/README.md](LICENSES/README.md)。保留的上游公开作者姓名或联系信息属于原始归属，不是项目作者的个人信息。
