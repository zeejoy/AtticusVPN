//go:build !android

// Copyright (c) 2026 TianzeL
// SPDX-License-Identifier: Apache-2.0

package atticusengine

import "syscall"

// 非 Android 构建用于单元测试；桌面系统没有 Android network handle，保持默认路由。
func controlNetwork(_ string, _ string, _ syscall.RawConn) error { return nil }
