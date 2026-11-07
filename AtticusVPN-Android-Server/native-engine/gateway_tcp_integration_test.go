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
	"strings"
	"testing"
	"time"
)

func regressionClient(t *testing.T) *wgnetstack.Net { return regressionClientWithDial(t, nil) }
func regressionClientWithDial(t *testing.T, dial func(context.Context, time.Duration, string, string) (net.Conn, error)) *wgnetstack.Net {
	t.Helper()
	Stop()
	t.Cleanup(Stop)
	sp, spub := testWireGuardKeyPair(t)
	cp, cpub := testWireGuardKeyPair(t)
	port := reserveUDPPort(t)
	if err := startWithDial(fmt.Sprintf("private_key=%s\nlisten_port=%d\nreplace_peers=true\npublic_key=%s\nreplace_allowed_ips=true\nallowed_ip=10.77.0.2/32\n", sp, port, cpub), dnsDisabledSentinel, defaultMTU, dial); err != nil {
		t.Fatal(err)
	}
	tun, n, err := wgnetstack.CreateNetTUN([]netip.Addr{netip.MustParseAddr("10.77.0.2")}, nil, defaultMTU)
	if err != nil {
		t.Fatal(err)
	}
	d := device.NewDevice(tun, conn.NewStdNetBind(), quietWireGuardLogger())
	t.Cleanup(d.Close)
	if err := d.IpcSet(fmt.Sprintf("private_key=%s\nreplace_peers=true\npublic_key=%s\nendpoint=127.0.0.1:%d\nreplace_allowed_ips=true\nallowed_ip=0.0.0.0/0\n", cp, spub, port)); err != nil {
		t.Fatal(err)
	}
	if err := d.Up(); err != nil {
		t.Fatal(err)
	}
	return n
}
func TestWireGuardTCPHalfClosePreservesResponse(t *testing.T) {
	ip := testHostIPv4(t)
	l, err := net.ListenTCP("tcp4", &net.TCPAddr{IP: net.IP(ip.AsSlice())})
	if err != nil {
		t.Fatal(err)
	}
	defer l.Close()
	serverDone := make(chan bool, 1)
	go func() {
		c, e := l.AcceptTCP()
		if e != nil {
			serverDone <- false
			return
		}
		defer c.Close()
		c.SetDeadline(time.Now().Add(5 * time.Second))
		request, _ := io.ReadAll(c)
		_, e = c.Write([]byte("response-after-fin"))
		serverDone <- string(request) == "request" && e == nil
	}()
	n := regressionClient(t)
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	c, err := n.DialContextTCPAddrPort(ctx, netip.AddrPortFrom(ip, uint16(l.Addr().(*net.TCPAddr).Port)))
	if err != nil {
		t.Fatal(err)
	}
	defer c.Close()
	c.SetDeadline(time.Now().Add(5 * time.Second))
	c.Write([]byte("request"))
	if err := c.CloseWrite(); err != nil {
		t.Fatal(err)
	}
	response, err := io.ReadAll(c)
	<-serverDone
	if string(response) != "response-after-fin" {
		t.Fatalf("half-close response lost: received %d bytes, read error %v", len(response), err)
	}
}

