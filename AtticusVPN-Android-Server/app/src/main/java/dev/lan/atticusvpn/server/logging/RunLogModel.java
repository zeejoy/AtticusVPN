/* Copyright (c) 2026 TianzeL; SPDX-License-Identifier: Apache-2.0 */
package dev.lan.atticusvpn.server.logging;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Process-session diagnostics. This model accepts selected metadata, never configurations or exceptions. */
public final class RunLogModel {
    public enum Protocol { WG, ALP, IKEV2 }
    public record DeviceSample(String id, String name, String remoteEndpoint, String tunnelAddresses,
                               String transport, long connectedAtMillis, long latestHandshakeUnix,
                               long receiveBytes, long transmitBytes, boolean active,
                               String timeBasis, String endCode, long endedAtMillis) {
        public DeviceSample(String id,String name,String remoteEndpoint,String tunnelAddresses,String transport,
                            long connectedAtMillis,long latestHandshakeUnix,long receiveBytes,long transmitBytes,
                            boolean active,String timeBasis,String endCode) {
            this(id,name,remoteEndpoint,tunnelAddresses,transport,connectedAtMillis,latestHandshakeUnix,
                    receiveBytes,transmitBytes,active,timeBasis,endCode,0);
        }
    }

    public static final int MAX_DEVICES = 1024, MAX_EVENTS = 512, MAX_UTF8_BYTES = 192 * 1024;
    private static final int MAX_INPUT_SAMPLES = 2048, MAX_ENDPOINT_HISTORY = 16;
    private static final String NONE = "NONE", UNKNOWN = "UNRECOGNIZED_CODE";
    private static final String TRUNCATED = "\n[日志输出达到大小上限 / Snapshot size limit reached; some rows omitted]\n";
    private static final Pattern IPV4_TEXT = Pattern.compile("(?<![0-9.])(?:[0-9]{1,3}\\.){3}[0-9]{1,3}(?![0-9.])");
    private static final Pattern IPV6_TEXT = Pattern.compile("(?i)(?<![a-z0-9])(?:[0-9a-f]{0,4}:){2,}[0-9a-f:.]*(?:%[a-z0-9_.-]+)?");
    private static final Set<String> PHASES = Set.of("STOPPED", "STARTING", "WAITING_NETWORK", "RUNNING", "STOPPING", "ERROR", "READY", "FAILED", "UNKNOWN");
    private static final Set<String> NETWORKS = Set.of("WIFI", "CELLULAR", "ETHERNET", "VPN", "BLUETOOTH", "OTHER", "UNKNOWN", "NONE");
    private static final Set<String> DDNS = Set.of("DISABLED", "WAITING", "STOPPED", "UNKNOWN", "SUCCESS", "UPDATED", "UNCHANGED", "RETRYABLE_ERROR", "PERMANENT_ERROR", "UPDATING", "SKIPPED", "NO_CHANGE", "VERIFIED", "PENDING_VERIFICATION", "WAITING_PHYSICAL_NETWORK");
    private static final Set<String> TRANSPORTS = Set.of("WG", "UDP", "TCP", "TLS", "TLS_TCP", "WSS", "QUIC", "DTLS", "DTLS_UDP", "IKEV2", "IPSEC", "IKEV2/IPSEC");
    private static final Set<String> INFO = Set.of("LINK_CERTIFICATE_RENEWED", "APP_STARTED", "APP_REPOSITORY_READY", "DEVICE_OBSERVED", "DEVICE_ENDED", "DEVICE_RESUMED", "DEVICE_ENDPOINT_CHANGED", "COUNTER_RESET", "STATUS_CHANGED", "SERVICE_STATE_CHANGED", "LOG_LIMIT", "NO_LONGER_OBSERVED", "NORMAL_CLOSE", "CLOSED", "PEER_CLOSED", "REMOTE_CLOSED", "SERVER_STOPPED", "AUTH_REVOKED", "NETWORK_CHANGED", "CANCELLED", "SESSION_ENDED", "WG_IDLE_INFERRED", "IDLE", "CHECKING", "PASSED", "PASSED_DNS_DISABLED", "PASSED_DNS_WARNING", "HTTPS_FAILED", "TIMEOUT", "UNAVAILABLE");
    private static final Set<String> ERRORS = Set.of(
            "ALP_HISTORY_DROPPED", "IKEV2_AUTH_FAILED", "IKEV2_CERT_INVALID", "IKEV2_CERT_EXPIRED", "IKEV2_CERT_REVOKED", "IKEV2_PROPOSAL_FAILED", "IKEV2_XFRM_INSTALL_FAILED", "IKEV2_PEER_TIMEOUT", "IKEV2_TRAFFIC_SELECTORS_FAILED", "IKEV2_TELEMETRY_UNAVAILABLE", "IKEV2_TELEMETRY_INVALID", "IKEV2_TELEMETRY_TIMEOUT", "IKEV2_TELEMETRY_SESSION_LIMIT", "TELEMETRY_READ_FAILED", "TELEMETRY_UNAVAILABLE", "IPSEC_TELEMETRY_UNAVAILABLE", "IPSEC_COUNTERS_INCOMPLETE", "ENGINE_STOP_FAILED", "REPOSITORY_INITIALIZATION_FAILED", "REPOSITORY_INITIALIZATION_TIMEOUT",
            "NATIVE_RUNTIME_ERROR", "WG_BIND_UPDATE_FAILED", "WG_BIND_CLOSE_FAILED", "WG_HANDSHAKE_FAILED", "WG_DATA_SEND_FAILED", "WG_TUN_IO_FAILED", "WG_MTU_FAILED", "WG_INVALID_PACKET", "WG_STATUS_FAILED", "WG_DOWN_FAILED",
            "GATEWAY_DIAL_TIMEOUT", "GATEWAY_TCP_LIMIT", "LINK_IDENTITY_RENEWAL_REJECTED", "GATEWAY_TCP_DIAL_FAILED", "GATEWAY_UDP_DIAL_FAILED", "GATEWAY_ENDPOINT_FAILED", "GATEWAY_UDP_LIMIT", "GATEWAY_DNS_FAILED", "GATEWAY_FORWARD_IO_FAILED",
            "ALP_LISTENER_ACCEPT_FAILED", "ALP_SESSION_LIMIT", "ALP_AUTH_TIMEOUT", "ALP_AUTH_REJECTED", "ALP_INVALID_HELLO", "ALP_INVALID_FRAME", "ALP_BACKEND_CONNECT_FAILED", "ALP_BACKEND_IO_FAILED", "ALP_TRANSPORT_READ_FAILED", "ALP_TRANSPORT_WRITE_FAILED", "ALP_IDLE_TIMEOUT",
            "RUNTIME_START_FAILED", "RUNTIME_RELOAD_FAILED", "RUNTIME_CONFIGURATION_FAILED", "CONFIGURATION_FAILED", "DDNS_CONFIGURATION_FAILED",
            "ANDROID_NETWORK_OBJECT_UNAVAILABLE", "NATIVE_IPV6_LISTENER_UNAVAILABLE", "NETWORK_UNAVAILABLE", "WAITING_PHYSICAL_NETWORK",
            "DEFAULT_NETWORK_IS_VPN", "NETWORK_NOT_VALIDATED", "GLOBAL_IPV6_UNAVAILABLE", "CUSTOM_DNS_REQUIRED", "UPSTREAM_DNS_UNAVAILABLE",
            "NATIVE_ENGINE_UNAVAILABLE", "NATIVE_ENGINE_API_MISMATCH", "NATIVE_ENGINE_CALL_FAILED", "ENGINE_ALREADY_RUNNING", "GATEWAY_START_FAILED",
            "WIREGUARD_CONFIG_REJECTED", "WIREGUARD_LISTENER_FAILED", "CONFIG_SIZE_INVALID", "CONFIG_LINE_INVALID", "CONFIG_FIELD_REJECTED",
            "CONFIG_FIELD_INVALID", "CONFIG_BOOLEAN_INVALID", "CONFIG_REQUIRED_FIELD_MISSING", "SELF_TEST_CONFIG_FAILED", "SELF_TEST_DNS_FAILED",
            "PRIVATE_KEY_INVALID", "LISTEN_PORT_INVALID", "PUBLIC_KEY_INVALID", "PRESHARED_KEY_INVALID", "ALLOWED_IP_WITHOUT_PEER",
            "LINK_ALREADY_RUNNING", "LINK_CONFIG_SIZE_INVALID", "LINK_CONFIG_INVALID", "LINK_IDENTITY_REQUIRED", "LINK_LISTENERS_INVALID", "LINK_PATH_INVALID",
            "LINK_PADDING_INVALID", "LINK_PEERS_INVALID", "LINK_SERVER_NAME_INVALID", "LINK_IDENTITY_GENERATION_FAILED", "LINK_IDENTITY_INVALID",
            "LINK_IDENTITY_EXPIRED_OR_INVALID", "LINK_IDENTITY_NAME_MISMATCH", "LINK_GATEWAY_START_FAILED", "LINK_WIREGUARD_CONFIG_REJECTED", "LINK_BACKEND_START_FAILED",
            "LINK_LISTENER_START_FAILED", "LINK_MTU_INVALID", "AUTH_FAILED", "AUTH_REJECTED", "READ_FAILED", "WRITE_FAILED", "BACKEND_WRITE_FAILED", "BACKEND_READ_FAILED",
            "PROTOCOL_ERROR", "SESSION_LIMIT", "HANDSHAKE_FAILED", "HANDSHAKE_TIMEOUT", "TRANSPORT_ERROR", "TRANSPORT_READ_FAILED", "TRANSPORT_WRITE_FAILED",
            "IKEV2_ERROR", "IKEV2_ROOT_REQUIRED", "IKEV2_START_FAILED", "IKEV2_RUNTIME_FAILED", "IKEV2_CLEANUP_FAILED", "IKEV2_SERVICE_START_DENIED", "IKEV2_SERVICE_STOP_DENIED",
            "IKEV2_NO_NETWORK", "IKEV2_VPN_EGRESS_UNSUPPORTED", "IKEV2_IPV4_EGRESS_REQUIRED", "IKEV2_NETWORK_CHANGED", "IKEV2_NOT_CONFIGURED", "IKEV2_NO_ENABLED_CLIENTS",
            "IKEV2_STOP_BEFORE_EDIT", "IKEV2_IDENTITY_IMMUTABLE", "IKEV2_STORE_INVALID", "IKEV2_SERVICE_INTERRUPTED", "IKEV2_CLIENT_EXPIRED", "IKEV2_CLIENT_REVOKED",
            "IKEV2_CLIENT_DISABLED", "IKEV2_ACCOUNT_DISABLED", "IPSEC_ROOT_REQUIRED", "IPSEC_INVALID_RUNTIME", "IPSEC_ENGINE_UNAVAILABLE", "IPSEC_FORWARDING_REQUIRED",
            "IPSEC_NETWORK_TOOLS_UNAVAILABLE", "IPSEC_RESOURCE_CONFLICT", "IPSEC_NETWORK_SETUP_FAILED", "IPSEC_ENGINE_START_FAILED", "IPSEC_ENGINE_OR_CONFIG_FAILED",
            "IPSEC_ENGINE_EXITED", "IPSEC_STATUS_FAILED", "IPSEC_CLEANUP_FAILED", "IPSEC_INSTANCE_BUSY", "IPSEC_XFRM_UNAVAILABLE", "IPSEC_CANCELLED", "IPSEC_START_TIMEOUT", "IPSEC_ROOT_PIPE_FAILED");
    private record Key(Protocol protocol, String id) { }
    private record Event(long at, long lastAt, String component, String code, long row, long count, String detail) { }
    private record ProtocolState(String phase, String error, String network, String ddns, String endpoint) { }
    private static final class Device {
        final Protocol protocol; final long row, firstSeen; final String originalId;
        String name, endpoint = "", tunnel = "", transport, timeBasis, endCode = NONE;
        long connectedAt, handshake, rx, tx, lastRawRx, lastRawTx, lastSeen, endedAt, omittedEndpoints;
        boolean active, actualEnd;
        final ArrayDeque<String> endpoints = new ArrayDeque<>();
        Device(Protocol protocol, String id, long row, long at) { this.protocol=protocol;originalId=id;this.row=row;firstSeen=at;lastSeen=at; }
    }
    private final long startedAt;
    private final LinkedHashMap<Key, Device> devices = new LinkedHashMap<>();
    private final EnumMap<Protocol, ProtocolState> protocols = new EnumMap<>(Protocol.class);
    private final ArrayDeque<Event> events = new ArrayDeque<>();
    private long nextRow, omittedDevices, omittedEvents;

