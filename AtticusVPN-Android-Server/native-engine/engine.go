// Copyright (c) 2026 TianzeL
// SPDX-License-Identifier: Apache-2.0

// Package atticusengine 提供 Android 可调用的标准 WireGuard 服务端数据面。
// gomobile 只导出本文件中使用基础类型的函数；线程、socket 与对象释放均由包内串行化。
package atticusengine

import (
	"bufio"
	"context"
	"dev.lan.atticusvpn/server/native-engine/internal/telemetry"
	"encoding/json"
	"errors"
	"fmt"
	"net"
	"net/netip"
	"regexp"
	"strconv"
	"strings"
	"sync"
	"time"

	"golang.zx2c4.com/wireguard/conn"
	"golang.zx2c4.com/wireguard/device"
)

const (
	engineVersion = "0.6.0-stable"
	maxUAPIBytes  = 64 * 1024
)

var (
	engineMu sync.Mutex
	current  *engineInstance
	hexKey   = regexp.MustCompile(`^[0-9a-f]{64}$`)
)

type engineInstance struct {
	failures *telemetry.Book
	gateway  *userGateway
	tun      *channelTUN
	device   *device.Device
	started  time.Time
	port     int
	bind     conn.Bind
}

type peerStatus struct {
	Endpoint            string `json:"endpoint"`
	PublicKey           string `json:"publicKey"`
	LatestHandshakeUnix int64  `json:"latestHandshakeUnix"`
	ReceiveBytes        uint64 `json:"receiveBytes"`
	TransmitBytes       uint64 `json:"transmitBytes"`
}

type engineStatus struct {
	PeerStatsAvailable bool              `json:"peerStatsAvailable"`
	Errors             []telemetry.Error `json:"errors"`
	Running            bool              `json:"running"`
	Version            string            `json:"version"`
	ListenPort         int               `json:"listenPort"`
	StartedUnix        int64             `json:"startedUnix"`
	TCPConnections     uint64            `json:"tcpConnections"`
	UDPSessions        uint64            `json:"udpSessions"`
	DNSQueries         uint64            `json:"dnsQueries"`
	DNSCacheHits       uint64            `json:"dnsCacheHits"`
	ToInternet         uint64            `json:"toInternetBytes"`
	ToPeer             uint64            `json:"toPeerBytes"`
	Peers              []peerStatus      `json:"peers"`
}

// Version 返回本地数据面版本，不访问网络，也不包含设备或密钥信息。
func Version() string { return engineVersion }

// SetNetworkHandle 必须在 Start 前由 Android Network.getNetworkHandle() 提供。
// 值为 0 表示使用系统默认路由。切网后 Java 会 Stop、更新 handle、再 Start，
// 从而关闭旧网络上的所有 listener/代理流，避免半连接长期悬挂。
func SetNetworkHandle(handle int64) {
	if handle <= 0 {
		selectedNetwork.Store(0)
		return
	}
	selectedNetwork.Store(uint64(handle))
}

// Start 启动一个 WireGuard interface 和用户态网关。
//
// uapi 是 Java 层从加密状态生成的 wireguard-go UAPI 文本，不是任意 wg-quick
// 配置。函数在进入 wireguard-go 前执行字段白名单和 key 形状检查，并且从不记录
// 原文；这样错误路径也不会把 private_key/preshared_key 泄漏到 Logcat。
func Start(uapi string, upstreamCSV string, mtu int) error {
	return startWithDial(uapi, upstreamCSV, mtu, nil)
}

