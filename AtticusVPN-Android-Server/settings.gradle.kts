/*
 * Copyright (c) 2026 TianzeL
 * SPDX-License-Identifier: Apache-2.0
 *
 * Android Server 独立成可直接由 Android Studio 打开的工程；core 保持纯 Java，
 * 使配置与 Peer 策略能够在没有 Android 设备的构建机上完整单元测试。
 */
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "AtticusVPN-Android-Server"
include(":core", ":app")
