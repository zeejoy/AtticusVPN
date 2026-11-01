// Copyright (c) 2026 TianzeL
// SPDX-License-Identifier: Apache-2.0

package atticusengine

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"net"
	"net/netip"
	"sync/atomic"
	"testing"
	"time"

	"golang.org/x/crypto/curve25519"
	"golang.zx2c4.com/wireguard/conn"
	"golang.zx2c4.com/wireguard/device"
	wgnetstack "golang.zx2c4.com/wireguard/tun/netstack"
)

// TestWireGuardClientToUserspaceGateway 使用第二个官方 wireguard-go Device 充当标准客户端。
// 测试不会访问公网：TCP echo、UDP echo 与 DNS upstream 都监听在当前测试主机上，
// 但数据必须先经过 localhost UDP 上的 WireGuard 握手，再穿过服务端 gVisor 转发器。
func TestWireGuardClientToUserspaceGateway(t *testing.T) {
	Stop()
	t.Cleanup(Stop)

	hostIP := testHostIPv4(t)
	tcpEcho := startTCPEcho(t, hostIP)
	udpEcho := startUDPEcho(t, hostIP, false)
	dnsUpstream := startDNSEcho(t, hostIP)

	serverPrivate, serverPublic := testWireGuardKeyPair(t)
	clientPrivate, clientPublic := testWireGuardKeyPair(t)
	listenPort := reserveUDPPort(t)

	serverConfig := fmt.Sprintf(
		"private_key=%s\nlisten_port=%d\nreplace_peers=true\n"+
			"public_key=%s\nreplace_allowed_ips=true\nallowed_ip=10.77.0.2/32\n",
		serverPrivate, listenPort, clientPublic,
	)
	upstream := dnsUpstream.address()
	if err := Start(serverConfig, upstream, defaultMTU); err != nil {
		t.Fatalf("Start server engine: %v", err)
	}

	clientTun, clientNet, err := wgnetstack.CreateNetTUN(
		[]netip.Addr{netip.MustParseAddr("10.77.0.2")}, nil, defaultMTU,
	)
	if err != nil {
		t.Fatalf("CreateNetTUN: %v", err)
	}
	clientDevice := device.NewDevice(clientTun, conn.NewStdNetBind(), quietWireGuardLogger())
	t.Cleanup(clientDevice.Close)
	clientConfig := fmt.Sprintf(
		"private_key=%s\nlisten_port=0\nreplace_peers=true\n"+
			"public_key=%s\nendpoint=127.0.0.1:%d\nreplace_allowed_ips=true\n"+
			"allowed_ip=0.0.0.0/0\npersistent_keepalive_interval=1\n",
		clientPrivate, serverPublic, listenPort,
	)
	if err := clientDevice.IpcSet(clientConfig); err != nil {
		t.Fatalf("configure standard client: %v", err)
	}
	if err := clientDevice.Up(); err != nil {
		t.Fatalf("start standard client: %v", err)
	}

	ctx, cancel := context.WithTimeout(context.Background(), 12*time.Second)
	defer cancel()
	tcpTarget := netip.AddrPortFrom(hostIP, uint16(tcpEcho.Addr().(*net.TCPAddr).Port))
	tcpConn, err := clientNet.DialContextTCPAddrPort(ctx, tcpTarget)
	if err != nil {
		t.Fatalf("TCP through WireGuard/gVisor: %v", err)
	}
	assertStreamEcho(t, tcpConn, []byte("atticus-tcp-echo"))
	_ = tcpConn.Close()

	udpTarget := netip.AddrPortFrom(hostIP, uint16(udpEcho.LocalAddr().(*net.UDPAddr).Port))
	udpConn, err := clientNet.DialUDPAddrPort(netip.AddrPort{}, udpTarget)
	if err != nil {
		t.Fatalf("UDP through WireGuard/gVisor: %v", err)
	}
	assertDatagramEcho(t, udpConn, []byte("atticus-udp-echo"))
	_ = udpConn.Close()

	dnsConn, err := clientNet.DialUDPAddrPort(
		netip.AddrPort{}, netip.MustParseAddrPort("10.77.0.1:53"),
	)
	if err != nil {
		t.Fatalf("DNS through WireGuard/gVisor: %v", err)
	}
	query := testDNSQuery()
	if err := dnsConn.SetDeadline(time.Now().Add(5 * time.Second)); err != nil {
		t.Fatal(err)
	}
	if _, err := dnsConn.Write(query); err != nil {
		t.Fatalf("write DNS query: %v", err)
	}
	answer := make([]byte, len(query)+32)
	n, err := dnsConn.Read(answer)
	if err != nil {
		t.Fatalf("read DNS response: %v", err)
	}
	_ = dnsConn.Close()
	if n != len(query) || answer[0] != query[0] || answer[1] != query[1] ||
		answer[2]&0x80 == 0 || answer[2]&0x02 != 0 {
		t.Fatalf("invalid relayed DNS response: length=%d flags=%02x", n, answer[2])
	}

	// TCP 客户端必须经过 10.77.0.1:53 代理，并由代理使用 upstream TCP，而非旁路 UDP。
	dnsTCP, err := clientNet.DialContextTCPAddrPort(
		ctx, netip.MustParseAddrPort("10.77.0.1:53"),
	)
	if err != nil {
		t.Fatalf("DNS TCP through WireGuard/gVisor: %v", err)
	}
	tcpQuery := testDNSQuery()
	tcpQuery[0], tcpQuery[1] = 0x56, 0x78
	if err := writeDNSFrame(dnsTCP, tcpQuery); err != nil {
		t.Fatalf("write DNS TCP query: %v", err)
	}
	tcpAnswer, err := readDNSFrame(dnsTCP)
	_ = dnsTCP.Close()
	if err != nil || !validDNSResponse(tcpQuery, tcpAnswer) {
		t.Fatalf("invalid DNS TCP response: length=%d err=%v", len(tcpAnswer), err)
	}

	var status engineStatus
	if err := json.Unmarshal([]byte(Status()), &status); err != nil {
		t.Fatalf("decode status: %v", err)
	}
	if len(status.Peers) != 1 || status.Peers[0].LatestHandshakeUnix == 0 {
		t.Fatalf("standard WireGuard handshake not observed: %+v", status.Peers)
	}
	if status.TCPConnections < 2 || status.UDPSessions < 2 || status.DNSQueries < 2 {
		t.Fatalf("expected TCP, UDP and DNS counters, got %+v", status)
	}
	if dnsUpstream.udpQueries.Load() == 0 || dnsUpstream.tcpQueries.Load() == 0 {
		t.Fatalf("expected both DNS upstream transports: udp=%d tcp=%d",
			dnsUpstream.udpQueries.Load(), dnsUpstream.tcpQueries.Load())
	}
}

