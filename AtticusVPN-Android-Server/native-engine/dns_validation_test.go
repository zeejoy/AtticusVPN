// Copyright (c) 2026 TianzeL
// SPDX-License-Identifier: Apache-2.0
package atticusengine

import (
	"encoding/binary"
	"golang.org/x/net/dns/dnsmessage"
	"testing"
)

func TestDNSResponseBindingAndCacheAdmission(t *testing.T) {
	q, good := makeDNSPair(t, 123, 60)
	for _, tc := range []struct {
		name   string
		change func(*dnsmessage.Message)
	}{
		{"id", func(m *dnsmessage.Message) { m.ID++ }},
		{"opcode", func(m *dnsmessage.Message) { m.OpCode++ }},
		{"not-response", func(m *dnsmessage.Message) { m.Response = false }},
		{"missing-question", func(m *dnsmessage.Message) { m.Questions = nil }},
		{"extra-question", func(m *dnsmessage.Message) { m.Questions = append(m.Questions, m.Questions[0]) }},
		{"name", func(m *dnsmessage.Message) { m.Questions[0].Name = dnsmessage.MustNewName("other.test.") }},
		{"type", func(m *dnsmessage.Message) { m.Questions[0].Type = dnsmessage.TypeAAAA }},
		{"class", func(m *dnsmessage.Message) { m.Questions[0].Class = dnsmessage.ClassCHAOS }},
	} {
		t.Run(tc.name, func(t *testing.T) {
			var m dnsmessage.Message
			if err := m.Unpack(good); err != nil {
				t.Fatal(err)
			}
			tc.change(&m)
			bad, err := m.Pack()
			if err != nil {
				t.Fatal(err)
			}
			if validDNSResponse(q, bad) {
				t.Fatal("invalid response accepted")
			}
			c := newDNSCache(2)
			c.put(q, bad)
			if _, ok := c.get(q); ok {
				t.Fatal("invalid response cached")
			}
		})
	}
	for _, mode := range []string{"upper", "compressed", "nxdomain", "ad-cd"} {
		t.Run(mode, func(t *testing.T) {
			var m dnsmessage.Message
			m.Unpack(good)
			switch mode {
			case "upper":
				m.Questions[0].Name = dnsmessage.MustNewName("EXAMPLE.TEST.")
			case "nxdomain":
				m.RCode = dnsmessage.RCodeNameError
				m.Answers = nil
			case "ad-cd":
				m.AuthenticData = true
				m.CheckingDisabled = true
			}
			b := dnsmessage.NewBuilder(nil, m.Header)
			if mode == "compressed" {
				b.EnableCompression()
			}
			b.StartQuestions()
			for _, v := range m.Questions {
				b.Question(v)
			}
			b.StartAnswers()
			for _, v := range m.Answers {
				b.AResource(v.Header, *v.Body.(*dnsmessage.AResource))
			}
			answer, err := b.Finish()
			if err != nil {
				t.Fatal(err)
			}
			if !validDNSResponse(q, answer) {
				t.Fatal("legal response rejected")
			}
		})
	}
}
func TestDNSMalformedAndTruncatedResponses(t *testing.T) {
	q, r := makeDNSPair(t, 1, 60)
	malformed := [][]byte{nil, make([]byte, 12), append(append([]byte(nil), r...), make([]byte, maxDNSPacket)...)}
	z := append([]byte(nil), r...)
	z[3] |= 0x40
	malformed = append(malformed, z)
	loop := append([]byte(nil), r[:12]...)
	loop = append(loop, 0xc0, 12, 0, 1, 0, 1)
	malformed = append(malformed, loop)
	for i := 12; i < len(r); i++ {
		malformed = append(malformed, append([]byte(nil), r[:i]...))
	}
	for i, b := range malformed {
		if validDNSResponse(q, b) {
			t.Fatalf("malformed response %d accepted", i)
		}
	}
	tc := append([]byte(nil), r[:len(r)-2]...)
	tc[2] |= 2
	if !validDNSResponse(q, tc) {
		t.Fatal("valid question in TC response should permit retry")
	}
	c := newDNSCache(2)
	c.put(q, tc)
	if _, ok := c.get(q); ok {
		t.Fatal("truncated response cached")
	}
	// The question itself may use a pointer to a bounded name elsewhere in the packet.
	compressed := append([]byte(nil), r[:12]...)
	binary.BigEndian.PutUint16(compressed[6:8], 0)
	compressed = append(compressed, 0xc0, 18, 0, 1, 0, 1)
	compressed = append(compressed, q[12:len(q)-4]...)
	if !validDNSResponse(q, compressed) {
		t.Fatal("legal compressed question rejected")
	}
	compressed[13] = 255
	if validDNSResponse(q, compressed) {
		t.Fatal("out-of-bounds compression accepted")
	}
}
func FuzzDNSResponseValidation(f *testing.F) {
	f.Add(testDNSQuery(), testDNSQuery())
	f.Add([]byte{}, []byte{0xc0, 0})
	f.Fuzz(func(t *testing.T, q, r []byte) {
		if len(q) > maxDNSPacket+1 || len(r) > maxDNSPacket+1 {
			return
		}
		validDNSResponse(q, r)
		c := newDNSCache(2)
		c.put(q, r)
		c.get(q)
	})
}
