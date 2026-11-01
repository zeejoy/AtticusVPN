// Copyright (c) 2026 TianzeL
// SPDX-License-Identifier: Apache-2.0

package atticusengine

import (
	"strings"
	"testing"
)

// validUAPI 只使用重复字节的测试 key，便于覆盖解析器且不会混入真实密钥。
func validUAPI() string {
	return "private_key=" + strings.Repeat("01", 32) + "\n" +
		"listen_port=51820\nreplace_peers=true\n" +
		"public_key=" + strings.Repeat("02", 32) + "\n" +
		"preshared_key=" + strings.Repeat("03", 32) + "\n" +
		"replace_allowed_ips=true\nallowed_ip=10.77.0.2/32\n"
}

func TestValidateServerUAPI(t *testing.T) {
	port, err := validateServerUAPI(validUAPI())
	if err != nil || port != 51820 {
		t.Fatalf("port=%d err=%v", port, err)
	}
}

func TestValidateServerUAPIRejectsUnknownAndSecretShape(t *testing.T) {
	for _, bad := range []string{
		validUAPI() + "endpoint=example.com:1\n",
		strings.Replace(validUAPI(), strings.Repeat("01", 32), "secret", 1),
		strings.Replace(validUAPI(), "listen_port=51820", "listen_port=0", 1),
		validUAPI() + "private_key=" + strings.Repeat("04", 32) + "\n",
	} {
		if _, err := validateServerUAPI(bad); err == nil {
			t.Fatal("invalid UAPI accepted")
		}
	}
}

func TestParsePeerStatusDoesNotExposeSecrets(t *testing.T) {
	raw := "private_key=" + strings.Repeat("01", 32) + "\n" +
		"public_key=" + strings.Repeat("02", 32) + "\n" +
		"preshared_key=" + strings.Repeat("03", 32) + "\n" +
		"last_handshake_time_sec=123\nrx_bytes=5\ntx_bytes=7\n"
	peers := parsePeerStatus(raw)
	if len(peers) != 1 || peers[0].LatestHandshakeUnix != 123 || peers[0].ReceiveBytes != 5 || peers[0].TransmitBytes != 7 {
		t.Fatalf("unexpected peer status: %+v", peers)
	}
	encoded := Status()
	if strings.Contains(encoded, "private_key") || strings.Contains(encoded, "preshared_key") {
		t.Fatal("status must not expose secrets")
	}
}

func TestParseUpstreamsAndDisabledSentinel(t *testing.T) {
	got, enabled, err := parseUpstreams("2001:4860:4860::8888, 1.1.1.1:5353, fe80::1%7")
	if err != nil || !enabled || len(got) != 3 ||
		got[0] != "[2001:4860:4860::8888]:53" || got[1] != "1.1.1.1:5353" {
		t.Fatalf("unexpected upstreams: %#v", got)
	}
	if got[2] != "[fe80::1%7]:53" {
		t.Fatalf("scoped link-local DNS lost its zone: %q", got[2])
	}
	disabled, enabled, err := parseUpstreams("  !disabled ")
	if err != nil || enabled || disabled != nil {
		t.Fatalf("DNS sentinel not honored: upstreams=%v enabled=%v err=%v", disabled, enabled, err)
	}
	if _, _, err := parseUpstreams("!disabled,1.1.1.1"); err == nil {
		t.Fatal("mixed DNS sentinel must be rejected")
	}
}

func TestUDPSessionLimit(t *testing.T) {
	gateway := &userGateway{udpSlots: make(chan struct{}, maxUDPSessions)}
	for index := 0; index < maxUDPSessions; index++ {
		if !gateway.acquireUDPSession() {
			t.Fatalf("slot %d rejected before limit", index)
		}
	}
	if gateway.acquireUDPSession() {
		t.Fatal("session above UDP concurrency limit was accepted")
	}
	for index := 0; index < maxUDPSessions; index++ {
		gateway.releaseUDPSession()
	}
	if !gateway.acquireUDPSession() {
		t.Fatal("released UDP slot was not reusable")
	}
	gateway.releaseUDPSession()
}
