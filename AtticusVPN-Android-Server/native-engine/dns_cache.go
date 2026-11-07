// Copyright (c) 2026 TianzeL
// SPDX-License-Identifier: Apache-2.0

package atticusengine

import (
	"encoding/binary"
	"errors"
	"sync"
	"time"

	"golang.org/x/net/dns/dnsmessage"
)

const (
	maxDNSPacket = 65535
	maxDNSTTL    = 5 * time.Minute
)

type dnsCacheEntry struct {
	response []byte
	stored   time.Time
	expires  time.Time
}

// dnsCache 是有大小上限的内存缓存，不把用户查询写入磁盘。
// key 会忽略 DNS transaction ID；命中时再写回当前请求的 ID，避免把另一客户端的
// ID 原样返回。TTL 取响应中资源记录的最小值；TTL=0 不缓存，上限为 5 分钟。
type dnsCache struct {
	mu      sync.Mutex
	entries map[string]dnsCacheEntry
	max     int
	now     func() time.Time
}

func newDNSCache(maxEntries int) *dnsCache {
	return &dnsCache{entries: make(map[string]dnsCacheEntry), max: maxEntries, now: time.Now}
}

func dnsQuestionKey(query []byte) (string, error) {
	if len(query) < 12 || len(query) > maxDNSPacket {
		return "", errors.New("invalid dns query size")
	}
	// 除 transaction ID 外保留 flags/question 等字节，避免不同类型请求错误共享缓存。
	return string(query[2:]), nil
}

func (c *dnsCache) get(query []byte) ([]byte, bool) {
	key, err := dnsQuestionKey(query)
	if err != nil {
		return nil, false
	}
	c.mu.Lock()
	defer c.mu.Unlock()
	entry, ok := c.entries[key]
	if !ok || !c.now().Before(entry.expires) {
		delete(c.entries, key)
		return nil, false
	}
	age := c.now().Sub(entry.stored)
	if age < 0 {
		age = 0
	}
	answer, ok := ageDNSResponse(
		entry.response,
		binary.BigEndian.Uint16(query[:2]),
		uint32(age/time.Second),
	)
	if !ok {
		delete(c.entries, key)
		return nil, false
	}
	return answer, true
}

func (c *dnsCache) put(query, response []byte) {
	key, err := dnsQuestionKey(query)
	if err != nil || !validDNSResponse(query, response) || dnsResponseTruncated(response) {
		return
	}
	ttl, ok := responseTTL(response)
	if !ok {
		return
	}
	c.mu.Lock()
	defer c.mu.Unlock()
	if len(c.entries) >= c.max {
		// 缓存很小，淘汰任一项即可控制内存；不记录域名，也不为 LRU 增加持久元数据。
		for oldKey := range c.entries {
			delete(c.entries, oldKey)
			break
		}
	}
	now := c.now()
	c.entries[key] = dnsCacheEntry{
		response: append([]byte(nil), response...),
		stored:   now,
		expires:  now.Add(ttl),
	}
}

func responseTTL(raw []byte) (time.Duration, bool) {
	var message dnsmessage.Message
	if err := message.Unpack(raw); err != nil || !message.Header.Response {
		return 0, false
	}
	var minimum uint32
	found := false
	consider := func(resources []dnsmessage.Resource) bool {
		for _, resource := range resources {
			// OPT 的 TTL 字段承载扩展 RCODE、版本与 flags，并不是可缓存 TTL。
			if resource.Header.Type == dnsmessage.TypeOPT {
				continue
			}
			found = true
			if resource.Header.TTL == 0 {
				return false
			}
			if minimum == 0 || resource.Header.TTL < minimum {
				minimum = resource.Header.TTL
			}
		}
		return true
	}
	if !consider(message.Answers) || !consider(message.Authorities) || !consider(message.Additionals) ||
		!found {
		return 0, false
	}
	ttl := time.Duration(minimum) * time.Second
	if ttl > maxDNSTTL {
		ttl = maxDNSTTL
	}
	return ttl, true
}

// ageDNSResponse 在缓存命中时递减所有真正的资源记录 TTL，并重写 transaction ID。
// 重新 Pack 会生成一份独立响应，避免修改缓存中供其他 Peer 使用的原始字节。
func ageDNSResponse(raw []byte, transactionID uint16, elapsed uint32) ([]byte, bool) {
	var message dnsmessage.Message
	if err := message.Unpack(raw); err != nil || !message.Header.Response {
		return nil, false
	}
	decrement := func(resources []dnsmessage.Resource) {
		for index := range resources {
			if resources[index].Header.Type == dnsmessage.TypeOPT {
				continue
			}
			if resources[index].Header.TTL <= elapsed {
				resources[index].Header.TTL = 0
			} else {
				resources[index].Header.TTL -= elapsed
			}
		}
	}
	decrement(message.Answers)
	decrement(message.Authorities)
	decrement(message.Additionals)
	message.Header.ID = transactionID
	answer, err := message.Pack()
	if err != nil || len(answer) > maxDNSPacket {
		return nil, false
	}
	return answer, true
}
