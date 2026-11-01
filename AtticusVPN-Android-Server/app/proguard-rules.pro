# Copyright 2026 TianzeL
# SPDX-License-Identifier: Apache-2.0
# gomobile 生成的桥接类和 native 方法名称必须保留，否则 release 混淆后 JNI 无法定位。
-keep class go.** { *; }
-keep class dev.lan.atticusvpn.atticusengine.** { *; }
-keepclasseswithmembernames class * { native <methods>; }

# WireGuard key/config 类型由应用直接调用，并不包含遥测或广告代码。
-keep class com.wireguard.crypto.** { *; }
