// Copyright (c) 2026 TianzeL
// SPDX-License-Identifier: Apache-2.0
package atticusengine

import (
	"context"
	"fmt"
	"io"
	"net"
	"net/netip"
	"strings"
	"sync"
	"testing"
	"time"
)

func tcpPair(t *testing.T) (*net.TCPConn, *net.TCPConn) {
	t.Helper()
	l, err := net.ListenTCP("tcp4", &net.TCPAddr{IP: net.IPv4(127, 0, 0, 1)})
	if err != nil {
		t.Fatal(err)
	}
	defer l.Close()
	a, err := net.DialTCP("tcp4", nil, l.Addr().(*net.TCPAddr))
	if err != nil {
		t.Fatal(err)
	}
	b, err := l.AcceptTCP()
	if err != nil {
		a.Close()
		t.Fatal(err)
	}
	t.Cleanup(func() { a.Close(); b.Close() })
	return a, b
}

func waitTCPRelay(t *testing.T, done <-chan struct{}) {
	t.Helper()
	select {
	case <-done:
	case <-time.After(3 * time.Second):
		t.Fatal("TCP workers not reclaimed")
	}
}

func TestTCPHalfClosePreservesEitherDirection(t *testing.T) {
	for _, first := range []string{"client", "server"} {
		t.Run(first, func(t *testing.T) {
			client, inbound := tcpPair(t)
			outbound, server := tcpPair(t)
			g := &userGateway{ctx: context.Background()}
			done := make(chan struct{})
			go func() { g.relayTCP(g.ctx, inbound, outbound, time.Second, time.Second); close(done) }()
			client.SetDeadline(time.Now().Add(3 * time.Second))
			server.SetDeadline(time.Now().Add(3 * time.Second))
			a, b := client, server
			if first == "server" {
				a, b = server, client
			}
			if _, err := a.Write([]byte("request")); err != nil {
				t.Fatal(err)
			}
			if err := a.CloseWrite(); err != nil {
				t.Fatal(err)
			}
			request, err := io.ReadAll(b)
			if err != nil || string(request) != "request" {
				t.Fatal("request FIN lost", err)
			}
			payload := strings.Repeat("response-after-fin", 8192)
			writeDone := make(chan error, 1)
			go func() {
				_, err := io.WriteString(b, payload)
				if err == nil {
					err = b.CloseWrite()
				}
				writeDone <- err
			}()
			response, err := io.ReadAll(a)
			if err != nil || string(response) != payload {
				t.Fatal("response truncated", err)
			}
			if err := <-writeDone; err != nil {
				t.Fatal(err)
			}
			waitTCPRelay(t, done)
			if g.stats.toInternet.Load()+g.stats.toPeer.Load() != uint64(7+len(payload)) {
				t.Fatal("traffic count mismatch")
			}
		})
	}
}

func TestTCPIdleCancelResetAndActiveHalfClose(t *testing.T) {
	for _, mode := range []string{"idle", "cancel", "reset", "active-after-fin"} {
		t.Run(mode, func(t *testing.T) {
			client, inbound := tcpPair(t)
			outbound, server := tcpPair(t)
			ctx, cancel := context.WithCancel(context.Background())
			defer cancel()
			g := &userGateway{ctx: ctx}
			done := make(chan struct{})
			go func() { g.relayTCP(ctx, inbound, outbound, 300*time.Millisecond, 300*time.Millisecond); close(done) }()
			if mode == "cancel" {
				cancel()
			}
			if mode == "reset" {
				server.SetLinger(0)
				server.Close()
			}
			if mode == "active-after-fin" {
				client.CloseWrite()
				server.SetReadDeadline(time.Now().Add(time.Second))
				if _, err := io.ReadAll(server); err != nil {
					t.Fatal(err)
				}
				for range 8 {
					time.Sleep(60 * time.Millisecond)
					if _, err := server.Write([]byte{1}); err != nil {
						t.Fatal(err)
					}
					client.SetReadDeadline(time.Now().Add(time.Second))
					var b [1]byte
					if _, err := io.ReadFull(client, b[:]); err != nil {
						t.Fatal("active stream expired", err)
					}
				}
				server.CloseWrite()
			}
			waitTCPRelay(t, done)
		})
	}
}

