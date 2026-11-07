// Copyright (c) 2026 TianzeL
// SPDX-License-Identifier: Apache-2.0
package atticusengine

import (
	"context"
	"dev.lan.atticusvpn/server/native-engine/internal/telemetry"
	"encoding/json"
	"errors"
	"io"
	"net"
	"net/netip"
	"strings"
	"testing"
	"time"
)

func TestGatewayFailureCategoriesAreBoundedAndDoNotRetainTargets(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	g := &userGateway{ctx: ctx, failures: &telemetry.Book{}}
	secret := errors.New("TEST_ONLY token=do-not-retain host=private.example.test")
	g.recordDial(ctx, "TCP", secret)
	g.recordDial(ctx, "UDP", secret)
	g.recordDial(ctx, "TCP", &net.DNSError{IsTimeout: true, Name: "private.example.test", Err: "TEST_ONLY_SECRET"})
	g.recordIO(secret)
	for range 10000 {
		g.record("GATEWAY_TCP_LIMIT")
		g.record("GATEWAY_UDP_LIMIT")
	}
	data, _ := json.Marshal(g.failures.Snapshot())
	for _, code := range []string{"GATEWAY_TCP_DIAL_FAILED", "GATEWAY_UDP_DIAL_FAILED", "GATEWAY_DIAL_TIMEOUT", "GATEWAY_FORWARD_IO_FAILED", "GATEWAY_TCP_LIMIT", "GATEWAY_UDP_LIMIT"} {
		if !strings.Contains(string(data), code) {
			t.Fatal("missing category", code)
		}
	}
	for _, raw := range []string{"private.example.test", "TEST_ONLY", "do-not-retain"} {
		if strings.Contains(string(data), raw) {
			t.Fatal("sensitive exception retained")
		}
	}
	before := string(data)
	for _, err := range []error{nil, io.EOF, net.ErrClosed, context.Canceled, &net.DNSError{IsTimeout: true}} {
		g.recordIO(err)
	}
	cancel()
	g.recordDial(ctx, "TCP", secret)
	g.recordIO(secret)
	after, _ := json.Marshal(g.failures.Snapshot())
	if string(after) != before {
		t.Fatal("normal idle/EOF/cancel misreported")
	}
}
func TestRealDNSFailureAndCanceledQueryTelemetry(t *testing.T) {
	g, e := newUserGateway(defaultMTU, "127.0.0.1:1")
	if e != nil {
		t.Fatal(e)
	}
	defer g.close()
	g.dialContext = func(context.Context, time.Duration, string, string) (net.Conn, error) {
		return nil, errors.New("TEST_ONLY_DNS_QUERY_PRIVATE")
	}
	if _, e := g.resolveDNS(testDNSQuery(), false); e == nil {
		t.Fatal("expected actual resolver failure")
	}
	rows := g.failures.Snapshot()
	if len(rows) != 1 || rows[0].Code != "GATEWAY_DNS_FAILED" {
		t.Fatal("DNS failure missing")
	}
	g.cancel()
	g.resolveDNS(testDNSQuery(), true)
	if g.failures.Snapshot()[0].Count != 1 {
		t.Fatal("cancellation logged as failure")
	}
}

func TestActualForwardersReleaseTimedOutTCPAndFailedUDP(t *testing.T) {
	n := regressionClientWithDial(t, func(ctx context.Context, _ time.Duration, network, address string) (net.Conn, error) {
		if network == "tcp" {
			select {
			case <-ctx.Done():
				return nil, ctx.Err()
			case <-time.After(time.Millisecond):
				return nil, &net.OpError{Op: "dial", Net: "tcp", Err: context.DeadlineExceeded}
			}
		}
		return nil, errors.New("TEST_ONLY_PRIVATE_UDP_DESTINATION")
	})
	target := netip.AddrPortFrom(testHostIPv4(t), 40001)
	for range maxTCPPerPeer + 2 {
		ctx, cancel := context.WithTimeout(context.Background(), time.Second)
		c, e := n.DialContextTCPAddrPort(ctx, target)
		cancel()
		if e == nil {
			c.Close()
			t.Fatal("timed-out egress admitted")
		}
	}
	u, e := n.DialUDPAddrPort(netip.AddrPort{}, target)
	if e != nil {
		t.Fatal(e)
	}
	defer u.Close()
	u.Write([]byte("test"))
	deadline := time.Now().Add(time.Second)
	for {
		var status engineStatus
		json.Unmarshal([]byte(Status()), &status)
		codes := map[string]uint64{}
		for _, row := range status.Errors {
			codes[row.Code] = row.Count
		}
		if codes["GATEWAY_UDP_DIAL_FAILED"] > 0 {
			if codes["GATEWAY_TCP_DIAL_FAILED"] != maxTCPPerPeer+2 || codes["GATEWAY_DIAL_TIMEOUT"] != maxTCPPerPeer+2 {
				t.Fatal("actual dial telemetry missing")
			}
			if strings.Contains(Status(), "TEST_ONLY_PRIVATE_UDP_DESTINATION") {
				t.Fatal("UDP error text leaked")
			}
			break
		}
		if time.Now().After(deadline) {
			t.Fatal("UDP failure absent from actual status")
		}
		time.Sleep(time.Millisecond)
	}
	engineMu.Lock()
	g := current.gateway
	engineMu.Unlock()
	Stop()
	if len(g.tcp.flows) != 0 || len(g.udpSlots) != 0 {
		t.Fatal("failed dials leaked admission")
	}
}

func TestStopCancelsActualPendingTCPDial(t *testing.T) {
	entered := make(chan struct{}, 1)
	n := regressionClientWithDial(t, func(ctx context.Context, _ time.Duration, _, _ string) (net.Conn, error) {
		entered <- struct{}{}
		<-ctx.Done()
		return nil, ctx.Err()
	})
	done := make(chan struct{})
	go func() {
		defer close(done)
		ctx, cancel := context.WithTimeout(context.Background(), time.Second)
		defer cancel()
		c, e := n.DialContextTCPAddrPort(ctx, netip.AddrPortFrom(testHostIPv4(t), 40002))
		if e == nil {
			c.Close()
		}
	}()
	select {
	case <-entered:
	case <-time.After(time.Second):
		t.Fatal("dial never started")
	}
	engineMu.Lock()
	g := current.gateway
	engineMu.Unlock()
	Stop()
	select {
	case <-done:
	case <-time.After(2 * time.Second):
		t.Fatal("dial did not cancel")
	}
	if len(g.tcp.flows) != 0 {
		t.Fatal("pending dial quota leaked")
	}
	if len(g.failures.Snapshot()) != 0 {
		t.Fatal("user stop misreported as failure")
	}
}
