// Copyright (c) 2026 TianzeL
// SPDX-License-Identifier: Apache-2.0

package atticusengine

import (
	"context"
	"errors"
	"io"
	"net"
	"net/netip"
	"strings"
	"sync"
	"sync/atomic"
	"time"
)

const (
	maxTCPConnections   = 512
	maxTCPPerPeer       = 64
	tcpIdleTimeout      = 15 * time.Minute
	tcpHalfCloseTimeout = 2 * time.Minute
)

type tcpFlowBook struct {
	mu     sync.Mutex
	closed bool
	flows  map[*tcpFlow]struct{}
	byPeer map[string]int
	// Populated from the configured WireGuard device before Up. IPv4/IPv6
	// addresses of one authenticated peer share the same public-key quota.
	owners map[netip.Prefix]string
	wg     sync.WaitGroup
}

type tcpFlow struct {
	ctx     context.Context
	cancel  context.CancelFunc
	owner   string
	mu      sync.Mutex
	sockets []net.Conn
	once    sync.Once
}

func (f *tcpFlow) attach(conn net.Conn) {
	f.mu.Lock()
	defer f.mu.Unlock()
	if f.ctx.Err() != nil {
		_ = conn.Close()
		return
	}
	f.sockets = append(f.sockets, conn)
}

func (f *tcpFlow) close() {
	f.cancel()
	f.mu.Lock()
	defer f.mu.Unlock()
	for _, conn := range f.sockets {
		_ = conn.Close()
	}
	f.sockets = nil
}

// The input is a successful IpcGet snapshot, not an untrusted claimed peer ID.
// Retain only public-key/AllowedIPs routing data; never retain or log the snapshot.
func (g *userGateway) configureTCPPeers(snapshot string) error {
	owners := make(map[netip.Prefix]string)
	peer := ""
	for _, line := range strings.Split(snapshot, "\n") {
		key, value, _ := strings.Cut(line, "=")
		switch key {
		case "public_key":
			if !hexKey.MatchString(value) {
				return errors.New("WIREGUARD_CONFIG_REJECTED")
			}
			peer = value
		case "allowed_ip":
			prefix, err := netip.ParsePrefix(value)
			if err != nil || peer == "" {
				return errors.New("WIREGUARD_CONFIG_REJECTED")
			}
			owners[prefix.Masked()] = peer
		}
	}
	g.tcp.mu.Lock()
	g.tcp.owners = owners
	g.tcp.mu.Unlock()
	return nil
}

func (g *userGateway) openTCPFlow(source netip.Addr) *tcpFlow {
	b := &g.tcp
	b.mu.Lock()
	defer b.mu.Unlock()
	if b.closed || g.ctx.Err() != nil {
		return nil
	}
	owner, longest := "", -1
	for prefix, peer := range b.owners {
		if prefix.Contains(source) && prefix.Bits() > longest {
			owner, longest = peer, prefix.Bits()
		}
	}
	if owner == "" || len(b.flows) >= maxTCPConnections || b.byPeer[owner] >= maxTCPPerPeer {
		return nil
	}
	if b.flows == nil {
		b.flows = make(map[*tcpFlow]struct{})
		b.byPeer = make(map[string]int)
	}
	ctx, cancel := context.WithCancel(g.ctx)
	f := &tcpFlow{ctx: ctx, cancel: cancel, owner: owner}
	b.flows[f] = struct{}{}
	b.byPeer[owner]++
	// Admission is closed under this same lock before shutdown can wait.
	b.wg.Add(1)
	return f
}

func (g *userGateway) releaseTCPFlow(f *tcpFlow) {
	f.once.Do(func() {
		f.close()
		b := &g.tcp
		b.mu.Lock()
		delete(b.flows, f)
		b.byPeer[f.owner]--
		if b.byPeer[f.owner] == 0 {
			delete(b.byPeer, f.owner)
		}
		b.mu.Unlock()
		b.wg.Done()
	})
}

func (g *userGateway) closeTCPFlows() {
	b := &g.tcp
	b.mu.Lock()
	b.closed = true
	flows := make([]*tcpFlow, 0, len(b.flows))
	for f := range b.flows {
		flows = append(flows, f)
	}
	b.mu.Unlock()
	for _, f := range flows {
		f.close()
	}
}

// Preserve independent FIN directions, including responses sent only after EOF.
// Any activity renews the idle timeout; after FIN the remaining direction has
// a shorter idle timeout, so active downloads still finish normally. Join both
// workers before the caller releases the connection quota.
func (g *userGateway) relayTCP(ctx context.Context, inbound, outbound net.Conn, idle, halfIdle time.Duration) {
	var deadlines sync.Mutex
	halfClosed := false
	refresh := func(fin bool) {
		deadlines.Lock()
		defer deadlines.Unlock()
		halfClosed = halfClosed || fin
		timeout := idle
		if halfClosed && halfIdle < timeout {
			timeout = halfIdle
		}
		until := time.Now().Add(timeout)
		_ = inbound.SetDeadline(until)
		_ = outbound.SetDeadline(until)
	}
	closeBoth := func() { _ = inbound.Close(); _ = outbound.Close() }
	stopCancel := context.AfterFunc(ctx, closeBoth)
	defer stopCancel()
	defer closeBoth()
	refresh(false)
	done := make(chan error, 2)
	copyOne := func(dst, src net.Conn, counter *atomic.Uint64) {
		_, err := io.Copy(tcpActivityWriter{dst, counter, func() { refresh(false) }}, src)
		if err == nil {
			if half, ok := dst.(interface{ CloseWrite() error }); ok {
				err = half.CloseWrite()
			} else {
				err = io.ErrClosedPipe
			}
			refresh(true)
		}
		done <- err
	}
	go copyOne(outbound, inbound, &g.stats.toInternet)
	go copyOne(inbound, outbound, &g.stats.toPeer)
	for range 2 {
		if err := <-done; err != nil {
			if ctx.Err() == nil {
				g.recordIO(err)
			}
			closeBoth()
		}
	}
}

type tcpActivityWriter struct {
	dst   io.Writer
	count *atomic.Uint64
	touch func()
}

func (w tcpActivityWriter) Write(p []byte) (int, error) {
	w.touch()
	n, err := w.dst.Write(p)
	if n > 0 {
		w.count.Add(uint64(n))
		w.touch()
	}
	return n, err
}
