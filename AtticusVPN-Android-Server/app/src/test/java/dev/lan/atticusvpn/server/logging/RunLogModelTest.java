/* Copyright (c) 2026 TianzeL; SPDX-License-Identifier: Apache-2.0 */
package dev.lan.atticusvpn.server.logging;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.Test;
import static org.junit.Assert.*;
import static dev.lan.atticusvpn.server.logging.RunLogModel.Protocol.*;

public class RunLogModelTest {
    private static RunLogModel.DeviceSample sample(String id,String endpoint,boolean active,long rx) {
        return new RunLogModel.DeviceSample(id,"中文设备 😀",endpoint,"10.78.0.2/32, 2001:db8::2/128","WSS",1000,1,rx,12,active,"authenticated","");
    }
    @Test public void completeDisappearanceRetainsAddressAndCountersWithoutInventingExactDisconnect() {
        var model=new RunLogModel(0);
        model.updateDevices(ALP,List.of(sample("one","[2001:db8::13]:1234",true,400)),true,2000);
        model.updateDevices(ALP,List.of(),true,5000);
        String text=model.render(true,10000);
        assertTrue(text.contains("[2001:db8::13]:1234"));
        assertTrue(text.contains("Rx=400 B"));
        assertTrue(text.contains("End observed (not exact disconnect time)"));
        assertTrue(text.contains("Connection duration: 0h 0m 4s"));
    }
    @Test public void failedReadDoesNotEndExistingDeviceAndTimeContinues() {
        var model=new RunLogModel(0);
        model.updateDevices(ALP,List.of(sample("one","198.51.100.21:1234",true,7)),true,2000);
        model.updateDevices(ALP,List.of(),false,6000);
        String text=model.render(true,11000);
        assertTrue(text.contains("活动 / active"));
        assertFalse(text.contains("device#1 — 连接已结束"));
        assertTrue(text.contains("Connection duration: 0h 0m 10s"));
    }
    @Test public void defaultsMaskAllIpFieldsIncludingProtocolAndPeerLabel() {
        var model=new RunLogModel(0);
        model.recordProtocol(WG,"RUNNING","","WIFI","VERIFIED","[2001:db8:abcd::99]:51820",2000);
        var device=new RunLogModel.DeviceSample("one","peer 198.51.100.22 2001:db8:1234::2","198.51.100.22:4131","10.78.0.2/32,2001:db8:1234::2/128","UDP",0,1,30,40,true,"observed","");
        model.updateDevices(WG,List.of(device),true,2000);
        String masked=model.render(false,5000),raw=model.render(true,5000);
        for(String ip:List.of("198.51.100.22","10.78.0.2","2001:db8:abcd::99","2001:db8:1234::2")) {
            assertFalse(ip,masked.contains(ip));assertTrue(ip,raw.contains(ip));
        }
        assertTrue(masked.contains("198.51.*.*"));assertTrue(masked.contains("2001:db8:*"));
    }
    @Test public void handlesMappedIpv6ScopedIpv6AndCidrWithoutDnsLookup() {
        var model=new RunLogModel(0);
        model.updateDevices(ALP,List.of(sample("one","[::ffff:198.51.100.51]:443",true,1)),true,1000);
        model.updateDevices(ALP,List.of(sample("one","[fe80::abc%wlan0]:443",true,2)),true,2000);
        String raw=model.render(true,3000),masked=model.render(false,3000);
        assertTrue(raw.contains("[::ffff:198.51.100.51]:443"));assertTrue(raw.contains("[fe80::abc%wlan0]:443"));
        assertFalse(masked.contains("198.51.100.51"));assertFalse(masked.contains("fe80::abc"));assertFalse(masked.contains("%wlan0"));
        assertFalse(masked.contains("10.78.0.2/32"));
    }
    @Test public void endpointHistorySurvivesChangesAndOfflineEmptySamples() {
        var model=new RunLogModel(0);
        model.updateDevices(ALP,List.of(sample("one","198.51.100.1:1234",true,10)),true,1000);
        model.updateDevices(ALP,List.of(sample("one","198.51.100.2:1234",true,20)),true,2000);
        model.updateDevices(ALP,List.of(sample("one","",false,20)),true,3000);
        String text=model.render(true,4000);
        assertTrue(text.contains("198.51.100.1:1234"));assertTrue(text.contains("198.51.100.2:1234"));
        assertTrue(text.contains("DEVICE_ENDPOINT_CHANGED"));
    }
    @Test public void wgDurationClearlyUsesObservationWindowInsteadOfHandshakeOrPretendConnection() {
        var model=new RunLogModel(0);
        var wg=new RunLogModel.DeviceSample("one","device","198.51.100.1:4000","10.77.0.2/32","UDP",0,100,1000,2000,true,"observed","");
        model.updateDevices(WG,List.of(wg),true,200000);
        String text=model.render(true,210000);
        assertTrue(text.contains("First observed (not actual connect time): 1970-01-01T00:03:20Z"));
        assertTrue(text.contains("Observed window (inferred): 0h 0m 10s"));
    }
    @Test public void actualNativeEndTimeFixesDurationBeforeItsLaterObservation() {
        var model=new RunLogModel(0);
        var closed=new RunLogModel.DeviceSample("one","old","198.51.100.1:443","10.78.0.2/32","WSS",1000,0,22,33,false,"authenticated","NORMAL_CLOSE",60000);
        model.updateDevices(ALP,List.of(closed),true,200000);
        String text=model.render(true,9000000);
        assertTrue(text.contains("Native end time: 1970-01-01T00:01:00Z"));
        assertTrue(text.contains("Connection duration: 0h 0m 59s"));
    }
    @Test public void reconnectSameIdKeepsEndedRowAndStartsNewObservationWindow() {
        var model=new RunLogModel(0);
        model.updateDevices(ALP,List.of(sample("one","198.51.100.1:111",true,10)),true,1000);
        model.updateDevices(ALP,List.of(),true,2000);
        model.updateDevices(ALP,List.of(sample("one","198.51.100.2:222",true,20)),true,3000);
        String text=model.render(true,4000);
        assertTrue(text.contains("#1 ALP"));assertTrue(text.contains("#2 ALP"));
        assertTrue(text.indexOf("#2 ALP")<text.indexOf("#1 ALP"));
        assertTrue(text.contains("198.51.100.1:111"));assertTrue(text.contains("198.51.100.2:222"));
    }
    @Test public void wgResumesSameObservationRowWithoutDuplicatingCumulativeTraffic() {
        var model=new RunLogModel(0);
        model.updateDevices(WG,List.of(sample("one","198.51.100.1:111",true,10)),true,1000);
        var idle=new RunLogModel.DeviceSample("one","device","198.51.100.1:111","10.77.0.2/32","UDP",0,1,10,12,false,"observed","WG_IDLE_INFERRED");
        model.updateDevices(WG,List.of(idle),true,2000);
        model.updateDevices(WG,List.of(sample("one","198.51.100.2:222",true,20)),true,3000);
        String text=model.render(true,4000);
        assertTrue(text.contains("#1 WG"));assertFalse(text.contains("#2 WG"));
        assertTrue(text.contains("DEVICE_RESUMED"));assertTrue(text.contains("Rx=20 B"));
        assertFalse(text.contains("Rx=10 B"));assertTrue(text.contains("Observed window (inferred): 0h 0m 3s"));
        assertFalse(text.contains("End observed (not exact disconnect time):"));
        assertTrue(text.contains("198.51.100.1:111"));assertTrue(text.contains("198.51.100.2:222"));
    }
    @Test public void counterResetRetainsMaximumAndAddsWarningWithoutInventingMissingBytes() {
        var model=new RunLogModel(0);
        model.updateDevices(ALP,List.of(sample("one","198.51.100.1:443",true,1000)),true,1000);
        model.updateDevices(ALP,List.of(sample("one","198.51.100.1:443",true,3)),true,2000);
        String text=model.render(true,3000);
        assertTrue(text.contains("COUNTER_RESET"));assertTrue(text.contains("Rx=1000 B"));
        assertFalse(text.contains("Rx=1003 B"));
    }
    @Test public void codesCannotCarryRawExceptionsConfigsOrInjectedLines() {
        var model=new RunLogModel(0);
        model.recordProtocol(WG,"RUNNING\nforged","PrivateKey=DO-NOT-LOG-ME","token=DO-NOT-LOG-ME","DDNS password=DO-NOT-LOG-ME","https://example.test/update?token=DO-NOT-LOG-ME",1000);
        model.recordEvent(null,"runtime exception password=DO-NOT-LOG-ME",1000);
        var bad=new RunLogModel.DeviceSample("one","phone\n\r\u202eprivatekey=DO-NOT-LOG-ME","https://example.test/?token=DO-NOT-LOG-ME","10.78.0.2/32 token=DO-NOT-LOG-ME","TCP",0,0,0,0,true,"observed","");
        model.updateDevices(ALP,List.of(bad),true,1000);
        String text=model.render(true,2000);
        assertFalse(text.contains("DO-NOT-LOG-ME"));assertFalse(text.contains("\u202e"));assertFalse(text.contains("\r"));
        assertTrue(text.contains("UNRECOGNIZED_CODE"));assertTrue(text.contains("exact cause unconfirmed"));
    }
    @Test public void eventCountsAreNativeDeltasAndStatusTransitionsRetainTheirSafeStates() {
        var model=new RunLogModel(0);
        model.recordProtocol(ALP,"STARTING","","WIFI","WAITING","example.test:443",1000);
        model.recordProtocol(ALP,"RUNNING","","CELLULAR","PENDING_VERIFICATION","example.test:443",2000);
        model.recordEventCount("ALP","AUTH_FAILED",7,3000);
        model.recordEventCount("ALP","AUTH_FAILED",3,4000);
        String text=model.render(false,5000);
        assertTrue(text.contains("phase=STARTING; network=WIFI; DDNS=WAITING"));
        assertTrue(text.contains("phase=RUNNING; network=CELLULAR; DDNS=PENDING_VERIFICATION"));
        assertTrue(text.contains("ALP AUTH_FAILED; count=10"));
    }
    @Test public void historyBoundsPreferActiveRowsAndSnapshotHasStrictUtf8Limit() {
        var model=new RunLogModel(0);var all=new ArrayList<RunLogModel.DeviceSample>();
        for(int i=0;i<RunLogModel.MAX_DEVICES;i++)all.add(new RunLogModel.DeviceSample("peer"+i,"设备".repeat(60),"[2001:db8::1]:443","10.78.0.2/32","WSS",1,0,10,20,true,"authenticated",""));
        model.updateDevices(ALP,all,true,1000);
        model.updateDevices(ALP,List.of(sample("excess","198.51.100.1:443",false,2)),false,2000);
        String text=model.render(false,3000);
        assertTrue(text.getBytes(StandardCharsets.UTF_8).length<=RunLogModel.MAX_UTF8_BYTES-256);
        assertTrue(text.contains("Snapshot size limit reached"));
        assertTrue(text.contains("Evicted or refused rows: 1"));
        model.updateDevices(ALP,List.of(),true,4000);
        model.updateDevices(ALP,List.of(new RunLogModel.DeviceSample("new","new active device","198.51.100.1:443","","WSS",4000,0,5,6,true,"authenticated","")),true,5000);
        assertTrue(model.render(false,6000).contains("new active device"));
    }
    @Test public void boundedRandomInputsDoNotThrowOrProduceOversizedSnapshots() {
        var model=new RunLogModel(0);var random=new Random(7012);
        for(int n=0;n<1500;n++) {
            StringBuilder input=new StringBuilder();for(int j=0;j<40;j++)input.append((char)random.nextInt(0xffff));
            String value=input.toString();
            model.recordProtocol(n%2==0?WG:ALP,value,value,value,value,value,n);
            model.recordEvent(value,value,n);
            model.updateDevices(IKEV2,List.of(new RunLogModel.DeviceSample("row"+(n%30),value,value,value,value,random.nextLong(),random.nextLong(),random.nextLong(),random.nextLong(),n%2==0,value,value)),n%3==0,n);
            if(n%50==0)assertTrue(model.render(n%2==0,n).getBytes(StandardCharsets.UTF_8).length<=RunLogModel.MAX_UTF8_BYTES-256);
        }
    }
    @Test public void anErrorFloodCannotDisplaceTheCurrentDeviceSection() {
        var model=new RunLogModel(0);
        model.updateDevices(ALP,List.of(new RunLogModel.DeviceSample("one","active device after flood","198.51.100.5:443","10.78.0.2/32","WSS",1,0,30,40,true,"authenticated","")),true,1000);
        for(int index=0;index<600;index++)model.recordEvent("ALP",index%2==0?"ALP_TRANSPORT_READ_FAILED":"ALP_AUTH_REJECTED",index+2000);
        String text=model.render(false,3000);
        assertTrue(text.contains("active device after flood"));
        assertTrue(text.indexOf("active device after flood")<text.indexOf("较早事件 / Older events"));
        assertTrue(text.getBytes(StandardCharsets.UTF_8).length<=RunLogModel.MAX_UTF8_BYTES-256);
    }
}
