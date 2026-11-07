// Copyright (c) 2026 TianzeL
// SPDX-License-Identifier: Apache-2.0
package atticusengine

import (
	"encoding/binary"
	"golang.org/x/net/dns/dnsmessage"
)

// DNS names compare ASCII case-insensitively, not by Unicode case folding.
func sameDNSName(a, b dnsmessage.Name) bool {
	if a.Length != b.Length {
		return false
	}
	for i := 0; i < int(a.Length); i++ {
		x, y := a.Data[i], b.Data[i]
		if x >= 'A' && x <= 'Z' {
			x += 'a' - 'A'
		}
		if y >= 'A' && y <= 'Z' {
			y += 'a' - 'A'
		}
		if x != y {
			return false
		}
	}
	return true
}

func validDNSResponse(query, response []byte) bool {
	if len(query) < 12 || len(query) > maxDNSPacket || len(response) < 12 || len(response) > maxDNSPacket {
		return false
	}
	// This resolver proxies ordinary one-question DNS (RFC 9619). The reserved
	// Z bit must be zero; AD/CD, AA, RA and error RCODEs remain legal.
	if query[3]&0x40 != 0 || response[3]&0x40 != 0 ||
		binary.BigEndian.Uint16(query[4:6]) != 1 || binary.BigEndian.Uint16(response[4:6]) != 1 {
		return false
	}
	var q, r dnsmessage.Parser
	qh, qe := q.Start(query)
	rh, re := r.Start(response)
	if qe != nil || re != nil || qh.Response || !rh.Response || qh.ID != rh.ID || qh.OpCode != rh.OpCode {
		return false
	}
	qq, qe := q.Question()
	rq, re := r.Question()
	if qe != nil || re != nil || qq.Type != rq.Type || qq.Class != rq.Class || !sameDNSName(qq.Name, rq.Name) {
		return false
	}
	var qm dnsmessage.Message
	if qm.Unpack(query) != nil {
		return false
	}
	// A truncated UDP response may end inside an RR; its validated Question
	// permits TCP retry, but it is never cached. Full responses must parse.
	if rh.Truncated {
		return true
	}
	var rm dnsmessage.Message
	return rm.Unpack(response) == nil
}
