/* Copyright (c) 2026 TianzeL; SPDX-License-Identifier: Apache-2.0 */
package dev.lan.atticusvpn.server.ui;

import android.content.Context;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.Spinner;

/**
 * 三页原生容器：只显示既有 View，不重新读取、保存配置或操作服务器。
 * 横滑在 dispatch 层判定，避免终端纵向滚动禁止父截获后无法左右切页。
 * 只有明确的单指水平手势才取消子控件点击；文本输入/下拉框区域保留原手势。
 */
final class HomePagesView extends FrameLayout {
    interface Listener { void selected(int index); }
    private final int touchSlop;
    private final int swipeDistance;
    private int selected;
    private float downX;
    private float downY;
    private boolean blocked;
    private boolean swiping;
    private Listener listener;

    HomePagesView(Context context) {
        super(context);
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        swipeDistance = Math.round(48 * getResources().getDisplayMetrics().density);
        setLayoutDirection(LAYOUT_DIRECTION_LTR);
    }

    void setListener(Listener value) { listener = value; }

    void select(int wanted) {
        final int bounded = Math.max(0, Math.min(getChildCount() - 1, wanted));
        selected = bounded;
        for (int index = 0; index < getChildCount(); index++) {
            getChildAt(index).setVisibility(index == bounded ? VISIBLE : GONE);
        }
        if (listener != null) listener.selected(bounded);
    }

    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        final int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            downX = event.getX();
            downY = event.getY();
            swiping = false;
            blocked = getChildCount() == 0 || isEditingControl(getChildAt(selected), downX, downY);
        } else if (event.getPointerCount() > 1 || action == MotionEvent.ACTION_POINTER_DOWN) {
            blocked = true; // 多指手势不翻页。
            if (swiping) return true;
        } else if (action == MotionEvent.ACTION_MOVE && !blocked && !swiping) {
            final float dx = Math.abs(event.getX() - downX);
            final float dy = Math.abs(event.getY() - downY);
            if (dy > touchSlop && dy > dx * 1.2f) blocked = true;
            else if (dx > touchSlop * 2f && dx > dy * 1.5f) {
                // 显式取消原按钮/列表的触摸目标，不能把翻页误当添加/保存/启停。
                final MotionEvent cancel = MotionEvent.obtain(event);
                cancel.setAction(MotionEvent.ACTION_CANCEL);
                super.dispatchTouchEvent(cancel);
                cancel.recycle();
                swiping = true;
            }
        }
        if (swiping) {
            if (action == MotionEvent.ACTION_UP) {
                final float dx = event.getX() - downX;
                if (!blocked && Math.abs(dx) >= swipeDistance) select(selected + (dx < 0 ? 1 : -1));
                swiping = false;
            } else if (action == MotionEvent.ACTION_CANCEL) swiping = false;
            return true;
        }
        return super.dispatchTouchEvent(event);
    }

    /** 命中测试同时考虑页内纵滚；输入框中拖动光标和 Spinner 操作不拦截。 */
    private static boolean isEditingControl(View view, float parentX, float parentY) {
        if (view.getVisibility() != VISIBLE) return false;
        final float x = parentX - view.getLeft() - view.getTranslationX();
        final float y = parentY - view.getTop() - view.getTranslationY();
        if (x < 0 || y < 0 || x >= view.getWidth() || y >= view.getHeight()) return false;
        if (view instanceof EditText || view instanceof Spinner) return true;
        if (view instanceof ViewGroup) {
            final ViewGroup group = (ViewGroup) view;
            for (int index = group.getChildCount() - 1; index >= 0; index--) {
                if (isEditingControl(group.getChildAt(index), x + view.getScrollX(), y + view.getScrollY())) return true;
            }
        }
        return false;
    }
}
