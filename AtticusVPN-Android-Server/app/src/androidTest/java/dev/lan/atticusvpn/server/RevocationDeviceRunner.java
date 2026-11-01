/* Copyright (c) 2026 TianzeL; SPDX-License-Identifier: Apache-2.0 */
package dev.lan.atticusvpn.server;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.ResultReceiver;
import android.net.ConnectivityManager;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.io.FileInputStream;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;
import dev.lan.atticusvpn.server.config.WireGuardConfigFactory;
import dev.lan.atticusvpn.server.core.SecretBytes;
import dev.lan.atticusvpn.server.model.StoredPeer;
import dev.lan.atticusvpn.server.runtime.ReflectiveNativeEngine;
import dev.lan.atticusvpn.server.runtime.RuntimeStatus;
import dev.lan.atticusvpn.server.runtime.RuntimeStatusStore;
import dev.lan.atticusvpn.server.service.AtticusServerService;
import dev.lan.atticusvpn.server.storage.EncryptedStateRepository;
import dev.lan.atticusvpn.server.ui.MainActivity;

/** Opt-in real-device fixture. Configuration files stay in this isolated test app's private files. */
public final class RevocationDeviceRunner extends Instrumentation {
    @Override public void onCreate(Bundle arguments) { super.onCreate(arguments); start(); }

    @Override public void onStart() {
        Context context = getTargetContext();
        Bundle result = new Bundle();
        if (!context.getPackageName().equals("dev.lan.atticusvpn.server.revocationtest")) {
            result.putString("result", "REFUSED_NON_ISOLATED_PACKAGE");
            finish(Activity.RESULT_CANCELED, result); return;
        }
        File phase = new File(context.getFilesDir(), "revocation-audit.phase");
        File command = new File(context.getFilesDir(), "revocation-audit.command");
        File oldConfig = new File(context.getFilesDir(), "revocation-audit-old.conf");
        File controlConfig = new File(context.getFilesDir(), "revocation-audit-control.conf");
        String stage = "REPOSITORY";
        DeviceDatagramRelay relay = null;
        try {
            EncryptedStateRepository repository = ((AtticusServerApplication)
                    context.getApplicationContext()).awaitRepository();
            if (!repository.state().peers().isEmpty()) throw new IllegalStateException("FIXTURE_NOT_EMPTY");
            stage = "SETTINGS";
            synchronized (repository) {
                repository.state().listenPort = 49518;
                repository.state().ddnsProvider = "disabled";
                repository.saveSettings();
            }
            stage = "PEERS";
            StoredPeer old = repository.addPeer("revoke-old");
            StoredPeer control = repository.addPeer("revoke-control");
            command.delete();
            stage = "FOREGROUND_ACTIVITY";
            startActivitySync(new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            stage = "SERVICE_START";
            AtticusServerService.start(context);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(45);
            while (RuntimeStatusStore.current().phase() != RuntimeStatus.Phase.RUNNING) {
                if (System.nanoTime() > deadline) throw new IllegalStateException("SERVICE_NOT_RUNNING");
                Thread.sleep(200);
            }
            stage = "EXPORT";
            String endpoint = RuntimeStatusStore.current().endpointAddress();
            ConnectivityManager connectivity = context.getSystemService(ConnectivityManager.class);
            relay = new DeviceDatagramRelay(connectivity.getActiveNetwork(), endpoint);
            writeConfiguration(repository, old, endpoint, oldConfig);
            writeConfiguration(repository, control, endpoint, controlConfig);
            write(phase, "READY");
            stage = "HOST_COORDINATION";
            boolean revoked = false;
            deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(8);
            while (System.nanoTime() < deadline) {
                if (command.isFile()) {
                    byte[] input = new byte[32];
                    int count;
                    try (FileInputStream stream = new FileInputStream(command)) { count = stream.read(input); }
                    String action = count > 0 ? new String(input, 0, count, StandardCharsets.US_ASCII).trim() : "";
                    command.delete();
                    if ("DELETE_OLD".equals(action) && !revoked) {
                        stage = "REVOCATION";
                        CountDownLatch acknowledged = new CountDownLatch(1);
                        int[] response = {0};
                        AtticusServerService.revokePeer(context, old.id(), new ResultReceiver(null) {
                            @Override protected void onReceiveResult(int code, Bundle data) {
                                response[0] = code; acknowledged.countDown();
                            }
                        });
                        if (!acknowledged.await(30, TimeUnit.SECONDS)
                                || response[0] != AtticusServerService.REVOCATION_APPLIED) {
                            throw new IllegalStateException("REVOCATION_NOT_ACKNOWLEDGED");
                        }
                        JSONObject nativeStatus = new JSONObject(new ReflectiveNativeEngine().status());
                        if (repository.state().peers().size() != 1
                                || !nativeStatus.getBoolean("running")
                                || nativeStatus.getJSONArray("peers").length() != 1) {
                            throw new IllegalStateException("NATIVE_AUTHORIZATION_MISMATCH");
                        }
                        revoked = true;
                        write(phase, "REVOKED_ENGINE_RUNNING_ONE_PEER");
                    } else if ("FINISH".equals(action) && revoked) {
                        result.putString("result", "REVOKED_ENGINE_RUNNING_ONE_PEER");
                        finish(Activity.RESULT_OK, result); return;
                    }
                }
                Thread.sleep(200);
            }
            throw new IllegalStateException("HOST_TEST_TIMEOUT");
        } catch (Exception failure) {
            // Exception text could include configuration paths. Output only the fixed test state.
            try { write(phase, "DEVICE_TEST_FAILED"); } catch (Exception ignored) { }
            result.putString("result", "DEVICE_TEST_FAILED");
            result.putString("stage", stage);
            result.putString("errorType", failure.getClass().getSimpleName());
            finish(Activity.RESULT_CANCELED, result);
        } finally {
            if (relay != null) relay.close();
            AtticusServerService.stop(context);
            oldConfig.delete(); controlConfig.delete(); command.delete();
        }
    }

    private static void writeConfiguration(EncryptedStateRepository repository, StoredPeer peer,
            String endpoint, File destination) throws Exception {
        try (SecretBytes bytes = new WireGuardConfigFactory().renderClientConfiguration(
                repository.state(), peer, repository.readClientPrivateKey(peer.id()), endpoint)) {
            byte[] raw = bytes.copy();
            try (FileOutputStream stream = new FileOutputStream(destination)) { stream.write(raw); }
            finally { Arrays.fill(raw, (byte) 0); }
        }
    }

    private static void write(File file, String value) throws Exception {
        try (FileOutputStream stream = new FileOutputStream(file)) {
            stream.write(value.getBytes(StandardCharsets.US_ASCII));
        }
    }
}
