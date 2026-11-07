/* Copyright (c) 2026 TianzeL; SPDX-License-Identifier: Apache-2.0 */
package dev.lan.atticusvpn.server.ui;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.os.Build;
import android.provider.Settings;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.animation.PathInterpolator;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.Spinner;

/**
 * Keeps all existing page Views and drafts. Dragged pages and the bottom glass
 * selection share one continuous progress value; navigation never changes VPN state.
 */
final class HomePagesView extends FrameLayout {
    interface Listener { void selected(int index); }
    interface ProgressListener { void progress(float position, float stretch, boolean dragging); }

    private final int touchSlop;
    private final float minimumFlingVelocity;
    private final int maximumFlingVelocity;
    private int selected;
    private int gestureAnchor, navigationAnchor;
    private float pagePosition;
    private float glassStretch;
    private float downX, downY, dragStartX, dragFromPosition, navigationFromPosition;
    private boolean blocked, swiping, ownsTouch, cancelledGesture, navigationDragging;
    private Listener listener;
    private ProgressListener progressListener;
    private ValueAnimator animator;
    private VelocityTracker velocity;
    private int pointerId = -1;

    HomePagesView(Context context) {
        super(context);
        ViewConfiguration configuration = ViewConfiguration.get(context);
        touchSlop = configuration.getScaledTouchSlop();
        minimumFlingVelocity = Math.max(configuration.getScaledMinimumFlingVelocity(),
                480f * getResources().getDisplayMetrics().density);
        maximumFlingVelocity = configuration.getScaledMaximumFlingVelocity();
        setLayoutDirection(LAYOUT_DIRECTION_LTR);
        setClipChildren(true);
        setClipToPadding(true);
    }

    void setListener(Listener value) { listener = value; }
    void setProgressListener(ProgressListener value) {
        progressListener = value;
        dispatchProgress();
    }
    float position() { return PageMotion.clamp(pagePosition, 0f, lastPage()); }
    boolean isMoving() { return swiping || navigationDragging || animator != null; }

    /** The bottom navigation owns its touch stream but shares this page position. */
    void beginNavigationDrag() {
        navigationAnchor = selected;
        navigationFromPosition = pagePosition;
        navigationDragging = true;
        swiping = false;
        if (ownsTouch) cancelledGesture = true;
        recycleVelocity();
        cancelAnimation();
        render(pagePosition, glassStretch);
    }

    void dragTo(float wantedPosition) {
        if (!navigationDragging || !Float.isFinite(wantedPosition)) return;
        float bounded = PageMotion.clamp(wantedPosition, 0f, lastPage());
        render(bounded, Math.min(.65f, Math.abs(bounded - navigationFromPosition) * .35f));
    }

    void endNavigationDrag(boolean cancelled) {
        if (!navigationDragging) return;
        int wanted = cancelled ? navigationAnchor : Math.round(position());
        navigationDragging = false;
        if (ownsTouch) gestureAnchor = wanted;
        settle(wanted, 0f);
    }

    /** A tap settles from the current rendered position, including interrupted animations. */
    void select(int wanted) {
        if (getChildCount() == 0) return;
        recycleVelocity();
        swiping = navigationDragging = false;
        int bounded = Math.max(0, Math.min(lastPage(), wanted));
        if (ownsTouch) {
            cancelledGesture = true;
            gestureAnchor = bounded;
        }
        settle(bounded, 0f);
    }

    private int lastPage() { return Math.max(0, getChildCount() - 1); }

    private void render(float position, float stretch) {
        pagePosition = position;
        glassStretch = PageMotion.clamp(stretch, 0f, 1f);
        float width = getWidth();
        for (int index = 0; index < getChildCount(); index++) {
            View page = getChildAt(index);
            // INVISIBLE still participates in measurement/layout; drafts and
            // independent vertical scroll positions remain in the same Views.
            page.setVisibility(Math.abs(index - position) <= 1.001f ? VISIBLE : INVISIBLE);
            page.setTranslationX((index - position) * width);
        }
        updatePageInteractionState();
        dispatchProgress();
    }

