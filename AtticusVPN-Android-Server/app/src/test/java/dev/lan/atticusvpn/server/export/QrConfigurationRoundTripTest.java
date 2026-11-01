/* Copyright (c) 2026 TianzeL; SPDX-License-Identifier: Apache-2.0 */
package dev.lan.atticusvpn.server.export;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;
import com.wireguard.config.Config;
import com.wireguard.crypto.KeyPair;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import dev.lan.atticusvpn.server.config.WireGuardConfigFactory;
import dev.lan.atticusvpn.server.core.ClientExportOptions;
import dev.lan.atticusvpn.server.core.SecretBytes;
import dev.lan.atticusvpn.server.model.ServerState;
import dev.lan.atticusvpn.server.model.StoredPeer;
import static org.junit.Assert.*;

/** 真实 ZXing 编解码 + 官方 WireGuard 配置解析；仅使用本次生成的测试密钥，不访问网络。 */
@RunWith(Parameterized.class)
public final class QrConfigurationRoundTripTest {
    @Parameterized.Parameters(name = "{0}-{1}")
    public static List<Object[]> cases() {
        return Arrays.asList(new Object[][]{
                {ClientExportOptions.AddressMode.IPV4_ONLY, "192.0.2.1"},
                {ClientExportOptions.AddressMode.IPV6_ONLY, "192.0.2.1"},
                {ClientExportOptions.AddressMode.DUAL_STACK, "192.0.2.1"},
                {ClientExportOptions.AddressMode.IPV4_ONLY, "vpn.example.com"},
                {ClientExportOptions.AddressMode.IPV6_ONLY, "vpn.example.com"},
                {ClientExportOptions.AddressMode.DUAL_STACK, "vpn.example.com"},
                {ClientExportOptions.AddressMode.IPV4_ONLY, "2001:db8::1"},
                {ClientExportOptions.AddressMode.IPV6_ONLY, "2001:db8::1"},
                {ClientExportOptions.AddressMode.DUAL_STACK, "2001:db8::1"}
        });
    }

    private final ClientExportOptions.AddressMode mode;
    private final String endpoint;
    public QrConfigurationRoundTripTest(ClientExportOptions.AddressMode mode, String endpoint) {
        this.mode = mode; this.endpoint = endpoint;
    }

    @Test public void qrAndPngDecodeToExactConfAcceptedByOfficialParser() throws Exception {
        KeyPair serverKeys = new KeyPair();
        KeyPair clientKeys = new KeyPair();
        ServerState state = new ServerState();
        state.serverPublicKey = serverKeys.getPublicKey().toBase64();
        state.listenPort = 34567;
        StoredPeer peer = new StoredPeer("11111111111111111111111111111111", "test-phone", 2,
                clientKeys.getPublicKey().toBase64(), clientKeys.getPrivateKey().toBase64(),
                true, false, 1L, 0L);
        ClientExportOptions options = new ClientExportOptions(mode,
                "192.0.2.53, 2001:db8::53", "192.0.2.0/24, 2001:db8::/32");
        try (SecretBytes configuration = new WireGuardConfigFactory().renderClientConfiguration(
                state, peer, clientKeys.getPrivateKey().toBase64(), endpoint, options)) {
            byte[] bytes = configuration.copy();
            BitMatrix matrix = new QrCodeGenerator().encode(configuration);
            try {
                assertEquals(1024, matrix.getWidth());
                assertEquals(1024, matrix.getHeight());
                int[] rectangle = matrix.getEnclosingRectangle();
                int finderWidth = 0;
                while (matrix.get(rectangle[0] + finderWidth, rectangle[1])) finderWidth++;
                int module = finderWidth / 7;
                assertTrue(module > 0);
                assertTrue(rectangle[0] >= 4 * module);
                assertTrue(rectangle[1] >= 4 * module);
                assertTrue(1024 - rectangle[0] - rectangle[2] >= 4 * module);
                assertTrue(1024 - rectangle[1] - rectangle[3] >= 4 * module);
                int[] pixels = new int[1024 * 1024];
                for (int y = 0; y < 1024; y++) for (int x = 0; x < 1024; x++) {
                    pixels[y * 1024 + x] = matrix.get(x, y) ? 0xff000000 : 0xffffffff;
                }
                String decoded = decode(pixels);
                assertArrayEquals(bytes, decoded.getBytes(StandardCharsets.UTF_8));
                Config parsed = Config.parse(new ByteArrayInputStream(decoded.getBytes(StandardCharsets.UTF_8)));
                assertEquals(clientKeys.getPrivateKey(), parsed.getInterface().getKeyPair().getPrivateKey());
                assertEquals(mode == ClientExportOptions.AddressMode.DUAL_STACK ? 2 : 1,
                        parsed.getInterface().getAddresses().size());
                assertEquals(2, parsed.getInterface().getDnsServers().size());
                assertEquals(2, parsed.getPeers().get(0).getAllowedIps().size());
                String expectedEndpoint = (endpoint.contains(":") ? "[" + endpoint + "]" : endpoint) + ":34567";
                assertEquals(expectedEndpoint, parsed.getPeers().get(0).getEndpoint().get().toString());

                // Android 单元测试的编译 API 不含 java.desktop，运行的 JDK 包含它。
                // 仅测试通过反射使用桌面 ImageIO；不冒充 Android Bitmap/MediaStore 真机验收。
                Class<?> imageClass = Class.forName("java.awt.image.BufferedImage");
                Class<?> imageIo = Class.forName("javax.imageio.ImageIO");
                Object image = imageClass.getConstructor(int.class, int.class, int.class).newInstance(1024, 1024, 1);
                imageClass.getMethod("setRGB", int.class, int.class, int.class, int.class,
                        int[].class, int.class, int.class).invoke(image, 0, 0, 1024, 1024, pixels, 0, 1024);
                ByteArrayOutputStream png = new ByteArrayOutputStream();
                assertEquals(Boolean.TRUE, imageIo.getMethod("write", Class.forName("java.awt.image.RenderedImage"),
                        String.class, java.io.OutputStream.class).invoke(null, image, "PNG", png));
                Object restored = imageIo.getMethod("read", java.io.InputStream.class)
                        .invoke(null, new ByteArrayInputStream(png.toByteArray()));
                int[] restoredPixels = (int[]) imageClass.getMethod("getRGB", int.class, int.class,
                        int.class, int.class, int[].class, int.class, int.class)
                        .invoke(restored, 0, 0, 1024, 1024, null, 0, 1024);
                assertArrayEquals(bytes, decode(restoredPixels)
                        .getBytes(StandardCharsets.UTF_8));
                Arrays.fill(pixels, 0);
            } finally { Arrays.fill(bytes, (byte) 0); matrix.clear(); }
        }
    }

    private static String decode(int[] pixels) throws Exception {
        // 输入是生成的完整黑白原图，使用纯码模式验证内容；场景定位器会对部分随机
        // 数据误选内部图案。仍执行纠错、逐字节比较和官方解析，不替代相机扫码验收。
        return new QRCodeReader().decode(new BinaryBitmap(new HybridBinarizer(
                new RGBLuminanceSource(1024, 1024, pixels))),
                Map.of(DecodeHintType.PURE_BARCODE, true)).getText();
    }
}