    public RunLogModel(long sessionStartedMillis) {
        startedAt=Math.max(0,sessionStartedMillis);
        addEvent("APP","APP_STARTED",startedAt,0);
    }

    /** A complete successful sample may end missing rows; failed reads must pass complete=false. */
    public synchronized void updateDevices(Protocol protocol, List<DeviceSample> samples, boolean complete, long sampledAtMillis) {
        if(protocol==null || samples==null)return;
        long at=Math.max(startedAt,Math.max(0,sampledAtMillis));
        Set<String> seen=new HashSet<>();
        int count=Math.min(MAX_INPUT_SAMPLES,samples.size());
        if(samples.size()>MAX_INPUT_SAMPLES){complete=false;recordEvent("LOG","LOG_LIMIT",at);}
        for(int index=0;index<count;index++) {
            DeviceSample sample=samples.get(index); if(sample==null)continue;
            String id=sample.id();
            if(id==null || !id.matches("[A-Za-z0-9._:/+=-]{1,192}"))continue;
            seen.add(id); Key key=new Key(protocol,id); Device device=devices.get(key);
            if(device!=null && !device.active && sample.active()) {
                if(protocol==Protocol.WG) {
                    // WireGuard has no authenticated session start/end event.
                    // An expired observation window must not split cumulative
                    // peer counters into overlapping fictional connections.
                    device.endedAt=0;device.actualEnd=false;device.endCode=NONE;
                    addEvent(protocol.name(),"DEVICE_RESUMED",at,device.row);
                } else {
                    devices.remove(key);devices.put(new Key(protocol,id+"~"+device.row),device);device=null;
                }
            }
            boolean created=device==null;
            if(created) {
                if(!makeRoom()){omittedDevices=saturatingIncrement(omittedDevices);continue;}
                device=new Device(protocol,id,++nextRow,at);devices.put(key,device);
                addEvent(protocol.name(),"DEVICE_OBSERVED",at,device.row);
            }
            boolean wasActive=device.active;
            device.name=safeLabel(sample.name(),96);
            device.transport=enumValue(sample.transport(),TRANSPORTS,"UNKNOWN");
            device.timeBasis=protocol!=Protocol.WG && "authenticated".equals(sample.timeBasis())?"authenticated":"observed";
            if(protocol!=Protocol.WG && sample.connectedAtMillis()>0 && sample.connectedAtMillis()<=at)device.connectedAt=sample.connectedAtMillis();
            device.handshake=Math.max(device.handshake,Math.max(0,sample.latestHandshakeUnix()));
            String endpoint=endpoint(sample.remoteEndpoint(),false);
            if(!endpoint.isEmpty() && !endpoint.equals(device.endpoint)) {
                if(!device.endpoint.isEmpty())addEvent(protocol.name(),"DEVICE_ENDPOINT_CHANGED",at,device.row);
                device.endpoint=endpoint;
                if(!device.endpoints.contains(endpoint)) {
                    if(device.endpoints.size()==MAX_ENDPOINT_HISTORY){device.endpoints.removeFirst();device.omittedEndpoints=saturatingIncrement(device.omittedEndpoints);}
                    device.endpoints.addLast(endpoint);
                }
            }
            String tunnel=tunnelAddresses(sample.tunnelAddresses());if(!tunnel.isEmpty())device.tunnel=tunnel;
            long rx=Math.max(0,sample.receiveBytes()),tx=Math.max(0,sample.transmitBytes());
            if(!created && (rx<device.lastRawRx || tx<device.lastRawTx))addEvent(protocol.name(),"COUNTER_RESET",at,device.row);
            device.lastRawRx=rx;device.lastRawTx=tx;device.rx=Math.max(device.rx,rx);device.tx=Math.max(device.tx,tx);
            device.lastSeen=Math.max(device.lastSeen,at);device.active=sample.active();
            if(!device.active) {
                if(sample.endedAtMillis()>0 && sample.endedAtMillis()<=at
                        && (device.connectedAt==0 || sample.endedAtMillis()>=device.connectedAt)) {
                    device.endedAt=sample.endedAtMillis();device.actualEnd=true;
                } else if(device.endedAt==0)device.endedAt=at;
                String code=code(sample.endCode());if(!NONE.equals(code))device.endCode=code;
                if(wasActive || created)addEvent(protocol.name(),"DEVICE_ENDED",at,device.row);
            }
        }
        if(complete)for(Device device:devices.values()) {
            if(device.protocol==protocol && device.active && !seen.contains(device.originalId)) {
                device.active=false;device.endedAt=at;device.endCode="NO_LONGER_OBSERVED";
                addEvent(protocol.name(),"DEVICE_ENDED",at,device.row);
            }
        }
    }

