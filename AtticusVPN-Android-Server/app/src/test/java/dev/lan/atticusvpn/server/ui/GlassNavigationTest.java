/* Copyright (c) 2026 TianzeL; SPDX-License-Identifier: Apache-2.0 */
package dev.lan.atticusvpn.server.ui;

import android.app.Activity;
import android.app.Application;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.RectF;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import dev.lan.atticusvpn.server.R;
import java.io.File;
import java.io.FileOutputStream;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.LooperMode;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

/** Native Android Canvas/MotionEvent component checks; synthetic data, not phone screenshots. */
@RunWith(org.robolectric.RobolectricTestRunner.class)
@Config(sdk=35,application=Application.class,qualifiers="w400dp-h760dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
public final class GlassNavigationTest {
    private ActivityController<Activity> controller;
    private Activity activity;
    private FrameLayout root;
    private HomePagesView pages;
    private HomePageNavigation navigation;
    private long downTime,eventTime;
    private final AtomicInteger clicks=new AtomicInteger();
    private void setup(boolean dark) {
        RuntimeEnvironment.setQualifiers("w400dp-h760dp"+(dark?"-night":"-notnight")+"-mdpi");
        controller=Robolectric.buildActivity(Activity.class);
        activity=controller.get();activity.setTheme(R.style.Theme_AtticusServer);controller.setup().visible();
        root=new FrameLayout(activity);root.setBackgroundColor(activity.getColor(R.color.atticus_surface));
        pages=new HomePagesView(activity);
        for(int i=0;i<3;i++) {
            LinearLayout page=new LinearLayout(activity);page.setOrientation(LinearLayout.VERTICAL);
            page.setPadding(20,20,20,90);page.setBackgroundColor(activity.getColor(R.color.atticus_surface));
            ImageView wordmark=new ImageView(activity);wordmark.setImageDrawable(UiArtwork.load(activity,R.drawable.atticus_wordmark));
            wordmark.setScaleType(ImageView.ScaleType.FIT_START);page.addView(wordmark,new LinearLayout.LayoutParams(-1,40));
            TextView heading=new TextView(activity);heading.setText("Server · Component preview");heading.setTextSize(20);heading.setTextColor(activity.getColor(R.color.black));page.addView(heading);
            for(int card=0;card<7;card++) {
                TextView text=new TextView(activity);text.setText("Demo device "+(card+1)+"\nIPv6 · WireGuard\n128 MB  ↓    426 MB  ↑");text.setTextSize(16);text.setTextColor(activity.getColor(R.color.black));
                text.setPadding(16,12,16,12);text.setBackgroundColor(activity.getColor(R.color.atticus_card));
                LinearLayout.LayoutParams layout=new LinearLayout.LayoutParams(-1,85);layout.topMargin=10;page.addView(text,layout);
            }
            ScrollView scroll=new ScrollView(activity);scroll.setFillViewport(true);
            scroll.addView(page,new ScrollView.LayoutParams(-1,-2));
            pages.addView(scroll,new FrameLayout.LayoutParams(-1,-1));
        }
        root.addView(pages,new FrameLayout.LayoutParams(-1,-1));
        navigation=new HomePageNavigation(activity);
        FrameLayout.LayoutParams layout=new FrameLayout.LayoutParams(-1,64,Gravity.BOTTOM);layout.setMargins(20,0,20,16);
        root.addView(navigation,layout);navigation.setBackdropSource(pages);
        navigation.setListener(new HomePageNavigation.Listener(){
            @Override public void selected(int page){clicks.incrementAndGet();pages.select(page);}
            @Override public void dragStarted(){pages.beginNavigationDrag();}
            @Override public void dragged(float position){pages.dragTo(position);}
            @Override public void dragFinished(boolean cancelled){pages.endNavigationDrag(cancelled);}
        });
        pages.setProgressListener(navigation::setProgress);pages.setListener(navigation::select);
        activity.setContentView(root);shadowOf(Looper.getMainLooper()).idle();layout();pages.select(0);draw();
    }
    private void layout(){root.measure(View.MeasureSpec.makeMeasureSpec(400,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(760,View.MeasureSpec.EXACTLY));root.layout(0,0,400,760);}
    private Bitmap draw() {
        layout();root.getViewTreeObserver().dispatchOnPreDraw();
        Bitmap bitmap=Bitmap.createBitmap(400,760,Bitmap.Config.ARGB_8888);root.draw(new Canvas(bitmap));return bitmap;
    }
    private void touch(int action,float x,float y) {
        eventTime=Math.max(eventTime,SystemClock.uptimeMillis())+32;if(action==MotionEvent.ACTION_DOWN)downTime=eventTime;
        MotionEvent event=MotionEvent.obtain(downTime,eventTime,action,x,y,0);navigation.dispatchTouchEvent(event);event.recycle();
    }
    private void drain(){shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(700));}
    @After public void destroy(){if(controller!=null)controller.pause().stop().destroy();}

    @Test public void glassTracksFractionalPagePositionBeforeSelectionCommit() {
        setup(false);RectF before=navigation.selectionBounds();pages.beginNavigationDrag();pages.dragTo(.5f);draw();
        RectF during=navigation.selectionBounds();assertEquals(.5f,navigation.position(),.0001f);
        assertTrue(during.centerX()>before.centerX());assertTrue(during.centerX()<navigation.getWidth()/2f);
        assertTrue(during.width()>before.width());assertEquals(.5f,pages.position(),.0001f);
        pages.endNavigationDrag(false);drain();draw();assertEquals(1,navigation.position(),.0001f);
    }
    @Test public void draggingTheBarMovesPagesAndCancelReturnsWithoutClicking() {
        setup(false);touch(MotionEvent.ACTION_DOWN,65,32);touch(MotionEvent.ACTION_MOVE,100,32);touch(MotionEvent.ACTION_MOVE,130,32);
        assertTrue(pages.position()>0);assertEquals(pages.position(),navigation.position(),.0001f);assertEquals(0,clicks.get());
        touch(MotionEvent.ACTION_CANCEL,130,32);drain();assertEquals(0,pages.position(),.0001f);assertEquals(0,clicks.get());
        touch(MotionEvent.ACTION_DOWN,65,32);touch(MotionEvent.ACTION_MOVE,130,32);touch(MotionEvent.ACTION_MOVE,170,32);touch(MotionEvent.ACTION_UP,170,32);drain();
        assertEquals(1,pages.position(),.0001f);assertEquals(0,clicks.get());
    }
    @Test public void tappingAnExistingIconUsesThePagerAndPreservesAllViews() {
        setup(false);View first=pages.getChildAt(0);ViewGroup row=(ViewGroup)navigation.getChildAt(0);
        row.getChildAt(2).performClick();assertEquals(1,clicks.get());assertTrue(pages.isMoving());drain();
        assertEquals(2,pages.position(),.0001f);assertSame(first,pages.getChildAt(0));assertTrue(row.getChildAt(2).isSelected());
    }
    @Test public void realBackdropUpdatesAndBuffersAreReleasedWhenHidden() {
        setup(false);ViewGroup scroll=(ViewGroup)pages.getChildAt(0);
        // Isolate the capture source, so opaque demo cards do not cover the test color.
        scroll.getChildAt(0).setVisibility(View.INVISIBLE);
        scroll.setBackgroundColor(Color.RED);SystemClock.sleep(60);Bitmap red=draw();assertTrue(navigation.hasBackdrop());
        scroll.setBackgroundColor(Color.BLUE);SystemClock.sleep(60);Bitmap blue=draw();
        int x=navigation.getLeft()+navigation.getWidth()/2,y=navigation.getTop()+10;
        assertNotEquals(red.getPixel(x,y),blue.getPixel(x,y));
        activity.setContentView(new View(activity));assertFalse(navigation.hasBackdrop());
    }
    @Test public void lightMaterialRendersAndExportsOnlySyntheticComponentFrames()throws Exception {
        setup(false);assertEquals(0xff007fff,activity.getColor(R.color.glass_icon_selected));export("glass-light",draw());
        String directory=System.getProperty("atticus.glass.previewDir");
        if(directory!=null)for(int i=0;i<=16;i++) {
            pages.beginNavigationDrag();pages.dragTo(i/8f);SystemClock.sleep(60);export(String.format(java.util.Locale.ROOT,"glass-frame-%02d",i),draw());
        }
    }
    @Test public void darkMaterialUsesDarkResourcesAndStillCapturesRealBackdrop()throws Exception {
        setup(true);assertEquals(0xff121820,activity.getColor(R.color.atticus_surface));assertTrue(navigation.hasBackdrop());export("glass-dark",draw());
    }
    @Test public void ancestorCaptureIsRejectedWithoutRecursiveDrawing() {
        setup(false);navigation.setBackdropSource(root);draw();assertFalse(navigation.hasBackdrop());
        navigation.setBackdropSource(pages);SystemClock.sleep(60);draw();assertTrue(navigation.hasBackdrop());
    }
    @Test public void hidingTheWindowCancelsAnUnfinishedBarDragAndDropsItsTexture() {
        setup(false);touch(MotionEvent.ACTION_DOWN,65,32);touch(MotionEvent.ACTION_MOVE,100,32);touch(MotionEvent.ACTION_MOVE,130,32);
        assertTrue(pages.position()>0);navigation.onWindowVisibilityChanged(View.INVISIBLE);
        assertFalse(navigation.hasBackdrop());pages.onWindowVisibilityChanged(View.INVISIBLE);
        assertEquals(0,pages.position(),.0001f);assertFalse(pages.isMoving());assertEquals(0,clicks.get());
        pages.onWindowVisibilityChanged(View.VISIBLE);navigation.onWindowVisibilityChanged(View.VISIBLE);
        pages.select(1);drain();assertEquals(1,pages.position(),.0001f);
    }
    @Test public void idleMaterialDoesNotContinuouslyCaptureFrames()throws Exception {
        setup(false);
        // ScrollView's initial scrollbar fade is a real external animation;
        // disable it for this specifically idle (no content changes) check.
        for(int i=0;i<pages.getChildCount();i++)pages.getChildAt(i).setVerticalScrollBarEnabled(false);
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3));
        java.lang.reflect.Field field=HomePageNavigation.class.getDeclaredField("sampledFrames");field.setAccessible(true);
        long sampled=field.getLong(navigation);
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3));assertEquals(sampled,field.getLong(navigation));
    }
    @Test @Config(sdk=24) @GraphicsMode(GraphicsMode.Mode.LEGACY) public void legacyAndroidCanConstructAndNavigateWithTheSameMaterial() {
        setup(false);assertEquals(3,((ViewGroup)navigation.getChildAt(0)).getChildCount());
        ((ViewGroup)navigation.getChildAt(0)).getChildAt(1).performClick();drain();assertEquals(1,pages.position(),.0001f);
    }
    private static void export(String name,Bitmap image)throws Exception {
        String directory=System.getProperty("atticus.glass.previewDir");if(directory==null)return;
        File folder=new File(directory);if(!folder.isDirectory()&&!folder.mkdirs())throw new java.io.IOException("Preview directory unavailable");
        try(FileOutputStream stream=new FileOutputStream(new File(folder,name+".png"))){assertTrue(image.compress(Bitmap.CompressFormat.PNG,100,stream));}
    }
}
