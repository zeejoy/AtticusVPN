// Copyright (c) 2026 TianzeL
// SPDX-License-Identifier: Apache-2.0
package atticusengine

import (
	"context"
	"fmt"
	"golang.zx2c4.com/wireguard/conn"
	"golang.zx2c4.com/wireguard/device"
	wgnetstack "golang.zx2c4.com/wireguard/tun/netstack"
	"io"
	"net"
	"net/netip"
	"os"
	"runtime"
	"strings"
	"testing"
	"time"
)

// Controlled host-only ceiling test: at most 512 established flows plus one
// rejected request. Never sends to an Internet destination.
func TestTCPRealGlobalCeilingAndPeerFairness(t *testing.T) {
	Stop()
	t.Cleanup(Stop)
	ip := testHostIPv4(t)
	listener, err := net.ListenTCP("tcp4", &net.TCPAddr{IP: net.IP(ip.AsSlice())})
	if err != nil {
		t.Fatal(err)
	}
	accepted := make(chan net.Conn, maxTCPConnections+1)
	acceptDone := make(chan struct{})
	go func() {
		defer close(acceptDone)
		for {
			c, e := listener.Accept()
			if e != nil {
				return
			}
			accepted <- c
		}
	}()
	defer func() {
		listener.Close()
		<-acceptDone
		close(accepted)
		for c := range accepted {
			c.Close()
		}
	}()
	sp, spub := testWireGuardKeyPair(t)
	port := reserveUDPPort(t)
	var config strings.Builder
	fmt.Fprintf(&config, "private_key=%s\nlisten_port=%d\nreplace_peers=true\n", sp, port)
	var keys []string
	for i := 0; i < 9; i++ {
		priv, pub := testWireGuardKeyPair(t)
		keys = append(keys, priv)
		fmt.Fprintf(&config, "public_key=%s\nreplace_allowed_ips=true\nallowed_ip=10.77.0.%d/32\n", pub, i+2)
	}
	if err := Start(config.String(), dnsDisabledSentinel, defaultMTU); err != nil {
		t.Fatal(err)
	}
	var clients []*wgnetstack.Net
	for i, key := range keys {
		tun, n, e := wgnetstack.CreateNetTUN([]netip.Addr{netip.MustParseAddr(fmt.Sprintf("10.77.0.%d", i+2))}, nil, defaultMTU)
		if e != nil {
			t.Fatal(e)
		}
		d := device.NewDevice(tun, conn.NewStdNetBind(), quietWireGuardLogger())
		t.Cleanup(d.Close)
		if e = d.IpcSet(fmt.Sprintf("private_key=%s\nreplace_peers=true\npublic_key=%s\nendpoint=127.0.0.1:%d\nreplace_allowed_ips=true\nallowed_ip=0.0.0.0/0\n", key, spub, port)); e != nil {
			t.Fatal(e)
		}
		if e = d.Up(); e != nil {
			t.Fatal(e)
		}
		clients = append(clients, n)
	}
	runtime.GC()
	var before, peak runtime.MemStats
	runtime.ReadMemStats(&before)
	goroutinesBefore := runtime.NumGoroutine()
	fdsBefore, _ := os.ReadDir("/proc/self/fd")
	var connections []net.Conn
	defer func() {
		for _, c := range connections {
			c.Close()
		}
	}()
	target := netip.AddrPortFrom(ip, uint16(listener.Addr().(*net.TCPAddr).Port))
	dial := func(n *wgnetstack.Net) (net.Conn, error) {
		ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
		defer cancel()
		c, e := n.DialContextTCPAddrPort(ctx, target)
		if e != nil {
			return nil, e
		}
		return c, nil
	}
	for peer := 0; peer < 8; peer++ {
		for i := 0; i < maxTCPPerPeer; i++ {
			c, e := dial(clients[peer])
			if e != nil {
				t.Fatalf("peer %d connection %d rejected before quota: %v", peer, i, e)
			}
			connections = append(connections, c)
		}
		if c, e := dial(clients[peer]); e == nil {
			c.Close()
			t.Fatal("peer quota exceeded")
		}
	}
	if c, e := dial(clients[8]); e == nil {
		c.Close()
		t.Fatal("global quota exceeded")
	}
	runtime.ReadMemStats(&peak)
	fdsPeak, _ := os.ReadDir("/proc/self/fd")
	t.Logf("bounded live flows=%d heap_delta_bytes=%d goroutines_delta=%d fds_delta=%d", len(connections), int64(peak.HeapAlloc)-int64(before.HeapAlloc), runtime.NumGoroutine()-goroutinesBefore, len(fdsPeak)-len(fdsBefore))
	engineMu.Lock()
	g := current.gateway
	engineMu.Unlock()
	Stop()
	for range maxTCPConnections {
		select {
		case c := <-accepted:
			c.SetReadDeadline(time.Now().Add(time.Second))
			var b [1]byte
			_, e := c.Read(b[:])
			c.Close()
			if e != io.EOF {
				t.Fatal("outbound not closed", e)
			}
		case <-time.After(3 * time.Second):
			t.Fatal("missing accepted connection")
		}
	}
	g.tcp.mu.Lock()
	live := len(g.tcp.flows)
	owners := len(g.tcp.byPeer)
	g.tcp.mu.Unlock()
	if live != 0 || owners != 0 {
		t.Fatal("flow bookkeeping leaked")
	}
	deadline := time.Now().Add(2 * time.Second)
	for runtime.NumGoroutine() > goroutinesBefore+4 && time.Now().Before(deadline) {
		runtime.Gosched()
		time.Sleep(5 * time.Millisecond)
	}
	fdsAfter, _ := os.ReadDir("/proc/self/fd")
	t.Logf("after stop: live=%d goroutines_delta=%d fds_delta=%d", live, runtime.NumGoroutine()-goroutinesBefore, len(fdsAfter)-len(fdsBefore))
	if runtime.NumGoroutine() > goroutinesBefore+4 {
		t.Fatal("forwarding goroutines did not return to baseline")
	}
	if len(fdsBefore) > 0 && len(fdsAfter) > len(fdsBefore) {
		t.Fatal("outbound file descriptors leaked")
	}

}

func TestTCPRepeatedConcurrentReleaseHasNoBookkeepingOrWorkerGrowth(t *testing.T) {
	g := quotaGateway(t)
	before := runtime.NumGoroutine()
	for round := 0; round < 20; round++ {
		done := make(chan struct{}, 128)
		for range 128 {
			go func() {
				if f := g.openTCPFlow(netip.MustParseAddr("10.77.0.2")); f != nil {
					g.releaseTCPFlow(f)
				}
				done <- struct{}{}
			}()
		}
		for range 128 {
			<-done
		}
		g.tcp.wg.Wait()
		if len(g.tcp.flows) != 0 || len(g.tcp.byPeer) != 0 {
			t.Fatal("quota accumulation")
		}
	}
	deadline := time.Now().Add(time.Second)
	for runtime.NumGoroutine() > before+2 && time.Now().Before(deadline) {
		runtime.Gosched()
		time.Sleep(time.Millisecond)
	}
	if runtime.NumGoroutine() > before+2 {
		t.Fatal("persistent worker growth")
	}
}

func TestTCPSimultaneousFIN(t *testing.T) {
	a, in := tcpPair(t)
	out, b := tcpPair(t)
	g := &userGateway{ctx: context.Background()}
	done := make(chan struct{})
	go func() { g.relayTCP(g.ctx, in, out, time.Second, time.Second); close(done) }()
	a.CloseWrite()
	b.CloseWrite()
	waitTCPRelay(t, done)
}
