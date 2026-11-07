/* Copyright (c) 2026 TianzeL; SPDX-License-Identifier: Apache-2.0 */
package dev.lan.atticusvpn.server.ui;

import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PersistableBundle;
import android.os.SystemClock;
import android.provider.DocumentsContract;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

import dev.lan.atticusvpn.server.R;
import dev.lan.atticusvpn.server.logging.RuntimeLogCollector;

/** Current process session only; the collector continues while this page is closed. */
public final class RuntimeLogActivity extends SettingsPageActivity {
    private static final int SAVE_LOG = 12001;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService writer = Executors.newSingleThreadExecutor();
    private final PendingLogExport pendingExport = new PendingLogExport();
    private ScrollView scroll;
    private LinearLayout content;
    private LinearLayout log;
    private String displayedLog="";
    private TextView message;
    private CheckBox revealIps;
    private Button copy;
    private Button save;
    private boolean resumed;
    private boolean destroyed;
    private boolean busy;
    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            if (!resumed || destroyed) return;
            refreshLog();
            main.postDelayed(this, 1_000L);
        }
    };
    private final Runnable expireExport = () -> {
        pendingExport.clear();
        if (!destroyed) {
            busy = false;
            message.setText(R.string.runtime_log_export_expired);
            updateControls();
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        content = createPage(R.string.runtime_log_title);
        scroll = (ScrollView) content.getParent();
        addText(content, getString(R.string.runtime_log_intro), 15);
        addText(content, getString(R.string.runtime_log_privacy), 14);
        revealIps = new CheckBox(this);
        revealIps.setText(R.string.runtime_log_show_full_ips);
        revealIps.setTextColor(getColor(R.color.black));
        revealIps.setSaveEnabled(false);
        revealIps.setChecked(false);
        content.addView(revealIps);
        revealIps.setOnCheckedChangeListener((button, checked) -> {
            // Immediately clear the previous privacy mode, including any unmasked text.
            displayedLog="";if(log!=null)log.removeAllViews();
            RuntimeLogCollector.current().setViewing(resumed,checked);
            refreshLog();
            message.setText(checked ? R.string.runtime_log_full_ip_warning : R.string.runtime_log_masked);
        });
        copy = addButton(content, R.string.runtime_log_copy);
        copy.setOnClickListener(view -> copyDisplayedLog());
        save = addButton(content, R.string.runtime_log_save);
        save.setOnClickListener(view -> requestSave());
        message = addText(content, getString(R.string.runtime_log_masked), 14);
        log = new LinearLayout(this);log.setOrientation(LinearLayout.VERTICAL);
        log.setSaveEnabled(false);content.addView(log);
        refreshLog();
    }

    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        RuntimeLogCollector.current().setViewing(true,revealIps.isChecked());
        main.removeCallbacks(refresh);
        main.post(refresh);
    }

    @Override protected void onPause() {
        resumed = false;
        RuntimeLogCollector.current().setViewing(false,false);
        main.removeCallbacks(refresh);
        super.onPause();
    }

    private void refreshLog() {
        if (destroyed || log == null) return;
        String text = RuntimeLogCollector.current().snapshotText(revealIps.isChecked());
        if (text == null || text.isEmpty()) text = getString(R.string.runtime_log_waiting);
        if (text.equals(displayedLog)) return;
        int position = scroll.getScrollY();
        boolean atBottom = !displayedLog.isEmpty()
                && position >= Math.max(0, content.getHeight() - scroll.getHeight()) - dp(32);
        // Separate device blocks retain their TextView/layout when only other rows change.
        String[] blocks=text.split("(?m)(?=^#[0-9]+ )");
        while(log.getChildCount()>blocks.length)log.removeViewAt(log.getChildCount()-1);
        for(int i=0;i<blocks.length;i++) {
            TextView row;
            if(i<log.getChildCount())row=(TextView)log.getChildAt(i);
            else {row=addText(log,"",12);row.setTypeface(Typeface.MONOSPACE);row.setSaveEnabled(false);row.setFreezesText(false);}
            if(!blocks[i].contentEquals(row.getText()))row.setText(blocks[i]);
        }
        displayedLog=text;
        scroll.post(() -> {
            if (destroyed) return;
            scroll.scrollTo(0, atBottom ? Math.max(0, content.getHeight() - scroll.getHeight()) : position);
        });
    }

    private String displayedSnapshot() {
        return displayedLog;
    }

    private void copyDisplayedLog() {
        if (busy || destroyed) return;
        try {
            ClipboardManager clipboard = getSystemService(ClipboardManager.class);
            if (clipboard == null) throw new IllegalStateException();
            ClipData clip = ClipData.newPlainText(getString(R.string.runtime_log_title), displayedSnapshot());
            if (Build.VERSION.SDK_INT >= 33) {
                PersistableBundle extras = new PersistableBundle();
                extras.putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true);
                clip.getDescription().setExtras(extras);
            }
            clipboard.setPrimaryClip(clip);
            message.setText(R.string.runtime_log_copied);
        } catch (RuntimeException ignored) {
            message.setText(R.string.runtime_log_copy_failed);
        }
    }

    private void requestSave() {
        if (busy || destroyed) return;
        try {
            pendingExport.capture(displayedSnapshot(), SystemClock.elapsedRealtime());
            busy = true;
            updateControls();
            main.postDelayed(expireExport, PendingLogExport.TTL_MILLIS);
            String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(new Date());
            startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT)
                    .addCategory(Intent.CATEGORY_OPENABLE).setType("text/plain")
                    .putExtra(Intent.EXTRA_TITLE, "AtticusVPN-" + dev.lan.atticusvpn.server.BuildConfig.VERSION_NAME + "-session-" + stamp + ".txt"), SAVE_LOG);
        } catch (RuntimeException ignored) {
            pendingExport.clear();
            main.removeCallbacks(expireExport);
            busy = false;
            message.setText(R.string.runtime_log_save_failed);
            updateControls();
        }
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != SAVE_LOG) return;
        main.removeCallbacks(expireExport);
        byte[] snapshot = pendingExport.take(SystemClock.elapsedRealtime());
        Uri uri = data == null ? null : data.getData();
        if (result != RESULT_OK || uri == null) {
            if (snapshot != null) Arrays.fill(snapshot, (byte) 0);
            busy = false;
            updateControls();
            return;
        }
        if (snapshot == null) {
            deleteEmptyDocument(uri);
            busy = false;
            message.setText(R.string.runtime_log_export_expired);
            updateControls();
            return;
        }
        busy = true;
        updateControls();
        try {
            writer.execute(() -> {
                boolean success = false;
                try (OutputStream stream = getContentResolver().openOutputStream(uri, "wt")) {
                    if (stream == null) throw new java.io.IOException();
                    stream.write(snapshot);
                    stream.flush();
                    success = true;
                } catch (Exception ignored) {
                    // Fixed UI message only; provider errors may contain private paths.
                    try { DocumentsContract.deleteDocument(getContentResolver(), uri); }
                    catch (Exception unsupported) { /* Provider may refuse deletion. */ }
                } finally {
                    Arrays.fill(snapshot, (byte) 0);
                }
                boolean saved = success;
                main.post(() -> {
                    if (destroyed) return;
                    busy = false;
                    message.setText(saved ? R.string.runtime_log_saved : R.string.runtime_log_save_failed);
                    updateControls();
                });
            });
        } catch (RejectedExecutionException ignored) {
            Arrays.fill(snapshot, (byte) 0);
            deleteEmptyDocument(uri);
            busy = false;
            message.setText(R.string.runtime_log_save_failed);
            updateControls();
        }
    }

    private void deleteEmptyDocument(Uri uri) {
        // The SAF provider created a new document for this request. Never touch
        // any unrelated file; cleanup is best effort and runs off the UI thread.
        try {
            writer.execute(() -> {
                try { DocumentsContract.deleteDocument(getContentResolver(), uri); }
                catch (Exception unsupported) { /* Fixed UI message only. */ }
            });
        } catch (RejectedExecutionException ignored) { /* Activity is closing. */ }
    }

    private void updateControls() {
        copy.setEnabled(!busy);
        save.setEnabled(!busy);
        revealIps.setEnabled(!busy);
    }

    @Override protected void onDestroy() {
        destroyed = true;
        resumed = false;
        RuntimeLogCollector.current().setViewing(false,false);
        main.removeCallbacks(refresh);
        main.removeCallbacks(expireExport);
        pendingExport.clear();
        if (log != null) log.removeAllViews();
        displayedLog="";
        writer.shutdown();
        super.onDestroy();
    }
}
