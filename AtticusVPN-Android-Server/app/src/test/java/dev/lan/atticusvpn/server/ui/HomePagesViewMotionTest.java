/* Copyright (c) 2026 TianzeL; SPDX-License-Identifier: Apache-2.0 */
package dev.lan.atticusvpn.server.ui;

import android.app.Activity;
import android.app.Application;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

/** Actual MotionEvents and native View state in a JVM Android runtime, not phone acceptance. */
@RunWith(org.robolectric.RobolectricTestRunner.class)
@Config(sdk=35, application=Application.class)
@LooperMode(LooperMode.Mode.PAUSED)
public final class HomePagesViewMotionTest {
    private ActivityController<Activity> controller;
    private Activity activity;
    private long eventTime, downTime;

    private HomePagesView pages() {
        controller=Robolectric.buildActivity(Activity.class).setup();
        activity=controller.get();
        HomePagesView pages=new HomePagesView(activity);
        for(int i=0;i<3;i++) {
            FrameLayout page=new FrameLayout(activity);page.setClickable(true);
            pages.addView(page,new FrameLayout.LayoutParams(-1,-1));
        }
        activity.setContentView(pages);
        pages.measure(View.MeasureSpec.makeMeasureSpec(400,View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(700,View.MeasureSpec.EXACTLY));
        pages.layout(0,0,400,700);
        pages.select(0);
        return pages;
    }

    private void touch(HomePagesView pages,int action,float x,float y) {
        eventTime=Math.max(eventTime,SystemClock.uptimeMillis())+32;
        if(action==MotionEvent.ACTION_DOWN)downTime=eventTime;
        MotionEvent event=MotionEvent.obtain(downTime,eventTime,action,x,y,0);
        pages.dispatchTouchEvent(event);event.recycle();
    }
    private void drain() {shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(800));}
    private void dragHalf(HomePagesView pages) {
        touch(pages,MotionEvent.ACTION_DOWN,340,300);
        touch(pages,MotionEvent.ACTION_MOVE,260,300); // Claim horizontal gesture and cancel child.
        touch(pages,MotionEvent.ACTION_MOVE,80,300);
    }
    @After public void destroy() {
        if(controller!=null)controller.pause().stop().destroy();
    }