// TestWireGuardIPv6LoopbackHandshake 固定外层 Endpoint 为 [::1]，证明服务端标准
// WireGuard IPv6 UDP listener 能完成握手；若主机明确禁用了 IPv6 loopback 才跳过。
func TestWireGuardIPv6LoopbackHandshake(t *testing.T) {
	Stop()
	t.Cleanup(Stop)

	listenPort := reserveUDP6Port(t)
	serverPrivate, serverPublic := testWireGuardKeyPair(t)
	clientPrivate, clientPublic := testWireGuardKeyPair(t)
	serverConfig := fmt.Sprintf(
		"private_key=%s\nlisten_port=%d\nreplace_peers=true\n"+
			"public_key=%s\nreplace_allowed_ips=true\nallowed_ip=10.77.0.2/32\n",
		serverPrivate, listenPort, clientPublic,
	)
	if err := Start(serverConfig, dnsDisabledSentinel, defaultMTU); err != nil {
		t.Fatalf("Start IPv6-listener server: %v", err)
	}

	clientTun, _, err := wgnetstack.CreateNetTUN(
		[]netip.Addr{netip.MustParseAddr("10.77.0.2")}, nil, defaultMTU,
	)
	if err != nil {
		t.Fatalf("CreateNetTUN: %v", err)
	}
	clientDevice := device.NewDevice(clientTun, conn.NewStdNetBind(), quietWireGuardLogger())
	t.Cleanup(clientDevice.Close)
	clientConfig := fmt.Sprintf(
		"private_key=%s\nlisten_port=0\nreplace_peers=true\n"+
			"public_key=%s\nendpoint=[::1]:%d\nreplace_allowed_ips=true\n"+
			"allowed_ip=0.0.0.0/0\npersistent_keepalive_interval=1\n",
		clientPrivate, serverPublic, listenPort,
	)
	if err := clientDevice.IpcSet(clientConfig); err != nil {
		t.Fatalf("configure IPv6 standard client: %v", err)
	}
	if err := clientDevice.Up(); err != nil {
		t.Fatalf("start IPv6 standard client: %v", err)
	}

	deadline := time.Now().Add(8 * time.Second)
	for time.Now().Before(deadline) {
		var status engineStatus
		if err := json.Unmarshal([]byte(Status()), &status); err != nil {
			t.Fatalf("decode IPv6 handshake status: %v", err)
		}
		if len(status.Peers) == 1 && status.Peers[0].LatestHandshakeUnix != 0 {
			return
		}
		time.Sleep(50 * time.Millisecond)
	}
	t.Fatal("no standard WireGuard handshake observed through [::1] UDP endpoint")
}

