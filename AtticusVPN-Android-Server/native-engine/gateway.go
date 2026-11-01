// Copyright (c) 2026 TianzeL
// SPDX-License-Identifier: Apache-2.0

package atticusengine

import (
	"context"
	"encoding/binary"
	"errors"
	"fmt"
	"io"
	"net"
	"net/netip"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"syscall"
	"time"

	"gvisor.dev/gvisor/pkg/tcpip"
	"gvisor.dev/gvisor/pkg/tcpip/adapters/gonet"
	"gvisor.dev/gvisor/pkg/tcpip/header"
	"gvisor.dev/gvisor/pkg/tcpip/link/channel"
	"gvisor.dev/gvisor/pkg/tcpip/network/ipv4"
	"gvisor.dev/gvisor/pkg/tcpip/network/ipv6"
	"gvisor.dev/gvisor/pkg/tcpip/stack"
	"gvisor.dev/gvisor/pkg/tcpip/transport/icmp"
	"gvisor.dev/gvisor/pkg/tcpip/transport/tcp"
	"gvisor.dev/gvisor/pkg/tcpip/transport/udp"
	"gvisor.dev/gvisor/pkg/waiter"
)

const (
	gatewayNIC          tcpip.NICID = 1
	defaultMTU                      = 1380
	serverIPv4                      = "10.77.0.1"
	serverIPv6                      = "fd77:6174:7469:6375::1"
	udpIdleTimeout                  = 2 * time.Minute
	tcpDialTimeout                  = 12 * time.Second
	maxUDPSessions                  = 256
	maxDNSUpstreams                 = 8
	dnsDisabledSentinel             = "!disabled"
)

type gatewayStats struct {
	tcpConnections atomic.Uint64
	udpSessions    atomic.Uint64
	dnsQueries     atomic.Uint64
	dnsCacheHits   atomic.Uint64
	toInternet     atomic.Uint64
	toPeer         atomic.Uint64
}

// userGateway 把 WireGuard 内的 TCP/UDP 会话转换成普通 Android socket。
//
// gVisor 在进程内完成 TCP 状态机、重传、分片与 UDP endpoint 管理；外层只做
// socket relay。因此不依赖 iptables/nftables，也不需要 Root。互联网看到的源地址
// 是 Android 当前网络 socket 的地址，而不是 10.77.0.x，这等价于用户态 NAT44。
type userGateway struct {
	stack      *stack.Stack
	link       *channel.Endpoint
	cache      *dnsCache
	upstreams  []string
	dnsEnabled bool
	udpSlots   chan struct{}
	ctx        context.Context
	cancel     context.CancelFunc
	wg         sync.WaitGroup
	stats      gatewayStats
}

var selectedNetwork atomic.Uint64

func newBoundDialer(timeout time.Duration) *net.Dialer {
	return &net.Dialer{
		Timeout: timeout,
		Control: func(network, address string, raw syscall.RawConn) error {
			return controlNetwork(network, address, raw)
		},
	}
}