    @Test public void fingerProgressMovesBothRetainedPagesBeforeSelectionCommits() {
        HomePagesView pages=pages();View first=pages.getChildAt(0),second=pages.getChildAt(1);
        AtomicInteger committed=new AtomicInteger(0);pages.setListener(committed::set);
        List<Float> progress=new ArrayList<>();List<Boolean> dragging=new ArrayList<>();
        pages.setProgressListener((p,s,d)->{progress.add(p);dragging.add(d);});
        dragHalf(pages);
        assertTrue(pages.position()>0 && pages.position()<1);
        assertEquals(-pages.position()*pages.getWidth(),first.getTranslationX(),.1f);
        assertEquals((1-pages.position())*pages.getWidth(),second.getTranslationX(),.1f);
        assertEquals(pages.position(),progress.get(progress.size()-1),.0001f);
        assertTrue(dragging.get(dragging.size()-1));assertEquals(0,committed.get());
        touch(pages,MotionEvent.ACTION_UP,80,300);drain();
        assertEquals(1f,pages.position(),.0001f);assertEquals(1,committed.get());
        assertSame(first,pages.getChildAt(0));assertSame(second,pages.getChildAt(1));assertFalse(pages.isMoving());
        assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS,first.getImportantForAccessibility());
        assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_AUTO,second.getImportantForAccessibility());
        for(int i=0;i<3;i++)assertEquals(View.LAYER_TYPE_NONE,pages.getChildAt(i).getLayerType());
    }

    @Test public void systemCancelReturnsToOriginAndMultitouchDoesNotCommit() {
        HomePagesView pages=pages();dragHalf(pages);touch(pages,MotionEvent.ACTION_CANCEL,80,300);drain();
        assertEquals(0,pages.position(),.0001f);
        dragHalf(pages);
        MotionEvent.PointerProperties[] properties={new MotionEvent.PointerProperties(),new MotionEvent.PointerProperties()};
        MotionEvent.PointerCoords[] coordinates={new MotionEvent.PointerCoords(),new MotionEvent.PointerCoords()};
        for(int i=0;i<2;i++){properties[i].id=i;properties[i].toolType=MotionEvent.TOOL_TYPE_FINGER;coordinates[i].x=80+20*i;coordinates[i].y=300;coordinates[i].pressure=1;coordinates[i].size=1;}
        eventTime+=32;
        MotionEvent multi=MotionEvent.obtain(downTime,eventTime,MotionEvent.ACTION_POINTER_DOWN|(1<<MotionEvent.ACTION_POINTER_INDEX_SHIFT),2,properties,coordinates,0,0,1,1,0,0,InputDevice.SOURCE_TOUCHSCREEN,0);
        pages.dispatchTouchEvent(multi);multi.recycle();touch(pages,MotionEvent.ACTION_UP,80,300);drain();
        assertEquals(0,pages.position(),.0001f);assertFalse(pages.isMoving());
    }

    @Test public void claimedHorizontalGestureCancelsChildInsteadOfClickingIt() {
        HomePagesView pages=pages();TouchRecorder child=new TouchRecorder(activity);
        ((FrameLayout)pages.getChildAt(0)).addView(child,new FrameLayout.LayoutParams(-1,-1));
        pages.measure(View.MeasureSpec.makeMeasureSpec(400,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(700,View.MeasureSpec.EXACTLY));pages.layout(0,0,400,700);
        dragHalf(pages);touch(pages,MotionEvent.ACTION_UP,80,300);drain();
        assertTrue(child.actions.contains(MotionEvent.ACTION_CANCEL));assertFalse(child.actions.contains(MotionEvent.ACTION_UP));assertEquals(0,child.clicks);
    }

    @Test public void verticalGestureRemainsWithChild() {
        HomePagesView pages=pages();TouchRecorder child=new TouchRecorder(activity);
        ((FrameLayout)pages.getChildAt(0)).addView(child,new FrameLayout.LayoutParams(-1,-1));
        pages.measure(View.MeasureSpec.makeMeasureSpec(400,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(700,View.MeasureSpec.EXACTLY));pages.layout(0,0,400,700);
        touch(pages,MotionEvent.ACTION_DOWN,200,150);touch(pages,MotionEvent.ACTION_MOVE,202,240);touch(pages,MotionEvent.ACTION_UP,202,240);
        assertEquals(0,pages.position(),.0001f);assertFalse(child.actions.contains(MotionEvent.ACTION_CANCEL));assertTrue(child.actions.contains(MotionEvent.ACTION_UP));
    }

    @Test public void editingControlsKeepTheirHorizontalGesturesAndDraft() {
        HomePagesView pages=pages();EditText edit=new EditText(activity);edit.setText("未保存 draft IPv6");
        ((FrameLayout)pages.getChildAt(0)).addView(edit,new FrameLayout.LayoutParams(400,140));
        Spinner spinner=new Spinner(activity);FrameLayout.LayoutParams spinnerLayout=new FrameLayout.LayoutParams(400,140);spinnerLayout.topMargin=150;
        ((FrameLayout)pages.getChildAt(0)).addView(spinner,spinnerLayout);
        pages.measure(View.MeasureSpec.makeMeasureSpec(400,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(700,View.MeasureSpec.EXACTLY));pages.layout(0,0,400,700);
        touch(pages,MotionEvent.ACTION_DOWN,280,60);touch(pages,MotionEvent.ACTION_MOVE,60,60);touch(pages,MotionEvent.ACTION_CANCEL,60,60);
        assertEquals(0,pages.position(),.0001f);assertEquals("未保存 draft IPv6",edit.getText().toString());
        touch(pages,MotionEvent.ACTION_DOWN,280,200);touch(pages,MotionEvent.ACTION_MOVE,60,200);touch(pages,MotionEvent.ACTION_CANCEL,60,200);
        assertEquals(0,pages.position(),.0001f);
    }

    @Test public void bottomDragSharesContinuousPositionAndCancelUsesCommittedPage() {
        HomePagesView pages=pages();pages.beginNavigationDrag();pages.dragTo(.65f);
        assertTrue(pages.isMoving());assertEquals(.65f,pages.position(),.0001f);
        pages.endNavigationDrag(true);drain();assertEquals(0,pages.position(),.0001f);
        pages.beginNavigationDrag();pages.dragTo(.65f);pages.endNavigationDrag(false);drain();assertEquals(1,pages.position(),.0001f);
        pages.beginNavigationDrag();pages.dragTo(20);assertEquals(2,pages.position(),.0001f);pages.endNavigationDrag(false);drain();assertEquals(2,pages.position(),.0001f);
    }

    @Test public void tapsCanInterruptSettlingWithoutJumpingOrRecreatingPages() {
        HomePagesView pages=pages();View first=pages.getChildAt(0);
        pages.select(2);
        // Set the real ValueAnimator's play time deterministically; a paused
        // JVM frame scheduler does not model hardware display cadence.
        android.animation.ValueAnimator animation=org.robolectric.util.ReflectionHelpers.getField(pages,"animator");
        assertNotNull(animation);animation.setCurrentPlayTime(100);
        float before=pages.position();assertTrue("position="+before+", moving="+pages.isMoving()+", width="+pages.getWidth(),before>0 && before<2);
        pages.beginNavigationDrag();assertEquals(before,pages.position(),.0001f);
        pages.dragTo(before+.1f);pages.endNavigationDrag(true);drain();assertEquals(0,pages.position(),.0001f);
        pages.select(1);pages.select(2);drain();assertEquals(2,pages.position(),.0001f);assertSame(first,pages.getChildAt(0));
    }

    @Test @Config(sdk=24) public void api24ZeroAnimatorScaleSettlesWithoutSyntheticAnimation() {
        HomePagesView pages=pages();
        Settings.Global.putFloat(activity.getContentResolver(),Settings.Global.ANIMATOR_DURATION_SCALE,0f);
        pages.select(2);assertEquals(2,pages.position(),.0001f);assertFalse(pages.isMoving());
        pages.beginNavigationDrag();pages.dragTo(.4f);assertEquals(.4f,pages.position(),.0001f);pages.endNavigationDrag(false);
        assertEquals(0,pages.position(),.0001f);assertFalse(pages.isMoving());
    }

    @Test public void pageScrollAndDraftAreSameObjectsAfterRepeatedNavigation() {
        HomePagesView pages=pages();FrameLayout first=(FrameLayout)pages.getChildAt(0);
        ScrollView scroll=new ScrollView(activity);EditText draft=new EditText(activity);draft.setText("kept draft");
        FrameLayout tall=new FrameLayout(activity);tall.setMinimumHeight(1800);tall.addView(draft,new FrameLayout.LayoutParams(200,100));
        scroll.addView(tall,new ViewGroup.LayoutParams(-1,1800));first.addView(scroll,new FrameLayout.LayoutParams(-1,-1));
        pages.measure(View.MeasureSpec.makeMeasureSpec(400,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(700,View.MeasureSpec.EXACTLY));pages.layout(0,0,400,700);scroll.scrollTo(0,180);
        pages.select(2);drain();pages.select(0);drain();
        assertSame(first,pages.getChildAt(0));assertEquals("kept draft",draft.getText().toString());assertEquals(180,scroll.getScrollY());
    }

    private static final class TouchRecorder extends View {
        final List<Integer> actions=new ArrayList<>();int clicks;
        TouchRecorder(Activity activity){super(activity);setClickable(true);}
        @Override public boolean onTouchEvent(MotionEvent event){actions.add(event.getActionMasked());if(event.getActionMasked()==MotionEvent.ACTION_UP)performClick();return true;}
        @Override public boolean performClick(){clicks++;return super.performClick();}
    }
}
