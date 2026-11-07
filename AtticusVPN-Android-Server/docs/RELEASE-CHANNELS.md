# 正式版与测试版 · TianzeL

0.5.8 是第一个正式版，0.6.0 是第二个正式版。既有 1.0.0–1.3.x 均为 TEST 测试系列，序号不与正式版比较。正式版只吸收本轮指定内容；不将测试版全部协议一并发布。

| 构件 | 命名与用途 |
|---|---|
| 正式源码 | AtticusVPN-Android-Server-STABLE-0.6.0-source.zip |
| 待原签名的正式包 | AtticusVPN-Android-Server-STABLE-0.6.0-UNSIGNED.apk；不能直接安装 |
| 原签名完成的正式包 | AtticusVPN-Android-Server-STABLE-0.6.0.apk；包名 dev.lan.atticusvpn.server |
| 调试测试包 | AtticusVPN-Android-Server-TEST-0.6.0-debug.apk；包名后缀 .debug，应用名称带 TEST |
| 既有实验测试系列 | 目录、文件名标注 TEST 和原版本；保留历史字节及下载 ID |

正式版版本显示 0.6.0-stable，调试包显示 0.6.0-test-debug。设备日志文件名包含同一版本渠道。旧测试 APK 内原版本字符串作为历史事实保留，不重新签名伪装正式版。

## 使用原签名覆盖升级

用户已确认保留 0.5.8 的签名密钥。密钥与密码继续留在用户电脑，不上传源码仓库、聊天或云盘。当前开发环境没有读取该密钥，也没有生成替代正式签名。

在保存原密钥的电脑安装 Python 3.10+、JDK 17 和 Android SDK Build Tools 36.0.0，准备原 0.5.8 安装包。运行（路径及别名替换为自己的）：

```text
python tools/sign-stable.py --unsigned AtticusVPN-Android-Server-STABLE-0.6.0-UNSIGNED.apk --previous-apk AtticusVPN-Android-Server-STABLE-0.5.8.apk --keystore ORIGINAL_KEYSTORE --alias ORIGINAL_ALIAS --build-tools ANDROID_SDK/build-tools/36.0.0
```

密码由本地隐藏输入提示读取，不写命令行参数。工具先验证旧 APK 签名、正式包名和版本递增，再对齐、签名并核对新旧签名证书完全一致。不同签名会拒绝输出；不支持未经单独评审的签名密钥轮换。

签名后保留旧应用直接安装，不先卸载。静态签名/包名/版本检查不能代替真机升级与配置保留验收。`.ipsectest` 测试包与正式版属于独立数据空间，不自动读取或迁移其私密配置。

本轮先交付源码和构建结果；GitHub 正式 main、版本标签和 release 尚未更新。签名与真机验收完成后才能把待签名包替换为正式可安装发行物。
