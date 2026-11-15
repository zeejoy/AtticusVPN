# desugar 2.1.5 对应源码交付 · STABLE 0.6.0

项目自有代码仍为 Apache-2.0。APK 中的 desugar 代码保留 GPLv2、Classpath Exception、Assembly Exception 及上游附加声明；例外不表示可以忽略对应运行库的源码分发条件。

正式 APK 与完整上游源码档案均已在 [v.0.6.0 Release](https://github.com/zeejoy/AtticusVPN/releases/tag/v.0.6.0) 公开提供：[AtticusVPN-STABLE-0.6.0-desugar-2.1.5-corresponding-source.zip](https://github.com/zeejoy/AtticusVPN/releases/download/v.0.6.0/AtticusVPN-STABLE-0.6.0-desugar-2.1.5-corresponding-source.zip)。
SHA-256：`b6fc9a0d46dacd3948bd06d6d52bc1f727680ca2c0045aa008edc395ca2cbee9`；30041737 字节。

同一 Release 还提供[正式 APK](https://github.com/zeejoy/AtticusVPN/releases/download/v.0.6.0/AtticusVPN-Server-v0.6.0.apk)、[项目源码 ZIP](https://github.com/zeejoy/AtticusVPN/releases/download/v.0.6.0/AtticusVPN-Android-Server-STABLE-0.6.0-source.zip)、[许可/构建说明](https://github.com/zeejoy/AtticusVPN/releases/download/v.0.6.0/LICENSE-AND-BUILD-STABLE-0.6.0.md)和[源码校验文件](https://github.com/zeejoy/AtticusVPN/releases/download/v.0.6.0/SHA256SUMS-STABLE-0.6.0.txt)。上述 desugar 摘要与 GitHub 附件记录一致；APK 使用独立的 [SHA-256 校验文件](https://github.com/zeejoy/AtticusVPN/releases/download/v.0.6.0/SHA256SUMS-APK-STABLE-0.6.0.txt)。

来源：[092407c51c3eaaaa9e46f7b7e436dc642f614064](https://github.com/google/desugar_jdk_libs/tree/092407c51c3eaaaa9e46f7b7e436dc642f614064)，`VERSION_JDK11.txt` 有效版本行为 2.1.5。已检查 ZIP CRC、10162 个档案条目，以及 LICENSE、ADDITIONAL_LICENSE_INFO、ASSEMBLY_EXCEPTION 与项目内保留许可逐字节相同。档案包含 jdk11 源码、BUILD、WORKSPACE、repos.bzl、setup.bzl 和 tools/build_maven_artifact.py。没有修改这些上游源码。

本应用使用官方 Maven 构件 `com.android.tools:desugar_jdk_libs:2.1.5`，由 AGP 9.1.0 / Gradle 9.3.1 在 JDK 17、compileSdk 36、minSdk 24 下执行标准 desugaring、R8 和资源缩减；具体配置在 app/build.gradle.kts，最终构件与原生依赖信息随验证结果交付。没有修改 desugar 库，也没有以自有 Apache 许可重新授权它。

重新构建上游 Maven 构件应遵循源码中的 tools/build_maven_artifact.py 及其 BUILD/WORKSPACE 配置；所需标准编译工具和外部依赖按上游固定引用获取。本次没有独立重建上游 Maven JAR，不能声称其字节可重现。已实际重新构建使用该官方构件的本项目 APK。

公开下载入口已提供上述完整对应源码、项目源码及许可/构建材料。后续分发 APK 时仍应同时提供这些材料；仅链接项目 Apache LICENSE 或临时上游网页不等于完成这些交付。

已发布 APK 内 `assets/licenses/CORRESPONDING_SOURCE.md`、版本标签及固定源码归档保留构建/首次源码发布时的文字，其中“尚未实施公开分发”描述的是当时阶段。当前公开状态与有效下载入口以本文和 Release 为准；本次文档维护不修改已发布二进制、应用资源、版本标签或附件。

其他实际链接组件及许可参见 [THIRD_PARTY_NOTICES.md](../THIRD_PARTY_NOTICES.md)、[native-engine/THIRD_PARTY_NOTICES.md](../native-engine/THIRD_PARTY_NOTICES.md)、[LICENSES/README.md](../LICENSES/README.md)；APK assets/licenses 同时提供项目及第三方原文。未改变整体项目许可证，未移除上游版权。此说明记录可复核的材料和流程，不作普遍法律合规保证。
