/* Copyright (c) 2026 TianzeL; SPDX-License-Identifier: Apache-2.0 */
package dev.lan.atticusvpn.server;

import android.net.Network;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.List;

/** Test APK only: loopback TCP framing for ADB, carrying unchanged encrypted WG datagrams. */
final class DeviceDatagramRelay implements AutoCloseable {
    private final ServerSocket listener;
    private final List<Socket> clients = new ArrayList<>();
    private volatile boolean closed;

    DeviceDatagramRelay(Network network, String endpoint) throws Exception {
        listener = new ServerSocket(39519, 1, InetAddress.getByName("127.0.0.1"));
        Thread accept = new Thread(() -> {
            while (!closed) {
                try {
                    Socket client = listener.accept();
                    synchronized (clients) { clients.add(client); }
                    Thread handler = new Thread(() -> serve(network, endpoint, client), "revocation-relay");
                    handler.setDaemon(true); handler.start();
                } catch (Exception ignored) { return; }
            }
        }, "revocation-relay-accept");
        accept.setDaemon(true); accept.start();
    }

    private void serve(Network network, String endpoint, Socket client) {
        try (Socket owned = client; DatagramSocket udp = new DatagramSocket()) {
            network.bindSocket(udp);
            udp.connect(InetAddress.getByName(endpoint), 49518);
            udp.setSoTimeout(500);
            DataOutputStream output = new DataOutputStream(owned.getOutputStream());
            Thread responses = new Thread(() -> {
                byte[] bytes = new byte[65535];
                while (!udp.isClosed() && !closed) {
                    try {
                        DatagramPacket packet = new DatagramPacket(bytes, bytes.length);
                        udp.receive(packet);
                        output.writeShort(packet.getLength());
                        output.write(bytes, 0, packet.getLength()); output.flush();
                    } catch (SocketTimeoutException timeout) { /* Keep waiting for datagrams. */ }
                    catch (Exception ignored) { return; }
                }
            }, "revocation-relay-responses");
            responses.setDaemon(true); responses.start();
            DataInputStream input = new DataInputStream(owned.getInputStream());
            while (!closed) {
                int length = input.readUnsignedShort();
                if (length == 0) break;
                byte[] bytes = new byte[length]; input.readFully(bytes);
                udp.send(new DatagramPacket(bytes, bytes.length));
            }
        } catch (Exception ignored) { /* Test transport never logs packet contents. */ }
        finally { synchronized (clients) { clients.remove(client); } }
    }

    @Override public void close() {
        closed = true;
        try { listener.close(); } catch (Exception ignored) { }
        synchronized (clients) {
            for (Socket client : clients) { try { client.close(); } catch (Exception ignored) { } }
        }
    }
}