func quotaGateway(t *testing.T) *userGateway {
	t.Helper()
	g := &userGateway{ctx: context.Background()}
	var snapshot strings.Builder
	for i := 1; i <= 9; i++ {
		fmt.Fprintf(&snapshot, "public_key=%064x\nallowed_ip=10.77.0.%d/32\nallowed_ip=fd77::%x/128\n", i, i+1, i+1)
	}
	if err := g.configureTCPPeers(snapshot.String()); err != nil {
		t.Fatal(err)
	}
	return g
}

func TestTCPQuotasDualStackGlobalUnknownAndRelease(t *testing.T) {
	g := quotaGateway(t)
	var flows []*tcpFlow
	defer func() {
		for _, f := range flows {
			g.releaseTCPFlow(f)
		}
	}()
	for peer := 2; peer < 10; peer++ {
		for i := 0; i < maxTCPPerPeer; i++ {
			source := fmt.Sprintf("10.77.0.%d", peer)
			if i%2 == 1 {
				source = fmt.Sprintf("fd77::%x", peer)
			}
			f := g.openTCPFlow(netip.MustParseAddr(source))
			if f == nil {
				t.Fatal("early quota limit")
			}
			flows = append(flows, f)
		}
		if g.openTCPFlow(netip.MustParseAddr(fmt.Sprintf("10.77.0.%d", peer))) != nil {
			t.Fatal("per-peer quota bypass")
		}
	}
	if len(flows) != maxTCPConnections {
		t.Fatal("fixture must reach global limit")
	}
	if g.openTCPFlow(netip.MustParseAddr("10.77.0.10")) != nil {
		t.Fatal("global limit bypass")
	}
	if g.openTCPFlow(netip.MustParseAddr("10.77.0.99")) != nil {
		t.Fatal("unknown peer admitted")
	}
	g.releaseTCPFlow(flows[0])
	g.releaseTCPFlow(flows[0])
	f := g.openTCPFlow(netip.MustParseAddr("fd77::2"))
	if f == nil {
		t.Fatal("quota not reusable")
	}
	flows = append(flows, f)
}

func TestTCPAdmissionConcurrentAndShutdown(t *testing.T) {
	g := quotaGateway(t)
	var workers sync.WaitGroup
	accepted := make(chan *tcpFlow, 256)
	for range 256 {
		workers.Add(1)
		go func() {
			defer workers.Done()
			if f := g.openTCPFlow(netip.MustParseAddr("10.77.0.2")); f != nil {
				accepted <- f
			}
		}()
	}
	workers.Wait()
	close(accepted)
	if len(accepted) != maxTCPPerPeer {
		t.Fatal("concurrent quota incorrect")
	}
	var flows []*tcpFlow
	for f := range accepted {
		flows = append(flows, f)
	}
	client, server := tcpPair(t)
	flows[0].attach(server)
	g.closeTCPFlows()
	if g.openTCPFlow(netip.MustParseAddr("10.77.0.3")) != nil {
		t.Fatal("shutdown admitted dial")
	}
	for _, f := range flows {
		if f.ctx.Err() == nil {
			t.Fatal("pending dial not canceled")
		}
		g.releaseTCPFlow(f)
	}
	g.tcp.wg.Wait()
	client.SetReadDeadline(time.Now().Add(time.Second))
	var b [1]byte
	if _, err := client.Read(b[:]); err == nil {
		t.Fatal("socket leaked")
	}
	if len(g.tcp.flows) != 0 || len(g.tcp.byPeer) != 0 {
		t.Fatal("quota leaked")
	}
	lateClient, lateServer := tcpPair(t)
	flows[0].attach(lateServer)
	lateClient.SetReadDeadline(time.Now().Add(time.Second))
	if _, err := lateClient.Read(b[:]); err == nil {
		t.Fatal("late socket leaked")
	}
}

func TestTCPPeerRoutingUsesLongestAuthenticatedPrefix(t *testing.T) {
	g := &userGateway{ctx: context.Background()}
	a, b := strings.Repeat("01", 32), strings.Repeat("02", 32)
	if err := g.configureTCPPeers("public_key=" + a + "\nallowed_ip=10.77.0.0/24\npublic_key=" + b + "\nallowed_ip=10.77.0.2/32\n"); err != nil {
		t.Fatal(err)
	}
	f := g.openTCPFlow(netip.MustParseAddr("10.77.0.2"))
	if f == nil || f.owner != b {
		t.Fatal("wrong peer")
	}
	g.releaseTCPFlow(f)
}