// Internal dependency injection lets bounded tests exercise actual forwarding
// failures. Android's public API always uses the normal network-bound dialer.
func startWithDial(uapi string, upstreamCSV string, mtu int, dial func(context.Context, time.Duration, string, string) (net.Conn, error)) error {
	engineMu.Lock()
	defer engineMu.Unlock()
	if current != nil {
		return errors.New("ENGINE_ALREADY_RUNNING")
	}
	port, err := validateServerUAPI(uapi)
	if err != nil {
		return err
	}
	if mtu == 0 {
		mtu = defaultMTU
	}
	gateway, err := newUserGateway(mtu, upstreamCSV)
	if err != nil {
		return errors.New("GATEWAY_START_FAILED")
	}
	gateway.dialContext = dial // assigned before publishing/starting the WireGuard device
	tunDevice := newChannelTUN("atticusserver", mtu, gateway.link)
	// 禁用 verbose logger，避免把流量元数据送入系统日志；固定安全错误码由 Java 层显示。
	failures := gateway.failures
	logger := &device.Logger{
		Verbosef: func(string, ...any) {},
		Errorf:   func(format string, _ ...any) { failures.Record(wgErrorCode(format)) },
	}
	networkBind := conn.NewStdNetBind()
	wgDevice := device.NewDevice(tunDevice, networkBind, logger)
	if err := wgDevice.IpcSet(uapi); err != nil {
		wgDevice.Close()
		gateway.close()
		return errors.New("WIREGUARD_CONFIG_REJECTED")
	}
	// Bind quotas to the effective authenticated AllowedIPs routing table before
	// any peer traffic can enter the gateway. No private UAPI fields are retained.
	snapshot, readErr := wgDevice.IpcGet()
	if readErr != nil || gateway.configureTCPPeers(snapshot) != nil {
		wgDevice.Close()
		gateway.close()
		return errors.New("WIREGUARD_CONFIG_REJECTED")
	}
	if err := wgDevice.Up(); err != nil {
		wgDevice.Close()
		gateway.close()
		return errors.New("WIREGUARD_LISTENER_FAILED")
	}
	current = &engineInstance{
		failures: failures,
		gateway:  gateway,
		tun:      tunDevice,
		device:   wgDevice,
		started:  time.Now(),
		port:     port,
		bind:     networkBind,
	}
	return nil
}

// SocketFD4/SocketFD6 返回 listener 的借用 fd，供 Android Network.bindSocket 使用。
// Java 必须 detach ParcelFileDescriptor，不能关闭它；fd 生命周期仍完全属于 wireguard-go。
func SocketFD4() int {
	return socketFD(false)
}

func SocketFD6() int {
	return socketFD(true)
}

func socketFD(ipv6 bool) int {
	engineMu.Lock()
	defer engineMu.Unlock()
	if current == nil {
		return -1
	}
	peek, ok := current.bind.(conn.PeekLookAtSocketFd)
	if !ok {
		return -1
	}
	var fd int
	var err error
	if ipv6 {
		fd, err = peek.PeekLookAtSocketFd6()
	} else {
		fd, err = peek.PeekLookAtSocketFd4()
	}
	if err != nil {
		return -1
	}
	return fd
}

// Stop 可以重复调用。先让 wireguard-go 关闭 UDP listener/peer worker，随后取消
// gVisor 与转发 socket；这一顺序防止停止期间继续向已关闭的用户态栈注入数据包。
func Stop() {
	engineMu.Lock()
	instance := current
	current = nil
	engineMu.Unlock()
	if instance == nil {
		return
	}
	instance.device.Close()
	instance.gateway.close()
}

// Status 返回脱敏后的 JSON。只解析 Peer 公钥、握手时间与计数器，明确忽略
// private_key 和 preshared_key；Java GUI 不需要、也不应收到这些敏感值。
func Status() string {
	engineMu.Lock()
	defer engineMu.Unlock()
	status := engineStatus{Version: engineVersion, Peers: []peerStatus{}}
	if current == nil {
		raw, _ := json.Marshal(status)
		return string(raw)
	}
	status.Running = true
	status.ListenPort = current.port
	status.StartedUnix = current.started.Unix()
	status.TCPConnections = current.gateway.stats.tcpConnections.Load()
	status.UDPSessions = current.gateway.stats.udpSessions.Load()
	status.DNSQueries = current.gateway.stats.dnsQueries.Load()
	status.DNSCacheHits = current.gateway.stats.dnsCacheHits.Load()
	status.ToInternet = current.gateway.stats.toInternet.Load()
	status.ToPeer = current.gateway.stats.toPeer.Load()
	status.Peers, status.PeerStatsAvailable = readPeerSnapshot(current.device.IpcGet, current.failures)
	status.Errors = current.failures.Snapshot()

	raw, _ := json.Marshal(status)
	return string(raw)
}

