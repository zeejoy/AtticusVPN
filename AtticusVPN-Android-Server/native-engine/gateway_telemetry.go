// Copyright (c) 2026 TianzeL
// SPDX-License-Identifier: Apache-2.0
package atticusengine

import (
	"context"
	"errors"
	"io"
	"net"
	"time"
)

func (g *userGateway) dial(ctx context.Context, timeout time.Duration, network, address string) (net.Conn, error) {
	if g.dialContext != nil {
		return g.dialContext(ctx, timeout, network, address)
	}
	return newBoundDialer(timeout).DialContext(ctx, network, address)
}
func (g *userGateway) record(code string) {
	if g.failures != nil {
		g.failures.Record(code)
	}
}
func (g *userGateway) recordDial(ctx context.Context, transport string, err error) {
	if ctx.Err() != nil || errors.Is(err, context.Canceled) {
		return
	}
	// The only caller-provided transport values are fixed internal TCP/UDP literals.
	g.record("GATEWAY_" + transport + "_DIAL_FAILED")
	var ne net.Error
	if errors.As(err, &ne) && ne.Timeout() {
		g.record("GATEWAY_DIAL_TIMEOUT")
	}
}
func (g *userGateway) recordIO(err error) {
	if err == nil || errors.Is(err, io.EOF) || errors.Is(err, net.ErrClosed) || errors.Is(err, context.Canceled) || (g.ctx != nil && g.ctx.Err() != nil) {
		return
	}
	var ne net.Error
	// Relay deadlines are deliberate idle collection, not failed VPN authentication.
	if errors.As(err, &ne) && ne.Timeout() {
		return
	}
	g.record("GATEWAY_FORWARD_IO_FAILED")
}