    public synchronized void recordProtocol(Protocol protocol,String phase,String errorCode,String network,String ddns,String endpoint,long now) {
        if(protocol==null)return;
        ProtocolState state=new ProtocolState(enumValue(phase,PHASES,"UNKNOWN"),code(errorCode),enumValue(network,NETWORKS,"UNKNOWN"),enumValue(ddns,DDNS,"UNKNOWN"),endpoint(endpoint,true));
        ProtocolState previous=protocols.put(protocol,state);
        if(!state.equals(previous))addEvent(protocol.name(),"STATUS_CHANGED",now,0,1,
                "phase="+state.phase+"; network="+state.network+"; DDNS="+state.ddns);
        if(!NONE.equals(state.error) && (previous==null || !state.error.equals(previous.error)))addEvent(protocol.name(),state.error,now,0);
    }

    public synchronized void recordEvent(String component,String code,long now) {
        recordEventCount(component,code,1,now);
    }

    /** Counter deltas must come from the native aggregate, never re-add its full count every poll. */
    public synchronized void recordEventCount(String component,String code,long delta,long now) {
        if(delta<=0)return;
        String safeComponent=component!=null && Set.of("APP","WG","ALP","IKEV2","DDNS","NETWORK","LOG","STORAGE").contains(component)?component:"APP";
        String safeCode=code(code);
        if(NONE.equals(safeCode))return;
        addEvent(safeComponent,safeCode,now,0,delta,"");
    }