func quietWireGuardLogger() *device.Logger {
	return &device.Logger{
		Verbosef: func(string, ...any) {},
		Errorf:   func(string, ...any) {},
	}
}

func testWireGuardKeyPair(t *testing.T) (string, string) {
	t.Helper()
	private := make([]byte, curve25519.ScalarSize)
	if _, err := rand.Read(private); err != nil {
		t.Fatal(err)
	}
	private[0] &= 248
	private[31] = (private[31] & 127) | 64
	public, err := curve25519.X25519(private, curve25519.Basepoint)
	if err != nil {
		t.Fatal(err)
	}
	return hex.EncodeToString(private), hex.EncodeToString(public)
}

func reserveUDPPort(t *testing.T) int {
	t.Helper()
	listener, err := net.ListenUDP("udp4", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1)})
	if err != nil {
		t.Fatal(err)
	}
	port := listener.LocalAddr().(*net.UDPAddr).Port
	if err := listener.Close(); err != nil {
		t.Fatal(err)
	}
	return port
}

func reserveUDP6Port(t *testing.T) int {
	t.Helper()
	listener, err := net.ListenUDP("udp6", &net.UDPAddr{IP: net.IPv6loopback})
	if err != nil {
		t.Skipf("IPv6 loopback UDP unavailable: %v", err)
	}
	port := listener.LocalAddr().(*net.UDPAddr).Port
	if err := listener.Close(); err != nil {
		t.Fatal(err)
	}
	return port
}

func testHostIPv4(t *testing.T) netip.Addr {
	t.Helper()
	addresses, err := net.InterfaceAddrs()
	if err != nil {
		t.Fatal(err)
	}
	for _, raw := range addresses {
		ip, _, err := net.ParseCIDR(raw.String())
		if err != nil || ip.To4() == nil || ip.IsLoopback() || ip.IsLinkLocalUnicast() {
			continue
		}
		v4 := ip.To4()
		if v4[0] == 10 && v4[1] == 77 && v4[2] == 0 {
			continue
		}
		return netip.AddrFrom4([4]byte(v4))
	}
	t.Skip("host has no usable non-loopback IPv4 address for local relay test")
	return netip.Addr{}
}

func startTCPEcho(t *testing.T, ip netip.Addr) *net.TCPListener {
	t.Helper()
	listener, err := net.ListenTCP("tcp4", &net.TCPAddr{IP: net.IP(ip.AsSlice())})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = listener.Close() })
	go func() {
		connection, err := listener.AcceptTCP()
		if err != nil {
			return
		}
		defer connection.Close()
		_, _ = io.Copy(connection, connection)
	}()
	return listener
}

func startUDPEcho(t *testing.T, ip netip.Addr, dnsResponse bool) *net.UDPConn {
	t.Helper()
	listener, err := net.ListenUDP("udp4", &net.UDPAddr{IP: net.IP(ip.AsSlice())})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = listener.Close() })
	go func() {
		buffer := make([]byte, 2048)
		n, remote, err := listener.ReadFromUDP(buffer)
		if err != nil {
			return
		}
		response := append([]byte(nil), buffer[:n]...)
		if dnsResponse && len(response) >= 4 {
			response[2] |= 0x80
			response[3] |= 0x80
		}
		_, _ = listener.WriteToUDP(response, remote)
	}()
	return listener
}

