/* Copyright (c) 2026 TianzeL; SPDX-License-Identifier: Apache-2.0 */
package dev.lan.atticusvpn.server.export;

import dev.lan.atticusvpn.server.config.AppSettingsPolicy;
import dev.lan.atticusvpn.server.config.WireGuardConfigFactory;
import dev.lan.atticusvpn.server.core.ClientExportOptions;
import dev.lan.atticusvpn.server.core.SecretBytes;
import dev.lan.atticusvpn.server.model.ServerState;
import dev.lan.atticusvpn.server.model.StoredPeer;
import dev.lan.atticusvpn.server.runtime.RuntimeStatusStore;
import dev.lan.atticusvpn.server.storage.EncryptedStateRepository;

/** 从既有 Keystore 导出库取得一次配置快照；不保存明文、不生成替代密钥。 */
public final class PeerConfigurationExport {
    public static final class UnavailableException extends Exception {
        private static final long serialVersionUID = 1L;
        public final boolean missingPeer;
        UnavailableException(boolean missingPeer) { this.missingPeer = missingPeer; }
    }

    public static final class Snapshot implements AutoCloseable {
        public final String name;
        public final String addresses;
        public final SecretBytes configuration;
        Snapshot(String name, String addresses, SecretBytes configuration) {
            this.name = name;
            this.addresses = addresses;
            this.configuration = configuration;
        }
        @Override public void close() { configuration.close(); }
    }

    public Snapshot load(EncryptedStateRepository repository, String peerId,
            ClientExportOptions options) throws Exception {
        synchronized (repository) {
            ServerState state = repository.state();
            StoredPeer found = null;
            for (StoredPeer peer : state.peers()) if (peer.id().equals(peerId)) found = peer;
            if (found == null) throw new UnavailableException(true);
            if (!repository.isExportable(peerId)) throw new UnavailableException(false);
            String endpoint = AppSettingsPolicy.resolveEndpointHost(
                    state, RuntimeStatusStore.current().endpointAddress());
            return new Snapshot(found.name(), found.ipv4Cidr() + ", " + found.ipv6Cidr(),
                    new WireGuardConfigFactory().renderClientConfiguration(state, found,
                            repository.readClientPrivateKey(peerId), endpoint, options));
        }
    }
}