    /** Immutable text snapshot. Copy and export must use this exact selected privacy mode. */
    public synchronized String render(boolean revealIps,long now) {
        long at=Math.max(startedAt,Math.max(0,now));
        Budget out=new Budget();
        out.add("AtticusVPN 运行日志 / Runtime log — this process session\n");
        out.add("打开 / Opened: "+timestamp(startedAt)+"; 快照 / Snapshot: "+timestamp(at)+"; 本次运行 / Uptime: "+duration(at-startedAt)+"\n");
        out.add(revealIps?"IP: 完整显示 / FULL — 分享前检查 / Review before sharing\n":"IP: 默认部分隐藏 / MASKED; 私钥/密码/Token/完整配置不采集 / credentials and configs not collected\n");
        out.add("数据范围 / Scope: sampled metadata, not packet capture or billing. WG 在线和时长为握手观察推断 / WG online/duration are inferred.\n");
        out.add("保留 / Retention: max "+MAX_DEVICES+" device rows / "+MAX_EVENTS+" events / "+MAX_ENDPOINT_HISTORY+" endpoints per row. 已省略旧设备 / Evicted or refused rows: "+omittedDevices+"; 旧事件 / older events: "+omittedEvents+"\n");
        out.add("\n协议状态 / Protocol status\n");
        for(Protocol protocol:Protocol.values()) {
            ProtocolState state=protocols.get(protocol);
            if(state==null && protocol!=Protocol.WG)continue;
            if(state==null){out.add(protocol+": 尚无采样 / no sample\n");continue;}
            out.add(protocol+": "+state.phase+"; network="+state.network+"; DDNS="+state.ddns+"; endpoint="+shown(state.endpoint,revealIps)+"; error="+state.error+"\n");
            if(!NONE.equals(state.error))out.add("  原因 / Reason: "+reason(state.error)+"\n");
            if("PERMANENT_ERROR".equals(state.ddns))out.add("  DDNS: 配置、授权或域名类别失败，具体原因未确认 / configuration/auth/domain category failure; exact cause unconfirmed\n");
            if("RETRYABLE_ERROR".equals(state.ddns))out.add("  DDNS: 网络或提供商类别暂时失败，具体原因未确认 / temporary network/provider category failure; exact cause unconfirmed\n");
            if("PENDING_VERIFICATION".equals(state.ddns))out.add("  DDNS: 更新完成，解析结果尚待核验 / update completed; DNS resolution verification pending\n");
        }
        out.add("\n最近事件 / Recent events (newest 12; older events follow devices)\n");
        List<Event> orderedEvents=new ArrayList<>(events);
        java.util.Collections.reverse(orderedEvents);
        int recent=Math.min(12,orderedEvents.size());
        for(int index=0;index<recent;index++)out.add(eventText(orderedEvents.get(index)));
        out.add("\n设备 / Devices (active first; ended rows retain IP and counters)\n");
        List<Device> ordered=new ArrayList<>(devices.values());
        ordered.sort(Comparator.comparing((Device d)->!d.active).thenComparing(Comparator.comparingLong((Device d)->d.row).reversed()));
        if(ordered.isEmpty())out.add("尚未观察到已认证设备 / No authenticated device observed\n");
        for(Device device:ordered) {
            long end=device.active?Math.max(at,device.lastSeen):device.endedAt>0?device.endedAt:device.lastSeen;
            boolean authenticated="authenticated".equals(device.timeBasis) && device.connectedAt>0;
            long begin=authenticated?device.connectedAt:device.firstSeen;
            StringBuilder row=new StringBuilder();
            row.append("#").append(device.row).append(' ').append(device.protocol).append(' ').append(shown(device.name,revealIps)).append("; transport=").append(device.transport).append("; ").append(device.active?"活动 / active":"已结束或未再观察到 / ended or no longer observed").append('\n');
            row.append("  来源 / Remote: ").append(shown(device.endpoint,revealIps)).append("; 隧道 / Tunnel: ").append(shown(device.tunnel,revealIps)).append('\n');
            row.append("  已观察来源 / Observed endpoints: ");
            for(String address:device.endpoints)row.append(shown(address,revealIps)).append("; ");
            if(device.endpoints.isEmpty())row.append("未提供 / unavailable");
            if(device.omittedEndpoints>0)row.append(" (older omitted: ").append(device.omittedEndpoints).append(')');
            row.append('\n').append("  ").append(authenticated?"认证连接开始 / Authenticated connection start: ":"首次观察 / First observed (not actual connect time): ").append(timestamp(begin));
            row.append("; ").append(authenticated?"连接时长 / Connection duration: ":"观察时长 / Observed window (inferred): ").append(duration(Math.max(0,end-begin))).append('\n');
            row.append("  最后采样 / Last sample: ").append(timestamp(device.lastSeen)).append("; 最新握手 / Last handshake: ").append(device.handshake>0?timestamp(saturatingMillis(device.handshake)):"未提供 / unavailable").append('\n');
            if(!device.active)row.append(device.actualEnd?"  原生结束时间 / Native end time: ":"  结束观察 / End observed (not exact disconnect time): ").append(timestamp(device.endedAt)).append("; ").append(device.endCode).append(" — ").append(reason(device.endCode)).append('\n');
            row.append("  服务端视角，已采样最大计数 / Server perspective, maximum sampled counters: Rx=").append(device.rx).append(" B; Tx=").append(device.tx).append(" B\n");
            if(!out.add(row.toString()))break;
        }
        if(orderedEvents.size()>recent) {
            out.add("\n较早事件 / Older events (newest first)\n");
            for(int index=recent;index<orderedEvents.size();index++)if(!out.add(eventText(orderedEvents.get(index))))break;
        }
        return out.finish();
    }

