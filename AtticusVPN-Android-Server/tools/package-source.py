#!/usr/bin/env python3
# Copyright (c) 2026 TianzeL; SPDX-License-Identifier: Apache-2.0
"""Package audited source from a Git checkout; never include ignored build/secret files.

This WireGuard-only stable archive excludes upstream download/build caches.
"""
import argparse
import hashlib
import importlib.util
import json
from pathlib import Path
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--out', type=Path, required=True)
    parser.add_argument('--report', type=Path, required=True)
    args = parser.parse_args()
    names = subprocess.check_output(
        ['git', 'ls-files', '--cached', '--others', '--exclude-standard', '-z'], cwd=ROOT
    ).decode('utf-8').split('\0')
    files = {}
    for name in sorted(set(filter(None, names))):
        file = ROOT / name
        if file.is_symlink() or not file.is_file():
            raise SystemExit('Source entry must be a regular existing file: ' + name)
        files[name] = file.read_bytes()
    spec = importlib.util.spec_from_file_location('release_audit', ROOT / 'tools/verify-public.py')
    audit = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(audit)
    result = audit.Audit().check(files)
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(json.dumps(result, indent=2) + '\n')
    if result['status'] != 'PASS':
        raise SystemExit('Source audit failed; inspect report categories before packaging')
    args.out.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(args.out, 'w', compression=zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
        for name, data in files.items():
            info = zipfile.ZipInfo('AtticusVPN-Android-Server-STABLE-0.6.0/' + name, (2026, 10, 25, 0, 0, 0))
            info.create_system = 3
            mode = 0o755 if (ROOT / name).stat().st_mode & 0o111 else 0o644
            info.external_attr = (0o100000 | mode) << 16
            info.compress_type = zipfile.ZIP_DEFLATED
            archive.writestr(info, data)
    with zipfile.ZipFile(args.out) as archive:
        if archive.testzip() is not None:
            raise SystemExit('Source ZIP CRC failure')
        for name, data in files.items():
            if archive.read('AtticusVPN-Android-Server-STABLE-0.6.0/' + name) != data:
                raise SystemExit('Source ZIP content mismatch')
    print(json.dumps({'source_files': len(files), 'bytes': args.out.stat().st_size,
                      'sha256': hashlib.sha256(args.out.read_bytes()).hexdigest(),
                      'status': 'PASS'}))


if __name__ == '__main__':
    main()
