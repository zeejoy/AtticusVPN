/* Copyright (c) 2026 TianzeL; SPDX-License-Identifier: Apache-2.0 */
package dev.lan.atticusvpn.server.ui;

import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * 终端显示层：不采集配置、不启动服务、不读写密钥。
 *
 * <p>打字、等待与光标是独立时钟。按 Unicode 码点追加，不拆开代理对；字段未就绪时
 * 只暂停打字，光标保持规律闪烁。每轮只有一个代号，销毁或新轮会丢弃旧回调。</p>
 */
final class TerminalStatusAnimator implements AutoCloseable {
    static final long INITIAL_PAUSE_MS = 1_000L;
    static final long PREFIX_TYPING_MS = 1_000L;
    static final long FIELD_WAIT_MS = 5_000L;
    // 当前 Windows GetCaretBlinkTime 的值；每 530 ms 切换明/暗，不随字符输出重置。
    static final long CURSOR_BLINK_MS = 530L;

    interface Source {
        List<Part> parts();
        void waiting(String key);
        String timeout(String key);
        void completed();
    }

    static final class Value {
        final String text;
        final String error;

        private Value(String text, String error) { this.text = text; this.error = error; }
        static Value text(String text) { return new Value(text, null); }
        static Value error(String error) { return new Value(null, error); }
    }

    static final class Part {
        final String key;
        final Supplier<Value> resolve;
        final boolean prefix;

        private Part(String key, Supplier<Value> resolve, boolean prefix) {
            this.key = key; this.resolve = resolve; this.prefix = prefix;
        }
        static Part fixed(String text) { return new Part("", () -> Value.text(text), true); }
        static Part field(String key, Supplier<Value> resolve) { return new Part(key, resolve, true); }
        static Part result(Supplier<Value> resolve) { return new Part("PROBE", resolve, false); }
    }

    private final TextView view;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Handler blinkMain = new Handler(Looper.getMainLooper());
    private final StringBuilder output = new StringBuilder();
    private final List<Part> parts = new ArrayList<>();
    private Source source;
    private int index;
    private String pendingText;
    private int pendingOffset;
    private long generation;
    private double nextCharacterAt;
    private double characterInterval;
    private long waitStarted;
    private boolean busy;
    private boolean cursorVisible = true;
    private boolean blinking;
    private boolean closed;
    private boolean errorOutput;
    private final Runnable blink = new Runnable() {
        @Override public void run() {
            if (closed || !blinking) return;
            cursorVisible = !cursorVisible;
            render();
            blinkMain.postDelayed(this, CURSOR_BLINK_MS);
        }
    };

    TerminalStatusAnimator(TextView view) { this.view = view; }
    boolean isBusy() { return busy; }

    /** 提示符立即显示；一秒内收集工作由 Source 的调用方并行推进。 */
    void begin(Source source, String command) {
        if (closed) return;
        main.removeCallbacksAndMessages(null);
        generation++;
        final long token = generation;
        this.source = source;
        busy = true;
        errorOutput = false;
        pendingText = null;
        pendingOffset = 0;
        waitStarted = 0L;
        index = 0;
        parts.clear();
        output.setLength(0);
        output.append('>');
        if (!blinking) {
            blinking = true;
            cursorVisible = true;
            blinkMain.postDelayed(blink, CURSOR_BLINK_MS);
        }
        render();
        main.postDelayed(() -> {
            if (!valid(token)) return;
            final List<Part> information = source.parts();
            // 就绪时精确按整段码点数分摊 1 秒；缺项时只用长度估计，等待时间另计。
            int prefixCharacters = 0;
            for (Part part : information) {
                if (!part.prefix) break;
                Value value = part.resolve.get();
                String text = value == null || value.text == null ? "......" : value.text;
                prefixCharacters += text.codePointCount(0, text.length());
            }
            characterInterval = PREFIX_TYPING_MS / (double) Math.max(1, prefixCharacters);
            if (command != null && !command.isEmpty()) parts.add(Part.fixed(" " + command + "\n"));
            parts.addAll(information);
            nextCharacterAt = SystemClock.uptimeMillis();
            tick(token);
        }, INITIAL_PAUSE_MS);
    }

    private boolean valid(long token) { return !closed && busy && generation == token; }

    private void tick(long token) {
        if (!valid(token)) return;
        if (pendingText == null) {
            if (index >= parts.size()) { finish(); return; }
            final Part part = parts.get(index);
            final Value value = part.resolve.get();
            if (value == null) {
                final long now = SystemClock.uptimeMillis();
                if (waitStarted == 0L) {
                    waitStarted = now;
                    source.waiting(part.key);
                }
                if (now - waitStarted >= FIELD_WAIT_MS) {
                    fail(source.timeout(part.key));
                } else {
                    main.postDelayed(() -> tick(token), 25L);
                }
                return;
            }
            if (value.error != null) { fail(value.error); return; }
            if (waitStarted != 0L) nextCharacterAt = SystemClock.uptimeMillis();
            waitStarted = 0L;
            pendingText = value.text == null ? "" : value.text;
            pendingOffset = 0;
        }
        if (pendingOffset >= pendingText.length()) {
            pendingText = null;
            index++;
            main.post(() -> tick(token));
            return;
        }
        final int codePoint = pendingText.codePointAt(pendingOffset);
        output.appendCodePoint(codePoint);
        pendingOffset += Character.charCount(codePoint);
        render();
        // 使用累计绝对时间与小数间隔，避免把每次布局开销或毫秒舍入累积到 1 秒预算。
        nextCharacterAt += Math.max(1.0, characterInterval);
        main.postAtTime(() -> tick(token), (long) Math.ceil(nextCharacterAt));
    }

    /** 固定本地化错误作为新命令提示行输出，不显示原始异常或底层配置。 */
    private void fail(String message) {
        if (errorOutput) { finish(); return; }
        errorOutput = true;
        parts.clear();
        parts.add(Part.fixed("\n> " + message));
        index = 0;
        pendingText = null;
        pendingOffset = 0;
        waitStarted = 0L;
        nextCharacterAt = SystemClock.uptimeMillis();
        final long token = generation;
        main.post(() -> tick(token));
    }

    private void finish() {
        output.append("\n>");
        busy = false;
        render();
        Source finished = source;
        source = null;
        if (finished != null) finished.completed();
    }

    private void render() {
        SpannableStringBuilder text = new SpannableStringBuilder(output);
        int cursor = text.length();
        text.append('▌');
        // 不删掉暗态光标字符，避免闪烁引起换行、窗口高度或滚动位置跳动。
        text.setSpan(new ForegroundColorSpan(cursorVisible ? view.getCurrentTextColor()
                : Color.TRANSPARENT), cursor, cursor + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        view.setText(text);
    }

    /** 离开主页时取消动画和光标，不影响服务器或已提交的设置事务。 */
    void stop() {
        generation++;
        busy = false;
        main.removeCallbacksAndMessages(null);
        blinking = false;
        blinkMain.removeCallbacksAndMessages(null);
        source = null;
    }

    @Override public void close() { stop(); closed = true; }
}
