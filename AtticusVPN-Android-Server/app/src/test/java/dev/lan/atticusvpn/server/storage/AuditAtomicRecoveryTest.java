/* Copyright (c) 2026 TianzeL; SPDX-License-Identifier: Apache-2.0 */
package dev.lan.atticusvpn.server.storage;
import android.app.Application;
import android.content.Context;
import android.util.AtomicFile;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;
@RunWith(RobolectricTestRunner.class)
@Config(sdk={28,35},application=Application.class)
public class AuditAtomicRecoveryTest {
 @Test public void recoverableBackupMustNotBeTreatedAsFreshInstall() throws Exception {
  Context c=RuntimeEnvironment.getApplication();File base=new File(c.getNoBackupFilesDir(),"audit-state.enc");
  File backup=new File(base+".bak");Files.write(backup.toPath(),"NONSECRET-RECOVERABLE-FIXTURE".getBytes(StandardCharsets.UTF_8));
  try {
   boolean exists=new KeystoreBlob(c,"audit-alias","audit-state.enc","audit").exists();
   try(InputStream in=new AtomicFile(base).openRead()){assertEquals('N',in.read());}
   assertTrue("valid AtomicFile backup existed, but repository would enter createFreshState branch",exists);
  }finally{new AtomicFile(base).delete();backup.delete();}
 }
}
