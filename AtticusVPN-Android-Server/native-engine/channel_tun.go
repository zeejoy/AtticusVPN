// Copyright (c) 2026 TianzeL
// SPDX-License-Identifier: Apache-2.0

package atticusengine

import (
	"context"
	"errors"
	"os"
	"sync"

	"golang.zx2c4.com/wireguard/tun"
	"gvisor.dev/gvisor/pkg/buffer"
	"gvisor.dev/gvisor/pkg/tcpip"
	"gvisor.dev/gvisor/pkg/tcpip/header"
	"gvisor.dev/gvisor/pkg/tcpip/link/channel"
	"gvisor.dev/gvisor/pkg/tcpip/stack"
)

// channelTUN 是 WireGuard 与 gVisor 之间的内存 TUN。
//
// 与 Android VpnService 提供的系统 TUN 不同，这个对象不会把解密后的包交回
// Android 内核做 IP forwarding。无 Root 设备通常没有内核转发/NAT 权限，因此
// 必须在进程内把包交给 gVisor，再由用户态 TCP/UDP 转发器建立普通 Android socket。
// 这种边界仍使用官方 wireguard-go 处理握手与密码学，本类只搬运明文 IP 包。
type channelTUN struct {
	name   string
	mtu    int
	link   *channel.Endpoint
	events chan tun.Event
	ctx    context.Context
	cancel context.CancelFunc
	once   sync.Once
}

func newChannelTUN(name string, mtu int, link *channel.Endpoint) *channelTUN {
	ctx, cancel := context.WithCancel(context.Background())
	t := &channelTUN{
		name:   name,
		mtu:    mtu,
		link:   link,
		events: make(chan tun.Event, 1),
		ctx:    ctx,
		cancel: cancel,
	}
	// wireguard-go 只有收到 Up 事件后才会开始从设备读取待加密的 IP 包。
	t.events <- tun.EventUp
	return t
}

// File 对内存 TUN 没有意义。wireguard-go 的通用 Device 接口保留该方法，
// 但标准 Go backend 不依赖它；返回 nil 也避免暴露任何 Android 文件描述符。
func (t *channelTUN) File() *os.File { return nil }

// Read 从 gVisor 的出站队列取出响应包，交给 wireguard-go 加密后发给 Peer。
// channel.Endpoint 的阻塞读取可由 context 取消，确保停止前台服务时不会泄漏 goroutine。
func (t *channelTUN) Read(bufs [][]byte, sizes []int, offset int) (int, error) {
	if len(bufs) == 0 || len(sizes) == 0 {
		return 0, nil
	}
	pkt := t.link.ReadContext(t.ctx)
	if pkt == nil {
		return 0, os.ErrClosed
	}
	defer pkt.DecRef()
	view := pkt.ToView()
	defer view.Release()
	data := view.AsSlice()
	if offset < 0 || offset+len(data) > len(bufs[0]) {
		return 0, errors.New("atticus tun: output buffer too small")
	}
	copy(bufs[0][offset:], data)
	sizes[0] = len(data)
	return 1, nil
}

// Write 接收 wireguard-go 已认证并解密的裸 IPv4/IPv6 包，并注入 gVisor。
// 这里只根据 IP version nibble 分流；包头、校验和和传输层状态均由 gVisor 验证。
func (t *channelTUN) Write(bufs [][]byte, offset int) (int, error) {
	written := 0
	for _, raw := range bufs {
		if offset < 0 || offset >= len(raw) {
			continue
		}
		packet := append([]byte(nil), raw[offset:]...)
		if len(packet) == 0 {
			continue
		}
		var protocol tcpip.NetworkProtocolNumber
		switch packet[0] >> 4 {
		case 4:
			protocol = header.IPv4ProtocolNumber
		case 6:
			protocol = header.IPv6ProtocolNumber
		default:
			continue
		}
		pkt := stack.NewPacketBuffer(stack.PacketBufferOptions{
			Payload: buffer.MakeWithData(packet),
		})
		t.link.InjectInbound(protocol, pkt)
		pkt.DecRef()
		written++
	}
	return written, nil
}

func (t *channelTUN) MTU() (int, error)        { return t.mtu, nil }
func (t *channelTUN) Name() (string, error)    { return t.name, nil }
func (t *channelTUN) Events() <-chan tun.Event { return t.events }
func (t *channelTUN) BatchSize() int           { return 1 }

// Close 可重复调用。先取消 ReadContext，再关闭 endpoint；事件 channel 由本对象独占。
func (t *channelTUN) Close() error {
	t.once.Do(func() {
		t.cancel()
		t.link.Close()
		close(t.events)
	})
	return nil
}