func newUserGateway(mtu int, upstreamCSV string) (*userGateway, error) {
	if mtu < 1280 || mtu > 9000 {
		return nil, fmt.Errorf("invalid mtu")
	}
	upstreams, dnsEnabled, err := parseUpstreams(upstreamCSV)
	if err != nil {
		return nil, err
	}
	ctx, cancel := context.WithCancel(context.Background())
	link := channel.New(1024, uint32(mtu), "")
	s := stack.New(stack.Options{
		NetworkProtocols: []stack.NetworkProtocolFactory{
			ipv4.NewProtocol,
			ipv6.NewProtocol,
		},
		TransportProtocols: []stack.TransportProtocolFactory{
			tcp.NewProtocol,
			udp.NewProtocol,
			icmp.NewProtocol4,
			icmp.NewProtocol6,
		},
	})
	if err := s.CreateNIC(gatewayNIC, link); err != nil {
		cancel()
		return nil, errors.New(err.String())
	}
	// Promiscuous 允许栈接收目的地址为任意互联网 IPv4 的包；Spoofing 允许响应包
	// 保留远端网站地址作为源地址。两者只作用于该进程内的虚拟 NIC，不修改系统网络。
	if err := s.SetPromiscuousMode(gatewayNIC, true); err != nil {
		cancel()
		return nil, errors.New(err.String())
	}
	if err := s.SetSpoofing(gatewayNIC, true); err != nil {
		cancel()
		return nil, errors.New(err.String())
	}
	s.SetRouteTable([]tcpip.Route{
		{Destination: header.IPv4EmptySubnet, NIC: gatewayNIC},
		{Destination: header.IPv6EmptySubnet, NIC: gatewayNIC},
	})
	if err := addGatewayAddress(s, serverIPv4); err != nil {
		cancel()
		return nil, err
	}
	if err := addGatewayAddress(s, serverIPv6); err != nil {
		cancel()
		return nil, err
	}
	g := &userGateway{
		stack:      s,
		link:       link,
		cache:      newDNSCache(256),
		upstreams:  upstreams,
		dnsEnabled: dnsEnabled,
		udpSlots:   make(chan struct{}, maxUDPSessions),
		ctx:        ctx,
		cancel:     cancel,
	}
	tcpForwarder := tcp.NewForwarder(s, 64*1024, 512, g.handleTCP)
	udpForwarder := udp.NewForwarder(s, g.handleUDP)
	s.SetTransportProtocolHandler(tcp.ProtocolNumber, tcpForwarder.HandlePacket)
	s.SetTransportProtocolHandler(udp.ProtocolNumber, udpForwarder.HandlePacket)
	return g, nil
}

func addGatewayAddress(s *stack.Stack, raw string) error {
	ip := net.ParseIP(raw)
	if ip == nil {
		return fmt.Errorf("invalid gateway address")
	}
	var protocol tcpip.NetworkProtocolNumber
	var addr tcpip.Address
	if v4 := ip.To4(); v4 != nil {
		protocol = ipv4.ProtocolNumber
		addr = tcpip.AddrFrom4([4]byte(v4))
	} else {
		protocol = ipv6.ProtocolNumber
		var bytes [16]byte
		copy(bytes[:], ip.To16())
		addr = tcpip.AddrFrom16(bytes)
	}
	if err := s.AddProtocolAddress(gatewayNIC, tcpip.ProtocolAddress{
		Protocol:          protocol,
		AddressWithPrefix: addr.WithPrefix(),
	}, stack.AddressProperties{}); err != nil {
		return errors.New(err.String())
	}
	return nil
}

func parseUpstreams(csv string) ([]string, bool, error) {
	if strings.TrimSpace(csv) == dnsDisabledSentinel {
		return nil, false, nil
	}
	var result []string
	for _, item := range strings.Split(csv, ",") {
		item = strings.TrimSpace(item)
		if item == "" {
			continue
		}
		if item == dnsDisabledSentinel || len(result) >= maxDNSUpstreams {
			return nil, false, errors.New("invalid dns upstream")
		}
		normalized, err := normalizeDNSUpstream(item)
		if err != nil {
			return nil, false, errors.New("invalid dns upstream")
		}
		result = append(result, normalized)
	}
	if len(result) == 0 {
		// Java 层通常传入 LinkProperties 的 DNS；空列表时使用稳定的公共回退，
		// 同时在 UI 标明这是回退而不是当前运营商的 Automatic DNS。
		return []string{"1.1.1.1:53", "8.8.8.8:53"}, true, nil
	}
	return result, true, nil
}