func validateServerUAPI(raw string) (int, error) {
	if len(raw) == 0 || len(raw) > maxUAPIBytes || strings.IndexByte(raw, 0) >= 0 {
		return 0, errors.New("CONFIG_SIZE_INVALID")
	}
	allowed := map[string]bool{
		"private_key": true, "listen_port": true, "replace_peers": true,
		"public_key": true, "preshared_key": true, "replace_allowed_ips": true,
		"allowed_ip": true,
	}
	seenPrivate := false
	listenPort := 0
	peerOpen := false
	scanner := bufio.NewScanner(strings.NewReader(raw))
	for scanner.Scan() {
		line := scanner.Text()
		if line == "" {
			continue
		}
		key, value, ok := strings.Cut(line, "=")
		if !ok || !allowed[key] || value == "" {
			return 0, errors.New("CONFIG_FIELD_INVALID")
		}
		switch key {
		case "private_key":
			if seenPrivate || !hexKey.MatchString(value) || strings.Trim(value, "0") == "" {
				return 0, errors.New("PRIVATE_KEY_INVALID")
			}
			seenPrivate = true
		case "listen_port":
			port, err := strconv.Atoi(value)
			if err != nil || port < 1 || port > 65535 || listenPort != 0 {
				return 0, errors.New("LISTEN_PORT_INVALID")
			}
			listenPort = port
		case "replace_peers", "replace_allowed_ips":
			if value != "true" {
				return 0, errors.New("CONFIG_BOOLEAN_INVALID")
			}
		case "public_key":
			if !hexKey.MatchString(value) {
				return 0, errors.New("PUBLIC_KEY_INVALID")
			}
			peerOpen = true
		case "preshared_key":
			if !peerOpen || !hexKey.MatchString(value) {
				return 0, errors.New("PRESHARED_KEY_INVALID")
			}
		case "allowed_ip":
			if !peerOpen {
				return 0, errors.New("ALLOWED_IP_WITHOUT_PEER")
			}
		}
	}
	if scanner.Err() != nil || !seenPrivate || listenPort == 0 {
		return 0, errors.New("CONFIG_REQUIRED_FIELD_MISSING")
	}
	return listenPort, nil
}

func parsePeerStatus(raw string) []peerStatus {
	peers, _ := parsePeerStatusChecked(raw)
	return peers
}

// A failed or partial counter read is unknown, never evidence of an empty peer
// set. The separate availability bit prevents consumers from inventing device
// disconnects when IpcGet or a recognized runtime field cannot be parsed.
func readPeerSnapshot(read func() (string, error), failures *telemetry.Book) ([]peerStatus, bool) {
	raw, err := read()
	if err == nil {
		if peers, complete := parsePeerStatusChecked(raw); complete {
			return peers, true
		}
	}
	failures.Record("WG_STATUS_FAILED")
	return []peerStatus{}, false
}

