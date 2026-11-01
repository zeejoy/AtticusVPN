/* Copyright (c) 2026 TianzeL; SPDX-License-Identifier: Apache-2.0 */
package dev.lan.atticusvpn.server.capability;

import dev.lan.atticusvpn.server.root.RootState;

/** 不含私钥、原始 XFRM 输出、完整网络地址或 Root provider 账户信息的 UI 快照。 */
public final class DeviceCapabilitySnapshot {
    private final RootState rootState;
    private final String androidVersion, kernelVersion, abis, selinux, ipv6Status;
    private final IpsecCapabilityChecker.Result ipsec;

    public DeviceCapabilitySnapshot(RootState rootState, String androidVersion, String kernelVersion,
            String abis, String selinux, String ipv6Status, IpsecCapabilityChecker.Result ipsec) {
        this.rootState = rootState; this.androidVersion = androidVersion; this.kernelVersion = kernelVersion;
        this.abis = abis; this.selinux = selinux; this.ipv6Status = ipv6Status; this.ipsec = ipsec;
    }
    public RootState rootState() { return rootState; }
    public String androidVersion() { return androidVersion; }
    public String kernelVersion() { return kernelVersion; }
    public String abis() { return abis; }
    public String selinux() { return selinux; }
    public String ipv6Status() { return ipv6Status; }
    public String rootProvider() { return "Unknown Root Provider"; }
    public IpsecCapabilityChecker.Result ipsec() { return ipsec; }
    public boolean ikev2DataPlaneVerified() { return false; }
}