func normalizeDNSUpstream(item string) (string, error) {
	host, port, err := net.SplitHostPort(item)
	if err != nil {
		unwrapped := strings.TrimSuffix(strings.TrimPrefix(item, "["), "]")
		if address, parseErr := netip.ParseAddr(unwrapped); parseErr == nil {
			// netip 保留 fe80::/10 自动 DNS 必需的接口 zone，例如 fe80::1%7。
			host, port = address.String(), "53"
		} else if !strings.Contains(item, ":") {
			host, port = item, "53"
		} else {
			return "", err
		}
	}
	parsedPort, err := strconv.Atoi(port)
	if err != nil || parsedPort < 1 || parsedPort > 65535 || strings.TrimSpace(host) == "" {
		return "", errors.New("invalid dns upstream")
	}
	return net.JoinHostPort(host, strconv.Itoa(parsedPort)), nil
}

func destination(id stack.TransportEndpointID) string {
	return net.JoinHostPort(net.IP(id.LocalAddress.AsSlice()).String(), strconv.Itoa(int(id.LocalPort)))
}

func blockedDestination(id stack.TransportEndpointID) bool {
	ip := net.IP(id.LocalAddress.AsSlice())
	if ip == nil {
		return true
	}
	// 禁止 Peer 通过转发器回连本机 loopback/link-local/multicast 及 WireGuard 管理网段。
	// 启用 DNS 时，10.77.0.1:53 会在 TCP/UDP handler 中先行处理，不经过此分支。
	if ip.IsLoopback() || ip.IsLinkLocalUnicast() || ip.IsMulticast() || ip.IsUnspecified() {
		return true
	}
	if v4 := ip.To4(); v4 != nil && v4[0] == 10 && v4[1] == 77 && v4[2] == 0 {
		return true
	}
	return false
}

func isGatewayDNS(id stack.TransportEndpointID) bool {
	return id.LocalPort == 53 && net.IP(id.LocalAddress.AsSlice()).Equal(net.ParseIP(serverIPv4))
}

func (g *userGateway) handleTCP(request *tcp.ForwarderRequest) {
	id := request.ID()
	isDNS := g.dnsEnabled && isGatewayDNS(id)
	if !isDNS && blockedDestination(id) {
		request.Complete(true)
		return
	}
	var outbound net.Conn
	if !isDNS {
		var err error
		outbound, err = newBoundDialer(tcpDialTimeout).DialContext(g.ctx, "tcp", destination(id))
		if err != nil {
			request.Complete(true)
			return
		}
	}
	var queue waiter.Queue
	endpoint, tcpErr := request.CreateEndpoint(&queue)
	if tcpErr != nil {
		request.Complete(true)
		if outbound != nil {
			_ = outbound.Close()
		}
		return
	}
	request.Complete(false)
	inbound := gonet.NewTCPConn(&queue, endpoint)
	g.stats.tcpConnections.Add(1)
	g.wg.Add(1)
	go func() {
		defer g.wg.Done()
		defer inbound.Close()
		if isDNS {
			g.serveDNSTCP(inbound)
			return
		}
		defer outbound.Close()
		done := make(chan struct{}, 2)
		go g.copyAndCount(outbound, inbound, &g.stats.toInternet, done)
		go g.copyAndCount(inbound, outbound, &g.stats.toPeer, done)
		select {
		case <-g.ctx.Done():
		case <-done:
		}
	}()
}

func (g *userGateway) handleUDP(request *udp.ForwarderRequest) {
	id := request.ID()
	isDNS := g.dnsEnabled && isGatewayDNS(id)
	if !isDNS && blockedDestination(id) {
		return
	}
	if !g.acquireUDPSession() {
		return
	}
	var queue waiter.Queue
	endpoint, udpErr := request.CreateEndpoint(&queue)
	if udpErr != nil {
		g.releaseUDPSession()
		return
	}
	inbound := gonet.NewUDPConn(&queue, endpoint)
	g.stats.udpSessions.Add(1)
	g.wg.Add(1)
	go func() {
		defer g.wg.Done()
		defer g.releaseUDPSession()
		defer inbound.Close()
		if isDNS {
			g.serveDNS(inbound)
			return
		}
		outbound, err := newBoundDialer(tcpDialTimeout).DialContext(g.ctx, "udp", destination(id))
		if err != nil {
			return
		}
		defer outbound.Close()
		refreshDeadline := func() {
			deadline := time.Now().Add(udpIdleTimeout)
			_ = inbound.SetDeadline(deadline)
			_ = outbound.SetDeadline(deadline)
		}
		refreshDeadline()
		done := make(chan struct{}, 2)
		go g.copyDatagrams(outbound, inbound, &g.stats.toInternet, refreshDeadline, done)
		go g.copyDatagrams(inbound, outbound, &g.stats.toPeer, refreshDeadline, done)
		select {
		case <-g.ctx.Done():
		case <-done:
		}
	}()
}