type dnsEchoServer struct {
	tcp        *net.TCPListener
	udp        *net.UDPConn
	tcpQueries atomic.Uint64
	udpQueries atomic.Uint64
}

func (server *dnsEchoServer) address() string {
	return server.tcp.Addr().String()
}

// startDNSEcho 在同一 IPv4 地址与端口监听 UDP/TCP，用于证明 DNS 代理的两种上游路径。
func startDNSEcho(t *testing.T, ip netip.Addr) *dnsEchoServer {
	t.Helper()
	tcpListener, err := net.ListenTCP("tcp4", &net.TCPAddr{IP: net.IP(ip.AsSlice())})
	if err != nil {
		t.Fatal(err)
	}
	port := tcpListener.Addr().(*net.TCPAddr).Port
	udpListener, err := net.ListenUDP("udp4", &net.UDPAddr{IP: net.IP(ip.AsSlice()), Port: port})
	if err != nil {
		_ = tcpListener.Close()
		t.Fatal(err)
	}
	server := &dnsEchoServer{tcp: tcpListener, udp: udpListener}
	t.Cleanup(func() {
		_ = server.tcp.Close()
		_ = server.udp.Close()
	})
	go func() {
		buffer := make([]byte, maxDNSPacket)
		for {
			n, remote, err := server.udp.ReadFromUDP(buffer)
			if err != nil {
				return
			}
			server.udpQueries.Add(1)
			// UDP 故意返回 TC=1，验证代理会用同一 upstream 的 TCP 取得完整响应。
			response := testDNSResponse(buffer[:n])
			response[2] |= 0x02
			_, _ = server.udp.WriteToUDP(response, remote)
		}
	}()
	go func() {
		for {
			connection, err := server.tcp.AcceptTCP()
			if err != nil {
				return
			}
			go func() {
				defer connection.Close()
				for {
					query, err := readDNSFrame(connection)
					if err != nil {
						return
					}
					server.tcpQueries.Add(1)
					if err := writeDNSFrame(connection, testDNSResponse(query)); err != nil {
						return
					}
				}
			}()
		}
	}()
	return server
}

func testDNSResponse(query []byte) []byte {
	response := append([]byte(nil), query...)
	if len(response) >= 4 {
		response[2] |= 0x80
		response[3] |= 0x80
	}
	return response
}

func assertStreamEcho(t *testing.T, connection net.Conn, payload []byte) {
	t.Helper()
	if err := connection.SetDeadline(time.Now().Add(5 * time.Second)); err != nil {
		t.Fatal(err)
	}
	if _, err := connection.Write(payload); err != nil {
		t.Fatalf("write TCP echo: %v", err)
	}
	response := make([]byte, len(payload))
	if _, err := io.ReadFull(connection, response); err != nil {
		t.Fatalf("read TCP echo: %v", err)
	}
	if string(response) != string(payload) {
		t.Fatalf("TCP echo mismatch: %q", response)
	}
}

func assertDatagramEcho(t *testing.T, connection net.Conn, payload []byte) {
	t.Helper()
	if err := connection.SetDeadline(time.Now().Add(5 * time.Second)); err != nil {
		t.Fatal(err)
	}
	if _, err := connection.Write(payload); err != nil {
		t.Fatalf("write UDP echo: %v", err)
	}
	response := make([]byte, len(payload)+32)
	n, err := connection.Read(response)
	if err != nil {
		t.Fatalf("read UDP echo: %v", err)
	}
	if string(response[:n]) != string(payload) {
		t.Fatalf("UDP echo mismatch: %q", response[:n])
	}
}

func testDNSQuery() []byte {
	// ID=0x1234，RD=1，QDCOUNT=1，问题为 atticus.test. A/IN。
	return []byte{
		0x12, 0x34, 0x01, 0x00, 0x00, 0x01, 0x00, 0x00,
		0x00, 0x00, 0x00, 0x00, 0x07, 'a', 't', 't', 'i', 'c', 'u', 's',
		0x04, 't', 'e', 's', 't', 0x00, 0x00, 0x01, 0x00, 0x01,
	}
}
