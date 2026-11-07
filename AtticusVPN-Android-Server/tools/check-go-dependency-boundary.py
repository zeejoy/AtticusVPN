#!/usr/bin/env python3
# Copyright (c) 2026 TianzeL; SPDX-License-Identifier: Apache-2.0
"""Reject imports of unmaintained x/crypto OpenPGP; does not replace vulnerability scans."""
import argparse
import json
from pathlib import Path
import subprocess

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--go',default='go')
    args=parser.parse_args()
    root=Path(__file__).resolve().parents[1]/'native-engine'
    result=subprocess.run([args.go,'list','-deps','-test','./...'],cwd=root,capture_output=True,text=True)
    if result.returncode:
        raise SystemExit('Dependency graph unavailable; boundary check NOT verified')
    imports=result.stdout.splitlines()
    blocked=[p for p in imports if p=='golang.org/x/crypto/openpgp' or p.startswith('golang.org/x/crypto/openpgp/')]
    print(json.dumps({'imports':len(imports),'openpgp_imports':blocked,'status':'FAIL' if blocked else 'PASS'}))
    if blocked:raise SystemExit(1)
if __name__=='__main__':main()