func (g *userGateway) acquireUDPSession() bool {
	select {
	case g.udpSlots <- struct{}{}:
		return true
	default:
		return false
	}
}

func (g *userGateway) releaseUDPSession() {
	<-g.udpSlots
}

// copyDatagrams 每次 Read 对应一次 Write，使用 65535 字节缓冲区保持 UDP 报文边界。
// io.Copy 的默认 32 KiB 缓冲会把较大的 UDP datagram 截断，因此不能用于此处。
func (g *userGateway) copyDatagrams(
	dst net.Conn,
	src net.Conn,
	counter *atomic.Uint64,
	refreshDeadline func(),
	done chan<- struct{},
) {
	buf := make([]byte, 65535)
	for {
		n, err := src.Read(buf)
		if err != nil {
			break
		}
		// 任一方向有活动都会延长会话两端的 deadline；单向长流不会在固定两分钟被截断。
		refreshDeadline()
		written, err := dst.Write(buf[:n])
		if written > 0 {
			counter.Add(uint64(written))
		}
		if err != nil || written != n {
			break
		}
		refreshDeadline()
	}
	done <- struct{}{}
}

func (g *userGateway) copyAndCount(dst io.Writer, src io.Reader, counter *atomic.Uint64, done chan<- struct{}) {
	n, _ := io.Copy(dst, src)
	if n > 0 {
		counter.Add(uint64(n))
	}
	done <- struct{}{}
}

// serveDNS 在 10.77.0.1:53 上逐个处理请求。Automatic upstream 由 Java 层从
// LinkProperties 传入；Custom upstream 则来自加密设置。查询名不写入日志或磁盘。
func (g *userGateway) serveDNS(client net.Conn) {
	buffer := make([]byte, maxDNSPacket)
	for {
		_ = client.SetReadDeadline(time.Now().Add(udpIdleTimeout))
		n, err := client.Read(buffer)
		if err != nil {
			return
		}
		query := append([]byte(nil), buffer[:n]...)
		response, err := g.resolveDNS(query, false)
		if err != nil {
			continue
		}
		_ = client.SetWriteDeadline(time.Now().Add(udpIdleTimeout))
		_, _ = client.Write(response)
	}
}

// serveDNSTCP 支持客户端标准 DNS-over-TCP 的两字节长度前缀；TCP 查询也直接使用
// upstream TCP，避免大响应先被 UDP 截断。
func (g *userGateway) serveDNSTCP(client net.Conn) {
	for {
		_ = client.SetDeadline(time.Now().Add(udpIdleTimeout))
		query, err := readDNSFrame(client)
		if err != nil {
			return
		}
		response, err := g.resolveDNS(query, true)
		if err != nil {
			continue
		}
		_ = client.SetDeadline(time.Now().Add(udpIdleTimeout))
		if err := writeDNSFrame(client, response); err != nil {
			return
		}
	}
}

func (g *userGateway) resolveDNS(query []byte, preferTCP bool) ([]byte, error) {
	if len(query) < 12 || len(query) > maxDNSPacket {
		return nil, errors.New("invalid dns query")
	}
	g.stats.dnsQueries.Add(1)
	if cached, ok := g.cache.get(query); ok {
		g.stats.dnsCacheHits.Add(1)
		return cached, nil
	}
	response, err := g.queryUpstream(query, preferTCP)
	if err != nil {
		return nil, err
	}
	g.cache.put(query, response)
	return response, nil
}