func parsePeerStatusChecked(raw string) ([]peerStatus, bool) {
	peers := make([]peerStatus, 0)
	var active *peerStatus
	complete := true
	seen := uint8(0)
	keys := make(map[string]bool)
	for _, line := range strings.Split(raw, "\n") {
		if line == "" {
			continue
		}
		key, value, ok := strings.Cut(line, "=")
		if !ok {
			complete = false
			continue
		}
		if key == "public_key" {
			if active != nil && seen != 7 {
				complete = false
			}
			active = nil
			seen = 0
			if !hexKey.MatchString(value) || keys[value] {
				complete = false
				continue
			}
			keys[value] = true
			peers = append(peers, peerStatus{PublicKey: value})
			active = &peers[len(peers)-1]
			continue
		}
		if active == nil {
			continue
		}
		switch key {
		case "endpoint":
			active.Endpoint = normalizeEndpoint(value)
			if active.Endpoint == "" {
				complete = false
			}
		case "last_handshake_time_sec":
			var err error
			active.LatestHandshakeUnix, err = strconv.ParseInt(value, 10, 64)
			if err != nil || active.LatestHandshakeUnix < 0 || seen&1 != 0 {
				complete = false
			}
			seen |= 1
		case "rx_bytes":
			var err error
			active.ReceiveBytes, err = strconv.ParseUint(value, 10, 64)
			if err != nil || seen&2 != 0 {
				complete = false
			}
			seen |= 2
		case "tx_bytes":
			var err error
			active.TransmitBytes, err = strconv.ParseUint(value, 10, 64)
			if err != nil || seen&4 != 0 {
				complete = false
			}
			seen |= 4
		}
	}
	if active != nil && seen != 7 {
		complete = false
	}
	return peers, complete
}

// SelfTest 供构建/诊断页执行不联网的 native 烟雾测试。
// 它验证 UAPI 输入边界和 DNS cache 构造，但不会占用 51820 或声称完成真机握手。
func SelfTest() error {
	key := strings.Repeat("01", 32)
	config := fmt.Sprintf("private_key=%s\nlisten_port=51820\nreplace_peers=true\n", key)
	if _, err := validateServerUAPI(config); err != nil {
		return errors.New("SELF_TEST_CONFIG_FAILED")
	}
	upstreams, enabled, err := parseUpstreams("2001:4860:4860::8888,1.1.1.1")
	if err != nil || !enabled || len(upstreams) != 2 {
		return errors.New("SELF_TEST_DNS_FAILED")
	}
	return nil
}

func wgErrorCode(format string) string {
	switch {
	case strings.Contains(format, "Unable to update bind"):
		return "WG_BIND_UPDATE_FAILED"
	case strings.Contains(format, "Bind close failed"):
		return "WG_BIND_CLOSE_FAILED"
	case strings.Contains(format, "Failed to send data packets"):
		return "WG_DATA_SEND_FAILED"
	case strings.Contains(format, "TUN device"):
		return "WG_TUN_IO_FAILED"
	case strings.Contains(format, "MTU"):
		return "WG_MTU_FAILED"
	case strings.Contains(format, "handshake"), strings.Contains(format, "derive keypair"), strings.Contains(format, "create initiation"), strings.Contains(format, "create response"), strings.Contains(format, "cookie reply"):
		return "WG_HANDSHAKE_FAILED"
	case strings.Contains(format, "Invalid packet"), strings.Contains(format, "Failed to decode"):
		return "WG_INVALID_PACKET"
	default:
		return "NATIVE_RUNTIME_ERROR"
	}
}

// Same numeric endpoint policy as test 1.3.0; never resolve names or emit secrets.
func normalizeEndpoint(raw string) string {
	if len(raw) == 0 || len(raw) > 128 {
		return ""
	}
	a, err := netip.ParseAddrPort(raw)
	if err != nil || !a.IsValid() || a.Port() == 0 || a.Addr().IsUnspecified() {
		return ""
	}
	zone := a.Addr().Zone()
	if len(zone) > 32 {
		return ""
	}
	for _, r := range zone {
		if !(r >= 'a' && r <= 'z' || r >= 'A' && r <= 'Z' || r >= '0' && r <= '9' || r == '_' || r == '-' || r == '.') {
			return ""
		}
	}
	return a.String()
}
