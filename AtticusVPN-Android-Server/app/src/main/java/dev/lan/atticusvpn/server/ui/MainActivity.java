/*
 * Copyright (c) 2026 TianzeL
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.lan.atticusvpn.server.ui;

import android.Manifest;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.graphics.PorterDuff;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.function.Supplier;

import dev.lan.atticusvpn.server.AtticusServerApplication;
import dev.lan.atticusvpn.server.BuildConfig;
import dev.lan.atticusvpn.server.R;
import dev.lan.atticusvpn.server.config.AppSettingsPolicy;
import dev.lan.atticusvpn.server.model.ServerState;
import dev.lan.atticusvpn.server.model.StoredPeer;
import dev.lan.atticusvpn.server.runtime.RuntimeStatus;
import dev.lan.atticusvpn.server.runtime.RuntimeStatusStore;
import dev.lan.atticusvpn.server.runtime.NetworkCheckResult;
import dev.lan.atticusvpn.server.service.AtticusServerService;
import dev.lan.atticusvpn.server.storage.EncryptedStateRepository;

/**
 * 不依赖 AndroidX/Compose 的管理界面，显示语言由独立设置页管理。
 *
 * <p>界面线程只采集输入和绘制不可变快照；Keystore、配置导出和存储事务全部进入单一
 * 后台线程。任何失败都映射为固定本地化提示，绝不显示异常原文、私钥、PSK 或 DDNS token。</p>
 */
public final class MainActivity extends LocalizedActivity implements RuntimeStatusStore.Listener {
    private static final int REQUEST_EXPORT = 1701;
    private static final int REQUEST_NOTIFICATIONS = 1702;
    private static final String STATE_PENDING_EXPORT_PEER_ID =
            "atticus.pendingExportPeerId";
    private static final String STATE_CONNECTION_EXPANDED = "atticus.connectionExpanded";
    private static final String STATE_DDNS_EXPANDED = "atticus.ddnsExpanded";
    private static final String STATE_HOME_PAGE = "atticus.homePage";
    private static final String[] DNS_MODES = {"AUTOMATIC", "CUSTOM"};
    private static final String[] DDNS_PROVIDERS = {
            "disabled", "duckdns", "cloudflare", "noip", "dynu", "freedns", "custom"
    };

    private final ExecutorService worker = Executors.newSingleThreadExecutor(new ThreadFactory() {
        @Override
        public Thread newThread(final Runnable runnable) {
            final Thread thread = new Thread(runnable, "AtticusVPN-UI-Worker");
            thread.setDaemon(true);
            return thread;
        }
    });

    private TextView statusView;
    private HomePagesView pages;
    private HomePageNavigation pageNavigation;
    private int selectedPage;
    private Button toggleServerButton;
    private Button checkNetworkButton;
    private TerminalStatusAnimator terminal;
    private HomeInfoCollector homeInfo;
    private final Handler terminalMain = new Handler(Looper.getMainLooper());
    private boolean homeVisible;
    private boolean pendingTerminalRefresh;
    private boolean commandPending;
    private String terminalCommands;
    private String runtimeFingerprint = "";
    private String localInformationFingerprint = "";
    private LinearLayout peersContainer;
    private EditText endpointField;
    private EditText portField;
    private EditText mtuField;
    private CheckBox dnsEnabledField;
    private Spinner dnsModeField;
    private EditText customDnsField;
    private Spinner ddnsProviderField;
    private EditText ddnsHostnameField;
    private EditText ddnsAccountField;
    private EditText ddnsSecretField;
    private EditText ddnsZoneField;
    private EditText ddnsRecordField;
    private EditText ddnsCustomUrlField;
    private DdnsFieldRow ddnsHostnameRow;
    private DdnsFieldRow ddnsAccountRow;
    private DdnsFieldRow ddnsSecretRow;
    private DdnsFieldRow ddnsZoneRow;
    private DdnsFieldRow ddnsRecordRow;
    private DdnsFieldRow ddnsCustomUrlRow;
    private String shownDdnsProvider = "disabled";
    private final DdnsFormPolicy.CredentialDrafts ddnsCredentialDrafts =
            new DdnsFormPolicy.CredentialDrafts();

