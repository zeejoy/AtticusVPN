# Copyright (c) 2026 TianzeL; SPDX-License-Identifier: Apache-2.0
import importlib.util
from pathlib import Path
import unittest
import tempfile
import subprocess
from unittest.mock import patch
spec=importlib.util.spec_from_file_location('sign_stable',Path(__file__).with_name('sign-stable.py'))
signing=importlib.util.module_from_spec(spec);spec.loader.exec_module(signing)
class UpgradePolicyTest(unittest.TestCase):
 def test_stable_upgrade_allowed(self):
  signing.verify_upgrade(('dev.lan.atticusvpn.server',19,'0.5.8'),('dev.lan.atticusvpn.server',20,'0.6.0-stable'))
 def test_wrong_channels_and_downgrades_refused(self):
  old=('dev.lan.atticusvpn.server',19,'0.5.8')
  for new in [('dev.lan.atticusvpn.server.debug',20,'0.6.0-stable'),('dev.lan.atticusvpn.server',19,'0.6.0-stable'),('dev.lan.atticusvpn.server',20,'0.6.0-test-debug')]:
   with self.subTest(new=new),self.assertRaises(ValueError):signing.verify_upgrade(old,new)
 def test_test_apk_never_serves_as_upgrade_baseline(self):
  with self.assertRaises(ValueError):signing.verify_upgrade(('dev.lan.atticusvpn.server.ipsectest',26,'1.3.1'),('dev.lan.atticusvpn.server',27,'0.6.0-stable'))
 def test_unsigned_or_multiple_signers_refused(self):
  for output in ['', 'Signer #1 certificate SHA-256 digest: aa\nSigner #2 certificate SHA-256 digest: bb']:
   with patch.object(signing,'run',return_value=output),self.assertRaises(ValueError):signing.signer('tool','apk')
 def test_signer_normalized(self):
  with patch.object(signing,'run',return_value='Signer #1 certificate SHA-256 digest: '+('AABB'*16)):
   self.assertEqual('aabb'*16,signing.signer('tool','apk'))
 def test_publish_cannot_overwrite_output_created_during_signing(self):
  with tempfile.TemporaryDirectory() as d:
   src=Path(d)/'new.apk';dst=Path(d)/'old.apk'
   src.write_bytes(b'TEST_ONLY_NEW');dst.write_bytes(b'TEST_ONLY_EXISTING')
   with self.assertRaises(FileExistsError):signing.publish_exclusive(src,dst)
   self.assertEqual(dst.read_bytes(),b'TEST_ONLY_EXISTING')
 def test_publish_creates_exact_output(self):
  with tempfile.TemporaryDirectory() as d:
   src=Path(d)/'new.apk';dst=Path(d)/'output.apk';src.write_bytes(b'TEST_ONLY_APK')
   signing.publish_exclusive(src,dst);self.assertEqual(src.read_bytes(),dst.read_bytes())
 def test_tool_errors_never_echo_sensitive_output(self):
  result=subprocess.CompletedProcess(['tool'],1,stdout='TEST_ONLY_PRIVATE',stderr='TEST_ONLY_PASSWORD')
  with patch.object(signing.subprocess,'run',return_value=result):
   with self.assertRaises(RuntimeError) as error:signing.run('tool')
  self.assertNotIn('TEST_ONLY',str(error.exception))
 def test_malformed_metadata_and_short_certificate_rejected(self):
  with patch.object(signing,'run',return_value="package: name='bad' versionCode='x'"):
   with self.assertRaises(ValueError):signing.identity('tool','apk')
  with patch.object(signing,'run',return_value='Signer #1 certificate SHA-256 digest: aa'):
   with self.assertRaises(ValueError):signing.signer('tool','apk')
if __name__=='__main__':unittest.main()