    private static String eventText(Event event) {
        return timestamp(event.at)+" "+event.component+" "+event.code+(event.row>0?" device#"+event.row:"")
                +(event.count>1?"; count="+event.count+"; last="+timestamp(event.lastAt):"")
                +(event.detail.isEmpty()?"":"; "+event.detail)+" — "+reason(event.code)+"\n";
    }

    private boolean makeRoom() {
        if(devices.size()<MAX_DEVICES)return true;
        Iterator<Map.Entry<Key,Device>> iterator=devices.entrySet().iterator();
        while(iterator.hasNext())if(!iterator.next().getValue().active){iterator.remove();omittedDevices=saturatingIncrement(omittedDevices);return true;}
        return false;
    }
    private void addEvent(String component,String code,long at,long row) {
        addEvent(component,code,at,row,1,"");
    }
    private void addEvent(String component,String code,long at,long row,long count,String detail) {
        long time=Math.max(startedAt,Math.max(0,at));
        Event previous=events.peekLast();
        if(row==0 && previous!=null && previous.row==0 && previous.component.equals(component)
                && previous.code.equals(code) && previous.detail.equals(detail)) {
            events.removeLast();long total=previous.count>Long.MAX_VALUE-count?Long.MAX_VALUE:previous.count+count;
            events.addLast(new Event(previous.at,Math.max(time,previous.lastAt),component,code,0,total,detail));return;
        }
        if(events.size()==MAX_EVENTS){events.removeFirst();omittedEvents=saturatingIncrement(omittedEvents);}
        events.addLast(new Event(time,time,component,code,row,count,detail));
    }
    private static long saturatingIncrement(long value){return value==Long.MAX_VALUE?value:value+1;}
    private static long saturatingMillis(long seconds){return seconds>Long.MAX_VALUE/1000?Long.MAX_VALUE:seconds*1000;}
    private static String code(String value) {
        if(value==null || value.isEmpty() || NONE.equals(value))return NONE;
        return ERRORS.contains(value) || INFO.contains(value)?value:UNKNOWN;
    }
    private static String enumValue(String value,Set<String> allowed,String fallback) {
        if(value==null || value.length()>64)return fallback;String upper=value.toUpperCase(Locale.ROOT);
        return allowed.contains(upper)?upper:fallback;
    }
    private static String safeLabel(String value,int max) {
        if(value==null)return "";StringBuilder result=new StringBuilder();
        for(int i=0;i<value.length() && result.length()<max;) {
            int point=value.codePointAt(i);i+=Character.charCount(point);
            if(!Character.isISOControl(point) && Character.getType(point)!=Character.FORMAT && point!=0x2028 && point!=0x2029)result.appendCodePoint(point);
        }
        String text=result.toString();
        if(Pattern.compile("(?i)(private.?key|preshared.?key|password|token|secret)\\s*[:=]").matcher(text).find())return "[已隐藏 / redacted]";
        return text;
    }
    private static String endpoint(String value,boolean allowDns) {
        if(value==null || value.length()>192 || value.isEmpty())return "";
        String host=value;String suffix="";
        if(value.startsWith("[")) {
            int close=value.indexOf(']');if(close<0)return "";host=value.substring(1,close);suffix=value.substring(close+1);
            if(!suffix.isEmpty() && !validPort(suffix))return "";
            return ipv6(host)?"["+host+"]"+suffix:"";
        }
        int colon=value.indexOf(':');
        if(colon>=0 && colon==value.lastIndexOf(':')){host=value.substring(0,colon);suffix=value.substring(colon);if(!validPort(suffix))return "";}
        if(ipv4(host) || ipv6(host))return host+suffix;
        return allowDns && host.length()<=160 && host.matches("[A-Za-z0-9](?:[A-Za-z0-9.-]*[A-Za-z0-9])?")?host+suffix:"";
    }
    private static boolean validPort(String value) {
        if(!value.matches(":[0-9]{1,5}"))return false;
        int port=Integer.parseInt(value.substring(1));return port>0 && port<=65535;
    }
    private static String tunnelAddresses(String value) {
        if(value==null || value.length()>1024)return "";
        StringBuilder out=new StringBuilder();int count=0;
        for(String item:value.split("[ ,;]+")) {
            if(++count>16)break;
            String[] pieces=item.split("/",-1);if(pieces.length>2)continue;
            String ip=pieces[0];boolean v4=ipv4(ip);if(!v4 && !ipv6(ip))continue;
            if(pieces.length==2) {
                if(!pieces[1].matches("[0-9]{1,3}"))continue;
                int prefix=Integer.parseInt(pieces[1]);if(prefix>(v4?32:128))continue;
            }
            if(out.length()>0)out.append(", ");out.append(item);
        }
        return out.toString();
    }
    private static boolean ipv4(String value) {
        String[] parts=value.split("\\.",-1);if(parts.length!=4)return false;
        for(String part:parts)if(!part.matches("[0-9]{1,3}") || Integer.parseInt(part)>255)return false;
        return true;
    }
    private static boolean ipv6(String value) {
        String ip=value;int zone=ip.indexOf('%');
        if(zone>=0){if(!ip.substring(zone+1).matches("[A-Za-z0-9_.-]{1,24}"))return false;ip=ip.substring(0,zone);}
        if(!ip.contains(":"))return false;
        int groups=0;
        if(ip.contains(".")){int colon=ip.lastIndexOf(':');if(colon<0 || !ipv4(ip.substring(colon+1)))return false;ip=ip.substring(0,colon)+":0:0";}
        if(!ip.matches("[0-9a-fA-F:]+") || ip.contains(":::") || ip.indexOf("::")!=ip.lastIndexOf("::"))return false;
        for(String part:ip.split(":",-1))if(!part.isEmpty()){if(part.length()>4)return false;groups++;}
        if(ip.contains("::"))return groups<8;
        return !ip.startsWith(":") && !ip.endsWith(":") && groups==8;
    }
    private static String shown(String value,boolean reveal) {
        if(value==null || value.isEmpty())return "未提供 / unavailable";
        if(reveal)return value;
        Matcher v6=IPV6_TEXT.matcher(value);StringBuffer masked=new StringBuffer();
        while(v6.find()) {
            String raw=v6.group();String replacement="IPv6:*";
            if(!raw.startsWith(":") && !raw.contains(".")) {
                String[] parts=raw.split(":",3);if(parts.length>=2)replacement=parts[0]+":"+parts[1]+":*";
            }
            v6.appendReplacement(masked,Matcher.quoteReplacement(replacement));
        }
        v6.appendTail(masked);
        Matcher v4=IPV4_TEXT.matcher(masked.toString());StringBuffer result=new StringBuffer();
        while(v4.find()){String[] parts=v4.group().split("\\.");v4.appendReplacement(result,Matcher.quoteReplacement(parts[0]+"."+parts[1]+".*.*"));}
        v4.appendTail(result);return result.toString();
    }
    private static String timestamp(long time){return Instant.ofEpochMilli(Math.max(0,time)).toString();}
    private static String duration(long elapsed){long seconds=Math.max(0,elapsed)/1000;return (seconds/3600)+"h "+(seconds/60%60)+"m "+(seconds%60)+"s";}