    private boolean settingsLoaded;
    private boolean connectionExpanded = true;
    private boolean ddnsExpanded;
    private String pendingExportPeerId;
    private RuntimeStatus latestRuntime = RuntimeStatus.stopped();
    private List<PeerItem> latestPeers = Collections.emptyList();
    private final Set<String> exportedThisSession = new HashSet<>();
    private volatile boolean activityDestroyed;

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (savedInstanceState != null) {
            pendingExportPeerId = savedInstanceState.getString(STATE_PENDING_EXPORT_PEER_ID);
            connectionExpanded = savedInstanceState.getBoolean(STATE_CONNECTION_EXPANDED, true);
            ddnsExpanded = savedInstanceState.getBoolean(STATE_DDNS_EXPANDED, false);
            selectedPage = Math.max(0, Math.min(2, savedInstanceState.getInt(STATE_HOME_PAGE, 0)));
        }
        latestRuntime = RuntimeStatusStore.current();
        runtimeFingerprint = informationFingerprint(latestRuntime);
        setContentView(buildContent());
        terminal = new TerminalStatusAnimator(statusView);
        homeInfo = new HomeInfoCollector(this);
        updateServerControls();
        RuntimeStatusStore.addListener(this);
        requestNotificationPermissionIfNeeded();
        refreshState(true);
    }

    @Override protected void onStart() {
        super.onStart();
        homeVisible = true;
        homeInfo.start(new HomeInfoCollector.Listener() {
            @Override public void onSnapshot(HomeInfoCollector.Snapshot snapshot) {
                // 打字器按当前轮的代号读取快照；检测结果不回写 Service，也不触发新一轮检测。
            }
            @Override public void onInformationChanged() { requestTerminalRefresh(false, null); }
        });
        requestTerminalRefresh(true, null);
    }

    @Override protected void onStop() {
        homeVisible = false;
        terminalMain.removeCallbacksAndMessages(null);
        pendingTerminalRefresh = false;
        terminalCommands = null;
        terminal.stop();
        homeInfo.stop();
        super.onStop();
    }

    @Override
    protected void onSaveInstanceState(final Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putBoolean(STATE_CONNECTION_EXPANDED, connectionExpanded);
        outState.putBoolean(STATE_DDNS_EXPANDED, ddnsExpanded);
        outState.putInt(STATE_HOME_PAGE, selectedPage);
        if (pendingExportPeerId != null) {
            outState.putString(STATE_PENDING_EXPORT_PEER_ID, pendingExportPeerId);
        }
        // exportedThisSession 故意不跨 Activity 重建恢复：无法证明之前的 SAF 写入仍完整，
        // 因而旋转后重新导出、再握手才允许 Seal，是更保守的安全行为。
    }

    @Override
    protected void onDestroy() {
        activityDestroyed = true;
        terminalMain.removeCallbacksAndMessages(null);
        if (terminal != null) terminal.close();
        if (homeInfo != null) homeInfo.close();
        RuntimeStatusStore.removeListener(this);
        ddnsCredentialDrafts.clear();
        // 已提交的持久化操作正常收尾，但完成后不得再触碰旧 Activity 的界面。
        worker.shutdown();
        super.onDestroy();
    }

    @Override
    public void onRuntimeStatus(final RuntimeStatus status) {
        if (activityDestroyed || isFinishing()) {
            return;
        }
        final RuntimeStatus.Phase previousPhase = latestRuntime.phase();
        latestRuntime = status;
        if (status.phase() != previousPhase) commandPending = false;
        updateServerControls();
        final String fingerprint = informationFingerprint(status);
        if (!fingerprint.equals(runtimeFingerprint)) {
            runtimeFingerprint = fingerprint;
            requestTerminalRefresh(false, null);
        }
        if (peersContainer != null && !latestPeers.isEmpty()) {
            drawPeers(latestPeers);
        }
    }

    private View buildContent() {
        final LinearLayout root = vertical();
        root.setPadding(dp(18), dp(22), dp(18), dp(16));
        root.setBackgroundColor(getColor(R.color.atticus_surface));

        final LinearLayout heading = horizontal();
        // 只替换 AtticusVPN 字样，不显示盾牌 Logo；Server 的原有字号/粗体保持。
        heading.setGravity(android.view.Gravity.CENTER_VERTICAL);
        final ImageView wordmark = new ImageView(this);
        wordmark.setImageDrawable(UiArtwork.load(this, R.drawable.atticus_wordmark));
        wordmark.setScaleType(ImageView.ScaleType.FIT_CENTER);
        wordmark.setContentDescription(getString(R.string.brand_wordmark));
        final LinearLayout.LayoutParams wordmarkLayout = new LinearLayout.LayoutParams(0, dp(40), 1f);
        wordmarkLayout.setMarginEnd(dp(6));
        heading.addView(wordmark, wordmarkLayout);
        final TextView serverTitle = text(getString(R.string.server_wordmark_suffix), 28, true);
        heading.addView(serverTitle, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        final ImageButton settings = new ImageButton(this);
        settings.setImageDrawable(UiArtwork.load(this, R.drawable.atticus_settings));
        settings.setScaleType(ImageView.ScaleType.FIT_CENTER);
        settings.setPadding(dp(12), dp(12), dp(12), dp(12));
        settings.setColorFilter(getColor(R.color.black), PorterDuff.Mode.SRC_IN);
        // 保留平台的无边框按压反馈，但不显示“设置”文字。
        final android.util.TypedValue selectable = new android.util.TypedValue();
        getTheme().resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, selectable, true);
        settings.setBackgroundResource(selectable.resourceId);
        settings.setContentDescription(getString(R.string.settings));
        settings.setOnClickListener(view -> startActivity(new Intent(this, SettingsActivity.class)));
        final LinearLayout.LayoutParams settingsLayout = new LinearLayout.LayoutParams(dp(48), dp(48));
        settingsLayout.setMarginStart(dp(6));
        heading.addView(settings, settingsLayout);
        root.addView(heading, marginBottom(18));

        pages = new HomePagesView(this);
        final LinearLayout informationPage = createHomePage();
        final LinearLayout peersPage = createHomePage();
        final LinearLayout connectionPage = createHomePage();
        root.addView(pages, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        final TerminalStatusView terminalWindow = new TerminalStatusView(this);
        statusView = terminalWindow.textView();
        statusView.setText(">");
        statusView.setTextSize(15);
        statusView.setTypeface(Typeface.MONOSPACE);
        statusView.setTextColor(getColor(R.color.terminal_text));
        statusView.setLineSpacing(dp(3), 1f);
        statusView.setPadding(dp(16), dp(16), dp(16), dp(16));
        final GradientDrawable terminalBackground = rounded(getColor(R.color.terminal_background), dp(10));
        terminalBackground.setStroke(dp(1), getColor(R.color.terminal_border));
        terminalWindow.setBackground(terminalBackground);
        // 不把每个字符作为无障碍实时播报，避免连续打断读屏用户。
        statusView.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_NONE);
        informationPage.addView(terminalWindow, marginBottom(10));

        final LinearLayout controls = horizontal();
        toggleServerButton = button(getString(R.string.start_server));
        toggleServerButton.setOnClickListener(view -> toggleServer());
        controls.addView(toggleServerButton, weight());
        checkNetworkButton = button(getString(R.string.check_network));
        checkNetworkButton.setOnClickListener(view -> requestTerminalRefresh(true, null));
        controls.addView(checkNetworkButton, weight());
        informationPage.addView(controls, marginBottom(22));

        final LinearLayout connectionContent = addCollapsibleSection(
                connectionPage, R.string.connection_settings, true);
        endpointField = input(getString(R.string.endpoint_hint), false);
        addConnectionField(connectionContent, R.string.endpoint_label, R.string.endpoint_help, endpointField);
        portField = numericInput(getString(R.string.port_hint));
        addConnectionField(connectionContent, R.string.port_label, R.string.port_help, portField);
        mtuField = numericInput(getString(R.string.mtu_hint));
        addConnectionField(connectionContent, R.string.mtu_label, R.string.mtu_help, mtuField);

        dnsEnabledField = new CheckBox(this);
        dnsEnabledField.setText(R.string.dns_server_enabled);
        connectionContent.addView(dnsEnabledField, matchWrap());
        dnsModeField = spinner(new String[]{getString(R.string.dns_automatic), getString(R.string.dns_custom)});
        connectionContent.addView(text(getString(R.string.dns_mode), 16, true), marginBottom(6));
        connectionContent.addView(dnsModeField, matchWrap());
        customDnsField = input(getString(R.string.custom_dns_hint), false);
        addConnectionField(connectionContent, R.string.custom_dns_label, R.string.custom_dns_hint, customDnsField);

        final LinearLayout ddnsContent = addCollapsibleSection(connectionPage, R.string.ddns_section, false);
        ddnsProviderField = spinner(new String[]{getString(R.string.provider_disabled), "DuckDNS", "Cloudflare", "No-IP", "Dynu", "FreeDNS", getString(R.string.provider_custom)});
        ddnsProviderField.setId(View.generateViewId());
        ddnsProviderField.setSaveEnabled(false);
        ddnsProviderField.setSaveFromParentEnabled(false);
        final TextView providerLabel = text(getString(R.string.provider), 16, true);
        providerLabel.setLabelFor(ddnsProviderField.getId());
        ddnsContent.addView(providerLabel, marginBottom(4));
        ddnsContent.addView(ddnsProviderField, marginBottom(12));

        ddnsHostnameField = input("", false);
        ddnsHostnameRow = addDdnsField(ddnsContent, R.string.ddns_hostname_label,
                R.string.ddns_hostname_help, ddnsHostnameField);
        ddnsAccountField = input("", false);
        ddnsAccountRow = addDdnsField(ddnsContent, R.string.ddns_account_label_noip,
                R.string.ddns_account_help_noip, ddnsAccountField);
        ddnsSecretField = input("", true);
        ddnsSecretRow = addDdnsField(ddnsContent, R.string.ddns_credential_label_duckdns,
                R.string.ddns_credential_help_duckdns, ddnsSecretField);
        ddnsZoneField = input("", false);
        ddnsZoneRow = addDdnsField(ddnsContent, R.string.ddns_zone_id_label,
                R.string.ddns_zone_id_help, ddnsZoneField);
        ddnsRecordField = input("", false);
        ddnsRecordRow = addDdnsField(ddnsContent, R.string.ddns_record_id_label,
                R.string.ddns_record_id_help, ddnsRecordField);
        ddnsCustomUrlField = input("", false);
        ddnsCustomUrlRow = addDdnsField(ddnsContent, R.string.ddns_custom_url_label,
                R.string.ddns_custom_url_help, ddnsCustomUrlField);
        ddnsProviderField.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view,
                    int position, long id) {
                // 读取实际选中项，避免配置加载后迟到的旧 Spinner 回调覆盖当前草稿。
                onDdnsProviderSelected();
            }
            @Override public void onNothingSelected(AdapterView<?> parent) { }
        });
        updateDdnsForm("disabled");

        final Button save = button(getString(R.string.save_settings));
        save.setOnClickListener(view -> saveSettings());
        connectionPage.addView(save, marginBottom(24));

        final LinearLayout peerHeader = horizontal();
        peerHeader.addView(sectionTitle(getString(R.string.peer_section)), weight());
        final Button addPeer = button(getString(R.string.add_peer));
        addPeer.setOnClickListener(view -> showAddPeerDialog());
        peerHeader.addView(addPeer);
        peersPage.addView(peerHeader);

        final TextView peerHint = text(
                getString(R.string.seal_help), 13, false);
        peerHint.setTextColor(getColor(R.color.atticus_secondary));
        peersPage.addView(peerHint, marginBottom(10));
        peersContainer = vertical();
        peersPage.addView(peersContainer, matchWrap());

        final TextView footer = text(
                getString(R.string.footer, BuildConfig.VERSION_NAME), 12, false);
        footer.setTextColor(getColor(R.color.atticus_secondary));
        informationPage.addView(footer, marginTop(24));

        pageNavigation = new HomePageNavigation(this);
        pageNavigation.setListener(index -> pages.select(index));
        pages.setListener(index -> {
            if (index != selectedPage) {
                // 仅收起输入法，不保存/重载设置；草稿和页内滚动位置保留在原 View。
                final InputMethodManager input = getSystemService(InputMethodManager.class);
                if (input != null) input.hideSoftInputFromWindow(pages.getWindowToken(), 0);
                final View focused = getCurrentFocus();
                if (focused instanceof EditText) {
                    focused.clearFocus();
                }
            }
            selectedPage = index;
            pageNavigation.select(index);
        });
        final LinearLayout.LayoutParams navigationLayout = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(56));
        navigationLayout.setMargins(dp(12), dp(12), dp(12), 0);
        root.addView(pageNavigation, navigationLayout);
        pages.select(selectedPage);
        return root;
    }

    /** 三页只创建一次；每页独立上下滚动，底部导航不属于任何滚动内容。 */
    private LinearLayout createHomePage() {
        final ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        final LinearLayout content = vertical();
        content.setPadding(0, 0, 0, dp(12));
        scroll.addView(content, matchWrap());
        pages.addView(scroll, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        return content;
    }

    private void refreshState(final boolean allowSettingsUpdate) {
        submitWorker(() -> {
            try {
                final EncryptedStateRepository repository = repository();
                final UiSnapshot snapshot;
                synchronized (repository) {
                    snapshot = UiSnapshot.from(repository.state(), repository);
                }
                postToLiveUi(() -> {
                    if (allowSettingsUpdate || !settingsLoaded) {
                        applySettings(snapshot.settings);
                        settingsLoaded = true;
                    }
                    drawPeers(snapshot.peers);
                    int enabledPeerCount = 0;
                    for (PeerItem peer : snapshot.peers) if (peer.enabled) enabledPeerCount++;
                    final String information = snapshot.settings.endpointHost + "\u0001"
                            + snapshot.settings.listenPort + "\u0001" + snapshot.settings.dnsEnabled
                            + "\u0001" + snapshot.settings.dnsMode + "\u0001"
                            + snapshot.settings.customDns + "\u0001" + snapshot.settings.ddnsProvider
                            + "\u0001" + snapshot.settings.ddnsHostname + "\u0001"
                            + snapshot.settings.mtu + "\u0001" + enabledPeerCount;
                    if (!information.equals(localInformationFingerprint)) {
                        boolean hadInformation = !localInformationFingerprint.isEmpty();
                        localInformationFingerprint = information;
                        if (hadInformation) requestTerminalRefresh(false, null);
                    }
                });
            } catch (final Exception ignored) {
                showFixedError("STATE_READ_FAILED");
            }
        });
    }

    private void applySettings(final SettingsValues values) {
        endpointField.setText(values.endpointHost);
        // 配置文件语法只接受 ASCII 十进制数字，不应随系统数字字形变化。
        portField.setText(String.format(Locale.ROOT, "%d", values.listenPort));
        mtuField.setText(String.format(Locale.ROOT, "%d", values.mtu));
        dnsEnabledField.setChecked(values.dnsEnabled);
        selectSpinner(dnsModeField, DNS_MODES, values.dnsMode);
        customDnsField.setText(values.customDns);
        ddnsHostnameField.setText(values.ddnsHostname);
        ddnsAccountField.setText(values.ddnsAccount);
        ddnsSecretField.setText("");
        ddnsZoneField.setText(values.ddnsZone);
        ddnsRecordField.setText(values.ddnsRecord);
        ddnsCustomUrlField.setText(values.ddnsCustomUrl);
        ddnsCredentialDrafts.reset(values.ddnsProvider);
        shownDdnsProvider = values.ddnsProvider;
        selectSpinner(ddnsProviderField, DDNS_PROVIDERS, values.ddnsProvider);
        // 已保存的服务商立即显示对应字段；空白凭据继续由原保存事务决定是否保留。
        updateDdnsForm(values.ddnsProvider);
    }

    /** 选择服务商仅改变表单，不保存、不重载设置，也不触发网络或服务操作。 */
    private void onDdnsProviderSelected() {
        final int selected = ddnsProviderField.getSelectedItemPosition();
        final String provider = selected >= 0 && selected < DDNS_PROVIDERS.length
                ? DDNS_PROVIDERS[selected] : "disabled";
        if (!provider.equals(shownDdnsProvider)) {
            ddnsSecretField.setText(ddnsCredentialDrafts.select(
                    provider, ddnsSecretField.getText().toString()));
            shownDdnsProvider = provider;
        }
        updateDdnsForm(provider);
    }

    /** 隐藏整行标题、说明和输入框；非秘密文本保留在原 View，不被切换操作清空。 */
    private void updateDdnsForm(final String provider) {
        final Set<DdnsFormPolicy.Field> fields = DdnsFormPolicy.fields(provider);
        ddnsHostnameRow.setVisible(fields.contains(DdnsFormPolicy.Field.HOSTNAME));
        ddnsAccountRow.setVisible(fields.contains(DdnsFormPolicy.Field.ACCOUNT));
        ddnsSecretRow.setVisible(fields.contains(DdnsFormPolicy.Field.CREDENTIAL));
        ddnsZoneRow.setVisible(fields.contains(DdnsFormPolicy.Field.ZONE_ID));
        ddnsRecordRow.setVisible(fields.contains(DdnsFormPolicy.Field.RECORD_ID));
        ddnsCustomUrlRow.setVisible(fields.contains(DdnsFormPolicy.Field.CUSTOM_URL));
        ddnsHostnameRow.setLabels(R.string.ddns_hostname_label,
                DdnsFormPolicy.usesDuckDnsHostnameHint(provider)
                        ? R.string.ddns_hostname_help_duckdns : R.string.ddns_hostname_help);
        if ("dynu".equals(provider)) {
            ddnsAccountRow.setLabels(R.string.ddns_account_label_dynu,
                    R.string.ddns_account_help_dynu);
        } else {
            ddnsAccountRow.setLabels(R.string.ddns_account_label_noip,
                    R.string.ddns_account_help_noip);
        }
        switch (provider) {
            case "duckdns":
                ddnsSecretRow.setLabels(R.string.ddns_credential_label_duckdns,
                        R.string.ddns_credential_help_duckdns);
                break;
            case "cloudflare":
                ddnsSecretRow.setLabels(R.string.ddns_credential_label_cloudflare,
                        R.string.ddns_credential_help_cloudflare);
                break;
            case "noip":
                ddnsSecretRow.setLabels(R.string.ddns_credential_label_noip,
                        R.string.ddns_credential_help_noip);
                break;
            case "dynu":
                ddnsSecretRow.setLabels(R.string.ddns_credential_label_dynu,
                        R.string.ddns_credential_help_dynu);
                break;
            case "freedns":
                ddnsSecretRow.setLabels(R.string.ddns_credential_label_freedns,
                        R.string.ddns_credential_help_freedns);
                break;
            case "custom":
                ddnsSecretRow.setLabels(R.string.ddns_credential_label_custom,
                        R.string.ddns_credential_help_custom);
                break;
            default:
                // 关闭时具体字段全部隐藏，保留其文本和当前行对象即可。
                break;
        }
    }

    private void drawPeers(final List<PeerItem> peers) {
        latestPeers = new ArrayList<>(peers);
        peersContainer.removeAllViews();
        if (peers.isEmpty()) {
            final TextView empty = text(getString(R.string.no_peers), 15, false);
            empty.setPadding(dp(4), dp(12), dp(4), dp(12));
            peersContainer.addView(empty);
            return;
        }
        for (final PeerItem peer : peers) {
            final LinearLayout card = vertical();
            card.setPadding(dp(13), dp(12), dp(13), dp(12));
            card.setBackground(rounded(getColor(R.color.atticus_card), dp(10)));
            final String stateText = peer.sealed ? getString(R.string.sealed)
                    : getString(peer.exportable ? R.string.exportable : R.string.wg_private_missing);
            card.addView(text(peer.name + " · " + (peer.enabled ? getString(R.string.enabled) : getString(R.string.disabled)), 16, true));
            final TextView address = text(peer.ipv4 + "  /  " + peer.ipv6 + "  ·  " + stateText, 12, false);
            address.setTextColor(getColor(R.color.atticus_secondary));
            card.addView(address);
            final RuntimeStatus.PeerRuntime runtime = findPeerRuntime(peer.name);
            final boolean online = runtime != null
                    && runtime.isOnline(System.currentTimeMillis() / 1000L);
            final TextView traffic = text(getString(R.string.peer_traffic,
                    getString(online ? R.string.online : R.string.offline),
                    formatHandshake(runtime == null ? 0L : runtime.latestHandshakeUnix()),
                    formatBytes(runtime == null ? 0L : runtime.receiveBytes()),
                    formatBytes(runtime == null ? 0L : runtime.transmitBytes())), 12, false);
            traffic.setTextColor(getColor(R.color.atticus_secondary));
            card.addView(traffic);
            if (peer.enabled && latestRuntime.phase() == RuntimeStatus.Phase.RUNNING
                    && (runtime == null || runtime.latestHandshakeUnix() == 0L)) {
                card.addView(text(getString(R.string.no_handshake_help), 12, false));
            }

            // 使用两行等宽操作按钮，避免四个按钮在窄屏或大字体下横向裁切。
            final LinearLayout primaryActions = actionRow();
            final LinearLayout destructiveActions = actionRow();
            final Button toggle = smallButton(peer.enabled ? getString(R.string.disable) : getString(R.string.enable));
            toggle.setOnClickListener(view -> setPeerEnabled(peer.id, !peer.enabled));
            primaryActions.addView(toggle, weight());
            // 旧 Peer / 已 Seal Peer 也可进入查看明确原因，不能重新生成私钥。
            final Button export = smallButton(getString(R.string.export_config));
            export.setOnClickListener(view -> beginExport(peer));
            primaryActions.addView(export, weight());
            if (!peer.sealed) {
                final boolean canSeal = peer.exportable
                        && exportedThisSession.contains(peer.id)
                        && runtime != null
                        && runtime.latestHandshakeUnix() > 0L;
                final Button seal = smallButton(canSeal ? getString(R.string.seal) : getString(R.string.seal_pending));
                seal.setEnabled(canSeal);
                seal.setOnClickListener(view -> confirmSeal(peer));
                destructiveActions.addView(seal, weight());
            }
            final Button delete = smallButton(getString(R.string.delete));
            delete.setOnClickListener(view -> confirmDelete(peer));
            destructiveActions.addView(delete, weight());
            card.addView(primaryActions);
            card.addView(destructiveActions);
            peersContainer.addView(card, marginBottom(10));
        }
    }

    private void saveSettings() {
        final SettingsDraft draft;
        try {
            draft = SettingsDraft.capture(this);
        } catch (final RuntimeException ignored) {
            toast(getString(R.string.invalid_port_mtu));
            return;
        }
        submitWorker(() -> {
            try {
                final EncryptedStateRepository repository = repository();
                synchronized (repository) {
                    final ServerState state = repository.state();
                    final SettingsValues previous = SettingsValues.from(state);
                    final SettingsValues checked = draft.validate(previous);
                    checked.apply(state);
                    try {
                        repository.saveSettings();
                    } catch (final Exception error) {
                        // 内存状态与加密持久状态保持一致；不把写入失败的半事务用于 runtime。
                        previous.apply(state);
                        throw error;
                    }
                }
                AtticusServerService.reloadIfActive(this);
                postToLiveUi(() -> {
                    toast(getString(R.string.settings_saved));
                    refreshState(true);
                    requestTerminalRefresh(false, null);
                });
            } catch (final Exception ignored) {
                showFixedError("SETTINGS_INVALID_OR_SAVE_FAILED");
            }
        });
    }

    private void showAddPeerDialog() {
        final EditText name = input(getString(R.string.peer_name_hint), false);
        name.setSingleLine(true);
        final int pad = dp(20);
        final LinearLayout content = vertical();
        content.setPadding(pad, 0, pad, 0);
        content.addView(name);
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.add_peer))
                .setMessage(getString(R.string.peer_name_rules))
                .setView(content)
                .setNegativeButton(getString(R.string.cancel), null)
                .setPositiveButton(getString(R.string.add), (dialog, which) -> addPeer(name.getText().toString()))
                .show();
    }

    private void addPeer(final String name) {
        submitWorker(() -> {
            try {
                repository().addPeer(name.trim());
                AtticusServerService.reloadIfActive(this);
                postToLiveUi(() -> {
                    toast(getString(R.string.peer_added));
                    refreshState(false);
                });
            } catch (final Exception ignored) {
                showFixedError("PEER_NAME_INVALID_OR_DUPLICATE");
            }
        });
    }

    private void setPeerEnabled(final String id, final boolean enabled) {
        submitWorker(() -> {
            try {
                repository().setEnabled(id, enabled);
                AtticusServerService.reloadIfActive(this);
                postToLiveUi(() -> refreshState(false));
            } catch (final Exception ignored) {
                showFixedError("PEER_UPDATE_FAILED");
            }
        });
    }

    private void confirmSeal(final PeerItem peer) {
        final RuntimeStatus.PeerRuntime runtime = findPeerRuntime(peer.name);
        if (!exportedThisSession.contains(peer.id)
                || runtime == null
                || runtime.latestHandshakeUnix() <= 0L) {
            toast(getString(R.string.seal_prerequisite));
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.seal_title, peer.name))
                .setMessage(getString(R.string.seal_warning))
                .setNegativeButton(getString(R.string.cancel), null)
                .setPositiveButton(getString(R.string.confirm_seal), (dialog, which) -> sealPeer(peer.id))
                .show();
    }

    private void sealPeer(final String id) {
        submitWorker(() -> {
            try {
                repository().seal(id);
                postToLiveUi(() -> {
                    toast(getString(R.string.peer_sealed));
                    refreshState(false);
                });
            } catch (final Exception ignored) {
                showFixedError("PEER_SEAL_FAILED");
            }
        });
    }

    private void confirmDelete(final PeerItem peer) {
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.delete_title, peer.name))
                .setMessage(getString(R.string.delete_warning))
                .setNegativeButton(getString(R.string.cancel), null)
                .setPositiveButton(getString(R.string.delete), (dialog, which) -> deletePeer(peer.id))
                .show();
    }

    private void deletePeer(final String id) {
        try {
            AtticusServerService.revokePeer(this, id, new android.os.ResultReceiver(
                    new android.os.Handler(android.os.Looper.getMainLooper())) {
                @Override protected void onReceiveResult(int result, Bundle data) {
                    postToLiveUi(() -> {
                        refreshState(false);
                        if (result == AtticusServerService.REVOCATION_SERVICE_STOPPED) {
                            toast(getString(R.string.peer_revoked_service_stopped));
                        } else if (result != AtticusServerService.REVOCATION_APPLIED) {
                            showFixedError("PEER_DELETE_FAILED");
                        }
                    });
                }
            });
        } catch (RuntimeException ignored) {
            showFixedError("PEER_DELETE_FAILED");
        }
    }

    private void beginExport(final PeerItem peer) {
        if (pendingExportPeerId != null) {
            toast(getString(R.string.export_pending));
            return;
        }
        pendingExportPeerId = peer.id;
        try {
            startActivityForResult(new Intent(this, PeerExportActivity.class)
                    .putExtra(PeerExportActivity.EXTRA_PEER_ID, peer.id), REQUEST_EXPORT);
        } catch (RuntimeException ignored) {
            pendingExportPeerId = null;
            showFixedError("CONFIG_EXPORT_FAILED");
        }
    }

    @Override
    protected void onActivityResult(
            final int requestCode,
            final int resultCode,
            final Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_EXPORT) {
            return;
        }
        final String peerId = pendingExportPeerId;
        pendingExportPeerId = null;
        if (resultCode == RESULT_OK && peerId != null && data != null
                && peerId.equals(data.getStringExtra(PeerExportActivity.EXTRA_PEER_ID))) {
            exportedThisSession.add(peerId);
            toast(getString(R.string.export_done));
            drawPeers(latestPeers);
        }
    }

    private EncryptedStateRepository repository() throws Exception {
        return ((AtticusServerApplication) getApplication()).awaitRepository();
    }

    private static StoredPeer findPeer(final ServerState state, final String id) {
        for (final StoredPeer peer : state.peers()) {
            if (peer.id().equals(id)) {
                return peer;
            }
        }
        throw new IllegalArgumentException("PEER_NOT_FOUND");
    }

    private void showFixedError(final String code) {
        postToLiveUi(() -> toast(getString(R.string.operation_failed, code)));
    }

    /** 拒绝销毁后的新任务，并吞掉 shutdown 与最后一次点击之间的无害竞态。 */
    private void submitWorker(final Runnable runnable) {
        if (activityDestroyed) {
            return;
        }
        try {
            worker.execute(runnable);
        } catch (final RejectedExecutionException ignored) {
            // Activity 已进入销毁流程；旧实例不再接受工作。
        }
    }

    /** 只允许仍存活的 Activity 更新控件，避免旋转后旧回调触发崩溃。 */
    private void postToLiveUi(final Runnable runnable) {
        if (activityDestroyed) {
            return;
        }
        runOnUiThread(() -> {
            if (!activityDestroyed && !isFinishing() && !isDestroyed()) {
                runnable.run();
            }
        });
    }

    private void toast(final String text) {
        Toast.makeText(this, text, Toast.LENGTH_LONG).show();
    }

    /** 状态刷新不是新运行意图；忽略后台五秒轮询的重复快照与检测自身的进度回调。 */
    private static String informationFingerprint(final RuntimeStatus value) {
        return value.phase() + "\u0001" + value.safeCode() + "\u0001" + value.enabledPeers()
                + "\u0001" + value.networkType() + "\u0001" + value.listenPort()
                + "\u0001" + value.endpoint() + "\u0001" + value.endpointAddress()
                + "\u0001" + value.dnsEnabled() + "\u0001" + value.ddnsProvider()
                + "\u0001" + value.ddnsStatus();
    }

    private void updateServerControls() {
        if (toggleServerButton == null) return;
        final RuntimeStatus.Phase phase = latestRuntime.phase();
        final boolean stopped = phase == RuntimeStatus.Phase.STOPPED;
        toggleServerButton.setText(stopped ? R.string.start_server : R.string.stop_server);
        toggleServerButton.setEnabled(!commandPending
                && phase != RuntimeStatus.Phase.STARTING && phase != RuntimeStatus.Phase.STOPPING);
        if (checkNetworkButton != null) checkNetworkButton.setEnabled(true);
    }

    /** 只在用户明确点击时切换服务；错误/等待网络仍有运行意图，按钮应执行停止。 */
    private void toggleServer() {
        if (commandPending || latestRuntime.phase() == RuntimeStatus.Phase.STARTING
                || latestRuntime.phase() == RuntimeStatus.Phase.STOPPING) return;
        final boolean start = latestRuntime.phase() == RuntimeStatus.Phase.STOPPED;
        commandPending = true;
        updateServerControls();
        requestTerminalRefresh(true, start ? "start server" : "stop server");
        try {
            if (start) AtticusServerService.start(this);
            else AtticusServerService.stop(this);
        } catch (final RuntimeException ignored) {
            commandPending = false;
            updateServerControls();
            toast(getString(R.string.terminal_command_failed));
        }
    }

    /** 合并动画期间的外部更新，只留最新的一次；显式点击可立即取消旧轮并重测。 */
    private void requestTerminalRefresh(final boolean immediate, final String command) {
        if (!homeVisible || activityDestroyed || homeInfo == null || terminal == null) return;
        if (!immediate && terminal.isBusy()) {
            pendingTerminalRefresh = true;
            return;
        }
        terminalMain.removeCallbacksAndMessages(null);
        pendingTerminalRefresh = false;
        // 重测或状态变化可以取消旧动画，但不能吞掉用户刚执行、尚未显示的英文指令。
        if (command != null && !command.isEmpty()) {
            terminalCommands = terminalCommands == null ? command : terminalCommands + "\n> " + command;
        }
        final long frame = homeInfo.refresh();
        terminal.begin(new TerminalStatusAnimator.Source() {
            @Override public List<TerminalStatusAnimator.Part> parts() {
                return terminalParts(frame, latestRuntime);
            }
            @Override public void waiting(String key) {
                final HomeInfoCollector.Snapshot data = frameSnapshot(frame);
                HomeInfoCollector.Field field;
                if ("PROBE".equals(key)) field = HomeInfoCollector.Field.PROBE;
                else if ("NETWORK".equals(key) || ("ENDPOINT".equals(key)
                        && data != null && data.settingsReady)) field = HomeInfoCollector.Field.NETWORK;
                else field = HomeInfoCollector.Field.SETTINGS;
                homeInfo.awaitDeadline(frame, field);
            }
            @Override public String timeout(String key) {
                return getString("PROBE".equals(key) ? R.string.terminal_network_timeout
                        : R.string.terminal_information_timeout);
            }
            @Override public void completed() {
                homeInfo.finishFrame(frame);
                terminalCommands = null;
                if (pendingTerminalRefresh && homeVisible && !activityDestroyed) {
                    // 最后一个提示符至少短暂可见；重复轮询与检测结果不会设置这个标记。
                    terminalMain.postDelayed(() -> requestTerminalRefresh(true, null), 250L);
                }
            }
        }, terminalCommands);
    }

    private HomeInfoCollector.Snapshot frameSnapshot(final long frame) {
        final HomeInfoCollector.Snapshot snapshot = homeInfo.snapshot();
        return snapshot.generation == frame ? snapshot : null;
    }

    /** 用资源模板分段，不用冒号截取 IPv6；标签与上一版本完全相同。 */
    private List<TerminalStatusAnimator.Part> terminalParts(final long frame,
            final RuntimeStatus runtime) {
        final List<TerminalStatusAnimator.Part> parts = new ArrayList<>();
        parts.add(TerminalStatusAnimator.Part.fixed(" " + runtimePhaseText(runtime)));
        final String[] markers = {"\u001f0\u001f", "\u001f1\u001f", "\u001f2\u001f",
                "\u001f3\u001f", "\u001f4\u001f", "\u001f5\u001f", "\u001f6\u001f", "\u001f7\u001f"};
        final String environment = getString(R.string.runtime_environment,
                markers[0], markers[1], markers[2], markers[3],
                markers[4], markers[5], markers[6], markers[7]);
        int offset = 0;
        for (int position = 0; position < markers.length; position++) {
            final int markerAt = environment.indexOf(markers[position], offset);
            if (markerAt < offset) throw new IllegalStateException("TERMINAL_RESOURCE_TEMPLATE");
            parts.add(TerminalStatusAnimator.Part.fixed(environment.substring(offset, markerAt)));
            final int field = position;
            final Supplier<TerminalStatusAnimator.Value> resolve =
                    () -> terminalField(frame, field, runtime);
            if (field == 7) parts.add(TerminalStatusAnimator.Part.result(resolve));
            else parts.add(TerminalStatusAnimator.Part.field(
                    field == 0 || field == 3 ? "NETWORK" : field == 2 ? "ENDPOINT" : "SETTINGS", resolve));
            offset = markerAt + markers[position].length();
        }
        if (offset < environment.length()) parts.add(TerminalStatusAnimator.Part.fixed(environment.substring(offset)));
        return parts;
    }

    private TerminalStatusAnimator.Value terminalField(final long frame, final int field,
            final RuntimeStatus runtime) {
        final HomeInfoCollector.Snapshot data = frameSnapshot(frame);
        if (data == null) return null;
        if (field == 7) {
            if (!data.probeReady) return null;
            if (data.networkCheck.isConnectivityConfirmed()) {
                return TerminalStatusAnimator.Value.text(UiMessages.networkCheck(this, data.networkCheck));
            }
            return TerminalStatusAnimator.Value.error("VPN".equals(data.networkType)
                    ? getString(R.string.terminal_other_vpn) : terminalNetworkError(data.networkCheck));
        }
        if (field != 0 && field != 3) {
            if (!data.settingsReady) return null;
            if (data.settingsError != HomeInfoCollector.SafeError.NONE) {
                return TerminalStatusAnimator.Value.error(getString(
                        data.settingsError == HomeInfoCollector.SafeError.INFORMATION_TIMEOUT
                                ? R.string.terminal_information_timeout : R.string.terminal_settings_failed));
            }
        }
        if (field == 0 || field == 2 || field == 3) {
            if (!data.networkReady) return null;
            if (data.networkError != HomeInfoCollector.SafeError.NONE) {
                return TerminalStatusAnimator.Value.error(getString(
                        data.networkError == HomeInfoCollector.SafeError.INFORMATION_TIMEOUT
                                ? R.string.terminal_information_timeout : R.string.terminal_network_unavailable));
            }
        }
        final String value;
        switch (field) {
            case 0: value = networkLabel(data.networkType); break;
            case 1: value = Integer.toString(data.listenPort); break;
            case 2: value = data.endpoint.isEmpty() ? getString(R.string.waiting_ipv6) : data.endpoint; break;
            case 3: value = data.globalIpv6.isEmpty() ? getString(R.string.unavailable) : data.globalIpv6; break;
            case 4: value = getString(runtime.phase() == RuntimeStatus.Phase.RUNNING
                    && data.dnsEnabled ? R.string.running : R.string.stop); break;
            case 5: value = providerLabel(data.ddnsProvider); break;
            case 6: value = ddnsLabel("disabled".equals(data.ddnsProvider) ? "DISABLED"
                    : runtime.phase() == RuntimeStatus.Phase.STOPPED ? "STOPPED" : runtime.ddnsStatus()); break;
            default: value = getString(R.string.unknown); break;
        }
        return TerminalStatusAnimator.Value.text(value);
    }

    private String terminalNetworkError(final NetworkCheckResult result) {
        switch (result) {
            case HTTPS_FAILED: return getString(R.string.terminal_https_failed);
            case TIMEOUT: return getString(R.string.terminal_network_timeout);
            case CANCELLED: return getString(R.string.check_cancelled);
            default: return getString(R.string.terminal_network_unavailable);
        }
    }

    private String runtimePhaseText(final RuntimeStatus status) {
        switch (status.phase()) {
            case RUNNING: return getString(R.string.runtime_running, status.enabledPeers());
            case STARTING: return getString(R.string.runtime_starting);
            case WAITING_NETWORK: return getString(R.string.runtime_waiting, status.safeCode(), UiMessages.waiting(this, status.safeCode()));
            case STOPPING: return getString(R.string.runtime_stopping);
            case ERROR: return getString(R.string.runtime_error, status.safeCode());
            default: return getString(R.string.runtime_stopped);
        }
    }

    private String formatRuntimeStatus(final RuntimeStatus status) {
        final String environment = getString(R.string.runtime_environment,
                networkLabel(status.networkType()),
                status.listenPort() == 0 ? getString(R.string.not_read) : Integer.toString(status.listenPort()),
                status.endpoint().isEmpty() ? getString(R.string.waiting_ipv6) : status.endpoint(),
                status.endpointAddress().isEmpty() ? getString(R.string.unavailable) : status.endpointAddress(),
                getString(status.phase() == RuntimeStatus.Phase.RUNNING && status.dnsEnabled() ? R.string.running : R.string.stop),
                providerLabel(status.ddnsProvider()), ddnsLabel(status.ddnsStatus()),
                UiMessages.networkCheck(this, status.networkCheck()));
        return runtimePhaseText(status) + environment;
    }

    private String providerLabel(final String provider) {
        if ("disabled".equals(provider)) return getString(R.string.provider_disabled);
        if ("custom".equals(provider)) return getString(R.string.provider_custom);
        return provider;
    }

    private RuntimeStatus.PeerRuntime findPeerRuntime(final String name) {
        for (final RuntimeStatus.PeerRuntime peer : latestRuntime.peers()) {
            if (peer.name().equals(name)) {
                return peer;
            }
        }
        return null;
    }

    private String formatHandshake(final long unixSeconds) {
        if (unixSeconds <= 0L) {
            return getString(R.string.no_handshake);
        }
        return java.text.DateFormat.getDateTimeInstance(
                java.text.DateFormat.SHORT,
                java.text.DateFormat.SHORT,
                getResources().getConfiguration().getLocales().get(0)).format(new Date(unixSeconds * 1000L));
    }

    private String formatBytes(final long bytes) {
        if (bytes < 1024L) {
            return bytes + " B";
        }
        final String[] units = {"KiB", "MiB", "GiB", "TiB"};
        double value = bytes;
        int unit = -1;
        do {
            value /= 1024.0;
            unit++;
        } while (value >= 1024.0 && unit < units.length - 1);
        return String.format(getResources().getConfiguration().getLocales().get(0), "%.1f %s", value, units[unit]);
    }

    private String networkLabel(final String value) {
        switch (value) {
            case "WIFI": return "Wi-Fi";
            case "CELLULAR": return getString(R.string.network_mobile);
            case "ETHERNET": return getString(R.string.network_ethernet);
            case "VPN": return getString(R.string.network_vpn);
            case "BLUETOOTH": return getString(R.string.network_bluetooth);
            case "OTHER": return getString(R.string.other);
            default: return getString(R.string.unknown);
        }
    }

    private String ddnsLabel(final String value) {
        switch (value) {
            case "DISABLED": return getString(R.string.ddns_disabled);
            case "WAITING": return getString(R.string.ddns_waiting);
            case "STOPPED": return getString(R.string.ddns_stopped);
            case "UPDATED": return getString(R.string.ddns_updated);
            case "UNCHANGED": return getString(R.string.ddns_unchanged);
            case "VERIFIED": return getString(R.string.ddns_verified);
            case "PENDING_VERIFICATION": return getString(R.string.ddns_propagating);
            case "SKIPPED": return getString(R.string.ddns_skipped);
            case "RETRYABLE_ERROR": return getString(R.string.ddns_retryable);
            case "PERMANENT_ERROR": return getString(R.string.ddns_error);
            default: return value;
        }
    }

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(
                    new String[]{Manifest.permission.POST_NOTIFICATIONS},
                    REQUEST_NOTIFICATIONS);
        }
    }

    private LinearLayout vertical() {
        final LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    private LinearLayout horizontal() {
        final LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.HORIZONTAL);
        layout.setGravity(android.view.Gravity.CENTER_VERTICAL);
        return layout;
    }

    private LinearLayout actionRow() {
        final LinearLayout layout = horizontal();
        layout.setPadding(0, dp(6), 0, 0);
        return layout;
    }

    private TextView text(final String value, final int sp, final boolean bold) {
        final TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(getColor(R.color.black));
        if (bold) {
            view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        }
        return view;
    }

    private TextView sectionTitle(final String value) {
        final TextView view = text(value, 20, true);
        view.setPadding(0, dp(10), 0, dp(8));
        return view;
    }

    /** 只切换主页内容的可见性：保留输入控件，不重载设置，也不触发服务或网络操作。 */
    private LinearLayout addCollapsibleSection(final LinearLayout root,
            final int titleResource, final boolean connectionSection) {
        final LinearLayout header = horizontal();
        header.setMinimumHeight(dp(48));
        header.setClickable(true);
        header.setFocusable(true);
        header.addView(sectionTitle(getString(titleResource)), weight());
        final TextView arrow = text("", 22, true);
        arrow.setGravity(android.view.Gravity.CENTER);
        arrow.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        header.addView(arrow, new LinearLayout.LayoutParams(dp(36), ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(header, matchWrap());
        final LinearLayout content = vertical();
        root.addView(content, marginBottom(8));
        final Runnable update = () -> {
            final boolean expanded = connectionSection ? connectionExpanded : ddnsExpanded;
            content.setVisibility(expanded ? View.VISIBLE : View.GONE);
            arrow.setText(expanded ? "▾" : "▸");
            header.setContentDescription(getString(expanded ? R.string.section_collapse
                    : R.string.section_expand, getString(titleResource)));
        };
        header.setOnClickListener(view -> {
            if (connectionSection) {
                connectionExpanded = !connectionExpanded;
            } else {
                ddnsExpanded = !ddnsExpanded;
            }
            update.run();
        });
        update.run();
        return content;
    }

    private EditText input(final String hint, final boolean secret) {
        final EditText field = new EditText(this);
        field.setHint(hint);
        field.setTextSize(15);
        field.setSingleLine(true);
        field.setInputType(secret
                ? InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD
                : InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        field.setLayoutParams(marginBottom(5));
        return field;
    }

    private EditText numericInput(final String hint) {
        final EditText field = input(hint, false);
        field.setInputType(InputType.TYPE_CLASS_NUMBER);
        return field;
    }

    /** 标题和说明保持可见，填入数值后仍能明确识别字段；不改变配置保存或校验。 */
    private void addConnectionField(LinearLayout content, int labelResource, int helpResource,
            EditText field) {
        field.setId(View.generateViewId());
        TextView label = text(getString(labelResource), 16, true);
        label.setLabelFor(field.getId());
        content.addView(label, marginBottom(4));
        content.addView(text(getString(helpResource), 12, false), marginBottom(6));
        content.addView(field, marginBottom(12));
    }

    /** DDNS 的说明在输入框上方，切换服务商时标题/说明与输入框作为整行隐藏。 */
    private DdnsFieldRow addDdnsField(final LinearLayout content, final int labelResource,
            final int helpResource, final EditText field) {
        field.setId(View.generateViewId());
        // DDNS 账户、URL 和输入中的 Token 不进入系统状态 Bundle；重建后重读加密设置。
        field.setSaveEnabled(false);
        field.setSaveFromParentEnabled(false);
        final LinearLayout container = vertical();
        container.setSaveEnabled(false);
        container.setSaveFromParentEnabled(false);
        final TextView label = text("", 16, true);
        label.setLabelFor(field.getId());
        final TextView help = text("", 12, false);
        help.setTextColor(getColor(R.color.atticus_secondary));
        container.addView(label, marginBottom(4));
        container.addView(help, marginBottom(6));
        container.addView(field, marginBottom(12));
        content.addView(container, matchWrap());
        final DdnsFieldRow row = new DdnsFieldRow(container, label, help, field);
        row.setLabels(labelResource, helpResource);
        return row;
    }

    private final class DdnsFieldRow {
        private final LinearLayout container;
        private final TextView label;
        private final TextView help;
        private final EditText field;

        private DdnsFieldRow(final LinearLayout container, final TextView label,
                final TextView help, final EditText field) {
            this.container = container;
            this.label = label;
            this.help = help;
            this.field = field;
        }

        private void setLabels(final int labelResource, final int helpResource) {
            label.setText(labelResource);
            help.setText(helpResource);
            // 输入框内只显示短字段名，具体说明始终放在其上方。
            field.setHint(labelResource);
        }

        private void setVisible(final boolean visible) {
            if (!visible && field.hasFocus()) field.clearFocus();
            container.setVisibility(visible ? View.VISIBLE : View.GONE);
        }
    }

    private Spinner spinner(final String[] values) {
        final Spinner spinner = new Spinner(this);
        spinner.setAdapter(new ArrayAdapter<>(
                this, android.R.layout.simple_spinner_dropdown_item, values));
        return spinner;
    }

    private LinearLayout labelAndView(final String label, final View view) {
        final LinearLayout row = horizontal();
        final TextView text = text(label, 14, false);
        row.addView(text, weight());
        row.addView(view, new LinearLayout.LayoutParams(dp(150), ViewGroup.LayoutParams.WRAP_CONTENT));
        return row;
    }

    private Button button(final String value) {
        final Button button = new Button(this);
        button.setText(value);
        button.setAllCaps(false);
        return button;
    }

    private Button smallButton(final String value) {
        final Button button = button(value);
        button.setTextSize(12);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setPadding(dp(9), 0, dp(9), 0);
        return button;
    }

    private GradientDrawable rounded(final int color, final int radius) {
        final GradientDrawable background = new GradientDrawable();
        background.setColor(color);
        background.setCornerRadius(radius);
        return background;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams weight() {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
    }

    private LinearLayout.LayoutParams marginBottom(final int value) {
        final LinearLayout.LayoutParams params = matchWrap();
        params.bottomMargin = dp(value);
        return params;
    }

    private LinearLayout.LayoutParams marginTop(final int value) {
        final LinearLayout.LayoutParams params = matchWrap();
        params.topMargin = dp(value);
        return params;
    }

    private int dp(final int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static void selectSpinner(
            final Spinner spinner,
            final String[] values,
            final String wanted) {
        for (int index = 0; index < values.length; index++) {
            if (values[index].equalsIgnoreCase(wanted)) {
                spinner.setSelection(index);
                return;
            }
        }
        spinner.setSelection(0);
    }

    /** UI 不可变 Peer 快照，故意不包含任何 key。 */
    private static final class PeerItem {
        private final String id;
        private final String name;
        private final String ipv4;
        private final String ipv6;
        private final boolean enabled;
        private final boolean sealed;
        private final boolean exportable;

        private PeerItem(final StoredPeer peer, final boolean exportable) {
            id = peer.id();
            name = peer.name();
            ipv4 = peer.ipv4Cidr();
            ipv6 = peer.ipv6Cidr();
            enabled = peer.enabled();
            sealed = peer.sealed();
            this.exportable = exportable;
        }
    }

    private static final class UiSnapshot {
        private final SettingsValues settings;
        private final List<PeerItem> peers;

        private UiSnapshot(final SettingsValues settings, final List<PeerItem> peers) {
            this.settings = settings;
            this.peers = peers;
        }

        private static UiSnapshot from(
                final ServerState state,
                final EncryptedStateRepository repository) {
            final List<PeerItem> peers = new ArrayList<>();
            for (final StoredPeer peer : state.peers()) {
                peers.add(new PeerItem(peer, repository.isExportable(peer.id())));
            }
            return new UiSnapshot(SettingsValues.from(state), peers);
        }
    }

    /** 已规范化设置；包含秘密但从不实现 toString，也不会跨 Intent 或主线程状态总线。 */
    private static final class SettingsValues {
        private String endpointHost;
        private int listenPort;
        private int mtu;
        private boolean dnsEnabled;
        private String dnsMode;
        private String customDns;
        private String ddnsProvider;
        private String ddnsHostname;
        private String ddnsAccount;
        private String ddnsSecret;
        private String ddnsZone;
        private String ddnsRecord;
        private String ddnsCustomUrl;

        private static SettingsValues from(final ServerState state) {
            final SettingsValues value = new SettingsValues();
            value.endpointHost = nullToEmpty(state.endpointHost);
            value.listenPort = state.listenPort;
            value.mtu = state.mtu;
            value.dnsEnabled = state.dnsEnabled;
            value.dnsMode = nullToEmpty(state.dnsMode);
            value.customDns = nullToEmpty(state.customDns);
            try {
                value.ddnsProvider = AppSettingsPolicy.normalizeProvider(state.ddnsProvider);
            } catch (final RuntimeException ignored) {
                value.ddnsProvider = "disabled";
            }
            value.ddnsHostname = nullToEmpty(state.ddnsHostname);
            value.ddnsAccount = nullToEmpty(state.ddnsAccount);
            value.ddnsSecret = nullToEmpty(state.ddnsSecret);
            value.ddnsZone = nullToEmpty(state.ddnsZone);
            value.ddnsRecord = nullToEmpty(state.ddnsRecord);
            value.ddnsCustomUrl = nullToEmpty(state.ddnsCustomUrl);
            return value;
        }

        private void apply(final ServerState state) {
            state.endpointHost = endpointHost;
            state.listenPort = listenPort;
            state.mtu = mtu;
            state.dnsEnabled = dnsEnabled;
            state.dnsMode = dnsMode;
            state.customDns = customDns;
            state.ddnsProvider = ddnsProvider;
            state.ddnsHostname = ddnsHostname;
            state.ddnsAccount = ddnsAccount;
            state.ddnsSecret = ddnsSecret;
            state.ddnsZone = ddnsZone;
            state.ddnsRecord = ddnsRecord;
            state.ddnsCustomUrl = ddnsCustomUrl;
        }
    }

    /** 主线程采集的草稿；后台执行完整校验后才可覆盖持久状态。 */
    private static final class SettingsDraft {
        private String endpointHost;
        private int listenPort;
        private int mtu;
        private boolean dnsEnabled;
        private String dnsMode;
        private String customDns;
        private String ddnsProvider;
        private String ddnsHostname;
        private String ddnsAccount;
        private String newDdnsSecret;
        private String ddnsZone;
        private String ddnsRecord;
        private String ddnsCustomUrl;

        private static SettingsDraft capture(final MainActivity activity) {
            final SettingsDraft value = new SettingsDraft();
            value.endpointHost = activity.endpointField.getText().toString();
            value.listenPort = Integer.parseInt(activity.portField.getText().toString());
            value.mtu = Integer.parseInt(activity.mtuField.getText().toString());
            value.dnsEnabled = activity.dnsEnabledField.isChecked();
            value.dnsMode = DNS_MODES[activity.dnsModeField.getSelectedItemPosition()];
            value.customDns = activity.customDnsField.getText().toString();
            value.ddnsProvider = DDNS_PROVIDERS[activity.ddnsProviderField.getSelectedItemPosition()];
            value.ddnsHostname = activity.ddnsHostnameField.getText().toString();
            value.ddnsAccount = activity.ddnsAccountField.getText().toString();
            value.newDdnsSecret = activity.ddnsSecretField.getText().toString();
            value.ddnsZone = activity.ddnsZoneField.getText().toString();
            value.ddnsRecord = activity.ddnsRecordField.getText().toString();
            value.ddnsCustomUrl = activity.ddnsCustomUrlField.getText().toString();
            return value;
        }

        private SettingsValues validate(final SettingsValues previous) {
            final SettingsValues value = new SettingsValues();
            value.endpointHost = AppSettingsPolicy.normalizeOptionalEndpointHost(endpointHost);
            AppSettingsPolicy.requirePortAndMtu(listenPort, mtu);
            value.listenPort = listenPort;
            value.mtu = mtu;
            value.dnsEnabled = dnsEnabled;
            value.dnsMode = AppSettingsPolicy.normalizeDnsMode(dnsMode);
            value.customDns = AppSettingsPolicy.normalizeCustomDns(customDns);
            if (AppSettingsPolicy.DNS_CUSTOM.equals(value.dnsMode) && value.customDns.isEmpty()) {
                throw new IllegalArgumentException("CUSTOM_DNS_REQUIRED");
            }
            value.ddnsProvider = AppSettingsPolicy.normalizeProvider(ddnsProvider);
            final Set<DdnsFormPolicy.Field> fields = DdnsFormPolicy.fields(value.ddnsProvider);
            // 明确保存时只采纳当前可见字段。隐藏的未提交草稿不应阻止保存，也不能
            // 覆盖此前持久值；例如 DuckDNS 的短名称切换为“关闭”后不再作为域名校验。
            value.ddnsHostname = fields.contains(DdnsFormPolicy.Field.HOSTNAME)
                    ? AppSettingsPolicy.normalizeDdnsHostname(ddnsHostname, value.ddnsProvider)
                    : previous.ddnsHostname;
            value.ddnsAccount = fields.contains(DdnsFormPolicy.Field.ACCOUNT)
                    ? bounded(ddnsAccount, 256, "INVALID_DDNS_ACCOUNT") : previous.ddnsAccount;
            value.ddnsZone = fields.contains(DdnsFormPolicy.Field.ZONE_ID)
                    ? bounded(ddnsZone, 256, "INVALID_DDNS_ZONE") : previous.ddnsZone;
            value.ddnsRecord = fields.contains(DdnsFormPolicy.Field.RECORD_ID)
                    ? bounded(ddnsRecord, 256, "INVALID_DDNS_RECORD") : previous.ddnsRecord;
            value.ddnsCustomUrl = fields.contains(DdnsFormPolicy.Field.CUSTOM_URL)
                    ? bounded(ddnsCustomUrl, 8192, "INVALID_DDNS_URL") : previous.ddnsCustomUrl;
            final String submittedSecret = fields.contains(DdnsFormPolicy.Field.CREDENTIAL)
                    ? newDdnsSecret : "";
            final boolean providerUnchanged = value.ddnsProvider.equals(previous.ddnsProvider);
            value.ddnsSecret = submittedSecret.isEmpty() && providerUnchanged
                    ? previous.ddnsSecret
                    : bounded(submittedSecret, 4096, "INVALID_DDNS_SECRET");
            requireProviderFields(value);
            return value;
        }

        private static void requireProviderFields(final SettingsValues value) {
            switch (value.ddnsProvider) {
                case "disabled":
                    return;
                case "duckdns":
                case "freedns":
                    requireNonEmpty(value.ddnsSecret, "DDNS_SECRET_REQUIRED");
                    return;
                case "cloudflare":
                    requireNonEmpty(value.ddnsZone, "DDNS_ZONE_REQUIRED");
                    requireNonEmpty(value.ddnsRecord, "DDNS_RECORD_REQUIRED");
                    requireNonEmpty(value.ddnsSecret, "DDNS_SECRET_REQUIRED");
                    return;
                case "noip":
                case "dynu":
                    requireNonEmpty(value.ddnsAccount, "DDNS_ACCOUNT_REQUIRED");
                    requireNonEmpty(value.ddnsSecret, "DDNS_SECRET_REQUIRED");
                    return;
                case "custom":
                    if (!value.ddnsCustomUrl.startsWith("https://")
                            || !value.ddnsCustomUrl.contains("{ipv6}")) {
                        throw new IllegalArgumentException("INVALID_CUSTOM_DDNS_URL");
                    }
                    return;
                default:
                    throw new IllegalArgumentException("INVALID_DDNS_PROVIDER");
            }
        }

        private static String bounded(final String raw, final int maximum, final String code) {
            final String value = nullToEmpty(raw).trim();
            if (value.length() > maximum || value.indexOf('\0') >= 0
                    || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) {
                throw new IllegalArgumentException(code);
            }
            return value;
        }

        private static void requireNonEmpty(final String value, final String code) {
            if (value.isEmpty()) {
                throw new IllegalArgumentException(code);
            }
        }
    }

    private static String nullToEmpty(final String value) {
        return value == null ? "" : value;
    }
}
