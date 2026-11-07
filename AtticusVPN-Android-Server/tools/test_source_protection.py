#!/usr/bin/env python3
# Copyright (c) 2026 TianzeL; SPDX-License-Identifier: Apache-2.0
"""Exercise standalone Git and content guards with public synthetic placeholders."""
import importlib.util
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest
ROOT=Path(__file__).resolve().parents[1]
spec=importlib.util.spec_from_file_location('audit',ROOT/'tools/verify-public.py')
audit=importlib.util.module_from_spec(spec);spec.loader.exec_module(audit)
PRIVATE=['wg0.conf','nested/server.conf','device.atticus.json','key.properties',
 'nested/signing.properties','release-signing.properties','keystore.properties',
 'server-state.enc','server-state.enc.bak','server-state.enc.new','id_ed25519','nested/id_rsa',
 'release.jks','sign.p12','client.pem','client.crt','client.der','state.db',
 'private/test.txt','secrets/test.txt','credentials.json','backups/snapshot.txt']
class SourceProtectionTest(unittest.TestCase):
 def test_standalone_and_nested_git_ignore_without_parent_help(self):
  for nested in (False,True):
   with self.subTest(nested=nested), tempfile.TemporaryDirectory(prefix='atticus-ignore-') as d:
    base=Path(d);project=base/'project' if nested else base;project.mkdir(exist_ok=True)
    subprocess.run(['git','init','-q',str(base)],check=True)
    shutil.copyfile(ROOT/'.gitignore',project/'.gitignore')
    for name in PRIVATE:
     path=project/name;path.parent.mkdir(parents=True,exist_ok=True);path.write_text('TEST_ONLY_PLACEHOLDER')
     result=subprocess.run(['git','check-ignore','-q',str(path)],cwd=base)
     self.assertEqual(result.returncode,0,name)
    for name in ['app/src/main/java/Example.java','LICENSE','NOTICE','gradle/wrapper/gradle-wrapper.properties','.env.example']:
     path=project/name;path.parent.mkdir(parents=True,exist_ok=True);path.write_text('PUBLIC_TEST_PLACEHOLDER')
     self.assertEqual(subprocess.run(['git','check-ignore','-q',str(path)],cwd=base).returncode,1,name)
 def test_content_guard_rejects_secrets_even_without_sensitive_filename(self):
  scanner=audit.Audit()
  # Construct a synthetic marker; never read deployment secrets.
  marker='-----BEGIN '+'PRIVATE KEY-----'
  scanner.inspect_text('docs/example.md',marker.encode())
  self.assertTrue(scanner.errors)
 def test_content_guard_rejects_sensitive_names(self):
  for name in PRIVATE[:20]:
   scanner=audit.Audit();scanner.inspect_path(name)
   self.assertTrue(scanner.errors,name)
if __name__=='__main__':unittest.main()
