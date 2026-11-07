// Copyright (c) 2026 TianzeL; SPDX-License-Identifier: Apache-2.0
package atticusengine

import (
	"dev.lan.atticusvpn/server/native-engine/internal/telemetry"
	"errors"
	"strings"
	"testing"
)

func TestPeerSnapshotFailureIsUnavailableRatherThanEmpty(t *testing.T) {
	key := strings.Repeat("02", 32)
	valid := "public_key=" + key + "\nendpoint=[2001:db8::1]:51515\nlast_handshake_time_sec=123\nrx_bytes=5\ntx_bytes=7\n"
	var failures telemetry.Book
	peers, available := readPeerSnapshot(func() (string, error) { return valid, nil }, &failures)
	if !available || len(peers) != 1 {
		t.Fatal("valid runtime snapshot unavailable")
	}
	for _, read := range []func() (string, error){
		func() (string, error) { return "", errors.New("TEST-ONLY-private-error-must-not-leak") },
		func() (string, error) { return strings.Replace(valid, "rx_bytes=5", "rx_bytes=bad", 1), nil },
		func() (string, error) { return strings.Replace(valid, "tx_bytes=7\n", "", 1), nil },
		func() (string, error) { return strings.Replace(valid, "public_key="+key, "public_key=invalid", 1), nil },
	} {
		peers, available = readPeerSnapshot(read, &failures)
		if available || len(peers) != 0 {
			t.Fatal("failed counter read marked complete")
		}
	}
	rows := failures.Snapshot()
	if len(rows) != 1 || rows[0].Code != "WG_STATUS_FAILED" || rows[0].Count != 4 {
		t.Fatal("status errors not retained")
	}
	peers, available = readPeerSnapshot(func() (string, error) { return "listen_port=51820\n", nil }, &failures)
	if !available || len(peers) != 0 {
		t.Fatal("genuinely empty successful snapshot not distinguished")
	}
}

func TestPeerEndpointParsingRejectsNonSocketValues(t *testing.T) {
	key := strings.Repeat("02", 32)
	for _, endpoint := range []string{"vpn.example.test:500", "[::1]:0", "192.0.2.1:1 password=LEAK", "[::1%bad zone]:1"} {
		peers := parsePeerStatus("public_key=" + key + "\nendpoint=" + endpoint + "\n")
		if len(peers) != 1 || peers[0].Endpoint != "" {
			t.Fatal("unsafe endpoint retained")
		}
	}
	peers := parsePeerStatus("public_key=" + key + "\nendpoint=[2001:db8::1]:51515\n")
	if peers[0].Endpoint != "[2001:db8::1]:51515" {
		t.Fatal("numeric IPv6 endpoint lost")
	}
}

func TestWireGuardErrorClassificationNeverRetainsArguments(t *testing.T) {
	for format, want := range map[string]string{"Unable to update bind: %v": "WG_BIND_UPDATE_FAILED", "%v - Failed to send data packets: %v": "WG_DATA_SEND_FAILED", "Failed to decode initiation message": "WG_INVALID_PACKET", "private_key=DO-NOT-RETAIN": "NATIVE_RUNTIME_ERROR"} {
		if got := wgErrorCode(format); got != want {
			t.Fatal("wrong error classification")
		}
	}
}
