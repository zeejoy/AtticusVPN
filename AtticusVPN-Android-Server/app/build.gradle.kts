/*
 * Copyright (c) 2026 TianzeL
 * SPDX-License-Identifier: Apache-2.0
 *
 * 应用依赖官方 WireGuard Android tunnel 库来生成标准 Curve25519 密钥，
 * native-engine.aar 则由 native-engine/ 中锁定版本的 Go 源码构建。
 */
plugins {
    id("com.android.application")
}

android {
    namespace = "dev.lan.atticusvpn.server"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.lan.atticusvpn.server"
        minSdk = 24
        targetSdk = 36
        versionCode = 19
        versionName = "0.5.8"
        testInstrumentationRunner = "dev.lan.atticusvpn.server.RevocationDeviceRunner"
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
    }

    // 应用内切换语言时，三种语言资源应始终随应用安装。
    bundle {
        language {
            enableSplit = false
        }
    }

    buildTypes {
        debug {
            // Explicit opt-in keeps device revocation tests away from existing installations.
            applicationIdSuffix = if (providers.gradleProperty("revocationDeviceTest").orNull == "true")
                ".revocationtest" else ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = true
        warningsAsErrors = true
        // 直接依赖使用固定版本；关闭联网“最新版本”检查，不声称全部 Maven 传递依赖已有哈希锁定。
        disable += "NewerVersionAvailable"
        disable += "OldTargetApi"
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    packaging {
        jniLibs.useLegacyPackaging = false
        // Server 只复用 tunnel AAR 的纯 Java Key/KeyPair；数据面来自 native-engine。
        // 排除客户端 GoBackend 的 JNI，避免把未使用的 VPN 客户端实现打入 Server APK。
        jniLibs.excludes += setOf(
            "**/libwg-go.so",
            "**/libwg.so",
            "**/libwg-quick.so"
        )
        // 不全局剥离上游 META-INF/LICENSE/NOTICE；二进制分发应保留依赖自带声明。
    }
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")
    implementation(project(":core"))
    implementation("com.wireguard.android:tunnel:1.0.20260102")
    // 仅使用本地二维码编码核心，不引入相机、在线服务或额外 UI 框架。
    implementation("com.google.zxing:core:3.5.4")
    implementation(files("libs/atticusengine.aar"))

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250517")
}
