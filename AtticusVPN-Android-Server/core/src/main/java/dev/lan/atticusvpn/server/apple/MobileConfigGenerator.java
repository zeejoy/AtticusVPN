/*
 * Copyright (c) 2026 TianzeL
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.lan.atticusvpn.server.apple;

import dev.lan.atticusvpn.server.core.EndpointFormatter;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collection;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * 实验性 Apple 原生 IKEv2 描述文件渲染器；它不是证书签发器或可运行的 VPN 服务。
 *
 * <p>只接受受信任的业务层已创建的 CA DER 和每设备独立的 RSA PKCS#12，拒绝占位
 * 证书、缺失私钥及错误身份。业务层仍必须验证服务器证书的 SAN/服务端用途、吊销状态、
 * 设备密钥唯一性、daemon 和数据面实际可用，才可以开放导出。不得将任意外部上传的
 * PKCS#12 交给此同步 API（标准提供程序解析密钥库可能执行高开销 KDF）。</p>
 *
 * <p>密码仅验证容器，不写入文件。安装人需通过另一安全渠道取得密码并在 Apple 安装
 * 提示中输入。未签名 XML 不等于加密文件；返回值含加密私钥，必须私密传送、用后清零。
 * 本类不写磁盘、不记录日志、不建立网络连接，也不改变 WireGuard 设置。</p>
 *
 * <p>依据 Apple DeviceManagement 的 VPN.IKEv2、VPN.DNS、CertificatePKCS12、
 * CertificateRoot 字段。完整来源及限制见本包 package-info.java。</p>
 */
public final class MobileConfigGenerator {
    private static final int MAX_CA_BYTES = 16_384;
    private static final int MAX_P12_BYTES = 131_072;
    private static final int MAX_PROFILE_BYTES = 262_144;
    private static final String CLIENT_AUTH = "1.3.6.1.5.5.7.3.2";

    /**
     * 仅支持明确的 ASCII DNS 身份（IDN 需预先转换成 Punycode）；不接受 IP、URL、端口
     * 或通配符。DDNS 地址变化无需改 profile，但更改域名/证书身份必须重新签发配置。
     * 名称只用于显示；所有 XML 元字符都会转义。
     */
    public record Settings(String displayName, String serverDns, String remoteIdentifier,
                           String localIdentifier, List<String> dnsServers) {
        public Settings {
            validateDisplayName(displayName);
            serverDns = requireDnsIdentity(serverDns);
            remoteIdentifier = requireDnsIdentity(remoteIdentifier);
            localIdentifier = requireDnsIdentity(localIdentifier);
            if (dnsServers == null || dnsServers.isEmpty() || dnsServers.size() > 4) {
                throw invalid("MOBILECONFIG_INVALID_DNS");
            }
            dnsServers = List.copyOf(dnsServers);
            for (final String address : dnsServers) {
                requireNumericDns(address);
            }
            if (new HashSet<>(dnsServers).size() != dnsServers.size()) {
                throw invalid("MOBILECONFIG_INVALID_DNS");
            }
        }
    }

    private MobileConfigGenerator() { }

    /** 返回独立的敏感字节数组；调用者拥有并负责用后 Arrays.fill(result, (byte) 0)。 */
    public static byte[] generate(final Settings settings, final byte[] trustedCaDer,
                                  final byte[] clientPkcs12, final char[] password,
                                  final Instant validationTime) {
        return generate(settings, trustedCaDer, clientPkcs12, password,
                validationTime, UUID::randomUUID);
    }

