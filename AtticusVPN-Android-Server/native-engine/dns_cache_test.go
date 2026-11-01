// Copyright (c) 2026 TianzeL
// SPDX-License-Identifier: Apache-2.0

package atticusengine

import (
	"encoding/binary"
	"testing"
	"time"

	"golang.org/x/net/dns/dnsmessage"
)

func makeDNSPair(t *testing.T, id uint16, ttl uint32) ([]byte, []byte) {
	t.Helper()
	name := dnsmessage.MustNewName("example.test.")
	query := dnsmessage.Message{
		Header:    dnsmessage.Header{ID: id, RecursionDesired: true},
		Questions: []dnsmessage.Question{{Name: name, Type: dnsmessage.TypeA, Class: dnsmessage.ClassINET}},
	}
	q, err := query.Pack()
	if err != nil {
		t.Fatal(err)
	}
	response := dnsmessage.Message{
		Header:    dnsmessage.Header{ID: id, Response: true, RecursionAvailable: true},
		Questions: query.Questions,
		Answers: []dnsmessage.Resource{{
			Header: dnsmessage.ResourceHeader{Name: name, Type: dnsmessage.TypeA, Class: dnsmessage.ClassINET, TTL: ttl},
			Body:   &dnsmessage.AResource{A: [4]byte{192, 0, 2, 1}},
		}},
	}
	r, err := response.Pack()
	if err != nil {
		t.Fatal(err)
	}
	return q, r
}

func TestDNSCacheRewritesTransactionID(t *testing.T) {
	cache := newDNSCache(8)
	query, response := makeDNSPair(t, 7, 60)
	cache.put(query, response)
	query2 := append([]byte(nil), query...)
	binary.BigEndian.PutUint16(query2[:2], 99)
	got, ok := cache.get(query2)
	if !ok || binary.BigEndian.Uint16(got[:2]) != 99 {
		t.Fatalf("cache miss or wrong id: ok=%v id=%d", ok, binary.BigEndian.Uint16(got[:2]))
	}
}

func TestDNSCacheExpires(t *testing.T) {
	cache := newDNSCache(8)
	now := time.Unix(100, 0)
	cache.now = func() time.Time { return now }
	query, response := makeDNSPair(t, 1, 1)
	cache.put(query, response)
	now = now.Add(2 * time.Second)
	if _, ok := cache.get(query); ok {
		t.Fatal("expired entry must not be returned")
	}
}

func TestDNSCacheDoesNotStoreZeroTTL(t *testing.T) {
	cache := newDNSCache(8)
	query, response := makeDNSPair(t, 2, 0)
	cache.put(query, response)
	if _, ok := cache.get(query); ok {
		t.Fatal("TTL=0 response must not be cached")
	}

	// 任一普通资源记录为 TTL=0 时，后续较大的 TTL 也不能把 minimum 重新抬高。
	var message dnsmessage.Message
	if err := message.Unpack(response); err != nil {
		t.Fatal(err)
	}
	message.Answers = append(message.Answers, dnsmessage.Resource{
		Header: dnsmessage.ResourceHeader{
			Name: message.Questions[0].Name, Type: dnsmessage.TypeA,
			Class: dnsmessage.ClassINET, TTL: 60,
		},
		Body: &dnsmessage.AResource{A: [4]byte{192, 0, 2, 2}},
	})
	mixed, err := message.Pack()
	if err != nil {
		t.Fatal(err)
	}
	cache.put(query, mixed)
	if _, ok := cache.get(query); ok {
		t.Fatal("response containing a zero TTL record must not be cached")
	}
}

func TestDNSCacheHitDecrementsTTL(t *testing.T) {
	cache := newDNSCache(8)
	now := time.Unix(200, 0)
	cache.now = func() time.Time { return now }
	query, response := makeDNSPair(t, 3, 60)
	cache.put(query, response)
	now = now.Add(7*time.Second + 900*time.Millisecond)

	query2 := append([]byte(nil), query...)
	binary.BigEndian.PutUint16(query2[:2], 44)
	answer, ok := cache.get(query2)
	if !ok {
		t.Fatal("expected cache hit")
	}
	var message dnsmessage.Message
	if err := message.Unpack(answer); err != nil {
		t.Fatal(err)
	}
	if message.Header.ID != 44 || len(message.Answers) != 1 || message.Answers[0].Header.TTL != 53 {
		t.Fatalf("aged response mismatch: id=%d answers=%+v", message.Header.ID, message.Answers)
	}
}
