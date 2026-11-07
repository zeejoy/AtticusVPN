/* Copyright (c) 2026 TianzeL; SPDX-License-Identifier: Apache-2.0 */
package dev.lan.atticusvpn.server.storage;

import android.app.Application;
import android.content.Context;
import android.util.AtomicFile;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.*;
import java.security.cert.Certificate;
import java.util.*;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Actual AtomicFile recovery and AES-GCM with a TEST ONLY in-memory key provider.
 * Does not claim hardware AndroidKeyStore or physical crash validation. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk={24,26,28,35}, application=Application.class)
public final class KeystoreBlobRecoveryTest {
    private static final SecretKeySpec TEST_KEY = new SecretKeySpec(new byte[32], "AES");
    private Context context;

    @Before public void setup() {
        context = RuntimeEnvironment.getApplication();
        TestStore.available = true; TestStore.wrongKey = false; TestStore.failRead = false;
        TestStore.deletions.clear();
        Security.insertProviderAt(new TestProvider(), 1);
    }
    @After public void cleanup() {
        Security.removeProvider("AtticusRecoveryTest");
        for (String name : new String[]{"audit.enc", "server-state.enc"}) {
            new AtomicFile(base(name)).delete();
            new File(base(name) + ".bak").delete();
            new File(base(name) + ".new").delete();
        }
    }
    private File base(String name) { return new File(context.getNoBackupFilesDir(), name); }
    private KeystoreBlob blob() { return new KeystoreBlob(context,"TEST_ONLY_ALIAS","audit.enc","audit"); }
    private byte[] frame(byte[] plain, String purpose) throws Exception {
        // Random IV even for fixtures; never export or install these test credentials.
        byte[] iv = new byte[12]; new SecureRandom().nextBytes(iv);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE,TEST_KEY,new GCMParameterSpec(128,iv));
        cipher.updateAAD(("AtticusVPN/"+purpose+"/v1").getBytes(StandardCharsets.UTF_8));
        byte[] encrypted = cipher.doFinal(plain);
        return ByteBuffer.allocate(6+iv.length+encrypted.length)
                .put(new byte[]{'A','T','S','V',1,12}).put(iv).put(encrypted).array();
    }
    private void write(String suffix, byte[] bytes) throws IOException {
        Files.write(new File(base("audit.enc")+suffix).toPath(),bytes);
    }

    @Test public void backupOnlyRecoversAndDecrypts() throws Exception {
        byte[] expected = "TEST ONLY recovery payload".getBytes(StandardCharsets.UTF_8);
        write(".bak",frame(expected,"audit"));
        assertTrue(blob().exists());
        assertArrayEquals(expected,blob().read());
        assertTrue(base("audit.enc").isFile());
        assertFalse(new File(base("audit.enc")+".bak").exists());
    }
    @Test @Config(shadows=AndroidAtomicRename.class)
    public void backupWinsOverInterruptedBaseWrite() throws Exception {
        byte[] expected = "committed TEST ONLY state".getBytes(StandardCharsets.UTF_8);
        byte[] committed = frame(expected,"audit");
        write(".bak",committed);write("",new byte[]{1,2});
        assertArrayEquals(expected,blob().read());
        assertArrayEquals(committed,Files.readAllBytes(base("audit.enc").toPath()));
        assertFalse(new File(base("audit.enc")+".bak").exists());
    }

    /** Test-only API 35 host adaptation, not a replacement recovery algorithm.
     * Android AtomicFile expects POSIX atomic replacement from File.renameTo;
     * Windows File.renameTo refuses an existing target. Only that successful
     * regular-file rename is adapted, preserving the real openRead/AES path.
     * Other hosts and failure shapes still use the original framework helper. */
    @org.robolectric.annotation.Implements(AtomicFile.class)
    public static class AndroidAtomicRename {
        @org.robolectric.annotation.Implementation(minSdk=35,maxSdk=35)
        protected static void rename(File source, File target) throws IOException {
            if (File.separatorChar == '\\' && source.isFile() && target.isFile()) {
                Files.move(source.toPath(),target.toPath(),
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } else {
                org.robolectric.shadow.api.Shadow.directlyOn(AtomicFile.class,"rename",
                        org.robolectric.util.ReflectionHelpers.ClassParameter.from(File.class,source),
                        org.robolectric.util.ReflectionHelpers.ClassParameter.from(File.class,target));
            }
        }
    }
    @Test public void committedBaseSurvivesUnfinishedNewFile() throws Exception {
        byte[] expected = "committed TEST ONLY state".getBytes(StandardCharsets.UTF_8);
        write("",frame(expected,"audit"));write(".new",new byte[]{1,2});
        assertArrayEquals(expected,blob().read());
    }
    @Test public void unfinishedNewFileAloneFailsClosed() throws Exception {
        write(".new",new byte[]{1,2});assertTrue(blob().exists());
        assertThrows(Exception.class,()->blob().read());
    }
    @Test public void missingFilesReturnNull() throws Exception {
        assertFalse(blob().exists());assertNull(blob().read());
    }
    @Test public void unexpectedDirectoryDoesNotBecomeFreshState() {
        assertTrue(base("audit.enc").mkdir());assertTrue(blob().exists());
        assertThrows(Exception.class,()->blob().read());
    }
    @Test public void failedAtomicWriteKeepsCommittedCiphertext() throws Exception {
        byte[] expected = "committed TEST ONLY state".getBytes(StandardCharsets.UTF_8);
        write("",frame(expected,"audit"));AtomicFile file=new AtomicFile(base("audit.enc"));
        FileOutputStream output=file.startWrite();output.write(new byte[]{1,2});file.failWrite(output);
        assertArrayEquals(expected,blob().read());
    }
    @Test public void corruptedBackupDoesNotInitializeOrOverwriteRepository() throws Exception {
        byte[] corrupted=new byte[]{'A','T','S','V',1,12};
        Files.write(new File(base("server-state.enc")+".bak").toPath(),corrupted);
        EncryptedStateRepository repository=new EncryptedStateRepository(context);
        assertThrows(Exception.class,repository::initialize);
        assertThrows(IllegalStateException.class,repository::state);
        assertArrayEquals(corrupted,Files.readAllBytes(new File(base("server-state.enc")+".bak").toPath()));
        assertThrows(Exception.class,new EncryptedStateRepository(context)::initialize);
        assertFalse(base("server-state.enc").exists());
    }
    @Test public void repositoryRecoveryPreservesIdentityPeersAndDdns() throws Exception {
        // These repeated-byte keys are synthetic TEST ONLY values, not credentials.
        byte[] keyBytes=new byte[32];Arrays.fill(keyBytes,(byte)7);
        String key=Base64.getEncoder().encodeToString(keyBytes);
        JSONObject peer=new JSONObject().put("id","1234567890abcdef1234567890abcdef")
                .put("name","TEST_ONLY_PEER").put("clientId",2).put("publicKey",key)
                .put("presharedKey",key).put("enabled",true).put("sealed",false)
                .put("createdAt",1000).put("sealedAt",0);
        JSONObject state=new JSONObject().put("schema",1).put("serverPrivateKey",key)
                .put("serverPublicKey",key).put("endpointHost","vpn.example.test")
                .put("listenPort",51820).put("mtu",1380).put("dnsEnabled",true)
                .put("dnsMode","AUTOMATIC").put("customDns","").put("ddnsProvider","NONE")
                .put("ddnsHostname","vpn.example.test").put("ddnsAccount","")
                .put("ddnsSecret","TEST_ONLY_SECRET").put("ddnsZone","").put("ddnsRecord","")
                .put("ddnsCustomUrl","").put("peers",new JSONArray().put(peer));
        byte[] encrypted=frame(state.toString().getBytes(StandardCharsets.UTF_8),"server-state");
        Files.write(new File(base("server-state.enc")+".bak").toPath(),encrypted);
        EncryptedStateRepository repository=new EncryptedStateRepository(context);repository.initialize();
        assertTrue(key.equals(repository.state().serverPrivateKey));
        assertTrue(key.equals(repository.state().serverPublicKey));
        assertEquals(1,repository.state().peers().size());
        assertEquals("TEST_ONLY_PEER",repository.state().peers().get(0).name());
        assertTrue("TEST_ONLY_SECRET".equals(repository.state().ddnsSecret));
        assertArrayEquals(encrypted,Files.readAllBytes(base("server-state.enc").toPath()));
    }

    @Test public void freshInitializationCommitsAndRestartsWithoutIdentityChange() throws Exception {
        EncryptedStateRepository first = new EncryptedStateRepository(context);
        first.initialize();
        String publicKey = first.state().serverPublicKey;
        byte[] committed = Files.readAllBytes(base("server-state.enc").toPath());
        EncryptedStateRepository second = new EncryptedStateRepository(context);
        second.initialize();
        assertEquals(publicKey, second.state().serverPublicKey);
        assertArrayEquals(committed, Files.readAllBytes(base("server-state.enc").toPath()));
    }
    @Test public void interruptedWriteBeforeAndAfterBytesRecoversCommittedState() throws Exception {
        byte[] expected = "TEST_ONLY_COMMITTED".getBytes(StandardCharsets.UTF_8);
        for (boolean writeBytes : new boolean[]{false,true}) {
            new AtomicFile(base("audit.enc")).delete();
            write("",frame(expected,"audit"));
            FileOutputStream stream = new AtomicFile(base("audit.enc")).startWrite();
            if(writeBytes) stream.write(new byte[]{1,2,3});
            stream.close(); // Simulated process exit; no finishWrite/failWrite.
            assertArrayEquals(expected,blob().read());
        }
    }
    @Test public void missingOrWrongKeyNeverOverwritesRecoveryCandidates() throws Exception {
        byte[] encrypted = frame("TEST_ONLY".getBytes(StandardCharsets.UTF_8),"audit");
        for (boolean missing : new boolean[]{true,false}) {
            write("",encrypted); write(".bak",encrypted); write(".new",new byte[]{9});
            TestStore.available = !missing; TestStore.wrongKey = !missing;
            assertThrows(Exception.class,()->blob().read());
            assertThrows(Exception.class,()->blob().read());
            assertArrayEquals(encrypted,Files.readAllBytes(base("audit.enc").toPath()));
            assertArrayEquals(encrypted,Files.readAllBytes(new File(base("audit.enc")+".bak").toPath()));
            assertTrue(TestStore.deletions.isEmpty());
        }
    }
    @Test public void corruptedBackupPreservesReadableBaseAndDoesNotFallbackSilently() throws Exception {
        byte[] valid = frame("TEST_ONLY".getBytes(StandardCharsets.UTF_8),"audit");
        write("",valid); write(".bak",new byte[]{1,2});
        assertThrows(Exception.class,()->blob().read());
        assertArrayEquals(valid,Files.readAllBytes(base("audit.enc").toPath()));
        assertArrayEquals(new byte[]{1,2},Files.readAllBytes(new File(base("audit.enc")+".bak").toPath()));
    }
    @Test public void failedFirstCommitDoesNotEnableKeyCleanup() throws Exception {
        String peerId = "1234567890abcdef1234567890abcdef";
        File peerFile = base("peer-"+peerId+".export.enc");
        byte[] oldKey = frame("TEST_ONLY_OLD_PEER".getBytes(StandardCharsets.UTF_8),"peer-export/"+peerId);
        Files.write(peerFile.toPath(),oldKey);
        try {
            TestStore.failRead = true;
            EncryptedStateRepository repository = new EncryptedStateRepository(context);
            assertThrows(Exception.class,repository::initialize);
            assertThrows(IllegalStateException.class,repository::state);
            assertThrows(IllegalStateException.class,repository::cleanupRevokedKeys);
            assertArrayEquals(oldKey,Files.readAllBytes(peerFile.toPath()));
            assertTrue(TestStore.deletions.isEmpty());
        } finally { peerFile.delete(); }
    }

    @Test @Config(shadows=RejectedCommit.class)
    public void silentAtomicCommitFailureDoesNotPublishOrLoseCommittedBytes() throws Exception {
        byte[] old = frame("TEST_ONLY_COMMITTED".getBytes(StandardCharsets.UTF_8),"audit");
        write("",old);
        assertThrows(Exception.class,()->blob().write("TEST_ONLY_NEW".getBytes(StandardCharsets.UTF_8)));
        assertArrayEquals(old,Files.readAllBytes(base("audit.enc").toPath()));
        EncryptedStateRepository repository=new EncryptedStateRepository(context);
        assertThrows(Exception.class,repository::initialize);
        assertThrows(IllegalStateException.class,repository::state);
        assertThrows(IllegalStateException.class,repository::cleanupRevokedKeys);
    }
    @org.robolectric.annotation.Implements(AtomicFile.class)
    public static class RejectedCommit {
        @org.robolectric.annotation.RealObject private AtomicFile actual;
        @org.robolectric.annotation.Implementation
        protected void finishWrite(FileOutputStream stream) {actual.failWrite(stream);}
    }

    public static final class TestProvider extends Provider {
        public TestProvider() {
            super("AtticusRecoveryTest",1.0,"TEST ONLY ephemeral key store");
            put("KeyStore.AndroidKeyStore",TestStore.class.getName());
        }
    }
    public static final class TestStore extends KeyStoreSpi {
        public static boolean available = true, wrongKey, failRead;
        public static final Set<String> deletions = new HashSet<>();
        public Key engineGetKey(String a,char[] p){
            if(failRead) throw new SecurityException("TEST_ONLY_READ_FAILURE");
            return wrongKey ? new SecretKeySpec(new byte[16], "AES") : TEST_KEY;
        }
        public boolean engineContainsAlias(String a){return available;}
        public boolean engineIsKeyEntry(String a){return true;}
        public void engineLoad(InputStream i,char[] p){}
        public void engineStore(OutputStream o,char[] p){throw new UnsupportedOperationException();}
        public Certificate[] engineGetCertificateChain(String a){return null;}
        public Certificate engineGetCertificate(String a){return null;}
        public Date engineGetCreationDate(String a){return null;}
        public void engineSetKeyEntry(String a,Key k,char[] p,Certificate[] c){throw new UnsupportedOperationException();}
        public void engineSetKeyEntry(String a,byte[] k,Certificate[] c){throw new UnsupportedOperationException();}
        public void engineSetCertificateEntry(String a,Certificate c){throw new UnsupportedOperationException();}
        public void engineDeleteEntry(String a){deletions.add(a);}
        public Enumeration<String> engineAliases(){return Collections.emptyEnumeration();}
        public int engineSize(){return 1;}
        public boolean engineIsCertificateEntry(String a){return false;}
        public String engineGetCertificateAlias(Certificate c){return null;}
    }
}