    // UUID 注入只对同包测试开放，生产 API 不允许调用方误复用同一 identity UUID。
    static byte[] generate(final Settings settings, final byte[] trustedCaDer,
                           final byte[] clientPkcs12, final char[] password,
                           final Instant validationTime, final Supplier<UUID> uuidSource) {
        Objects.requireNonNull(settings, "settings");
        Objects.requireNonNull(validationTime, "validationTime");
        requireLength(trustedCaDer, 32, MAX_CA_BYTES);
        requireLength(clientPkcs12, 32, MAX_P12_BYTES);
        if (password == null || password.length < 12 || password.length > 128) {
            throw invalid("MOBILECONFIG_INVALID_PASSWORD");
        }
        for (final char character : password) {
            if (character < 0x21 || character > 0x7e) {
                throw invalid("MOBILECONFIG_INVALID_PASSWORD");
            }
        }
        final UUID[] identifiers = {uuidSource.get(), uuidSource.get(),
                uuidSource.get(), uuidSource.get()};
        final HashSet<UUID> distinct = new HashSet<>();
        for (final UUID identifier : identifiers) {
            if (identifier == null || identifier.equals(new UUID(0, 0))
                    || !distinct.add(identifier)) {
                throw invalid("MOBILECONFIG_UUID_COLLISION");
            }
        }
        final byte[] ca = trustedCaDer.clone();
        final byte[] p12 = clientPkcs12.clone();
        final char[] passwordCopy = password.clone();
        try {
            validateIdentity(ca, p12, passwordCopy, settings.localIdentifier(), validationTime);
            try (final XmlBytes xml = new XmlBytes()) {
                xml.ascii("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<plist version=\"1.0\"><dict>\n");
                payload(xml, "Configuration", identifiers[0], settings.displayName());
                xml.string("PayloadOrganization", "TianzeL");
                xml.ascii("<key>PayloadRemovalDisallowed</key><false/>\n<key>PayloadContent</key><array>\n<dict>\n");
                payload(xml, "com.apple.security.root", identifiers[1], "AtticusVPN CA");
                xml.string("PayloadCertificateFileName", "AtticusVPN-CA.der");
                xml.data("PayloadContent", ca);
                xml.ascii("</dict>\n<dict>\n");
                payload(xml, "com.apple.security.pkcs12", identifiers[2], "AtticusVPN client identity");
                xml.string("PayloadCertificateFileName", "AtticusVPN-client.p12");
                xml.data("PayloadContent", p12);
                // 不附 Password：避免单个 mobileconfig 同时泄露私钥容器及解密密码。
                xml.ascii("</dict>\n<dict>\n");
                payload(xml, "com.apple.vpn.managed", identifiers[3], settings.displayName());
                xml.string("UserDefinedName", settings.displayName());
                xml.string("VPNType", "IKEv2");
                xml.ascii("<key>IKEv2</key><dict>\n");
                xml.string("RemoteAddress", settings.serverDns());
                xml.string("RemoteIdentifier", settings.remoteIdentifier());
                xml.string("LocalIdentifier", settings.localIdentifier());
                xml.string("AuthenticationMethod", "Certificate");
                xml.integer("ExtendedAuthEnabled", 0);
                xml.string("PayloadCertificateUUID", identifiers[2].toString());
                // 默认 RSA 类型；不写 CertificateType，避免产生不完整的 issuer-CN 约束。
                // daemon 必须 send_cert=always；CA 验证与 SAN 身份仍由 Apple 原生 IKE 实施。
                xml.string("DeadPeerDetectionRate", "Medium");
                xml.integer("DisableMOBIKE", 0);
                xml.integer("DisableRedirect", 1);
                xml.integer("UseConfigurationAttributeInternalIPSubnet", 1);
                xml.integer("IncludeAllNetworks", 1);
                xml.integer("ExcludeLocalNetworks", 0);
                xml.ascii("</dict>\n<key>DNS</key><dict>\n");
                xml.string("DNSProtocol", "Cleartext");
                xml.ascii("<key>ServerAddresses</key><array>\n");
                for (final String address : settings.dnsServers()) {
                    xml.element("string", address);
                }
                xml.ascii("</array>\n<key>SupplementalMatchDomains</key><array><string></string></array>\n");
                xml.integer("SupplementalMatchDomainsNoSearch", 1);
                xml.ascii("</dict>\n</dict>\n</array>\n</dict></plist>\n");
                return xml.result();
            }
        } finally {
            Arrays.fill(ca, (byte) 0);
            Arrays.fill(p12, (byte) 0);
            Arrays.fill(passwordCopy, '\0');
        }
    }

    private static void payload(final XmlBytes xml, final String type, final UUID uuid,
                                final String name) {
        xml.string("PayloadType", type);
        xml.integer("PayloadVersion", 1);
        xml.string("PayloadUUID", uuid.toString());
        xml.string("PayloadIdentifier", "dev.lan.atticusvpn.apple." + uuid);
        xml.string("PayloadDisplayName", name);
    }

