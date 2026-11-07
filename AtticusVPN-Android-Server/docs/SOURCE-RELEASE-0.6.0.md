# STABLE 0.6.0 source release validation

Validation date: 2026-11-07. This record describes checks executed for this source publication. The dated 2026-11-05 repair report remains historical evidence and is not counted as a rerun.

## Source identity and integration

- Input: `AtticusVPN-Android-Server-STABLE-0.6.0-FIXED-source.zip`, 8,411,379 bytes.
- SHA-256: `ba17e529a9251a98c2411c2fd8de8d89c21c68d94892e2a2aa0a502f159b679e`.
- All 375 archive entries passed CRC and extraction checks. Files were integrated into the existing Android Server directory, retaining the public repository history based on `5af69e4f1078ca34c45139ed4562d9b459758175`.
- Production metadata remains `dev.lan.atticusvpn.server`, versionCode 20, Release versionName `0.6.0-stable`. Version 0.5.8 used code 19.
- No TEST 1.3.3 protocol implementation, test signing material, private evidence bundle, or existing binary was added. Historical capability stubs and generic diagnostic labels are not an operational IKEv2 or Atticus Link server.

## Additional changes found during this Windows validation

1. Separate native command stdout from stderr in `Invoke-Captured`. Go dependency-download progress on stderr had contaminated a successful JSON stdout response. Added actual child-process checks for clean JSON and rejection of nonzero exit; existing assertions remain enabled.
2. Make the closed-port gateway logging regression wait for the actual asynchronous outbound failure. A two-second client deadline could expire before Windows completed the separately bounded gateway dial. The assertion still requires the real fixed gateway error within a bounded wait; production dialing and logging code are unchanged.
3. An API 35 backup-over-existing-base test exposed a Windows host mismatch: Android's private AtomicFile rename helper expects POSIX atomic replacement, while Windows File.renameTo refuses an existing target. A synthetic host control and the actual framework bytecode confirmed the cause. A narrow test-only helper adapts that regular-file rename on Windows; the real openRead, decryption, recovery ordering and all assertions remain. Ciphertext-byte preservation and backup removal assertions were added. Production storage code was not changed. See the [Android framework implementation](https://github.com/aosp-mirror/platform_frameworks_base/blob/android-15.0.0_r1/core/java/android/util/AtomicFile.java). This simulated result is not a hardware/filesystem certification.
4. Strengthen the repository root ignore rules and update publication documentation. The standalone module retains its own complete source protection rules.

## Checks executed in this run

| Check | Result and scope |
|---|---|
| Original workspace backup | Existing backup matched all 2,129 files and 270,100,983 bytes by SHA-256; original tracked changes were empty. The untracked terminal-colored patch was preserved and matched the existing lint commit after removing display control codes. |
| Source ZIP packaging guard | PASS, 375 input files; human findings reviewed as upstream attribution and synthetic tests. |
| Python signing/source protection tests | 12 passed; standalone and nested Git scenarios included. |
| Root ignore policy | 22 synthetic private-file patterns checked and ignored. |
| Gitleaks 8.30.1 | Four existing commits scanned without findings. Staged scan returned one generic-key finding on the symbolic diagnostic constant `IKEV2_CERT_INVALID` in the fixed-code allowlist; manually reviewed as a false positive. The raw finding was retained; no scanner rule, baseline or allow-comment was added to suppress it. |
| Go unit tests | PASS: 38 top-level tests/fuzz seed groups, 61 including subtests; no test skips. Two command packages have no tests. |
| Go vet / module integrity | PASS; checksum verification remained enabled. |
| Go race | Not executed successfully: Windows CGO race build requires a C compiler; explicit CGO retry confirmed that `gcc` is unavailable. |
| OpenPGP import boundary | PASS; 236 host import dependencies, no OpenPGP package imports. |
| govulncheck | Windows host and Android arm64 source scans returned success, retaining only GO-2026-5932 at module level; no package or symbol trace for that advisory. Android scan used NDK/CGO. |
| OSV | 13 Go/standard-library coordinates: only GO-2026-5932. Twelve declared/documented Maven coordinates: no findings returned. This is not exhaustive validation of every Maven/build-tool transitive dependency. |
| PowerShell native script regression | 19 checks passed, including real Go child environment and separated output streams. |
| Native AAR | Rebuilt on Windows with Go 1.27.3, JDK 17 and NDK 29.0.14206865 for API 24 and all four ABIs. Module verification, embedded-path protection, normalization and public Java API checks passed. |
| Java core | Fresh direct JDK 17 compilation and JUnit run: 47 passed. |
| Android app tests / Lint / APK builds | PASS: core 47 + app 324 = 371 tests, failures/errors/skips all zero; Debug and Release Lint each had zero issues; Debug and unsigned Release APK builds succeeded. The final serial run disabled task/test caches and executed all 110 actionable tasks. |
| Final staged tree and archive | The 380-file staged tree and standalone source archive passed the packaging/content guard and permitted-scope review; APK/AAR outputs, caches, private evidence and signing material were excluded. |

The Windows native AAR SHA-256 is `7e94e95aed47abc8a3cdbde63785c055d711d0f5d39001c95228a3df7e396e70`. This local build artifact is not a release attachment.

All 60 encrypted-state recovery scenarios passed on simulated APIs 24/26/28/35; the narrow Windows API 35 rename adaptation above applies to one scenario. Robolectric API 36/37 execution was not validated under JDK 17. Both local APKs parsed correctly, contained all four target ABIs and passed ZIP alignment checks; the unsigned Release had the production package, code 20, name 0.6.0-stable and no debuggable flag. These build checks do not establish production signing or device acceptance.

Initial failures and environmental limits were retained in private execution evidence: restricted Git/DNS access, a Python launcher dispatch failure, toolchain downloads, Gradle cache-lock waits, missing offline Robolectric dependencies, incorrect initial Go executable selection, stderr/JSON mixing, the asynchronous logging assertion, and the API 35 Windows host rename mismatch. Checks were repaired or retried with their protections enabled; no failing test was deleted or skipped.

## Licenses and corresponding source

Project-owned code remains Apache-2.0. WireGuard, gVisor, Go, Android dependencies, Gradle Wrapper and other components retain their original licenses and notices. Source notice files and application license assets were retained.

The complete desugar 2.1.5 source archive has 10,162 entries and passed CRC and byte comparisons of LICENSE, ADDITIONAL_LICENSE_INFO and ASSEMBLY_EXCEPTION with the retained notices. Its SHA-256 is `b6fc9a0d46dacd3948bd06d6d52bc1f727680ca2c0045aa008edc395ca2cbee9`. Build instructions and the archive accompany this source release. The upstream Maven JAR was not independently rebuilt; no universal legal or reproducible-build guarantee is made.

## APK and device acceptance

This is a **Source-only** publication. No official 0.6.0 APK is supplied. The production signing key/password were not read, copied, exposed or uploaded. Original-certificate signing, real 0.5.8-to-0.6.0 data-preserving installation, hardware Keystore recovery, public IPv6 connectivity, OEM background behavior and sustained device load remain unverified.

The remaining OpenPGP module advisory is not claimed to be eliminated. Package/symbol reachability and the import guard support the current source decision; future dependency or feature changes require reassessment. Pattern scanning and passing host checks do not establish absence of every secret, vulnerability or device-specific failure.
