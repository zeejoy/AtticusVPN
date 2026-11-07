/* Copyright (c) 2026 TianzeL; SPDX-License-Identifier: Apache-2.0 */
package dev.lan.atticusvpn.server.logging;

import android.app.Application;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;
import static org.junit.Assert.*;

@RunWith(org.robolectric.RobolectricTestRunner.class)
@Config(sdk=35,application=Application.class)
public final class RuntimeLogDemandTest {
    private void drain(RuntimeLogCollector collector)throws Exception {
        ReflectionHelpers.<ScheduledExecutorService>getField(collector,"timer").submit(()->{}).get(3,TimeUnit.SECONDS);
    }
    @Test public void hiddenLogKeepsCollectingWithoutRenderingAndClearsRevealedSnapshot()throws Exception {
        var collector=RuntimeLogCollector.current();collector.setViewing(true,false);drain(collector);
        String before=collector.snapshotText(false);assertTrue(before.contains("Runtime log"));
        collector.setViewing(false,false);collector.event("ALP","LINK_CERTIFICATE_RENEWED");
        ReflectionHelpers.callInstanceMethod(collector,"renderRequested");assertEquals(before,collector.snapshotText(false));
        collector.setViewing(true,false);drain(collector);assertTrue(collector.snapshotText(false).contains("LINK_CERTIFICATE_RENEWED"));
        assertEquals("",collector.snapshotText(true));
        collector.setViewing(true,true);drain(collector);assertTrue(collector.snapshotText(true).contains("FULL"));
        collector.setViewing(false,false);assertEquals("",collector.snapshotText(true));
    }
}
