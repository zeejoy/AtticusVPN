// Copyright (c) 2026 TianzeL; SPDX-License-Identifier: Apache-2.0
// Adapted from original audit observation: rejection is now the expected behavior.
package atticusengine

import (
	"context"
	"encoding/json"
	"net"
	"net/netip"
	"testing"
	"time"
)

func TestSinglePeer520AttemptsRespectConnectionQuota(t *testing.T) {
	ip := testHostIPv4(t)
	l, err := net.ListenTCP("tcp4", &net.TCPAddr{IP: net.IP(ip.AsSlice())})
	if err != nil {
		t.Fatal(err)
	}
	defer l.Close()
	accepted := make(chan net.Conn, 520)
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
	n := auditClient(t)
	target := netip.AddrPortFrom(ip, uint16(l.Addr().(*net.TCPAddr).Port))
	clients := []net.Conn{}
	defer func() {
		for _, c := range clients {
			c.Close()
		}
	}()
	for i := 0; i < 520; i++ {
		ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
		c, e := n.DialContextTCPAddrPort(ctx, target)
		cancel()
		if e != nil {
			continue
		}
		clients = append(clients, c)
	}
	if len(clients) != maxTCPPerPeer {
		t.Fatalf("520 bounded attempts admitted %d, expected per-peer limit %d", len(clients), maxTCPPerPeer)
	}
	var status engineStatus
	json.Unmarshal([]byte(Status()), &status)
	t.Logf("one authenticated peer retained %d simultaneous TCP connections; total counter %d", len(clients), status.TCPConnections)
}
