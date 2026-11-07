/* Copyright (c) 2026 TianzeL; SPDX-License-Identifier: Apache-2.0 */
package atticusengine

import (
	"context"
	"fmt"
	"golang.org/x/net/dns/dnsmessage"
	"golang.zx2c4.com/wireguard/conn"
	"golang.zx2c4.com/wireguard/device"
	wgnetstack "golang.zx2c4.com/wireguard/tun/netstack"
	"io"
	"net"
	"net/netip"
	"testing"
	"time"
)

func auditClient(t *testing.T) *wgnetstack.Net {
	t.Helper()
	Stop()
	t.Cleanup(Stop)
	sp, spub := testWireGuardKeyPair(t)
	cp, cpub := testWireGuardKeyPair(t)
	port := reserveUDPPort(t)
	if err := Start(fmt.Sprintf("private_key=%s\nlisten_port=%d\nreplace_peers=true\npublic_key=%s\nreplace_allowed_ips=true\nallowed_ip=10.77.0.2/32\n", sp, port, cpub), dnsDisabledSentinel, defaultMTU); err != nil {
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
func TestAuditTCPHalfClosePreservesResponse(t *testing.T) {
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
	n := auditClient(t)
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
func TestAuditDNSRejectsMismatchedQuestion(t *testing.T) {
	query, answer := makeDNSPair(t, 123, 60)
	var m dnsmessage.Message
	if err := m.Unpack(answer); err != nil {
		t.Fatal(err)
	}
	m.Questions[0].Name = dnsmessage.MustNewName("unrelated.example.")
	changed, err := m.Pack()
	if err != nil {
		t.Fatal(err)
	}
	if validDNSResponse(query, changed) {
		t.Error("accepted same ID but different DNS question")
	}
	cache := newDNSCache(2)
	cache.put(query, changed)
	if _, ok := cache.get(query); ok {
		t.Error("cached different-question answer under original query")
	}
}
func FuzzAuditNativeParsers(f *testing.F) {
	f.Add([]byte("invalid"))
	f.Add([]byte(validUAPI()))
	f.Add(testDNSQuery())
	f.Fuzz(func(t *testing.T, b []byte) {
		if len(b) > 65536 {
			return
		}
		validateServerUAPI(string(b))
		parsePeerStatusChecked(string(b))
		normalizeEndpoint(string(b))
		responseTTL(b)
		ageDNSResponse(b, 7, 1)
		validDNSResponse(b, b)
	})
}
