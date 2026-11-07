/* Copyright (c) 2026 TianzeL; SPDX-License-Identifier: Apache-2.0 */
package dev.lan.atticusvpn.server.service;
import android.app.*;
import android.content.Intent;
import dev.lan.atticusvpn.server.R;
import dev.lan.atticusvpn.server.runtime.*;
import java.lang.reflect.*;
import java.util.concurrent.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.ServiceController;
import org.robolectric.annotation.*;
import org.robolectric.util.ReflectionHelpers;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;
@RunWith(RobolectricTestRunner.class)
@Config(sdk={24,28,35}, application=Application.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class AuditServiceNotificationTest {
 @Test public void manualNetworkCheckMustNotLeavePreparingNotification() throws Exception {
  ServiceController<AtticusServerService> controller=Robolectric.buildService(AtticusServerService.class).create();
  AtticusServerService s=controller.get();
  try{
   ReflectionHelpers.setField(s,"desiredRunning",true);
   RuntimeStatus state=new RuntimeStatus(RuntimeStatus.Phase.RUNNING,"","2001:db8::1",1);
   Method publish=AtticusServerService.class.getDeclaredMethod("publish",RuntimeStatus.class);publish.setAccessible(true);
   publish.invoke(s,state);
   NotificationManager manager=s.getSystemService(NotificationManager.class);
   String running=shadowOf(manager).getNotification(17077).extras.getCharSequence(Notification.EXTRA_TEXT).toString();
   s.onStartCommand(new Intent(s,AtticusServerService.class).setAction("dev.lan.atticusvpn.server.action.CHECK"),0,1);
   ((ScheduledExecutorService)ReflectionHelpers.getField(s,"worker")).submit(()->{}).get(2,TimeUnit.SECONDS);
   publish.invoke(s,state);
   String actual=shadowOf(manager).getNotification(17077).extras.getCharSequence(Notification.EXTRA_TEXT).toString();
   assertEquals("notification should describe running after check completes",running,actual);
  } finally {controller.destroy();}
 }
 @Test public void languageRepeatedCheckAndStopUseActualDisplayedNotification() throws Exception {
  ServiceController<AtticusServerService> controller=Robolectric.buildService(AtticusServerService.class).create();
  AtticusServerService s=controller.get();
  try {
   ReflectionHelpers.setField(s,"desiredRunning",true);
   RuntimeStatus status=new RuntimeStatus(RuntimeStatus.Phase.RUNNING,"","2001:db8::1",2);
   Method publish=AtticusServerService.class.getDeclaredMethod("publish",RuntimeStatus.class);publish.setAccessible(true);
   NotificationManager manager=s.getSystemService(NotificationManager.class);
   for(String language:new String[]{"en","zh-CN","ja","ar","en"}) {
    assertTrue(dev.lan.atticusvpn.server.ui.AppLanguage.select(s,language));
    publish.invoke(s,status);
    String expected=shadowOf(manager).getNotification(17077).extras.getCharSequence(Notification.EXTRA_TEXT).toString();
    for(int i=0;i<2;i++) {
     s.onStartCommand(new Intent(s,AtticusServerService.class).setAction("dev.lan.atticusvpn.server.action.CHECK"),0,1);
     ((ScheduledExecutorService)ReflectionHelpers.getField(s,"worker")).submit(()->{}).get(2,TimeUnit.SECONDS);
     publish.invoke(s,status);
     assertEquals(expected,shadowOf(manager).getNotification(17077).extras.getCharSequence(Notification.EXTRA_TEXT).toString());
     assertEquals(RuntimeStatus.Phase.RUNNING,RuntimeStatusStore.current().phase());
    }
   }
   s.onStartCommand(new Intent(s,AtticusServerService.class).setAction("dev.lan.atticusvpn.server.action.STOP"),0,2);
   ((ScheduledExecutorService)ReflectionHelpers.getField(s,"worker")).submit(()->{}).get(2,TimeUnit.SECONDS);
   assertEquals(RuntimeStatus.Phase.STOPPED,RuntimeStatusStore.current().phase());
   assertNull(shadowOf(manager).getNotification(17077));
  } finally {controller.destroy();}
 }
}
