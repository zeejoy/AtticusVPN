#!/usr/bin/env python3
# Copyright 2026 TianzeL
# SPDX-License-Identifier: Apache-2.0
"""Check an Android-server source tree or ZIP without printing matched values.

This is a packaging guard, not a proof that arbitrary content has no secrets.
Known public test fixtures are kept and separately reported for human review.
No network requests or filesystem writes are performed.
"""

from __future__ import annotations

import argparse
from collections import Counter
import io
import ipaddress
import json
from pathlib import Path, PurePosixPath
import re
import struct
import sys
from urllib.parse import urlsplit
import zipfile
import zlib


ALIAS = "TianzeL"
ALLOWED_DIRECTORIES = {
    ".github", "app", "core", "docs", "gradle", "native-engine", "tools",
    "licenses", "artwork",
}
FORBIDDEN_SEGMENTS = {
    ".git", ".gradle", ".gradle-agent", ".gradle-user-home", ".android",
    ".idea", ".vs", "build", "bin", "obj", "out", "outputs", "exports",
    "artifacts", "node_modules", "__pycache__", "android-client", "windows",
    "client-android", "client-windows", "server-linux", "ikev2-native",
}
FORBIDDEN_SUFFIXES = {
    ".aar", ".apk", ".aab", ".so", ".dex", ".class", ".keystore", ".jks",
    ".p12", ".pfx", ".pem", ".key", ".log", ".dmp", ".exe", ".dll",
    ".zip", ".bak", ".tmp", ".sqlite", ".db", ".conf",
}
REQUIRED = {
    "LICENSE", "NOTICE", "README.md", "THIRD_PARTY_NOTICES.md",
    "build.gradle.kts", "settings.gradle.kts", "gradle.properties",
    "gradlew", "gradlew.bat", "gradle/wrapper/gradle-wrapper.jar",
    "gradle/wrapper/gradle-wrapper.properties", "app/build.gradle.kts",
    "app/src/main/AndroidManifest.xml", "core/build.gradle.kts",
    "native-engine/go.mod", "native-engine/go.sum", "native-engine/engine.go",
    "native-engine/build-aar.ps1", "tools/verify-public.py",
}
OWNED_SUFFIXES = {".java", ".go", ".kts", ".ps1", ".py", ".pro"}
RULES = {
    "personal-home-path": re.compile(
        r"(?i)(?:[a-z]:[\\/](?:Users|Documents and Settings)[\\/]|"
        r"[\\/](?:Users|home)[\\/][^\s\\/]+[\\/]|AppData[\\/])"
    ),
    "cloud-access-key": re.compile(r"\bAKIA[A-Z0-9]{16}\b"),
    "github-token": re.compile(
        r"\b(?:gh[pousr]_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,})\b"
    ),
    "google-api-key": re.compile(r"\bAIza[A-Za-z0-9_-]{30,}\b"),
    "private-key-block": re.compile(
        r"-----BEGIN (?:RSA |EC |OPENSSH |ENCRYPTED )?PRIVATE KEY-----"
    ),
    "device-identifier": re.compile(
        r"(?i)(?:\b(?:IMEI|IMSI|ANDROID_ID)\s*[:=]\s*[\"']?[a-z0-9]{8,}|"
        r"\bwxid_[a-z0-9]{6,}|\b[0-9a-f]{2}(?::[0-9a-f]{2}){5}\b)"
    ),
    "credential-assignment": re.compile(
        r"(?i)\b(?:privatekey|presharedkey|password|token|secret|api[_-]?key)"
        r"\s*[:=]\s*[\"'][^\"'\r\n]{6,}[\"']"
    ),
    "credential-in-url": re.compile(
        r"(?i)[?&](?:token|password|secret|api[_-]?key)=[A-Za-z0-9._~-]{6,}"
    ),
}
IPV4 = re.compile(r"(?<![\d.])(?:\d{1,3}\.){3}\d{1,3}(?![\d.])")
IPV6 = re.compile(r"(?i)(?<![a-z0-9])[0-9a-f]{0,4}(?::[0-9a-f]{0,4}){2,8}(?![a-z0-9])")
EMAIL = re.compile(r"[A-Za-z0-9._%+-]+@([A-Za-z0-9.-]+\.[A-Za-z]{2,})")
URL = re.compile(r"\bhttps?://[^\s<>\"',)]+")
DNS_HOST = re.compile(r"(?i)^[a-z0-9](?:[a-z0-9.-]*[a-z0-9])?$")
HOST_LITERAL = re.compile(
    r"(?i)(?<![a-z0-9.-])[a-z0-9][a-z0-9.-]*\."
    r"(?:com|net|org|cn|io|dev|app|co|me|xyz|info|biz|top|online|site|cloud)(?![a-z0-9.-])"
)
QUOTED = re.compile(r'''"(?:\\.|[^"\\\r\n])*"|'(?:\\.|[^'\\\r\n])*' ''', re.X)
KNOWN_PUBLIC_HOSTS = {
    "github.com", "gradle.org", "services.gradle.org", "downloads.gradle.org",
    "docs.gradle.org", "apache.org", "www.apache.org", "www.duckdns.org",
    "api.cloudflare.com", "api.dynu.com", "dynupdate.no-ip.com",
    "freedns.afraid.org", "developer.android.com", "schemas.android.com",
    "developer.apple.com", "www.cloudflare.com", "www.microsoft.com",
    "www.baidu.com", "www.bilibili.com", "proxy.golang.org", "sum.golang.org",
    "go.dev", "pkg.go.dev", "go.googlesource.com", "docs.strongswan.org",
    "docs.oracle.com", "opensource.org", "www.gnu.org", "www.zxing.org",
    "zxing.github.io", "openjdk.java.net", "openjdk.org", "source.android.com",
    "spdx.org", "www.spdx.org", "git.zx2c4.com", "gvisor.dev",
    "android.googlesource.com", "www.wireguard.com", "www.cloudflare.com.cn",
    "duckdns.org", "cloudflare.com", "microsoft.com", "baidu.com", "bilibili.com",
    "dynu.com", "no-ip.com", "json.org", "www.json.org", "code.google.com",
    "dl.google.com", "maven.google.com", "repo.maven.apache.org",
    "docs.github.com", "cli.github.com", "docs.python.org", "git.io",
    "golang.org", "golang.zx2c4.com", "junit.org", "hamcrest.org",
    "repo1.maven.org", "mvnrepository.com",
}
KNOWN_PUBLIC_RESOLVERS = {
    "1.1.1.1", "1.0.0.1", "8.8.8.8", "8.8.4.4", "9.9.9.9",
    "149.112.112.112", "2606:4700:4700::1111", "2606:4700:4700::1001",
    "2001:4860:4860::8888", "2001:4860:4860::8844",
}
PNG_SIGNATURE = b"\x89PNG\r\n\x1a\n"
PNG_ALLOWED = {b"IHDR", b"PLTE", b"tRNS", b"IDAT", b"IEND",
               b"sRGB", b"gAMA", b"cHRM", b"pHYs"}
