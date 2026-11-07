/* Copyright (c) 2026 TianzeL; SPDX-License-Identifier: Apache-2.0 */
package dev.lan.atticusvpn.server.ui;
import android.app.Application;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import android.graphics.*;
import dev.lan.atticusvpn.server.runtime.*;
import java.util.List;
import java.util.concurrent.*;
import java.io.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.*;
import org.robolectric.util.ReflectionHelpers;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,application=Application.class,qualifiers="w400dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
public class StableHomeFlowTest {
 private ActivityController<? extends MainActivity> controller;private MainActivity activity;
 private <T>T field(String name){return ReflectionHelpers.getField(activity,name);}
 private void open(boolean settings,boolean dark)throws Exception {
  Application app=RuntimeEnvironment.getApplication();AppLanguage.select(app,"zh-CN");AppAppearance.select(app,dark?AppAppearance.DARK:AppAppearance.LIGHT);
  shadowOf(app).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS);
  RuntimeStatusStore.publish(RuntimeStatus.stopped());
  controller=settings?Robolectric.buildActivity(VpnSettingsActivity.class):Robolectric.buildActivity(MainActivity.class);
  controller.setup().visible();activity=controller.get();
  ((ExecutorService)field("worker")).submit(()->{}).get(3,TimeUnit.SECONDS);shadowOf(Looper.getMainLooper()).idle();
  View root=activity.findViewById(android.R.id.content);root.measure(View.MeasureSpec.makeMeasureSpec(400,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(800,View.MeasureSpec.EXACTLY));root.layout(0,0,400,800);
 }
 private static boolean has(View v,Class<?> kind){if(kind.isInstance(v))return true;if(v instanceof ViewGroup g)for(int i=0;i<g.getChildCount();i++)if(has(g.getChildAt(i),kind))return true;return false;}
 private static String text(View v){StringBuilder b=new StringBuilder();if(v instanceof TextView t)b.append(t.getText()).append('\n');if(v instanceof ViewGroup g)for(int i=0;i<g.getChildCount();i++)b.append(text(g.getChildAt(i)));return b.toString();}
 @After public void close(){if(controller!=null)controller.pause().stop().destroy();}
 @Test public void threePagesKeepFormsInSettingsAndOnlyWireGuardIsShown()throws Exception {
  open(false,false);HomePagesView pages=field("pages");assertEquals(3,pages.getChildCount());
  assertTrue(has(pages.getChildAt(0),ServerDashboardView.class));assertTrue(has(pages.getChildAt(2),TerminalStatusView.class));assertFalse(has(pages,EditText.class));
  String shown=text(pages);assertFalse(shown.contains("Atticus Link"));assertFalse(shown.contains("IKEv2"));
  for(int i=0;i<3;i++)pages.select(i);assertNull(shadowOf(RuntimeEnvironment.getApplication()).getNextStartedService());
 }
 @Test public void vpnSettingsContainsDdnsAndProtectsCredentialsWithoutStartingVpn()throws Exception {
  open(true,false);assertNull(field("pages"));assertNotNull(field("ddnsSecretField"));
  assertTrue(text(activity.findViewById(android.R.id.content)).contains("DDNS"));
  assertTrue((activity.getWindow().getAttributes().flags&android.view.WindowManager.LayoutParams.FLAG_SECURE)!=0);
  assertNull(shadowOf(RuntimeEnvironment.getApplication()).getNextStartedService());
 }
 @Test public void summaryShowsLiveCountersAndServiceButtonTargetsWireGuard()throws Exception {
  open(false,false);activity.onRuntimeStatus(new RuntimeStatus(RuntimeStatus.Phase.RUNNING,"","2001:db8::10",1,"WIFI","DISABLED",List.of(new RuntimeStatus.PeerRuntime("fixture",System.currentTimeMillis()/1000,128*1024L,256*1024L)),51820,"[2001:db8::10]:51820",true));
  String summary=text(field("dashboard"));assertTrue(summary.contains("128.0 KiB"));assertTrue(summary.contains("256.0 KiB"));
  ((Button)field("toggleServerButton")).performClick();var command=shadowOf(RuntimeEnvironment.getApplication()).getNextStartedService();assertNotNull(command);assertTrue(command.getComponent().getClassName().endsWith(".AtticusServerService"));
 }
 @Test public void actualHomeRendersInLightAndDark()throws Exception {
  for(boolean dark:List.of(false,true)){
   open(false,dark);String dir=System.getProperty("atticus.glass.previewDir");
   if(dir!=null){File out=new File(dir,"stable-0.6.0-home-"+(dark?"dark":"light")+".png");out.getParentFile().mkdirs();Bitmap b=Bitmap.createBitmap(400,800,Bitmap.Config.ARGB_8888);activity.findViewById(android.R.id.content).draw(new Canvas(b));try(var stream=new FileOutputStream(out)){b.compress(Bitmap.CompressFormat.PNG,100,stream);}b.recycle();}
   close();controller=null;
  }
 }
}
