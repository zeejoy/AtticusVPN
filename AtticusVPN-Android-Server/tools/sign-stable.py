#!/usr/bin/env python3
# Copyright (c) 2026 TianzeL; SPDX-License-Identifier: Apache-2.0
"""Sign locally with the owner's existing key, enforcing previous APK identity."""
import argparse
import getpass
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import shutil
import tempfile


def run(*args, env=None):
    result = subprocess.run([str(arg) for arg in args], env=env, capture_output=True, text=True)
    if result.returncode:
        # Signing provider output may include paths or sensitive diagnostic context.
        raise RuntimeError('Android signing/verification tool failed; check inputs and credentials locally')
    return result.stdout


def identity(aapt, apk):
    data = run(aapt, 'dump', 'badging', apk)
    match = re.search(r"package: name='([^']+)' versionCode='([0-9]+)' versionName='([^']+)'", data)
    if not match:
        raise ValueError('APK metadata unavailable')
    return match[1], int(match[2]), match[3]


def signer(apksigner, apk):
    text = run(apksigner, 'verify', '--verbose', '--print-certs', apk)
    digests = re.findall(r'Signer #[0-9]+ certificate SHA-256 digest: ([0-9a-fA-F]{64})(?:\r?\n|$)', text)
    if len(digests) != 1:
        raise ValueError('Expected one verified signer; signing-key rotation needs a separate reviewed workflow')
    return digests[0].lower()


def verify_upgrade(previous, current):
    if previous[0] != 'dev.lan.atticusvpn.server' or current[0] != previous[0]:
        raise ValueError('Package mismatch: never replace the installed stable app with a test package')
    if previous[1] >= current[1] or current[2] != '0.6.0-stable':
        raise ValueError('Expected stable 0.6.0 with an increasing version code')


def publish_exclusive(source, target):
    """Never replace an existing APK, including one created during signing."""
    created = False
    try:
        with target.open('xb') as output:
            created = True
            with source.open('rb') as incoming:
                shutil.copyfileobj(incoming, output)
            output.flush()
            os.fsync(output.fileno())
    except BaseException:
        if created:
            target.unlink(missing_ok=True)
        raise


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--unsigned', type=Path, required=True)
    parser.add_argument('--previous-apk', type=Path, required=True)
    parser.add_argument('--keystore', type=Path, required=True)
    parser.add_argument('--alias', required=True)
    parser.add_argument('--build-tools', type=Path, required=True, help='Android SDK build-tools/36.0.0')
    parser.add_argument('--out', type=Path, default=Path('AtticusVPN-Android-Server-STABLE-0.6.0.apk'))
    args = parser.parse_args()
    if args.out.exists() or args.out.is_symlink():
        raise ValueError('Output already exists; choose another path to preserve it')
    for source in (args.unsigned, args.previous_apk, args.keystore):
        if not source.is_file():
            raise ValueError('An input file is missing')
    if not args.alias.strip() or any(c in args.alias for c in '\r\n\x00'):
        raise ValueError('A nonempty local keystore alias is required')
    windows = os.name == 'nt'
    aapt = args.build_tools / ('aapt.exe' if windows else 'aapt')
    align = args.build_tools / ('zipalign.exe' if windows else 'zipalign')
    sign = args.build_tools / ('apksigner.bat' if windows else 'apksigner')
    for tool in (aapt, align, sign):
        if not tool.is_file():
            raise ValueError('Android build-tools executable is missing')
    if 'application-debuggable' in run(aapt, 'dump', 'badging', args.unsigned):
        raise ValueError('Debuggable APK cannot be distributed as stable')
    old, new = identity(aapt, args.previous_apk), identity(aapt, args.unsigned)
    verify_upgrade(old, new)
    expected = signer(sign, args.previous_apk)
    args.out.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(dir=args.out.parent) as work:
        aligned, signed = Path(work) / 'aligned.apk', Path(work) / 'signed.apk'
        run(align, '-P', '16', '-f', '4', args.unsigned, aligned)
        environment = os.environ.copy()
        environment['ATTICUS_SIGN_STORE_PASS'] = getpass.getpass('Existing keystore password: ')
        environment['ATTICUS_SIGN_KEY_PASS'] = getpass.getpass('Key password (blank = keystore password): ') or environment['ATTICUS_SIGN_STORE_PASS']
        try:
            run(sign, 'sign', '--ks', args.keystore, '--ks-key-alias', args.alias,
                '--ks-pass', 'env:ATTICUS_SIGN_STORE_PASS', '--key-pass', 'env:ATTICUS_SIGN_KEY_PASS',
                '--out', signed, aligned, env=environment)
        finally:
            environment.pop('ATTICUS_SIGN_STORE_PASS', None)
            environment.pop('ATTICUS_SIGN_KEY_PASS', None)
        if signer(sign, signed) != expected:
            raise ValueError('Signer differs from the old APK; output rejected, keep the installed app')
        run(align, '-c', '-P', '16', '4', signed)
        verify_upgrade(old, identity(aapt, signed))
        publish_exclusive(signed, args.out)
    print(json.dumps({'file': str(args.out), 'sha256': hashlib.sha256(args.out.read_bytes()).hexdigest(),
                      'signer_sha256': expected, 'upgrade_metadata_matches': True,
                      'real_device_installation_tested': False}, indent=2))


if __name__ == '__main__':
    main()