MAX_ENTRY = 64 * 1024 * 1024
MAX_TOTAL = 512 * 1024 * 1024
MAX_NESTING = 4


class Audit:
    def __init__(self) -> None:
        self.errors: Counter[tuple[str, str]] = Counter()
        self.reviews: Counter[tuple[str, str]] = Counter()
        self.file_count = 0
        self.expanded_members = 0
        self.png_count = 0
        self.byte_count = 0

    def record(self, name: str, category: str, count: int = 1,
               review: bool = False) -> None:
        (self.reviews if review else self.errors)[(name, category)] += count

    @staticmethod
    def is_fixture(name: str) -> bool:
        return "/src/test/" in name or name.endswith("_test.go")

    @staticmethod
    def is_upstream(name: str) -> bool:
        return ("licenses/" in name.lower() or name.startswith("gradle/wrapper/")
                or "gradle/wrapper/gradle-wrapper.jar!" in name)

    def inspect_path(self, name: str) -> bool:
        path = PurePosixPath(name)
        if (path.is_absolute() or ".." in path.parts or "\\" in name
                or re.match(r"^[A-Za-z]:", name)):
            self.record(name, "unsafe-path")
            return False
        if any(part.lower() in FORBIDDEN_SEGMENTS for part in path.parts):
            self.record(name, "excluded-artifact")
        if len(path.parts) > 1 and path.parts[0].lower() not in ALLOWED_DIRECTORIES:
            self.record(name, "outside-android-server-scope")
        if path.suffix.lower() in FORBIDDEN_SUFFIXES:
            self.record(name, "excluded-file-type")
        if path.name in {"local.properties", ".env", "id_rsa", "id_ed25519"}:
            self.record(name, "local-configuration-or-secret")
        if path.suffix.lower() == ".jar" and name != "gradle/wrapper/gradle-wrapper.jar":
            self.record(name, "unexpected-binary-archive")
        return True

    def inspect_text(self, name: str, data: bytes, owned: bool = False) -> None:
        # Three views also expose strings embedded in ELF/class/UTF-16 metadata.
        views = [data.decode("utf-8", errors="replace")]
        if b"\x00" in data:
            views.extend(data.decode(enc, errors="ignore") for enc in ("utf-16le", "utf-16be"))
        for category, rule in RULES.items():
            count = max((len(rule.findall(text)) for text in views), default=0)
            if count:
                test_literal = self.is_fixture(name) and category in {
                    "credential-assignment", "private-key-block", "credential-in-url"
                }
                self.record(name, category, count, review=test_literal)
        text = views[0]
        emails = [match for match in EMAIL.finditer(text)
                  if not match[1].lower().endswith((".example", ".test"))
                  and match[1].lower() not in {"example.com", "example.net", "example.org"}]
        if emails:
            self.record(name, "non-example-email", len(emails), review=self.is_upstream(name))
        unapproved_hosts: set[str] = set()
        for match in URL.finditer(text):
            try:
                parsed = urlsplit(match[0])
                host = (parsed.hostname or "").lower()
            except ValueError:
                continue
            if parsed.username or parsed.password:
                self.record(name, "url-embedded-credentials", review=self.is_fixture(name))
            if not DNS_HOST.fullmatch(host) or "." not in host:
                continue  # Incomplete code fragments and UI scheme-only examples.
            reserved = host.endswith((".example", ".test", ".example.com",
                                      ".example.net", ".example.org"))
            if host not in KNOWN_PUBLIC_HOSTS and host not in {
                    "example.com", "example.net", "example.org"} and not reserved:
                unapproved_hosts.add(host)
        if unapproved_hosts:
            self.record(name, "unknown-url-host-requires-review", len(unapproved_hosts),
                        review=self.is_fixture(name) or self.is_upstream(name))
        code_file = PurePosixPath(name).suffix.lower() in OWNED_SUFFIXES | {
            ".xml", ".properties", ".yaml", ".yml"
        }
        # Do not interpret Java namespaces or calls such as logger.info() as DNS.
        host_contexts = [text]
        if code_file:
            host_contexts = [match[0] for match in QUOTED.finditer(text)]
            host_contexts.extend(line for line in text.splitlines()
                                 if line.lstrip().startswith(("//", "#", "*", "<!--")))
        literal_hosts = {match[0].lower() for context in host_contexts
                         for match in HOST_LITERAL.finditer(context)
                         if not context[match.end():].startswith("(")}
        literal_hosts = {host for host in literal_hosts
                         if host not in KNOWN_PUBLIC_HOSTS and host not in {
                             "example.com", "example.net", "example.org"}
                         and not host.startswith(("java.", "javax.", "android.",
                                                  "androidx.", "dev.lan."))
                         and not host.endswith((".example.com", ".example.net", ".example.org"))}
        if literal_hosts:
            self.record(name, "unknown-host-literal-requires-review", len(literal_hosts),
                        review=self.is_fixture(name) or self.is_upstream(name))
        ips: set[str] = set()
        for rule in (IPV4, IPV6):
            for match in rule.finditer(text):
                try:
                    address = ipaddress.ip_address(match[0])
                except ValueError:
                    continue
                if str(address) == "2000::":
                    continue  # IANA Global Unicast range definition, not an endpoint.
                if address.is_global and str(address) not in KNOWN_PUBLIC_RESOLVERS:
                    ips.add(str(address))
        if ips:
            self.record(name, "global-address-requires-review", len(ips),
                        review=self.is_fixture(name) or self.is_upstream(name))
        if owned and PurePosixPath(name).suffix in OWNED_SUFFIXES:
            header = "\n".join(text.splitlines()[:25])
            if ALIAS not in header or "SPDX-License-Identifier: Apache-2.0" not in header:
                self.record(name, "missing-project-author-or-apache-header")
            if "SPDX-License-Identifier: MIT" in header:
                self.record(name, "incorrect-project-license-header")

    def inspect_png(self, name: str, data: bytes) -> None:
        self.png_count += 1
        if not data.startswith(PNG_SIGNATURE):
            self.record(name, "invalid-png")
            return
        offset = 8
        ended = False
        while offset + 12 <= len(data):
            length = struct.unpack_from(">I", data, offset)[0]
            if length > MAX_ENTRY or offset + length + 12 > len(data):
                self.record(name, "invalid-png-chunk")
                return
            kind = data[offset + 4:offset + 8]
            payload = data[offset + 8:offset + 8 + length]
            checksum = struct.unpack_from(">I", data, offset + 8 + length)[0]
            if zlib.crc32(kind + payload) & 0xffffffff != checksum:
                self.record(name, "invalid-png-crc")
            if kind not in PNG_ALLOWED:
                self.record(name, "png-metadata-or-unknown-chunk")
                self.inspect_text(name, payload)
            offset += length + 12
            if kind == b"IEND":
                ended = True
                break
        if not ended or offset != len(data):
            self.record(name, "png-trailing-or-missing-end")

    def inspect_archive(self, name: str, data: bytes, depth: int) -> None:
        if depth >= MAX_NESTING:
            self.record(name, "archive-depth-limit")
            return
        try:
            with zipfile.ZipFile(io.BytesIO(data)) as archive:
                if archive.comment:
                    self.record(name, "archive-comment")
                    self.inspect_text(name, archive.comment)
                seen: set[str] = set()
                for member in archive.infolist():
                    nested = name + "!" + member.filename
                    if member.is_dir():
                        continue
                    if member.filename in seen:
                        self.record(nested, "duplicate-archive-entry")
                    seen.add(member.filename)
                    path = PurePosixPath(member.filename)
                    if path.is_absolute() or ".." in path.parts or "\\" in member.filename:
                        self.record(nested, "unsafe-archive-entry")
                    if member.comment:
                        self.record(nested, "archive-member-comment")
                    if member.file_size > MAX_ENTRY or member.flag_bits & 1:
                        self.record(nested, "oversize-or-encrypted-member")
                        continue
                    self.expanded_members += 1
                    self.inspect_data(nested, archive.read(member), depth + 1)
        except (zipfile.BadZipFile, RuntimeError, OSError):
            self.record(name, "unreadable-archive")

    def inspect_data(self, name: str, data: bytes, depth: int = 0,
                     owned: bool = False) -> None:
        self.byte_count += len(data)
        if len(data) > MAX_ENTRY or self.byte_count > MAX_TOTAL:
            self.record(name, "size-limit")
            return
        if name.lower().endswith(".png"):
            self.inspect_png(name, data)
        elif data.startswith((b"PK\x03\x04", b"PK\x05\x06")):
            self.inspect_archive(name, data, depth)
        else:
            self.inspect_text(name, data, owned=owned)

    def check(self, files: dict[str, bytes]) -> dict[str, object]:
        for missing in sorted(REQUIRED - set(files)):
            self.record(missing, "missing-required-file")
        for name, data in sorted(files.items()):
            self.file_count += 1
            if self.inspect_path(name):
                self.inspect_data(name, data, owned=not self.is_upstream(name))
        license_text = files.get("LICENSE", b"").decode("utf-8", errors="replace")
        if "Apache License" not in license_text or "Version 2.0, January 2004" not in license_text:
            self.record("LICENSE", "missing-full-apache-2-license")
        return {
            "status": "FAIL" if self.errors else "PASS",
            "source_files": self.file_count,
            "expanded_archive_members": self.expanded_members,
            "png_files": self.png_count,
            "errors": self.findings(self.errors),
            "human_review": self.findings(self.reviews),
            "limits": "Pattern checks do not establish absence of all personal data or secrets.",
        }

    @staticmethod
    def findings(items: Counter[tuple[str, str]]) -> list[dict[str, object]]:
        return [{"file": name, "category": category, "count": count}
                for (name, category), count in sorted(items.items())]