func (g *userGateway) queryUpstream(query []byte, preferTCP bool) ([]byte, error) {
	var last error
	var truncated []byte
	for _, address := range g.upstreams {
		if !preferTCP {
			response, err := g.queryDNSUDP(address, query)
			if err == nil && !dnsResponseTruncated(response) {
				return response, nil
			}
			if err == nil {
				truncated = response
			} else {
				last = err
			}
		}
		response, err := g.queryDNSTCP(address, query)
		if err == nil {
			return response, nil
		}
		last = err
	}
	if truncated != nil {
		// TCP upstream 暂时不可用时，把合法 TC 响应交给客户端，由其决定是否重试。
		return truncated, nil
	}
	if last == nil {
		last = errors.New("no dns upstream")
	}
	return nil, last
}

func (g *userGateway) queryDNSUDP(address string, query []byte) ([]byte, error) {
	conn, err := newBoundDialer(5*time.Second).DialContext(g.ctx, "udp", address)
	if err != nil {
		return nil, err
	}
	defer conn.Close()
	_ = conn.SetDeadline(time.Now().Add(5 * time.Second))
	written, err := conn.Write(query)
	if err != nil || written != len(query) {
		return nil, errors.New("dns udp write failed")
	}
	answer := make([]byte, maxDNSPacket)
	n, err := conn.Read(answer)
	if err != nil {
		return nil, err
	}
	answer = answer[:n]
	if !validDNSResponse(query, answer) {
		return nil, errors.New("invalid dns udp response")
	}
	return answer, nil
}

func (g *userGateway) queryDNSTCP(address string, query []byte) ([]byte, error) {
	conn, err := newBoundDialer(5*time.Second).DialContext(g.ctx, "tcp", address)
	if err != nil {
		return nil, err
	}
	defer conn.Close()
	_ = conn.SetDeadline(time.Now().Add(5 * time.Second))
	if err := writeDNSFrame(conn, query); err != nil {
		return nil, err
	}
	answer, err := readDNSFrame(conn)
	if err != nil {
		return nil, err
	}
	if !validDNSResponse(query, answer) {
		return nil, errors.New("invalid dns tcp response")
	}
	return answer, nil
}

func writeDNSFrame(writer io.Writer, message []byte) error {
	if len(message) < 12 || len(message) > maxDNSPacket {
		return errors.New("invalid dns tcp message")
	}
	frame := make([]byte, len(message)+2)
	binary.BigEndian.PutUint16(frame[:2], uint16(len(message)))
	copy(frame[2:], message)
	for len(frame) > 0 {
		n, err := writer.Write(frame)
		if err != nil {
			return err
		}
		if n == 0 {
			return io.ErrUnexpectedEOF
		}
		frame = frame[n:]
	}
	return nil
}

func readDNSFrame(reader io.Reader) ([]byte, error) {
	header := make([]byte, 2)
	if _, err := io.ReadFull(reader, header); err != nil {
		return nil, err
	}
	size := int(binary.BigEndian.Uint16(header))
	if size < 12 || size > maxDNSPacket {
		return nil, errors.New("invalid dns tcp frame")
	}
	message := make([]byte, size)
	if _, err := io.ReadFull(reader, message); err != nil {
		return nil, err
	}
	return message, nil
}

func validDNSResponse(query, response []byte) bool {
	return len(query) >= 2 && len(response) >= 12 && response[2]&0x80 != 0 &&
		binary.BigEndian.Uint16(query[:2]) == binary.BigEndian.Uint16(response[:2])
}

func dnsResponseTruncated(response []byte) bool {
	return len(response) >= 3 && response[2]&0x02 != 0
}

func (g *userGateway) close() {
	g.cancel()
	g.stack.Close()
	g.link.Close()
	done := make(chan struct{})
	go func() {
		g.wg.Wait()
		close(done)
	}()
	select {
	case <-done:
	case <-time.After(3 * time.Second):
		// socket deadline 最终会回收剩余 goroutine；停止服务不能无限阻塞 Android 主线程。
	}
}