    /** Fixed explanations describe only what the code establishes, never invented root causes. */
    private static String reason(String code) {
        switch(code) {
            case "GATEWAY_DIAL_TIMEOUT":return "出口连接建立超时；不记录目标 / outbound dial timed out; destination not retained";
            case "GATEWAY_TCP_LIMIT":return "达到全局或单设备 TCP 连接上限 / total or per-device TCP limit reached";
            case "LINK_CERTIFICATE_RENEWED":return "证书已续期，密钥和指纹保持不变 / certificate renewed with the same key and pin";
            case "LINK_IDENTITY_RENEWAL_REJECTED":return "证书续期未通过身份校验 / local certificate renewal failed identity validation";
            case NONE:return "未报告错误 / no error reported";
            case "APP_STARTED":return "本次应用进程启动 / this application process started";
            case "DEVICE_OBSERVED":return "首次观察到已认证设备元数据 / authenticated device metadata first observed";
            case "DEVICE_ENDED":case "NO_LONGER_OBSERVED":return "连接已结束或后续完整采样未再观察到；断开瞬间未确认 / ended or absent from a later complete sample; exact disconnect instant unconfirmed";
            case "DEVICE_ENDPOINT_CHANGED":return "来源地址变化，历史地址保留在设备行 / endpoint changed; earlier addresses retained in device row";
            case "DEVICE_RESUMED":return "WireGuard再次观察为活跃；继续同一观察窗口与累计计数，不推断新的连接 / WireGuard observed active again; same observation window and cumulative counters, not a new inferred connection";
            case "WG_IDLE_INFERRED":return "握手观察窗口超时，仅推断不活跃，不代表实际断开 / handshake observation window expired; inactivity inferred, not an actual disconnect event";
            case "COUNTER_RESET":return "原生计数降低；保留已采样最大值，不推算漏失流量 / counter decreased; maximum sampled values retained, missing traffic not inferred";
            case "STATUS_CHANGED":case "SERVICE_STATE_CHANGED":return "协议或网络/DDNS状态变化 / protocol or network/DDNS state changed";
            case "IPSEC_ROOT_REQUIRED":case "IKEV2_ROOT_REQUIRED":case "IPSEC_ROOT_PIPE_FAILED":return "未取得有效Root会话或授权通道中断 / valid Root session unavailable or authorization channel interrupted";
            case "IPSEC_FORWARDING_REQUIRED":return "IPv4转发未启用 / IPv4 forwarding is not enabled";
            case "IPSEC_XFRM_UNAVAILABLE":return "内核ESP/XFRM安装探测未通过；具体策略或模块原因未确认 / kernel ESP/XFRM installation probe failed; exact policy/module cause unconfirmed";
            case "IPSEC_NETWORK_TOOLS_UNAVAILABLE":return "所需系统路由/防火墙工具不可执行 / required routing/firewall tools unavailable";
            case "IPSEC_RESOURCE_CONFLICT":case "IPSEC_INSTANCE_BUSY":return "端口或实例资源被占用，未接管其他实例 / port or instance resource conflict; other owner not adopted";
            case "IPSEC_CLEANUP_FAILED":case "IKEV2_CLEANUP_FAILED":return "本实例资源清理未确认，需恢复检查 / owned resource cleanup unconfirmed; recovery inspection required";
            case "IPSEC_ENGINE_EXITED":case "IKEV2_SERVICE_INTERRUPTED":return "服务进程意外退出；底层原因未确认 / service process interrupted; underlying cause unconfirmed";
            case "IKEV2_IPV4_EGRESS_REQUIRED":return "没有可用的物理IPv4出口 / usable physical IPv4 egress unavailable";
            case "IKEV2_VPN_EGRESS_UNSUPPORTED":case "DEFAULT_NETWORK_IS_VPN":return "默认出口是其他VPN，本路径不支持 / default egress is another VPN and is unsupported";
            case "NETWORK_UNAVAILABLE":case "WAITING_PHYSICAL_NETWORK":case "ANDROID_NETWORK_OBJECT_UNAVAILABLE":case "IKEV2_NO_NETWORK":return "所选物理网络不可用 / selected physical network unavailable";
            case "GLOBAL_IPV6_UNAVAILABLE":case "NATIVE_IPV6_LISTENER_UNAVAILABLE":return "未取得可用IPv6地址或监听 / usable IPv6 address or listener unavailable";
            case "IKEV2_NETWORK_CHANGED":case "NETWORK_CHANGED":return "默认物理网络/出口变化，需重新建立服务 / physical network or egress changed; service restart required";
            case "DDNS_CONFIGURATION_FAILED":return "DDNS配置或提供商初始化失败，具体字段原因未确认 / DDNS configuration/provider initialization failed; exact field cause unconfirmed";
            case "AUTH_FAILED":case "AUTH_REJECTED":return "身份验证被拒绝，具体凭据错误不公开 / authentication rejected; credential detail not exposed";
            case "ALP_AUTH_REJECTED":return "Atticus Link身份凭据未获授权，具体凭据不公开 / Atticus Link credentials not authorized; credential detail not exposed";
            case "ALP_AUTH_TIMEOUT":return "Atticus Link初始认证未在期限内完成 / Atticus Link initial authentication did not finish before its deadline";
            case "ALP_INVALID_HELLO":case "ALP_INVALID_FRAME":return "Atticus Link协议帧或初始版本校验未通过 / Atticus Link initial version or frame validation failed";
            case "ALP_IDLE_TIMEOUT":return "Atticus Link会话空闲达到期限 / Atticus Link session idle deadline reached";
            case "ALP_HISTORY_DROPPED":return "原生短连接历史已达到保留上限，部分记录未采集 / native short-session history limit reached; some records were not collected";
            case "IKEV2_AUTH_FAILED":return "IKEv2身份认证未通过；不保留凭据或未认证身份 / IKEv2 authentication failed; credentials and unauthenticated identities not retained";
            case "IKEV2_CERT_INVALID":case "IKEV2_CERT_EXPIRED":case "IKEV2_CERT_REVOKED":return "IKEv2证书校验、有效期或吊销检查未通过；具体证书见安全错误码 / IKEv2 certificate validity or revocation check failed; inspect the fixed error code";
            case "IKEV2_PROPOSAL_FAILED":case "IKEV2_TRAFFIC_SELECTORS_FAILED":return "IKEv2加密提议或流量选择器无法协商 / IKEv2 cryptographic proposal or traffic selector negotiation failed";
            case "IKEV2_XFRM_INSTALL_FAILED":return "内核IPsec SA或策略安装失败，具体系统原因未确认 / kernel IPsec SA/policy installation failed; underlying system cause unconfirmed";
            case "IKEV2_PEER_TIMEOUT":return "IKEv2对端未在重试期限内响应；具体网络原因未确认 / IKEv2 peer did not respond before retry deadline; exact network cause unconfirmed";
            case "ALP_SESSION_LIMIT":case "SESSION_LIMIT":return "Atticus Link会话达到资源上限 / Atticus Link session resource limit reached";
            case "ALP_BACKEND_CONNECT_FAILED":case "ALP_BACKEND_IO_FAILED":return "Atticus Link与本机WireGuard后端的连接/读写失败 / Atticus Link connection/I/O to local WireGuard backend failed";
            case "ALP_TRANSPORT_READ_FAILED":case "ALP_TRANSPORT_WRITE_FAILED":case "ALP_LISTENER_ACCEPT_FAILED":return "Atticus Link传输接入或读写失败，具体网络原因未确认 / Atticus Link transport accept/read/write failed; exact network cause unconfirmed";
            case "WG_BIND_UPDATE_FAILED":case "WG_BIND_CLOSE_FAILED":case "WG_DOWN_FAILED":return "WireGuard监听绑定、更新或关闭失败，具体系统原因未确认 / WireGuard listener bind/update/close failed; exact system cause unconfirmed";
            case "WG_HANDSHAKE_FAILED":return "WireGuard握手处理或发送失败，不足以确定凭据错误 / WireGuard handshake processing/send failed; does not establish incorrect credentials";
            case "WG_DATA_SEND_FAILED":return "WireGuard数据发送失败，具体网络/系统原因未确认 / WireGuard data send failed; exact network/system cause unconfirmed";
            case "WG_TUN_IO_FAILED":case "WG_MTU_FAILED":return "WireGuard内部隧道I/O或MTU操作失败 / WireGuard internal tunnel I/O or MTU operation failed";
            case "WG_INVALID_PACKET":return "WireGuard数据包解析或校验未通过，不记录包正文 / WireGuard packet parsing/validation failed; packet body not recorded";
            case "WG_STATUS_FAILED":return "WireGuard状态读取失败；保留上次采样 / WireGuard status read failed; previous sample retained";
            case "GATEWAY_TCP_DIAL_FAILED":case "GATEWAY_UDP_DIAL_FAILED":case "GATEWAY_ENDPOINT_FAILED":return "手机出口建立转发端点失败；具体目标、网络或系统原因未确认 / phone egress forwarding endpoint setup failed; exact target/network/system cause unconfirmed";
            case "GATEWAY_UDP_LIMIT":return "用户态出口UDP会话数达到资源上限 / userspace egress UDP session resource limit reached";
            case "GATEWAY_DNS_FAILED":return "出口DNS转发/解析未完成，不记录查询正文 / egress DNS forwarding/resolution failed; query body not recorded";
            case "GATEWAY_FORWARD_IO_FAILED":return "出口转发读写失败，不能据此推断VPN认证失败 / egress forwarding I/O failed; does not establish VPN authentication failure";
            case "AUTH_REVOKED":return "该会话授权已撤销 / session authorization revoked";
            case "NATIVE_ENGINE_UNAVAILABLE":case "NATIVE_ENGINE_API_MISMATCH":return "原生引擎缺失或桥接API不匹配 / native engine unavailable or bridge API mismatch";
            case "IKEV2_TELEMETRY_UNAVAILABLE":case "IKEV2_TELEMETRY_INVALID":case "IKEV2_TELEMETRY_TIMEOUT":case "TELEMETRY_UNAVAILABLE":case "TELEMETRY_READ_FAILED":case "IPSEC_TELEMETRY_UNAVAILABLE":case "IPSEC_STATUS_FAILED":return "本次遥测未能取得，保留上次数据；不据此推断已断开 / telemetry unavailable; previous data retained, disconnection not inferred";
            case "IKEV2_TELEMETRY_SESSION_LIMIT":return "IKEv2遥测记录达到上限，部分记录可能省略 / IKEv2 telemetry retention limit reached; some records may be omitted";
            case "IPSEC_COUNTERS_INCOMPLETE":return "部分IPsec计数未取得；只显示已确认的采样值 / some IPsec counters unavailable; only confirmed samples shown";
            case "REPOSITORY_INITIALIZATION_FAILED":case "REPOSITORY_INITIALIZATION_TIMEOUT":return "加密状态仓库初始化未完成；具体底层原因未确认 / encrypted state initialization failed or timed out; underlying cause unconfirmed";
            case "HTTPS_FAILED":case "TIMEOUT":return "手机出口探测未确认，不代表客户端连接失败 / phone egress probe unconfirmed; does not establish client failure";
            case "PASSED_DNS_WARNING":return "手机HTTPS出口可用，上游DNS未确认 / phone HTTPS egress available, upstream DNS unconfirmed";
            case "PASSED":case "PASSED_DNS_DISABLED":return "手机出口检测通过；远端客户端仍需独立验证 / phone egress probe passed; remote client still requires separate verification";
            case "NORMAL_CLOSE":case "CLOSED":case "PEER_CLOSED":case "REMOTE_CLOSED":case "SERVER_STOPPED":case "SESSION_ENDED":return "会话已关闭；最终计数为最后一次采样 / session closed; counters reflect last observation";
            case "LOG_LIMIT":return "达到保留/输入限制，旧信息或额外输入被省略 / retention/input limit reached; older or excess data omitted";
            case UNKNOWN:return "未识别的安全代码；具体原因未确认，原始文本不采集 / unknown code; exact cause unconfirmed, raw text not collected";
            default:
                if(ERRORS.contains(code))return "操作失败，具体底层原因未确认；结合代码和前后状态排查 / operation failed; underlying cause unconfirmed, inspect code and surrounding states";
                return "状态观察记录 / status observation";
        }
    }
    private static final class Budget {
        final StringBuilder text=new StringBuilder();int bytes;boolean truncated;
        boolean add(String value) {
            if(truncated)return false;
            int length=value.getBytes(StandardCharsets.UTF_8).length;
            if(bytes+length>MAX_UTF8_BYTES-256-TRUNCATED.getBytes(StandardCharsets.UTF_8).length){truncated=true;return false;}
            text.append(value);bytes+=length;return true;
        }
        String finish(){if(truncated)text.append(TRUNCATED);return text.toString();}
    }
}