def load_files(source: Path) -> dict[str, bytes]:
    if source.is_dir():
        paths = list(source.rglob("*"))
        if any(path.is_symlink() for path in paths):
            raise ValueError("Symlinks are not accepted in a release tree")
        files = {}
        total = 0
        for path in paths:
            if path.is_file():
                length = path.stat().st_size
                total += length
                if length > MAX_ENTRY or total > MAX_TOTAL:
                    raise ValueError("Source size limit")
                files[path.relative_to(source).as_posix()] = path.read_bytes()
        return files
    files: dict[str, bytes] = {}
    with zipfile.ZipFile(source) as archive:
        if archive.comment:
            raise ValueError("Unexpected release ZIP comment")
        members = [member for member in archive.infolist() if not member.is_dir()]
        names = [member.filename for member in members]
        for name in names:
            path = PurePosixPath(name)
            if path.is_absolute() or ".." in path.parts or "\\" in name or re.match(r"^[A-Za-z]:", name):
                raise ValueError("Unsafe release ZIP path")
        first_parts = {PurePosixPath(name).parts[0] for name in names}
        # A deliverable ZIP may have a single enclosing repository directory.
        prefix = next(iter(first_parts)) + "/" if len(first_parts) == 1 and all("/" in n for n in names) else ""
        total = 0
        for member in members:
            name = member.filename[len(prefix):]
            total += member.file_size
            if (name in files or member.file_size > MAX_ENTRY or total > MAX_TOTAL
                    or member.flag_bits & 1 or member.comment):
                raise ValueError("Duplicate, oversized or encrypted ZIP entry")
            files[name] = archive.read(member)
    return files


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", nargs="?", type=Path, default=Path(__file__).resolve().parents[1])
    args = parser.parse_args()
    try:
        report = Audit().check(load_files(args.source))
    except (OSError, ValueError, zipfile.BadZipFile):
        print(json.dumps({"status": "FAIL", "category": "unreadable-source-or-archive"}))
        return 1
    print(json.dumps(report, indent=2, ensure_ascii=True))
    return 0 if report["status"] == "PASS" else 1


if __name__ == "__main__":
    sys.exit(main())
