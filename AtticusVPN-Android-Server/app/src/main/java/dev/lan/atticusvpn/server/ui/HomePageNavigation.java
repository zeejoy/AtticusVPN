/* Copyright (c) 2026 TianzeL; SPDX-License-Identifier: Apache-2.0 */
package dev.lan.atticusvpn.server.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.ViewParent;
import android.view.ViewTreeObserver;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import dev.lan.atticusvpn.server.R;
import java.util.Arrays;

/** A/B floating glass material, fed by the pager's actual continuous position. */
final class HomePageNavigation extends FrameLayout {
    interface Listener {
        void selected(int index);
        default void dragStarted() { }
        default void dragged(float position) { }
        default void dragFinished(boolean cancelled) { }
    }
    private static final long CAPTURE_INTERVAL_MS=48;
    private final ImageView[] icons=new ImageView[3];
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);
    private final Path capsule=new Path(),lensClip=new Path(),edgeRing=new Path();
    private final RectF bounds=new RectF(),lens=new RectF(),textureBounds=new RectF();
    private final int[] sourceLocation=new int[2],ownLocation=new int[2];
    private final int touchSlop,inactiveColor,activeColor;
    private final Runnable requestCapture=()->{captureScheduled=false;invalidate();};
    private final ViewTreeObserver.OnPreDrawListener preDraw=()->{captureBackdrop();return true;};
    private final ViewTreeObserver.OnScrollChangedListener scrollChanged=this::invalidateBackdrop;
    private final ViewTreeObserver.OnGlobalLayoutListener layoutChanged=this::invalidateBackdrop;
    private Listener listener;
    private View backdrop;
    private ViewTreeObserver observing;
    private Bitmap texture,captureFrame;
    private Canvas textureCanvas;
    private int[] pixels,scratch;
    private Shader surfaceShader,lensShader,edgeShader,sideShader;
    private float[] edgeMesh;
    private float position,stretch,downX,downY,dragOrigin;
    private boolean dragging,gestureBlocked,captureRequested=true,captureScheduled;
    private int selected;
    private long lastCapture,lastSample,contentHash,sampledFrames;
    private boolean hasContentHash;

    HomePageNavigation(Context context) {
        super(context);
        setLayoutDirection(LAYOUT_DIRECTION_LTR);setWillNotDraw(false);setClipChildren(false);setElevation(dp(7));
        touchSlop=ViewConfiguration.get(context).getScaledTouchSlop();
        inactiveColor=color(R.color.glass_icon);activeColor=color(R.color.glass_icon_selected);
        setOutlineProvider(new ViewOutlineProvider(){
            @Override public void getOutline(View view,Outline outline) {
                outline.setRoundRect(0,0,view.getWidth(),view.getHeight(),view.getHeight()/2f);outline.setAlpha(.34f);
            }
        });
        LinearLayout row=new LinearLayout(context);row.setOrientation(LinearLayout.HORIZONTAL);row.setGravity(Gravity.CENTER);
        row.setPadding(dp(5),dp(5),dp(5),dp(5));
        int[] descriptions={R.string.page_information,R.string.page_peers,R.string.page_connection};
        int[] artwork={R.drawable.atticus_glass_server,R.drawable.atticus_glass_peers,R.drawable.atticus_glass_network};
        for(int i=0;i<icons.length;i++) {
            final int index=i;
            ImageView icon=new ImageView(context);icon.setImageDrawable(context.getDrawable(artwork[i]));
            icon.setScaleType(ImageView.ScaleType.FIT_CENTER);icon.setPadding(dp(14),dp(13),dp(14),dp(13));
            icon.setMinimumHeight(dp(48));icon.setClickable(true);icon.setFocusable(true);
            icon.setContentDescription(context.getString(descriptions[i]));
            icon.setAccessibilityDelegate(new AccessibilityDelegate(){
                @Override public void onInitializeAccessibilityNodeInfo(View host,AccessibilityNodeInfo info) {
                    super.onInitializeAccessibilityNodeInfo(host,info);info.setClassName("android.widget.Button");info.setSelected(index==selected);
                }
            });
            icon.setOnClickListener(view->{if(listener!=null)listener.selected(index);});
            row.addView(icon,new LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.MATCH_PARENT,1f));icons[i]=icon;
        }
        addView(row,new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.MATCH_PARENT));
        select(0);setProgress(0,0,false);
    }
    void setListener(Listener value){listener=value;}
    void setBackdropSource(View source) {
        stopObserving();releaseTexture();backdrop=source;if(isAttachedToWindow())startObserving();invalidateBackdrop();
    }
    void select(int index) {
        selected=Math.max(0,Math.min(icons.length-1,index));
        for(int i=0;i<icons.length;i++)icons[i].setSelected(i==selected);
        // Position comes separately from the pager, including the final frame.
        invalidate();
    }
    void setProgress(float value,float deformation,boolean fingerDown) {
        if(!Float.isFinite(value)||!Float.isFinite(deformation))return;
        position=Math.max(0,Math.min(icons.length-1,value));stretch=Math.max(0,Math.min(1,deformation));
        for(int i=0;i<icons.length;i++) {
            float strength=Math.max(0,1-Math.abs(position-i));
            icons[i].setColorFilter(GlassPixels.mix(inactiveColor,activeColor,strength),PorterDuff.Mode.SRC_IN);
            float scale=1f+.055f*strength*(fingerDown?1:stretch);icons[i].setScaleX(scale);icons[i].setScaleY(scale);
        }
        invalidateBackdrop();
    }
    float position(){return position;}
    RectF selectionBounds(){return new RectF(lens);}
    boolean hasBackdrop(){return texture!=null;}
    private void invalidateBackdrop(){captureRequested=true;invalidate();}
    private void startObserving() {
        if(backdrop==null||observing!=null)return;
        observing=backdrop.getViewTreeObserver();observing.addOnPreDrawListener(preDraw);
        observing.addOnScrollChangedListener(scrollChanged);observing.addOnGlobalLayoutListener(layoutChanged);
    }
    private void stopObserving() {
        if(observing!=null&&observing.isAlive()) {
            observing.removeOnPreDrawListener(preDraw);observing.removeOnScrollChangedListener(scrollChanged);
            observing.removeOnGlobalLayoutListener(layoutChanged);
        }
        observing=null;removeCallbacks(requestCapture);captureScheduled=false;
    }
    @Override protected void onAttachedToWindow(){super.onAttachedToWindow();startObserving();invalidateBackdrop();}
    @Override protected void onDetachedFromWindow(){if(dragging)finishDrag(true);stopObserving();releaseTexture();super.onDetachedFromWindow();}
    @Override protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        if(visibility!=VISIBLE){if(dragging)finishDrag(true);removeCallbacks(requestCapture);captureScheduled=false;releaseTexture();}else invalidateBackdrop();
    }
    private void releaseTexture() {
        if(texture!=null){texture.eraseColor(Color.TRANSPARENT);texture.recycle();}
        if(captureFrame!=null){captureFrame.eraseColor(Color.TRANSPARENT);captureFrame.recycle();}
        texture=null;captureFrame=null;textureCanvas=null;hasContentHash=false;
        if(pixels!=null)Arrays.fill(pixels,0);if(scratch!=null)Arrays.fill(scratch,0);
        pixels=null;scratch=null;captureRequested=true;
    }
    private void captureBackdrop() {
        if(backdrop==null||!isShown()||getWindowVisibility()!=VISIBLE||getWidth()==0||getHeight()==0)return;
        // Capture only the sibling page, never an ancestor containing this bar.
        for(ViewParent parent=getParent();parent instanceof View;parent=parent.getParent())if(parent==backdrop)return;
        if(backdrop==this||(!captureRequested&&!backdrop.isDirty()))return;
        long now=SystemClock.uptimeMillis(),remaining=CAPTURE_INTERVAL_MS-(now-lastSample);
        if(texture!=null&&remaining>0) {
            if(!captureScheduled){captureScheduled=true;postDelayed(requestCapture,remaining);}return;
        }
        int width=Math.max(1,Math.min(320,Math.round(getWidth()*.32f)));
        int height=Math.max(1,Math.min(96,Math.round(getHeight()*width/(float)getWidth())));
        if(texture==null||texture.getWidth()!=width||texture.getHeight()!=height) {
            releaseTexture();texture=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);
            captureFrame=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);
            textureCanvas=new Canvas(captureFrame);pixels=new int[width*height];scratch=new int[pixels.length];
        }
        captureFrame.eraseColor(color(R.color.atticus_surface));
        backdrop.getLocationInWindow(sourceLocation);getLocationInWindow(ownLocation);
        int checkpoint=textureCanvas.save();
        try {
            textureCanvas.scale(width/(float)getWidth(),height/(float)getHeight());
            textureCanvas.translate(sourceLocation[0]-ownLocation[0],sourceLocation[1]-ownLocation[1]);backdrop.draw(textureCanvas);
        }finally{textureCanvas.restoreToCount(checkpoint);}
        captureFrame.getPixels(pixels,0,width,0,0,width,height);sampledFrames++;lastSample=now;captureRequested=false;
        long hash=0xcbf29ce484222325L;
        for(int pixel:pixels)hash=(hash^pixel)*0x100000001b3L;
        // A translucent sibling may be marked dirty by our own invalidation.
        // Only changed content schedules a redraw: no idle capture feedback loop.
        if(hasContentHash&&hash==contentHash)return;
        contentHash=hash;hasContentHash=true;GlassPixels.blur(pixels,scratch,width,height,2);
        texture.setPixels(pixels,0,width,0,0,width,height);lastCapture=now;invalidate();
    }
    @Override protected void onSizeChanged(int w,int h,int oldw,int oldh) {
        super.onSizeChanged(w,h,oldw,oldh);
        bounds.set(dp(.8f),dp(.8f),w-dp(.8f),h-dp(.8f));capsule.reset();capsule.addRoundRect(bounds,h/2f,h/2f,Path.Direction.CW);
        surfaceShader=gradient(h,R.color.glass_surface_top,R.color.glass_surface_bottom);
        lensShader=gradient(h,R.color.glass_lens_top,R.color.glass_lens_bottom);
        edgeShader=new LinearGradient(0,0,0,h,new int[]{color(R.color.glass_edge_top),color(R.color.glass_edge_middle),color(R.color.glass_edge_bottom)},new float[]{0,.5f,1},Shader.TileMode.CLAMP);
        sideShader=new LinearGradient(0,0,w,0,new int[]{0x90ffffff,0x24778aa5,0x00ffffff,0x00ffffff,0x24778aa5,0x90ffffff},new float[]{0,.018f,.065f,.935f,.982f,1},Shader.TileMode.CLAMP);
        edgeMesh=GlassOptics.edgeMesh(w,h,dp(10));
        edgeRing.reset();edgeRing.setFillType(Path.FillType.EVEN_ODD);
        edgeRing.addRoundRect(bounds,h/2f,h/2f,Path.Direction.CW);
        edgeRing.addRoundRect(bounds.left+dp(6),bounds.top+dp(6),bounds.right-dp(6),bounds.bottom-dp(6),Math.max(0,h/2f-dp(6)),Math.max(0,h/2f-dp(6)),Path.Direction.CW);
        textureBounds.set(0,0,w,h);invalidateOutline();invalidateBackdrop();
    }
    private Shader gradient(int h,int top,int bottom){return new LinearGradient(0,0,0,h,new int[]{color(top),color(bottom)},null,Shader.TileMode.CLAMP);}
    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);if(bounds.isEmpty())return;
        paint.setStyle(Paint.Style.FILL);paint.setShader(null);paint.setColor(color(R.color.atticus_surface));
        canvas.drawRoundRect(bounds,bounds.height()/2,bounds.height()/2,paint);
        int checkpoint=canvas.save();canvas.clipPath(capsule);
        if(texture!=null)canvas.drawBitmap(texture,null,textureBounds,paint);
        paint.setShader(surfaceShader);canvas.drawRect(bounds,paint);paint.setShader(null);
        // The rim bends the *actual* sharper page backdrop. No decorative stock image
        // or extra full-screen capture; reuse the two existing bounded textures.
        if(captureFrame!=null&&edgeMesh!=null) {
            int optical=canvas.save();canvas.clipPath(edgeRing);
            canvas.drawBitmapMesh(captureFrame,GlassOptics.COLUMNS,GlassOptics.ROWS,edgeMesh,0,null,0,paint);
            paint.setShader(sideShader);canvas.drawRect(bounds,paint);paint.setShader(null);canvas.restoreToCount(optical);
        }
        canvas.restoreToCount(checkpoint);
        float inset=dp(5),slot=(getWidth()-2*inset)/3f,center=inset+slot*(position+.5f);
        float halfWidth=Math.min(slot-dp(6),(getHeight()-2*inset)*1.18f)*(1+.12f*stretch)/2,halfHeight=(getHeight()-2*inset)*(1-.05f*stretch)/2;
        lens.set(Math.max(inset,center-halfWidth),getHeight()/2f-halfHeight,Math.min(getWidth()-inset,center+halfWidth),getHeight()/2f+halfHeight);
        lensClip.reset();lensClip.addRoundRect(lens,halfHeight,halfHeight,Path.Direction.CW);
        checkpoint=canvas.save();canvas.clipPath(lensClip);
        if(texture!=null) {
            int optical=canvas.save();canvas.scale(1.065f,1.11f,center,getHeight()/2f);
            canvas.drawBitmap(texture,null,textureBounds,paint);canvas.restoreToCount(optical);
        }
        paint.setShader(lensShader);canvas.drawRect(lens,paint);paint.setShader(null);canvas.restoreToCount(checkpoint);
        paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(dp(1.35f));paint.setShader(edgeShader);
        canvas.drawRoundRect(bounds,bounds.height()/2,bounds.height()/2,paint);
        paint.setStrokeWidth(dp(1.1f));canvas.drawRoundRect(lens,halfHeight,halfHeight,paint);paint.setShader(null);
        paint.setColor(color(R.color.glass_inner_edge));paint.setStrokeWidth(dp(.65f));
        canvas.drawRoundRect(bounds.left+dp(2.2f),bounds.top+dp(2.2f),bounds.right-dp(2.2f),bounds.bottom-dp(2.2f),bounds.height()/2,bounds.height()/2,paint);
        paint.setStyle(Paint.Style.FILL);
    }
    @Override public boolean onInterceptTouchEvent(MotionEvent event) {
        int action=event.getActionMasked();
        if(action==MotionEvent.ACTION_DOWN) {
            downX=event.getX();downY=event.getY();dragOrigin=position;dragging=false;gestureBlocked=false;
        }else if(event.getPointerCount()>1||action==MotionEvent.ACTION_POINTER_DOWN) {
            gestureBlocked=true;if(dragging)finishDrag(true);
        }else if(action==MotionEvent.ACTION_MOVE&&!gestureBlocked&&!dragging) {
            float dx=Math.abs(event.getX()-downX),dy=Math.abs(event.getY()-downY);
            if(dy>touchSlop&&dy>dx)gestureBlocked=true;
            else if(dx>touchSlop&&dx>dy*1.35f) {
                dragging=true;if(getParent()!=null)getParent().requestDisallowInterceptTouchEvent(true);
                if(listener!=null)listener.dragStarted();
            }
        }
        return dragging;
    }
    @android.annotation.SuppressLint("ClickableViewAccessibility") // Only drags; taps/accessibility belong to the three ImageView buttons.
    @Override public boolean onTouchEvent(MotionEvent event) {
        int action=event.getActionMasked();
        if(event.getPointerCount()>1||action==MotionEvent.ACTION_POINTER_DOWN){if(dragging)finishDrag(true);return true;}
        if(dragging) {
            if(action==MotionEvent.ACTION_MOVE&&listener!=null) {
                float slot=Math.max(1,(getWidth()-dp(10))/3f);listener.dragged(Math.max(0,Math.min(2,dragOrigin+(event.getX()-downX)/slot)));
            }else if(action==MotionEvent.ACTION_UP||action==MotionEvent.ACTION_CANCEL)finishDrag(action==MotionEvent.ACTION_CANCEL);
            return true;
        }
        return super.onTouchEvent(event);
    }
    private void finishDrag(boolean cancelled) {
        if(!dragging)return;dragging=false;if(listener!=null)listener.dragFinished(cancelled);
        if(getParent()!=null)getParent().requestDisallowInterceptTouchEvent(false);
    }
    private int color(int id){return getContext().getColor(id);}
    private float dp(float value){return value*getResources().getDisplayMetrics().density;}
    private int dp(int value){return Math.round(dp((float)value));}
}
