// Copyright (c) 2026 TianzeL
// SPDX-License-Identifier: Apache-2.0

// Package telemetry keeps bounded counters for fixed, non-secret failure causes.
package telemetry

import (
	"sort"
	"sync"
	"time"
)

type Error struct {
	Code      string `json:"code"`
	Reason    string `json:"reason"`
	FirstUnix int64  `json:"firstUnix"`
	LastUnix  int64  `json:"lastUnix"`
	Count     uint64 `json:"count"`
}

// The fixed vocabulary prevents arbitrary error text, URLs, credentials or
// payload values from being retained, even when a caller passes an unknown code.
var reasons = map[string]string{
	"GATEWAY_DIAL_TIMEOUT":       "An outbound socket could not be established before its dial deadline.",
	"NATIVE_RUNTIME_ERROR":       "A native runtime error occurred; raw arguments are not retained.",
	"WG_BIND_UPDATE_FAILED":      "WireGuard could not update its UDP socket binding.",
	"WG_BIND_CLOSE_FAILED":       "WireGuard could not close its UDP socket cleanly.",
	"WG_HANDSHAKE_FAILED":        "WireGuard could not construct, derive or send a handshake response.",
	"WG_DATA_SEND_FAILED":        "WireGuard could not send encrypted data packets.",
	"WG_TUN_IO_FAILED":           "WireGuard could not read or write the userspace tunnel interface.",
	"WG_MTU_FAILED":              "WireGuard could not determine or update the tunnel MTU.",
	"WG_INVALID_PACKET":          "WireGuard received an invalid handshake packet.",
	"WG_STATUS_FAILED":           "WireGuard runtime counters could not be read.",
	"WG_DOWN_FAILED":             "WireGuard could not stop its interface cleanly.",
	"GATEWAY_TCP_LIMIT":          "The gateway reached its total or per-peer TCP connection limit.",
	"GATEWAY_TCP_DIAL_FAILED":    "The userspace gateway could not open an outbound TCP connection.",
	"GATEWAY_UDP_DIAL_FAILED":    "The userspace gateway could not open an outbound UDP socket.",
	"GATEWAY_ENDPOINT_FAILED":    "The userspace network stack could not create a forwarding endpoint.",
	"GATEWAY_UDP_LIMIT":          "The userspace gateway reached its concurrent UDP session limit.",
	"GATEWAY_DNS_FAILED":         "No configured DNS upstream returned a usable response.",
	"ALP_LISTENER_ACCEPT_FAILED": "An encrypted transport listener stopped accepting connections unexpectedly.",
	"ALP_SESSION_LIMIT":          "The Atticus Link concurrent connection limit was reached.",
	"ALP_AUTH_TIMEOUT":           "The peer did not complete its initial hello within the authentication timeout.",
	"ALP_AUTH_REJECTED":          "The configured peer ID and carrier token did not authorize this connection.",
	"ALP_INVALID_HELLO":          "The initial Atticus Link hello was missing, malformed or not supported.",
	"ALP_INVALID_FRAME":          "An encrypted connection delivered an invalid Atticus Link frame.",
	"ALP_BACKEND_CONNECT_FAILED": "The loopback WireGuard backend could not be opened.",
	"ALP_BACKEND_IO_FAILED":      "The loopback WireGuard backend could not read or write a datagram.",
	"ALP_TRANSPORT_READ_FAILED":  "The encrypted transport closed or failed while receiving; the exact remote cause is unknown.",
	"ALP_TRANSPORT_WRITE_FAILED": "The encrypted transport failed while sending an Atticus Link frame.",
	"ALP_IDLE_TIMEOUT":           "No Atticus Link frame arrived before the session idle timeout.",
	"GATEWAY_FORWARD_IO_FAILED":  "A forwarded TCP or UDP connection failed while reading or writing; destination and payload are not retained.",
}

// Book has at most one row for each fixed code. It is safe to use as a zero value.
type Book struct {
	mu   sync.Mutex
	rows map[string]Error
}

func (b *Book) Record(code string) {
	reason, ok := reasons[code]
	if !ok {
		code = "NATIVE_RUNTIME_ERROR"
		reason = reasons[code]
	}
	now := time.Now().Unix()
	b.mu.Lock()
	defer b.mu.Unlock()
	if b.rows == nil {
		b.rows = make(map[string]Error)
	}
	row := b.rows[code]
	if row.Count == 0 {
		row = Error{Code: code, Reason: reason, FirstUnix: now}
	}
	if row.Count < uint64(1<<63-1) {
		row.Count++
	}
	if now > row.LastUnix {
		row.LastUnix = now
	}
	b.rows[code] = row
}

func (b *Book) Snapshot() []Error {
	b.mu.Lock()
	defer b.mu.Unlock()
	rows := make([]Error, 0, len(b.rows))
	for _, row := range b.rows {
		rows = append(rows, row)
	}
	sort.Slice(rows, func(i, j int) bool { return rows[i].Code < rows[j].Code })
	return rows
}
