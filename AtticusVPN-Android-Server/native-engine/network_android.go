//go:build android

// Copyright (c) 2026 TianzeL
// SPDX-License-Identifier: Apache-2.0

package atticusengine

/*
#cgo LDFLAGS: -landroid
#include <android/multinetwork.h>

static int atticus_bind_network(unsigned long long network, int fd) {
    return android_setsocknetwork((net_handle_t)network, fd);
}
*/
import "C"

import (
	"fmt"
	"syscall"
)

// controlNetwork 在 connect 之前把新建 socket 绑定到 ConnectivityManager 选中的
// 物理 Network。责任边界：Go 只使用 fd，不取得其所有权；net 包仍负责最终 close。
// 监听用的 WireGuard socket 由 Java 通过 SocketFD4/6 + Network.bindSocket 处理。
func controlNetwork(_ string, _ string, raw syscall.RawConn) error {
	handle := selectedNetwork.Load()
	if handle == 0 {
		return nil
	}
	var bindErr error
	if err := raw.Control(func(fd uintptr) {
		if result := C.atticus_bind_network(C.ulonglong(handle), C.int(fd)); result != 0 {
			bindErr = fmt.Errorf("network bind failed")
		}
	}); err != nil {
		return err
	}
	return bindErr
}
