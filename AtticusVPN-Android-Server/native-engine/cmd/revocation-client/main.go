// Copyright (c) 2026 TianzeL
// SPDX-License-Identifier: Apache-2.0

// A process-local standard WireGuard client for the opt-in device revocation test.
// Never configures an OS adapter, prints keys, or records the private configuration.
package main

import (
	"encoding/base64"
	"encoding/binary"
	"encoding/hex"
	"encoding/json"
	"flag"
	"fmt"
	"io"
	"net"
	"net/netip"
	"os"
	"strconv"
	"strings"
	"sync"
	"time"

	"golang.zx2c4.com/wireguard/conn"
	"golang.zx2c4.com/wireguard/device"
	"golang.zx2c4.com/wireguard/tun/netstack"
)

func main() {
	configPath := flag.String("config", "", "private test configuration file")
	expected := flag.String("expect", "allow", "allow or deny")
	relay := flag.String("relay", "", "optional test-only ADB TCP datagram relay")
	duration := flag.Duration("duration", 18*time.Second, "observation interval")
	flag.Parse()
	if *expected != "allow" && *expected != "deny" {
		fatal()
	}
	raw, err := os.ReadFile(*configPath)
	if err != nil {
		fatal()
	}
	values := make(map[string]string)
	for _, line := range strings.Split(string(raw), "\n") {
		key, value, ok := strings.Cut(line, "=")
		if ok {
			values[strings.TrimSpace(key)] = strings.TrimSpace(value)
		}
	}
	for i := range raw {
		raw[i] = 0
	}
	keyHex := func(name string) string {
		decoded, err := base64.StdEncoding.DecodeString(values[name])
		if err != nil || len(decoded) != 32 {
			fatal()
		}
		result := hex.EncodeToString(decoded)
		for i := range decoded {
			decoded[i] = 0
		}
		return result
	}
	address, err := netip.ParsePrefix(strings.TrimSpace(strings.Split(values["Address"], ",")[0]))
	if err != nil || !address.Addr().Is4() {
		fatal()
	}
	tun, network, err := netstack.CreateNetTUN([]netip.Addr{address.Addr()}, nil, 1380)
	if err != nil {
		fatal()
	}
	logger := &device.Logger{Verbosef: func(string, ...any) {}, Errorf: func(string, ...any) {}}
	endpoint := values["Endpoint"]
	if *relay != "" {
		local, cleanup, err := datagramRelay(*relay)
		if err != nil {
			fatal()
		}
		defer cleanup()
		endpoint = local
	}
	wg := device.NewDevice(tun, conn.NewStdNetBind(), logger)
	defer wg.Close()
	uapi := fmt.Sprintf("private_key=%s\nlisten_port=0\nreplace_peers=true\npublic_key=%s\npreshared_key=%s\nendpoint=%s\nreplace_allowed_ips=true\nallowed_ip=0.0.0.0/0\npersistent_keepalive_interval=1\n",
		keyHex("PrivateKey"), keyHex("PublicKey"), keyHex("PresharedKey"), endpoint)
	if wg.IpcSet(uapi) != nil || wg.Up() != nil {
		fatal()
	}
	udp, err := network.DialUDPAddrPort(netip.AddrPort{}, netip.MustParseAddrPort("10.77.0.1:53"))
	if err != nil {
		fatal()
	}
	defer udp.Close()
	// Fixed, non-personal example.com DNS query through the VPN.
	query := []byte{0x41, 0x74, 1, 0, 0, 1, 0, 0, 0, 0, 0, 0, 7, 'e', 'x', 'a', 'm', 'p', 'l', 'e', 3, 'c', 'o', 'm', 0, 0, 1, 0, 1}
	deadline := time.Now().Add(*duration)
	dnsResponse := false
	for time.Now().Before(deadline) {
		_ = udp.SetDeadline(time.Now().Add(2 * time.Second))
		_, _ = udp.Write(query)
		answer := make([]byte, 4096)
		n, err := udp.Read(answer)
		if err == nil && n >= 12 && answer[0] == query[0] && answer[1] == query[1] && answer[2]&0x80 != 0 {
			dnsResponse = true
			if *expected == "allow" {
				break
			}
		}
	}
	handshake := false
	status, err := wg.IpcGet()
	if err != nil {
		fatal()
	}
	for _, line := range strings.Split(status, "\n") {
		if strings.HasPrefix(line, "last_handshake_time_sec=") {
			value, _ := strconv.ParseInt(strings.TrimPrefix(line, "last_handshake_time_sec="), 10, 64)
			handshake = handshake || value > 0
		}
	}
	passed := handshake && dnsResponse
	if *expected == "deny" {
		passed = !handshake && !dnsResponse
	}
	_ = json.NewEncoder(os.Stdout).Encode(map[string]any{
		"handshake": handshake, "dns_response": dnsResponse, "expected": *expected, "pass": passed})
	if !passed {
		os.Exit(1)
	}
}

func fatal() { fmt.Println(`{"pass":false,"error":"CLIENT_SETUP_FAILED"}`); os.Exit(2) }

func datagramRelay(target string) (string, func(), error) {
	stream, err := net.DialTimeout("tcp", target, 5*time.Second)
	if err != nil {
		return "", nil, err
	}
	udp, err := net.ListenUDP("udp4", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1)})
	if err != nil {
		stream.Close()
		return "", nil, err
	}
	var mu sync.Mutex
	var client *net.UDPAddr
	cleanup := func() { stream.Close(); udp.Close() }
	go func() {
		defer cleanup()
		buf := make([]byte, 65535)
		for {
			n, address, err := udp.ReadFromUDP(buf)
			if err != nil {
				return
			}
			mu.Lock()
			client = address
			mu.Unlock()
			frame := make([]byte, n+2)
			binary.BigEndian.PutUint16(frame, uint16(n))
			copy(frame[2:], buf[:n])
			for len(frame) > 0 {
				n, err := stream.Write(frame)
				if err != nil || n == 0 {
					return
				}
				frame = frame[n:]
			}
		}
	}()
	go func() {
		defer cleanup()
		for {
			var length uint16
			if binary.Read(stream, binary.BigEndian, &length) != nil || length == 0 {
				return
			}
			buf := make([]byte, int(length))
			if _, err := io.ReadFull(stream, buf); err != nil {
				return
			}
			mu.Lock()
			address := client
			mu.Unlock()
			if address != nil {
				if _, err := udp.WriteToUDP(buf, address); err != nil {
					return
				}
			}
		}
	}()
	return udp.LocalAddr().String(), cleanup, nil
}
