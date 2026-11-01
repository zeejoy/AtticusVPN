/* Copyright (c) 2026 TianzeL; SPDX-License-Identifier: Apache-2.0 */
package dev.lan.atticusvpn.server.ui;

import android.Manifest;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.sqlite.SQLiteFullException;
import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PersistableBundle;
import android.system.ErrnoException;
import android.system.OsConstants;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import dev.lan.atticusvpn.server.AtticusServerApplication;
import dev.lan.atticusvpn.server.R;
import dev.lan.atticusvpn.server.core.ClientExportOptions;
import dev.lan.atticusvpn.server.core.SecretBytes;
import dev.lan.atticusvpn.server.export.ExportFileNames;
import dev.lan.atticusvpn.server.export.PeerConfigurationExport;
import dev.lan.atticusvpn.server.export.QrCodeGallerySaver;
import dev.lan.atticusvpn.server.export.QrCodeGenerator;

/**
 * Peer 的统一导出页。Intent/Bundle 只含 Peer ID 和非敏感选项，不传递配置或 Bitmap。
 * 编码/磁盘操作在单线程执行；画面离开后立即释放二维码，销毁后不再更新旧页面。
 */
public final class PeerExportActivity extends LocalizedActivity {
    public static final String EXTRA_PEER_ID = "atticus.export.peerId";
    private static final int SAVE_CONF = 1801;
    private static final int STORAGE_PERMISSION = 1802;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private String peerId;
    private TextView details;
    private TextView status;
    private ImageView image;
    private Button showQr;
    private Button saveImage;
    private Button saveConf;
    private Button copyConfig;
    private Button applyOptions;
    private Spinner addressMode;
    private EditText dns;
    private EditText allowedIps;
    private Bitmap displayedQr;
    private PeerConfigurationExport.Snapshot snapshot;
    private SecretBytes pendingFile;
    private boolean pendingPermission;
    private boolean foreground;
    private boolean busy;
    private boolean qrTooLong;
    private volatile boolean destroyed;
    private int generation;
    private int originalSecureFlag;
    private int appliedMode;
    private String appliedDns = "";
    private String appliedRoutes = "";

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        originalSecureFlag = getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_SECURE;
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        peerId = getIntent().getStringExtra(EXTRA_PEER_ID);
        buildContent();
        if (state != null) {
            appliedMode = Math.max(0, Math.min(2, state.getInt("mode", 0)));
            appliedDns = state.getString("dns", "");
            appliedRoutes = state.getString("routes", "");
            addressMode.setSelection(appliedMode);
            dns.setText(appliedDns);
            allowedIps.setText(appliedRoutes);
        }
        updateButtons();
    }

    @Override protected void onStart() {
        super.onStart();
        foreground = true;
        if (snapshot == null && !busy && pendingFile == null) loadConfiguration();
    }

    @Override protected void onStop() {
        foreground = false;
        generation++;
        clearQr();
        clearSnapshot();
        super.onStop();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        // 不调用控件的自动文本保存；只有明确的非敏感导出选项可以进入 Bundle。
        // 只保存已验证过的数字 IP/CIDR；用户误粘贴的原始输入不会进入 Bundle。
        state.putInt("mode", appliedMode);
        state.putString("dns", appliedDns);
        state.putString("routes", appliedRoutes);
        super.onSaveInstanceState(state);
    }

    @Override protected void onDestroy() {
        destroyed = true;
        generation++;
        clearQr();
        clearSnapshot();
        if (pendingFile != null) { pendingFile.close(); pendingFile = null; }
        worker.shutdown(); // 已经确认的写入正常收尾，事务负责清理失败的半成品。
        if (originalSecureFlag == 0) getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
        super.onDestroy();
    }

    private void buildContent() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        int padding = dp(20);
        content.setPadding(padding, padding, padding, padding);
        content.setBackgroundColor(getColor(R.color.atticus_surface));
        scroll.addView(content);
        BackNavigation.addTo(this, content);
        TextView title = text(content, getString(R.string.wg_export_title), 26);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        details = text(content, "", 16);
        text(content, getString(R.string.wg_export_private_warning), 14);
        status = text(content, getString(R.string.wg_export_loading), 14);
        showQr = button(content, R.string.wg_show_qr);
        showQr.setOnClickListener(view -> generateQr(false));
        image = new ImageView(this);
        image.setSaveEnabled(false);
        image.setContentDescription(getString(R.string.wg_qr_description));
        image.setAdjustViewBounds(true);
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        image.setVisibility(View.GONE);
        content.addView(image, new LinearLayout.LayoutParams(-1, -2));
        text(content, getString(R.string.wg_scan_help), 14);
        saveImage = button(content, R.string.wg_save_gallery);
        saveImage.setOnClickListener(view -> confirmGallerySave());
        saveConf = button(content, R.string.wg_save_conf);
        saveConf.setOnClickListener(view -> beginFileSave());
        copyConfig = button(content, R.string.wg_copy_config);
        copyConfig.setOnClickListener(view -> copyConfiguration());

        Button optionsToggle = button(content, R.string.wg_export_options);
        LinearLayout options = new LinearLayout(this);
        options.setOrientation(LinearLayout.VERTICAL);
        options.setVisibility(View.GONE);
        content.addView(options);
        optionsToggle.setOnClickListener(view -> options.setVisibility(
                options.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE));
        text(options, getString(R.string.wg_options_help), 14);
        text(options, getString(R.string.wg_address_mode), 16);
        addressMode = new Spinner(this);
        addressMode.setSaveEnabled(false);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item,
                new String[]{getString(R.string.wg_dual_stack), "IPv4", "IPv6"});
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        addressMode.setAdapter(adapter);
        options.addView(addressMode);
        dns = input(options, R.string.wg_export_dns);
        allowedIps = input(options, R.string.wg_export_routes);
        applyOptions = button(options, R.string.wg_apply_export_options);
        applyOptions.setOnClickListener(view -> applyExportOptions());
        setContentView(scroll);
    }

    private void loadConfiguration() {
        if (busy || destroyed) return;
        if (peerId == null || !peerId.matches("[0-9a-f]{32}")) {
            status.setText(R.string.wg_peer_missing);
            return;
        }
        final ClientExportOptions options = new ClientExportOptions(
                ClientExportOptions.AddressMode.values()[appliedMode], appliedDns, appliedRoutes);
        clearQr();
        clearSnapshot();
        qrTooLong = false;
        final int ticket = ++generation;
        setBusy(true);
        status.setText(R.string.wg_export_loading);
        worker.execute(() -> {
            PeerConfigurationExport.Snapshot loaded = null;
            int error = R.string.wg_export_unavailable;
            try {
                loaded = new PeerConfigurationExport().load(((AtticusServerApplication)
                        getApplication()).awaitRepository(), peerId, options);
            } catch (PeerConfigurationExport.UnavailableException unavailable) {
                error = unavailable.missingPeer ? R.string.wg_peer_missing : R.string.wg_private_missing;
            } catch (Exception | OutOfMemoryError ignored) {
                // 固定本地化提示，不把 Keystore/Endpoint 异常内容传到界面或日志。
            }
            final PeerConfigurationExport.Snapshot result = loaded;
            final int message = error;
            runOnUiThread(() -> {
                if (destroyed || !foreground || ticket != generation) {
                    if (result != null) result.close();
                    if (!destroyed) { setBusy(false); if (foreground) loadConfiguration(); }
                    return;
                }
                snapshot = result;
                setBusy(false);
                if (result == null) status.setText(message);
                else {
                    details.setText(getString(R.string.wg_peer_details, result.name, result.addresses));
                    status.setText(R.string.wg_export_ready);
                }
            });
        });
    }

    private void applyExportOptions() {
        if (busy || pendingFile != null) return;
        final int mode = addressMode.getSelectedItemPosition();
        final String customDns = dns.getText().toString();
        final String customRoutes = allowedIps.getText().toString();
        try {
            new ClientExportOptions(ClientExportOptions.AddressMode.values()[mode], customDns, customRoutes);
            appliedMode = mode;
            appliedDns = customDns;
            appliedRoutes = customRoutes;
            loadConfiguration();
        } catch (RuntimeException ignored) {
            clearQr(); clearSnapshot(); updateButtons();
            status.setText(R.string.wg_options_invalid);
        }
    }

    private void generateQr(boolean gallery) {
        if (snapshot == null || busy || qrTooLong) return;
        final SecretBytes owned = snapshot.configuration.duplicate();
        final String peerName = snapshot.name;
        final int ticket = generation;
        setBusy(true);
        worker.execute(() -> {
            Bitmap bitmap = null;
            int error = 0;
            boolean saved = false;
            try (SecretBytes configuration = owned) {
                bitmap = new QrCodeGenerator().generate(configuration);
                if (gallery) {
                    new QrCodeGallerySaver(getApplicationContext()).save(bitmap, peerName);
                    saved = true;
                }
            } catch (QrCodeGenerator.TooLongException ignored) {
                error = R.string.wg_qr_too_long;
            } catch (Exception | OutOfMemoryError failure) {
                error = gallery ? galleryError(failure) : R.string.wg_qr_failed;
            }
            if (gallery || error != 0) { QrCodeGenerator.release(bitmap); bitmap = null; }
            final Bitmap display = bitmap;
            final int message = error;
            final boolean success = saved;
            runOnUiThread(() -> {
                if (destroyed) { QrCodeGenerator.release(display); return; }
                setBusy(false);
                if (success) markExported();
                if (!foreground || ticket != generation) {
                    QrCodeGenerator.release(display);
                    if (foreground && snapshot == null) loadConfiguration();
                    return;
                }
                if (message != 0) {
                    status.setText(message);
                    if (message == R.string.wg_qr_too_long) { qrTooLong = true; updateButtons(); }
                } else if (gallery) {
                    status.setText(R.string.wg_gallery_saved);
                    toast(R.string.wg_gallery_saved);
                } else {
                    clearQr();
                    displayedQr = display;
                    image.setImageBitmap(display);
                    image.setVisibility(View.VISIBLE);
                    status.setText(R.string.wg_scan_help);
                }
            });
        });
    }

    private void confirmGallerySave() {
        if (snapshot == null || busy) return;
        // 每次保存都重新确认，包含“首次点击必须警告”的要求；不记住免提示授权。
        new AlertDialog.Builder(this).setTitle(R.string.wg_security_title)
                .setMessage(R.string.wg_gallery_warning)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.wg_continue_save, (dialog, which) -> {
                    if (Build.VERSION.SDK_INT <= 28 && checkSelfPermission(
                            Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                        pendingPermission = true;
                        requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, STORAGE_PERMISSION);
                    } else generateQr(true);
                }).show();
    }

    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(request, permissions, results);
        if (request != STORAGE_PERMISSION || !pendingPermission) return;
        pendingPermission = false;
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) generateQr(true);
        else { status.setText(R.string.wg_storage_denied); toast(R.string.wg_storage_denied); }
    }

    private void beginFileSave() {
        if (snapshot == null || busy || pendingFile != null) return;
        pendingFile = snapshot.configuration.duplicate();
        try {
            startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT)
                    .addCategory(Intent.CATEGORY_OPENABLE).setType("application/octet-stream")
                    .putExtra(Intent.EXTRA_TITLE, ExportFileNames.conf(snapshot.name)), SAVE_CONF);
            updateButtons();
        } catch (RuntimeException ignored) {
            pendingFile.close(); pendingFile = null;
            status.setText(R.string.wg_conf_failed);
        }
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != SAVE_CONF) return;
        final SecretBytes owned = pendingFile;
        pendingFile = null;
        final Uri destination = result == RESULT_OK && data != null ? data.getData() : null;
        if (destination == null) {
            if (owned != null) owned.close();
            updateButtons();
            if (foreground && snapshot == null && !busy) loadConfiguration();
            return;
        }
        // 进程死亡/旋转后不重新生成另一份配置冒充用户此前看到的二维码。
        setBusy(true);
        worker.execute(() -> {
            boolean success = false;
            try (SecretBytes configuration = owned) {
                if (configuration == null) throw new IllegalStateException("EXPORT_INTERRUPTED");
                SafConfigExporter.write(getContentResolver(), destination, configuration);
                success = true;
            } catch (Exception | OutOfMemoryError ignored) {
                try { getContentResolver().delete(destination, null, null); }
                catch (RuntimeException ignoredCleanup) { /* 仅尽力删除本次 SAF 文件。 */ }
            }
            final boolean saved = success;
            runOnUiThread(() -> {
                if (destroyed) return;
                setBusy(false);
                if (saved) markExported();
                toast(saved ? R.string.wg_conf_saved : R.string.wg_conf_failed);
                if (foreground && snapshot == null) loadConfiguration();
            });
        });
    }

    private void copyConfiguration() {
        if (snapshot == null || busy) return;
        byte[] bytes = snapshot.configuration.copy();
        try {
            ClipboardManager clipboard = getSystemService(ClipboardManager.class);
            if (clipboard == null) throw new IllegalStateException("CLIPBOARD_UNAVAILABLE");
            ClipData clip = ClipData.newPlainText("WireGuard", new String(bytes, StandardCharsets.UTF_8));
            PersistableBundle extras = new PersistableBundle();
            // 新系统使用公开常量；旧系统保留相同扩展标记，不调用 API 33 专属字段。
            extras.putBoolean(Build.VERSION.SDK_INT >= 33
                    ? ClipDescription.EXTRA_IS_SENSITIVE : "android.content.extra.IS_SENSITIVE", true);
            clip.getDescription().setExtras(extras);
            clipboard.setPrimaryClip(clip);
            toast(R.string.wg_config_copied);
        } catch (RuntimeException | OutOfMemoryError ignored) {
            toast(R.string.wg_copy_failed);
        } finally { Arrays.fill(bytes, (byte) 0); }
    }

    private void markExported() {
        // 沿用 Seal 的“持久导出 + 已握手”前置条件；仅显示或复制不满足持久导出。
        setResult(RESULT_OK, new Intent().putExtra(EXTRA_PEER_ID, peerId));
    }

    private void clearQr() {
        if (image != null) { image.setImageDrawable(null); image.setVisibility(View.GONE); }
        QrCodeGenerator.release(displayedQr);
        displayedQr = null;
    }

    private void clearSnapshot() {
        if (snapshot != null) { snapshot.close(); snapshot = null; }
    }

    private void setBusy(boolean value) { busy = value; updateButtons(); }

    private void updateButtons() {
        boolean ready = snapshot != null && !busy && pendingFile == null;
        showQr.setEnabled(ready && !qrTooLong);
        saveImage.setEnabled(ready && !qrTooLong);
        saveConf.setEnabled(ready);
        copyConfig.setEnabled(ready);
        applyOptions.setEnabled(!busy && pendingFile == null);
    }

    private static int galleryError(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SecurityException) return R.string.wg_storage_denied;
            if (cause instanceof SQLiteFullException
                    || cause instanceof ErrnoException errno && errno.errno == OsConstants.ENOSPC) {
                return R.string.wg_storage_full;
            }
        }
        return R.string.wg_gallery_failed;
    }

    private void toast(int message) { Toast.makeText(this, message, Toast.LENGTH_LONG).show(); }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private TextView text(LinearLayout parent, String value, int size) {
        TextView view = new TextView(this);
        view.setText(value); view.setTextSize(size); view.setSaveEnabled(false);
        view.setPadding(0, dp(8), 0, dp(8));
        parent.addView(view, new LinearLayout.LayoutParams(-1, -2));
        return view;
    }
    private Button button(LinearLayout parent, int label) {
        Button view = new Button(this);
        view.setText(label); view.setAllCaps(false);
        parent.addView(view, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, -2));
        return view;
    }
    private EditText input(LinearLayout parent, int label) {
        text(parent, getString(label), 16);
        EditText view = new EditText(this);
        view.setHint(R.string.wg_empty_default);
        view.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        view.setSingleLine(true); view.setSaveEnabled(false);
        view.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(16_384)});
        parent.addView(view, new LinearLayout.LayoutParams(-1, -2));
        return view;
    }
}