    /**
     * 使用 JCA 的 X.509/PKCS#12/签名实现，不自研密码学。本期缩窄为 CA 直接签发的
     * RSA 客户端，避免默默接受未实现的中间 CA、任意密钥类型或错误私钥配对。
     * 检查不代替吊销数据库，也不证明服务器已运行。
     */
    private static void validateIdentity(final byte[] caDer, final byte[] p12,
                                         final char[] password, final String localIdentifier,
                                         final Instant time) {
        PrivateKey privateKey = null;
        byte[] signature = null;
        try {
            final X509Certificate ca = (X509Certificate) CertificateFactory.getInstance("X.509")
                    .generateCertificate(new ByteArrayInputStream(caDer));
            if (!Arrays.equals(caDer, ca.getEncoded()) || ca.getBasicConstraints() < 0
                    || !ca.getSubjectX500Principal().equals(ca.getIssuerX500Principal())) {
                throw invalid("MOBILECONFIG_INVALID_IDENTITY");
            }
            final boolean[] caUsage = ca.getKeyUsage();
            if (caUsage == null || caUsage.length <= 5 || !caUsage[5]) {
                throw invalid("MOBILECONFIG_INVALID_IDENTITY");
            }
            ca.checkValidity(Date.from(time));
            ca.verify(ca.getPublicKey());
            final KeyStore store = KeyStore.getInstance("PKCS12");
            store.load(new ByteArrayInputStream(p12), password);
            if (store.size() != 1) {
                throw invalid("MOBILECONFIG_INVALID_IDENTITY");
            }
            final String alias = store.aliases().nextElement();
            final Key key = store.getKey(alias, password);
            if (!(key instanceof PrivateKey)) {
                throw invalid("MOBILECONFIG_INVALID_IDENTITY");
            }
            privateKey = (PrivateKey) key;
            final Certificate[] chain = store.getCertificateChain(alias);
            if (chain == null || chain.length != 2 || !(chain[0] instanceof X509Certificate)
                    || !Arrays.equals(chain[1].getEncoded(), caDer)) {
                throw invalid("MOBILECONFIG_INVALID_IDENTITY");
            }
            final X509Certificate client = (X509Certificate) chain[0];
            final boolean[] usage = client.getKeyUsage();
            if (client.getBasicConstraints() != -1 || usage == null || !usage[0]
                    || client.getExtendedKeyUsage() == null
                    || !client.getExtendedKeyUsage().contains(CLIENT_AUTH)
                    || !(client.getPublicKey() instanceof RSAPublicKey)
                    || ((RSAPublicKey) client.getPublicKey()).getModulus().bitLength() < 2048
                    || !client.getIssuerX500Principal().equals(ca.getSubjectX500Principal())
                    || !hasDnsIdentity(client, localIdentifier)) {
                throw invalid("MOBILECONFIG_INVALID_IDENTITY");
            }
            client.checkValidity(Date.from(time));
            client.verify(ca.getPublicKey());
            // 标准签名/验签确认容器私钥与客户端证书一致。挑战是公开常量，不是密钥。
            final byte[] challenge = "AtticusVPN profile identity validation".getBytes(StandardCharsets.US_ASCII);
            final Signature signer = Signature.getInstance("SHA256withRSA");
            signer.initSign(privateKey);
            signer.update(challenge);
            signature = signer.sign();
            signer.initVerify(client.getPublicKey());
            signer.update(challenge);
            if (!signer.verify(signature)) {
                throw invalid("MOBILECONFIG_INVALID_IDENTITY");
            }
        } catch (final Exception ignored) {
            // 不把提供程序错误/证书主题/密码等可能敏感的信息拼接到日志或异常中。
            throw invalid("MOBILECONFIG_INVALID_IDENTITY");
        } finally {
            if (signature != null) {
                Arrays.fill(signature, (byte) 0);
            }
            if (privateKey != null) {
                try {
                    privateKey.destroy();
                } catch (final Exception ignored) {
                    // 提供程序不一定支持 destroy；Java 堆/密钥库内部副本不能保证擦除。
                }
            }
        }
    }

