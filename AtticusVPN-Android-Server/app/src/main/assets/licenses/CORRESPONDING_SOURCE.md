# desugar 2.1.5 对应源码交付 · STABLE 0.6.0

项目自有代码仍为 Apache-2.0。APK 中的 desugar 代码保留 GPLv2、Classpath Exception、Assembly Exception 及上游附加声明；例外不表示可以忽略对应运行库的源码分发条件。

本交付随 APK 提供完整上游源码档案：`AtticusVPN-STABLE-0.6.0-desugar-2.1.5-corresponding-source.zip`。
SHA-256：`b6fc9a0d46dacd3948bd06d6d52bc1f727680ca2c0045aa008edc395ca2cbee9`；30041737 字节。

来源：[092407c51c3eaaaa9e46f7b7e436dc642f614064](https://github.com/google/desugar_jdk_libs/tree/092407c51c3eaaaa9e46f7b7e436dc642f614064)，`VERSION_JDK11.txt` 有效版本行为 2.1.5。已检查 ZIP CRC、10162 个档案条目，以及 LICENSE、ADDITIONAL_LICENSE_INFO、ASSEMBLY_EXCEPTION 与项目内保留许可逐字节相同。档案包含 jdk11 源码、BUILD、WORKSPACE、repos.bzl、setup.bzl 和 tools/build_maven_artifact.py。没有修改这些上游源码。

本应用使用官方 Maven 构件 `com.android.tools:desugar_jdk_libs:2.1.5`，由 AGP 9.1.0 / Gradle 9.3.1 在 JDK 17、compileSdk 36、minSdk 24 下执行标准 desugaring、R8 和资源缩减；具体配置在 app/build.gradle.kts，最终构件与原生依赖信息随验证结果交付。没有修改 desugar 库，也没有以自有 Apache 许可重新授权它。

重新构建上游 Maven 构件应遵循源码中的 tools/build_maven_artifact.py 及其 BUILD/WORKSPACE 配置；所需标准编译工具和外部依赖按上游固定引用获取。本次没有独立重建上游 Maven JAR，不能声称其字节可重现。已实际重新构建使用该官方构件的本项目 APK。

发布 APK 时必须同时在同一下载位置提供此完整对应源码档案、项目源码与本说明；仅链接项目 Apache LICENSE 或临时上游网页不等于完成这些交付。此轮保持本地/私有，尚未实施公开分发，因此公开下载入口可用性需发布者在发布时核验。

其他实际链接组件及许可参见 THIRD_PARTY_NOTICES.md、native-engine/THIRD_PARTY_NOTICES.md、LICENSES/README.md；APK assets/licenses 同时提供项目及第三方原文。未改变整体项目许可证，未移除上游版权。此说明记录可复核的材料和流程，不作普遍法律合规保证。
