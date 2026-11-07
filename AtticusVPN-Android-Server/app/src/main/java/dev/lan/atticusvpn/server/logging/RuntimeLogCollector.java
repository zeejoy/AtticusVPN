/* Copyright (c) 2026 TianzeL; SPDX-License-Identifier: Apache-2.0 */
package dev.lan.atticusvpn.server.logging;

import dev.lan.atticusvpn.server.AtticusServerApplication;
import dev.lan.atticusvpn.server.BuildConfig;
import dev.lan.atticusvpn.server.model.StoredPeer;
import dev.lan.atticusvpn.server.runtime.RuntimeStatus;
import dev.lan.atticusvpn.server.storage.EncryptedStateRepository;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import com.wireguard.crypto.Key;

/** One process session; bounded RAM only. No raw configuration/exception/logcat input. */
public final class RuntimeLogCollector {
    public static final int MAX_NATIVE_JSON = 512 * 1024;
    private static final RuntimeLogCollector INSTANCE = new RuntimeLogCollector();
    public static RuntimeLogCollector current() { return INSTANCE; }
    private final RunLogModel model = new RunLogModel(System.currentTimeMillis());
    private final AtomicBoolean started = new AtomicBoolean();
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r,"AtticusVPN-SessionLog"); t.setDaemon(true); return t;
    });
    private volatile String masked = "Preparing session log…";
    private volatile String revealed = "Preparing session log…";
    private final Object viewLock=new Object();
    private long viewGeneration;
    private volatile boolean viewing;
    private volatile boolean revealRequested;
    private AtticusServerApplication application;
    private final Map<String,Long> nativeErrorCounts = new HashMap<>();
    private final Map<RunLogModel.Protocol,Long> generations = new HashMap<>();
    private final Map<RunLogModel.Protocol,String> networkChecks = new HashMap<>();

    private RuntimeLogCollector() { }
    public void start(AtticusServerApplication app) {
        if (!started.compareAndSet(false,true)) return;
        application = app;
        model.recordEvent("APPLICATION","APP_STARTED",System.currentTimeMillis());
        timer.scheduleWithFixedDelay(this::tick,0,1,TimeUnit.SECONDS);
    }
    /** Cached immutable snapshots only: never run collection, storage or crypto on UI thread. */
    public String snapshotText(boolean revealIps) { return revealIps ? revealed : masked; }
    /** Collection is continuous; expensive text rendering follows only the visible privacy mode. */
    public void setViewing(boolean active,boolean revealIps) {
        final long request;
        synchronized(viewLock) {
            viewing=active;revealRequested=revealIps;request=++viewGeneration;
            if(!active||!revealIps)revealed="";
        }
        if(active)timer.execute(()->renderRequested(request));
    }
    private void renderRequested() {
        final long request;synchronized(viewLock){request=viewGeneration;}
        renderRequested(request);
    }
    private void renderRequested(long request) {
        final boolean reveal;
        synchronized(viewLock){if(!viewing||request!=viewGeneration)return;reveal=revealRequested;}
        try {
            String header="AtticusVPN Android Server "+BuildConfig.VERSION_NAME+" · API "+android.os.Build.VERSION.SDK_INT+" · TianzeL\n";
            String text=header+model.render(reveal,System.currentTimeMillis());
            synchronized(viewLock){
                if(!viewing||request!=viewGeneration)return;
                if(reveal)revealed=text;else masked=text;
            }
        }catch(RuntimeException ignored){ /* Keep last bounded snapshot. */ }
    }
    public void event(String component,String code) { model.recordEvent(component,code,System.currentTimeMillis()); }
    public void eventCount(String component,String code,long delta) {model.recordEventCount(component,code,delta,System.currentTimeMillis());}

    public synchronized void runtime(RunLogModel.Protocol protocol,RuntimeStatus status) {
        long now=System.currentTimeMillis(); String phase=status.phase().name();
        model.recordProtocol(protocol,phase,status.safeCode(),status.networkType(),status.ddnsStatus(),status.endpointAddress(),now);
        String check=status.networkCheck().name();
        if(!check.equals(networkChecks.put(protocol,check)))model.recordEvent(protocol.name(),check,now);
        if (phase.equals("STOPPED")||phase.equals("ERROR")||phase.equals("WAITING_NETWORK"))
            model.updateDevices(protocol,List.of(),true,now);
    }
    public synchronized void engineStarted(RunLogModel.Protocol protocol) {
        generations.merge(protocol,1L,Long::sum);
    }
    /** Invoked on the existing service worker after it reads the existing native status API. */
    public synchronized void nativeSnapshot(RunLogModel.Protocol protocol,String json,EncryptedStateRepository repository) {
        try {
            if(json==null||json.length()>MAX_NATIVE_JSON)throw new IllegalArgumentException();
            nativeSnapshot(protocol,new JSONObject(json),repository);
        }catch(Exception ignored){model.recordEvent(protocol.name(),"TELEMETRY_READ_FAILED",System.currentTimeMillis());}
    }
    public synchronized void nativeSnapshot(RunLogModel.Protocol protocol,JSONObject root,EncryptedStateRepository repository) {
        long now=System.currentTimeMillis();
        try {
            Map<String,PeerLabel> byKey=new HashMap<>(),byId=new HashMap<>();
            synchronized(repository) {
                for(StoredPeer peer:repository.state().peers()) {
                    PeerLabel label=new PeerLabel(peer.id(),peer.name(),peer.ipv4Cidr()+", "+peer.ipv6Cidr());
                    byKey.put(Key.fromBase64(peer.publicKey()).toHex(),label); byId.put(peer.id(),label);
                }
            }
            String epoch=generations.getOrDefault(protocol,0L)+":"+root.optLong("startedUnix",0);
            List<RunLogModel.DeviceSample> samples=new ArrayList<>();
            {
                JSONArray peers=root.optJSONArray("peers");
                if(peers!=null)for(int i=0;i<Math.min(peers.length(),1024);i++) {
                    JSONObject p=peers.optJSONObject(i); if(p==null)continue;
                    PeerLabel label=byKey.get(p.optString("publicKey")); if(label==null)continue;
                    long handshake=p.optLong("latestHandshakeUnix",0),rx=nonnegative(p,"receiveBytes"),tx=nonnegative(p,"transmitBytes");
                    if(handshake<=0)continue;
                    boolean active=root.optBoolean("running")&&handshake>0&&now/1000>=handshake&&now/1000-handshake<=180;
                    samples.add(new RunLogModel.DeviceSample(epoch+":"+label.id,label.name,p.optString("endpoint"),label.addresses,"UDP",0,handshake,rx,tx,active,"observed",active?"":"WG_IDLE_INFERRED"));
                }
            }
                JSONArray errors=root.optJSONArray("errors");
                if(errors!=null)for(int i=0;i<Math.min(errors.length(),64);i++) {
                    JSONObject e=errors.optJSONObject(i);if(e==null)continue;
                    String code=e.optString("code");long count=nonnegative(e,"count");
                    String key=protocol.name()+":"+epoch+":"+code;
                    long previous=nativeErrorCounts.getOrDefault(key,0L);
                    if(count>previous)model.recordEventCount(protocol.name(),code,count-previous,millis(e.optLong("lastUnix",0))>0?millis(e.optLong("lastUnix",0)):now);
                    nativeErrorCounts.put(key,count);
                }
                if(nativeErrorCounts.size()>128)nativeErrorCounts.clear();
            model.updateDevices(protocol,samples,protocol!=RunLogModel.Protocol.WG||root.optBoolean("peerStatsAvailable",true),now);
        }catch(Exception ignored){model.recordEvent(protocol.name(),"TELEMETRY_READ_FAILED",now);}
    }
    private void tick() { renderRequested(); }
    private static long nonnegative(JSONObject object,String key){return Math.max(0,object.optLong(key,0));}
    private static long millis(long seconds){return seconds>0&&seconds<=Long.MAX_VALUE/1000?seconds*1000:0;}
    private record PeerLabel(String id,String name,String addresses) { }
}