    private static boolean hasDnsIdentity(final X509Certificate certificate,
                                          final String identity) throws Exception {
        final Collection<List<?>> names = certificate.getSubjectAlternativeNames();
        if (names != null) {
            for (final List<?> name : names) {
                if (name.size() == 2 && Integer.valueOf(2).equals(name.get(0))
                        && identity.equalsIgnoreCase(String.valueOf(name.get(1)))) {
                    return true;
                }
            }
        }
        return false;
    }

    private static String requireDnsIdentity(final String value) {
        if (value == null || value.indexOf('.') < 1 || value.indexOf(':') >= 0
                || value.matches("[0-9.]+")) {
            throw invalid("MOBILECONFIG_INVALID_DNS_IDENTITY");
        }
        try {
            return EndpointFormatter.requireValidHost(value).toLowerCase(Locale.ROOT);
        } catch (final IllegalArgumentException ignored) {
            throw invalid("MOBILECONFIG_INVALID_DNS_IDENTITY");
        }
    }

    private static void requireNumericDns(final String value) {
        try {
            if (value == null || value.length() > 45
                    || !(value.matches("[0-9]+\\.[0-9]+\\.[0-9]+\\.[0-9]+")
                    || (value.indexOf(':') >= 0 && value.matches("[0-9A-Fa-f:]+")))) {
                throw invalid("MOBILECONFIG_INVALID_DNS");
            }
            EndpointFormatter.requireValidHost(value);
            // 仅数字语法通过后才调用 getByName；此分支不会对主机名做 DNS 查询。
            final InetAddress address = InetAddress.getByName(value);
            if (address.isAnyLocalAddress() || address.isLoopbackAddress()
                    || address.isMulticastAddress() || address.isLinkLocalAddress()) {
                throw invalid("MOBILECONFIG_INVALID_DNS");
            }
        } catch (final Exception ignored) {
            throw invalid("MOBILECONFIG_INVALID_DNS");
        }
    }

    private static void validateDisplayName(final String value) {
        if (value == null || value.isBlank() || value.length() > 80) {
            throw invalid("MOBILECONFIG_INVALID_NAME");
        }
        for (int index = 0; index < value.length(); index++) {
            final char character = value.charAt(index);
            // 禁止控制字符、孤立 surrogate 与 XML 1.0 禁止的码位；显示名允许正常中英文。
            if (character < 0x20 || character == 0x7f || character >= 0xfffe
                    || Character.isSurrogate(character)) {
                throw invalid("MOBILECONFIG_INVALID_NAME");
            }
        }
    }

    private static void requireLength(final byte[] value, final int min, final int max) {
        if (value == null || value.length < min || value.length > max) {
            throw invalid("MOBILECONFIG_INVALID_MATERIAL_SIZE");
        }
    }

    private static IllegalArgumentException invalid(final String code) {
        return new IllegalArgumentException(code);
    }

    /** 固定上限避免 ByteArrayOutputStream 扩容留下不可清理的旧敏感缓冲区。 */
    private static final class XmlBytes extends ByteArrayOutputStream implements AutoCloseable {
        private XmlBytes() {
            super(MAX_PROFILE_BYTES);
        }

        private void ascii(final String value) {
            append(value.getBytes(StandardCharsets.US_ASCII));
        }

        private void append(final byte[] value) {
            if (count > MAX_PROFILE_BYTES - value.length) {
                throw invalid("MOBILECONFIG_PROFILE_TOO_LARGE");
            }
            write(value, 0, value.length);
        }

        private void string(final String key, final String value) {
            element("key", key);
            element("string", value);
        }

        private void integer(final String key, final int value) {
            element("key", key);
            element("integer", Integer.toString(value));
        }

        private void element(final String tag, final String value) {
            ascii("<" + tag + ">");
            final String escaped = value.replace("&", "&amp;").replace("<", "&lt;")
                    .replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;");
            append(escaped.getBytes(StandardCharsets.UTF_8));
            ascii("</" + tag + ">\n");
        }

        private void data(final String key, final byte[] value) {
            element("key", key);
            ascii("<data>");
            final byte[] encoded = Base64.getEncoder().encode(value);
            try {
                append(encoded);
            } finally {
                Arrays.fill(encoded, (byte) 0);
            }
            ascii("</data>\n");
        }

        private byte[] result() {
            return toByteArray();
        }

        @Override
        public void close() {
            Arrays.fill(buf, (byte) 0);
            reset();
        }
    }
}
