// Copyright (c) 2026 TianzeL
// SPDX-License-Identifier: Apache-2.0

package telemetry

import (
	"encoding/json"
	"strings"
	"sync"
	"testing"
)

func TestBookAggregatesConcurrentErrorsAndRejectsUntrustedText(t *testing.T) {
	var book Book
	var workers sync.WaitGroup
	for i := 0; i < 8; i++ {
		workers.Add(1)
		go func() {
			defer workers.Done()
			for j := 0; j < 1000; j++ {
				book.Record("WG_BIND_UPDATE_FAILED")
			}
		}()
	}
	workers.Wait()
	const private = "TEST-ONLY-untrusted-password-DO-NOT-RETAIN"
	for i := 0; i < 1000; i++ {
		book.Record(private)
	}
	rows := book.Snapshot()
	if len(rows) != 2 {
		t.Fatal("unbounded error vocabulary")
	}
	for _, row := range rows {
		if row.Code == "WG_BIND_UPDATE_FAILED" && row.Count != 8000 {
			t.Fatal("lost concurrent events")
		}
		if row.LastUnix < row.FirstUnix || row.FirstUnix == 0 || row.Reason == "" {
			t.Fatal("missing reason/timestamps")
		}
	}
	encoded, _ := json.Marshal(rows)
	if strings.Contains(string(encoded), private) {
		t.Fatal("raw error value retained")
	}
	rows[0].Reason = private
	encoded, _ = json.Marshal(book.Snapshot())
	if strings.Contains(string(encoded), private) {
		t.Fatal("snapshot mutation changed stored errors")
	}
}