func TestWireGuardTCPQuotaFailureReleaseAndStop(t *testing.T) {
	ip := testHostIPv4(t)
	l, err := net.ListenTCP("tcp4", &net.TCPAddr{IP: net.IP(ip.AsSlice())})
	if err != nil {
		t.Fatal(err)
	}
	defer l.Close()
	accepted := make(chan net.Conn, maxTCPPerPeer+1)
	acceptDone := make(chan struct{})
	go func() {
		defer close(acceptDone)
		for {
			c, e := l.Accept()
			if e != nil {
				return
			}
			accepted <- c
		}
	}()
	defer func() {
		l.Close()
		<-acceptDone
		close(accepted)
		for c := range accepted {
			c.Close()
		}
	}()
	n := regressionClient(t)
	engineMu.Lock()
	gateway := current.gateway
	engineMu.Unlock()
	target := netip.AddrPortFrom(ip, uint16(l.Addr().(*net.TCPAddr).Port))
	dial := func(addr netip.AddrPort) (net.Conn, error) {
		ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
		defer cancel()
		connection, err := n.DialContextTCPAddrPort(ctx, addr)
		if err != nil {
			return nil, err
		}
		return connection, nil
	}
	// Failed outbound dials must release their pending quota.
	closed, err := net.ListenTCP("tcp4", &net.TCPAddr{IP: net.IP(ip.AsSlice())})
	if err != nil {
		t.Fatal(err)
	}
	closedTarget := netip.AddrPortFrom(ip, uint16(closed.Addr().(*net.TCPAddr).Port))
	closed.Close()
	for range 3 {
		c, e := dial(closedTarget)
		if c != nil {
			c.Close()
		}
		if e == nil {
			t.Fatal("closed destination unexpectedly connected")
		}
	}
	var clients []net.Conn
	defer func() {
		for _, c := range clients {
			c.Close()
		}
	}()
	for range maxTCPPerPeer {
		c, e := dial(target)
		if e != nil {
			t.Fatal("quota leaked before limit", e)
		}
		clients = append(clients, c)
	}
	if c, e := dial(target); e == nil {
		c.Close()
		t.Fatal("real WireGuard peer bypassed TCP quota")
	}
	Stop()
	// The WireGuard UDP listener is now closed, so clients need not receive a
	// FIN. Verify the actual outbound sockets instead of waiting on 64 clients.
	for range maxTCPPerPeer {
		select {
		case server := <-accepted:
			server.SetReadDeadline(time.Now().Add(time.Second))
			var b [1]byte
			_, err := server.Read(b[:])
			server.Close()
			if err != io.EOF {
				t.Fatal("outbound socket did not close", err)
			}
		case <-time.After(3 * time.Second):
			t.Fatal("target did not accept completed connections")
		}
	}
	gateway.tcp.mu.Lock()
	count := len(gateway.tcp.flows)
	gateway.tcp.mu.Unlock()
	if count != 0 {
		t.Fatal("stop leaked TCP quota")
	}
}

// Java revocation applies a durable reduced Peer set by stopping/restarting the
// whole engine. Exercise those exact native operations with an established flow.
func TestRevocationRestartClosesOldFlowsAndRejectsRemovedPeer(t *testing.T) {
	ip := testHostIPv4(t)
	l, e := net.ListenTCP("tcp4", &net.TCPAddr{IP: net.IP(ip.AsSlice())})
	if e != nil {
		t.Fatal(e)
	}
	defer l.Close()
	n := regressionClient(t)
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()
	target := netip.AddrPortFrom(ip, uint16(l.Addr().(*net.TCPAddr).Port))
	c, e := n.DialContextTCPAddrPort(ctx, target)
	if e != nil {
		t.Fatal(e)
	}
	defer c.Close()
	l.SetDeadline(time.Now().Add(2 * time.Second))
	remote, e := l.AcceptTCP()
	if e != nil {
		t.Fatal(e)
	}
	defer remote.Close()
	engineMu.Lock()
	old := current.gateway
	snapshot, e := current.device.IpcGet()
	engineMu.Unlock()
	if e != nil {
		t.Fatal("snapshot unavailable")
	}
	// Retain the server identity but no Peer authorization. Never log the snapshot.
	reduced := "replace_peers=true\n"
	for _, line := range strings.Split(snapshot, "\n") {
		if strings.HasPrefix(line, "private_key=") || strings.HasPrefix(line, "listen_port=") {
			reduced += line + "\n"
		}
	}
	Stop()
	if e = Start(reduced, dnsDisabledSentinel, defaultMTU); e != nil {
		t.Fatal(e)
	}
	remote.SetReadDeadline(time.Now().Add(time.Second))
	var b [1]byte
	_, e = remote.Read(b[:])
	if e != io.EOF {
		t.Fatal("revocation left outbound socket alive", e)
	}
	old.tcp.mu.Lock()
	remaining := len(old.tcp.flows)
	old.tcp.mu.Unlock()
	if remaining != 0 {
		t.Fatal("revocation leaked quota")
	}
	rejected, stop := context.WithTimeout(context.Background(), 250*time.Millisecond)
	defer stop()
	next, e := n.DialContextTCPAddrPort(rejected, target)
	if e == nil {
		next.Close()
		t.Fatal("removed peer still forwarded")
	}
}
