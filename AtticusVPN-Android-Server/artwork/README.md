# 界面素材

Copyright 2026 TianzeL. SPDX-License-Identifier: Apache-2.0.

素材提供者确认可以随本项目公开分发。项目自有/授权 PNG 适用根 Apache-2.0；不是 WireGuard 或其他上游项目的商标授权。
`original/` 是字标、设置齿轮和三个导航图标的来源。应用的透明资源位于 `app/src/main/res/drawable-nodpi` 及深色目录，保留现有文字形状和像素提取规则。
新启动图标的原图直接保存在 `app/src/main/res/drawable-nodpi/app_launcher_art.png`，未重绘；旧版/API 26+ 遮罩布局由 mipmap XML 定义。

原图与最终图均需通过 PNG 元数据、CRC 和尾随数据检查。仓库不附带截图、聊天附件目录名或设备照片。

在 Windows 复现原六张透明界面资源（不生成新启动图标）：

```powershell
./tools/extract-ui-artwork.ps1 -WordmarkImage ./artwork/original/wordmark-reference.png -SettingsImage ./artwork/original/settings-reference.png -ServerImage ./artwork/original/server-reference.png -PeersImage ./artwork/original/peers-reference.png -ConnectionImage ./artwork/original/connection-reference.png
```

该步骤要求 System.Drawing，只用于可选素材复现，不是安卓构建的前置依赖。原图不被覆盖。
