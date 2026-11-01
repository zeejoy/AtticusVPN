# Native engine licenses

Project-owned native engine: Copyright 2026 TianzeL, Apache-2.0.

- wireguard-go v0.0.0-20260522210424-ecfc5a8d5446: MIT.
  Copyright (C) 2017-2025 WireGuard LLC. All Rights Reserved.
  See wireguard-go-MIT.txt.
- gVisor v0.0.0-20250503011706-39ed1f5ac29c: complete original mixed license
  in gVisor-LICENSE.txt (Apache-2.0 plus applicable file-specific MIT terms).
- google/btree v1.1.2: Apache-2.0, google-btree-Apache-2.0.txt.
- Go 1.27.0: Go-standard-library-BSD-3-Clause.txt and
  Go-standard-library-PATENTS.txt.
- golang.org/x/mobile v0.0.0-20260821190718-4776eadac327,
  x/crypto v0.55.0, x/net v0.58.0, x/mod v0.39.0, x/sync v0.22.0,
  x/sys v0.47.0, x/time v0.7.0 and x/tools v0.49.0:
  Go-BSD-3-Clause.txt and Go-PATENTS.txt.
  Copyright 2009 The Go Authors.

Some modules are build/test-only. The Windows-conditional wintun module
does not link into Android. Original third-party licenses are not replaced
by the project's Apache license. Exact source versions and hashes are
recorded in native-engine/go.mod and go.sum in the source repository.
