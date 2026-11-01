# Peer 撤销回归测试

Copyright 2026 TianzeL. SPDX-License-Identifier: Apache-2.0.

## 处理约定

删除由 `AtticusServerService` 的串行队列处理：先提交授权删除，再关闭旧数据面并同步当前授权，最后请求独立密钥清理。只有真实同步完成才回执成功。同步失败会撤销运行意图并停止服务；无法确认 native 已停止时终止本应用进程，确保进程拥有的 socket 不会继续使用旧授权。

`EncryptedStateRepository.revokePeerAuthorization()` 不执行密钥清理。清理队列只根据最新已提交状态保留未 Seal 的有效 Peer；失败只记录固定事件码，按 1、5、30、60 秒有限重试，耗尽后等待下一次请求或应用启动。清理失败不会恢复授权，也不会使删除阻塞在 Keystore 清理上。

## 自动化失败测试

在已生成原生 AAR 的源码根目录运行：

```powershell
./gradlew.bat --no-daemon :core:test :app:testDebugUnitTest :app:lintDebug :app:lintRelease
```

`PeerRevocationControllerTest` 覆盖正常顺序、清理失败、运行授权同步失败后停服、持久化失败、无法确认停服时不报告成功。`SecretCleanupSchedulerTest` 覆盖有限重试、仅固定日志、并发请求合并/补跑及队列拒绝。

## Android 真机测试

必须选择允许用于测试的已授权 Android 设备。构建专用测试包，不覆盖常规 debug/release 安装：

```powershell
./gradlew.bat -PrevocationDeviceTest=true :app:assembleDebug :app:assembleDebugAndroidTest
```

专用应用包名固定为 `dev.lan.atticusvpn.server.revocationtest`，测试 APK 包名为其后加 `.test`。测试 runner 拒绝其他目标包，且要求专用应用 Peer 列表为空。只有专用测试包的数据可以重置；不能对已有真实使用数据的应用执行清除或卸载。

该 runner 使用真实 AndroidKeyStore、持久仓库、服务队列和 native WireGuard，引入两名新建临时 Peer。私钥配置仅临时保存在专用应用的私有文件内，不能复制到公开报告或提交中。测试完成会停止服务并清理本次配置文件。

本机标准客户端由以下命令生成（输出路径由你选择）：

```powershell
cd native-engine
go build -o PATH_TO_PRIVATE_TEST_CLIENT ./cmd/revocation-client
```

测试时应验证：

1. 被删除 Peer 和对照 Peer 在删除前均能完成握手并通过隧道收到固定测试 DNS 查询的响应。
2. 向 runner 的私有命令文件写入 `DELETE_OLD`，等待 `REVOKED_ENGINE_RUNNING_ONE_PEER`。
3. 使用原私钥/PSK/服务器公钥的新建客户端重复连接，在观察期内既无新握手也无 DNS 响应。
4. 对照 Peer 仍能重新握手和传输，排除“整个服务器/网络都不可用”的假阳性。
5. 完成后写入 `FINISH`，移除本次临时转发，清理本次临时配置及可丢弃的专用安装。

仅在直连条件不满足时使用测试 APK 的 loopback TCP/UDP 转发夹具：ADB 转发至设备 `tcp:39519`，主机客户端使用 `-relay 127.0.0.1:HOST_PORT`。夹具只承载原样 WireGuard 加密报文，实际认证和 Peer 撤销仍由手机 native 引擎完成。它不属于生产协议功能，也不能证明公网 UDP、运营商入站或其他网络场景可达。

客户端命令形状：

```text
revocation-client -config PRIVATE_CONFIG_PATH -expect allow -relay 127.0.0.1:HOST_PORT
revocation-client -config PRIVATE_CONFIG_PATH -expect deny -duration 18s -relay 127.0.0.1:HOST_PORT
```

客户端仅使用进程内网络栈，不创建系统 VPN 适配器或修改系统路由；输出只有握手、DNS 响应及判定布尔值。失败测试和成功构建不能代替以上 Android 真机验收，也不能证明未知攻击场景全部安全。