    private void updatePageInteractionState() {
        boolean moving = isMoving();
        for (int index = 0; index < getChildCount(); index++) {
            View page = getChildAt(index);
            page.setImportantForAccessibility(!moving && index == selected
                    ? IMPORTANT_FOR_ACCESSIBILITY_AUTO : IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
            int layer = moving && isHardwareAccelerated() && page.getVisibility() == VISIBLE
                    ? LAYER_TYPE_HARDWARE : LAYER_TYPE_NONE;
            if (page.getLayerType() != layer) page.setLayerType(layer, null);
        }
    }

    private void dispatchProgress() {
        if (progressListener != null) progressListener.progress(position(), glassStretch, swiping || navigationDragging);
    }

    private void cancelAnimation() {
        ValueAnimator previous = animator;
        animator = null; // Cancel must never commit an obsolete animation target.
        if (previous != null) previous.cancel();
        updatePageInteractionState();
    }

    private void settle(int wanted, float fingerVelocity) {
        cancelAnimation();
        float from = pagePosition;
        long duration = PageMotion.duration(from, wanted, getWidth(), fingerVelocity);
        if (getWidth() <= 0 || !isAttachedToWindow() || !animationsEnabled() || duration == 0L) {
            finishSelection(wanted);
            return;
        }
        float initialStretch = glassStretch;
        ValueAnimator next = ValueAnimator.ofFloat(0f, 1f);
        animator = next;
        next.setDuration(duration);
        next.setInterpolator(new PathInterpolator(.18f, .82f, .2f, 1f));
        next.addUpdateListener(value -> {
            if (animator != next) return;
            float fraction = (float) value.getAnimatedValue();
            float pulse = (float) Math.sin(Math.PI * fraction)
                    * Math.min(.22f, Math.abs(wanted - from) * .18f);
            render(from + (wanted - from) * fraction, initialStretch * (1f - fraction) + pulse);
        });
        next.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator value) {
                if (animator != next) return;
                animator = null;
                finishSelection(wanted);
            }
        });
        next.start();
    }

    private void finishSelection(int wanted) {
        selected = Math.max(0, Math.min(lastPage(), wanted));
        render(selected, 0f);
        if (listener != null) listener.selected(selected);
    }

    private boolean animationsEnabled() {
        if (isInEditMode()) return false;
        try {
            if (Build.VERSION.SDK_INT >= 26) return ValueAnimator.areAnimatorsEnabled();
            return Settings.Global.getFloat(getContext().getContentResolver(),
                    Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f;
        } catch (RuntimeException ignored) {
            return true;
        }
    }

    @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        render(pagePosition, glassStretch);
    }

    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        final int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            recycleVelocity();
            velocity = VelocityTracker.obtain();
            pointerId = event.getPointerId(0);
            velocity.addMovement(event);
            // A touch during a transition holds its exact position. It must not
            // accidentally click a business button moving underneath the finger.
            ownsTouch = isMoving() || Math.abs(pagePosition - Math.round(pagePosition)) > .001f;
            swiping = navigationDragging = false;
            cancelAnimation();
            downX = dragStartX = event.getX();
            downY = event.getY();
            dragFromPosition = pagePosition;
            gestureAnchor = Math.round(position());
            cancelledGesture = false;
            blocked = getChildCount() == 0 || (!ownsTouch && hitEditingControl(downX, downY));
            if (ownsTouch) return true;
        } else if (velocity != null) velocity.addMovement(event);

        if (event.getPointerCount() > 1 || action == MotionEvent.ACTION_POINTER_DOWN) {
            boolean firstCancellation = !cancelledGesture;
            blocked = true;
            cancelledGesture = true;
            if (ownsTouch) {
                if (firstCancellation) {
                    swiping = false;
                    settle(gestureAnchor, 0f);
                }
                return true;
            }
        } else if (action == MotionEvent.ACTION_MOVE && !blocked && !swiping) {
            float dx = Math.abs(event.getX() - downX), dy = Math.abs(event.getY() - downY);
            if (dy > touchSlop && dy > dx * 1.2f) blocked = true;
            else if (dx > touchSlop * 2f && dx > dy * 1.5f) {
                if (!ownsTouch) {
                    MotionEvent cancel = MotionEvent.obtain(event);
                    cancel.setAction(MotionEvent.ACTION_CANCEL);
                    super.dispatchTouchEvent(cancel);
                    cancel.recycle();
                }
                ownsTouch = true;
                swiping = true;
                dragStartX = event.getX();
                dragFromPosition = pagePosition;
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
            }
        }

        if (swiping && action == MotionEvent.ACTION_MOVE) {
            float drag = event.getX() - dragStartX;
            float next = PageMotion.drag(dragFromPosition, drag, getWidth(), gestureAnchor, lastPage());
            float stretch = Math.min(.7f, Math.abs(drag) / Math.max(1, getWidth()) * .6f);
            render(next, stretch);
            return true;
        }
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            boolean consumed = ownsTouch;
            if (consumed) {
                float speed = 0f;
                if (velocity != null && !cancelledGesture && action == MotionEvent.ACTION_UP) {
                    velocity.computeCurrentVelocity(1000, maximumFlingVelocity);
                    speed = velocity.getXVelocity(pointerId);
                }
                int wanted = cancelledGesture || action == MotionEvent.ACTION_CANCEL ? gestureAnchor
                        : swiping ? PageMotion.target(pagePosition, speed, getWidth(), gestureAnchor,
                                lastPage(), minimumFlingVelocity) : Math.round(position());
                swiping = false;
                // Multi-touch cancellation has already started its return animation.
                if (!cancelledGesture || animator == null) settle(wanted, speed);
                ownsTouch = false;
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
            }
            recycleVelocity();
            if (consumed) return true;
        }
        return ownsTouch || super.dispatchTouchEvent(event);
    }

    private boolean hitEditingControl(float x, float y) {
        for (int index = getChildCount() - 1; index >= 0; index--)
            if (isEditingControl(getChildAt(index), x, y)) return true;
        return false;
    }

    /** Hit testing respects page translation and each page's vertical scroll. */
    private static boolean isEditingControl(View view, float parentX, float parentY) {
        if (view.getVisibility() != VISIBLE) return false;
        float x = parentX - view.getLeft() - view.getTranslationX();
        float y = parentY - view.getTop() - view.getTranslationY();
        if (x < 0 || y < 0 || x >= view.getWidth() || y >= view.getHeight()) return false;
        if (view instanceof EditText || view instanceof Spinner) return true;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int index = group.getChildCount() - 1; index >= 0; index--)
                if (isEditingControl(group.getChildAt(index), x + view.getScrollX(), y + view.getScrollY())) return true;
        }
        return false;
    }

    private void recycleVelocity() {
        if (velocity != null) velocity.recycle();
        velocity = null;
        pointerId = -1;
    }

    @Override protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        if (visibility != VISIBLE) {
            swiping = ownsTouch = navigationDragging = false;
            cancelAnimation();recycleVelocity();render(selected, 0f);
        }
    }

    @Override protected void onDetachedFromWindow() {
        swiping = ownsTouch = navigationDragging = false;
        cancelAnimation();
        recycleVelocity();
        render(selected, 0f);
        super.onDetachedFromWindow();
    }
}
