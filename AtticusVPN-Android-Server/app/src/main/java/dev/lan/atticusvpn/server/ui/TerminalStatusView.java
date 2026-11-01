/* Copyright (c) 2026 TianzeL; SPDX-License-Identifier: Apache-2.0 */
package dev.lan.atticusvpn.server.ui;

import android.annotation.SuppressLint;
import android.content.Context;
import android.os.LocaleList;
import android.text.Editable;
import android.text.Layout;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ViewGroup;
import android.view.ViewConfiguration;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.Locale;
import dev.lan.atticusvpn.server.R;

/**
 * 340dp 固定终端视口，以用户当前手机中信息末尾的提示符位置为尺寸参考。
 * 内容不再撑大窗口；右侧常显滚动位置，内部滚动不依赖逐字替换的文字 Span。
 * 只负责显示，不访问配置、网络、密钥或设备权限。
 */
final class TerminalStatusView extends ScrollView {
    private static final int HEIGHT_DP = 340;
    private final TextView text;
    private final int touchSlop;
    private float touchX;
    private float touchY;
    private float previousY;
    private boolean touchMoved;
    private boolean followingOutput = true;
    private boolean revealOutput;
    private int previousLength;

    TerminalStatusView(Context context) {
        super(context, null, 0, R.style.Widget_AtticusTerminal);
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        text = new TextView(context);
        text.setGravity(Gravity.TOP | Gravity.START);
        text.setTextDirection(TEXT_DIRECTION_LTR);
        setCompatibleSimpleBreakStrategy(text);
        text.setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE);
        text.setTextLocales(new LocaleList(Locale.ENGLISH, Locale.SIMPLIFIED_CHINESE));
        text.setEllipsize(null);
        addView(text, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        setFillViewport(true);
        setClipToOutline(true);
        setVerticalScrollBarEnabled(true);
        setHorizontalScrollBarEnabled(false);
        setVerticalScrollbarPosition(SCROLLBAR_POSITION_RIGHT);
        setScrollbarFadingEnabled(false);
        setScrollBarStyle(SCROLLBARS_INSIDE_OVERLAY);
        text.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence value, int start, int count, int after) {}
            @Override public void afterTextChanged(Editable value) {}
            @Override public void onTextChanged(CharSequence value, int start, int before, int count) {
                // 闪烁只改变颜色，长度不变，不抢走用户正在查看历史的滚动位置。
                if (value.length() == previousLength) return;
                previousLength = value.length();
                if (value.length() <= 2 && value.length() > 0 && value.charAt(0) == '>') {
                    followingOutput = true;
                    scrollTo(0, 0);
                }
                revealOutput = followingOutput;
            }
        });
    }

    TextView textView() { return text; }

    // SDK 36 的 IntDef 只列 LineBreaker 的同值常量，但该类从 API 29 才提供。
    // Layout 的 SIMPLE 从 API 23 可用且同为 0；仅此处抑制新注解造成的误报。
    @SuppressLint("WrongConstant")
    private static void setCompatibleSimpleBreakStrategy(TextView text) {
        text.setBreakStrategy(Layout.BREAK_STRATEGY_SIMPLE);
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        final int height = Math.round(HEIGHT_DP * getResources().getDisplayMetrics().density);
        super.onMeasure(widthSpec, MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY));
    }

    @Override protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        if (revealOutput && followingOutput) {
            scrollTo(0, Math.max(0, text.getHeight() - getHeight() + getPaddingTop() + getPaddingBottom()));
        }
        revealOutput = false;
    }

    private void beginTouch(MotionEvent event) {
        touchX = event.getX();
        touchY = previousY = event.getY();
        touchMoved = false;
        followingOutput = false;
        if (getParent() != null && (canScrollVertically(-1) || canScrollVertically(1))) {
            getParent().requestDisallowInterceptTouchEvent(true);
        }
    }

    private void endTouch() {
        followingOutput = !canScrollVertically(1);
        if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
    }

    @Override public boolean onInterceptTouchEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) beginTouch(event);
        else if (event.getActionMasked() == MotionEvent.ACTION_UP
                || event.getActionMasked() == MotionEvent.ACTION_CANCEL) endTouch();
        return super.onInterceptTouchEvent(event);
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: beginTouch(event); break;
            case MotionEvent.ACTION_MOVE:
                final float deltaY = event.getY() - previousY;
                previousY = event.getY();
                if (Math.abs(event.getY() - touchY) > touchSlop
                        || Math.abs(event.getX() - touchX) > touchSlop) touchMoved = true;
                if (Math.abs(event.getY() - touchY) > touchSlop
                        && Math.abs(event.getY() - touchY) > Math.abs(event.getX() - touchX)
                        && getParent() != null && deltaY != 0f) {
                    // 按实际上一帧方向判断；内部到边缘时允许继续滚动整个主页。
                    getParent().requestDisallowInterceptTouchEvent(canScrollVertically(deltaY > 0 ? -1 : 1));
                }
                break;
            case MotionEvent.ACTION_UP:
                // 只把未越过拖动阈值的抬手作为点击；不把滑动或取消转换为点击。
                if (!touchMoved && Math.abs(event.getY() - touchY) <= touchSlop
                        && Math.abs(event.getX() - touchX) <= touchSlop) performClick();
                endTouch();
                break;
            case MotionEvent.ACTION_CANCEL: endTouch(); break;
            default: break;
        }
        return super.onTouchEvent(event);
    }

    @Override public boolean performClick() {
        return super.performClick();
    }
}
