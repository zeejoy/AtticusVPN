/* Copyright (c) 2026 TianzeL; SPDX-License-Identifier: Apache-2.0 */
package atticusengine

import (
	"context"
	"encoding/json"
	"net"
	"net/netip"
	"testing"
	"time"
)

func TestAuditFailedEgressMustRecordSafeReason(t *testing.T) {
	ip := testHostIPv4(t)
	l, err := net.ListenTCP("tcp4", &net.TCPAddr{IP: net.IP(ip.AsSlice())})
	if err != nil {
		t.Fatal(err)
	}
	target := netip.AddrPortFrom(ip, uint16(l.Addr().(*net.TCPAddr).Port))
	l.Close()
	n := auditClient(t)
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()
	c, err := n.DialContextTCPAddrPort(ctx, target)
	if c != nil {
		c.Close()
	}
	if err == nil {
		t.Fatal("fixture expected closed TCP destination")
	}
	// The client deadline is independent of the gateway's bounded outbound
	// dial. Windows can finish a refused connection after that client deadline.
	// Observe the actual asynchronous gateway failure instead of treating the
	// client timeout as proof that the outbound dial has already completed.
	deadline := time.Now().Add(15 * time.Second)
	var s engineStatus
	for {
		if err := json.Unmarshal([]byte(Status()), &s); err != nil {
			t.Fatal(err)
		}
		for _, e := range s.Errors {
			if e.Code == "GATEWAY_TCP_DIAL_FAILED" {
				return
			}
		}
		if time.Now().After(deadline) {
			break
		}
		time.Sleep(10 * time.Millisecond)
	}
	t.Fatalf("real egress connection failed but no fixed gateway reason recorded; error categories=%d", len(s.Errors))
}
