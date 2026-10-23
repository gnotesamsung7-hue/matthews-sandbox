package io.github.gnotesamsung7hue.matthewssandbox;

import android.content.Context;
import android.net.wifi.WifiManager;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * LanLink: lets two phones on the same Wi-Fi (or hotspot) talk directly.
 * Host opens a TCP server and announces itself with a UDP beacon; the guest
 * finds it (or types the code) and connects. Messages are one JSON line each.
 */
@CapacitorPlugin(name = "LanLink")
public class LanLinkPlugin extends Plugin {
    private static final int BEACON_PORT = 47801;

    private ServerSocket server;
    private final List<Peer> peers = new CopyOnWriteArrayList<>();
    private volatile Peer client;
    private volatile boolean beaconOn = false;
    private final ExecutorService writer = Executors.newSingleThreadExecutor();
    private WifiManager.MulticastLock mlock;
    private int nextId = 1;

    private static class Peer {
        final int id;
        final Socket s;
        final BufferedWriter out;

        Peer(int id, Socket s) throws IOException {
            this.id = id;
            this.s = s;
            s.setTcpNoDelay(true);
            this.out = new BufferedWriter(new OutputStreamWriter(s.getOutputStream(), "UTF-8"));
        }
    }

    @PluginMethod
    public void getIp(PluginCall call) {
        JSObject r = new JSObject();
        r.put("ip", localIp());
        call.resolve(r);
    }

    @PluginMethod
    public void host(PluginCall call) {
        final int port = call.getInt("port", 47800);
        final String name = call.getString("name", "Game");
        try {
            stopAll();
            final ServerSocket ss = new ServerSocket();
            ss.setReuseAddress(true);
            ss.bind(new InetSocketAddress(port));
            server = ss;
            new Thread(() -> {
                while (!ss.isClosed()) {
                    try {
                        Socket s = ss.accept();
                        Peer p = new Peer(nextId++, s);
                        peers.add(p);
                        JSObject e = new JSObject();
                        e.put("event", "join");
                        e.put("peer", p.id);
                        notifyListeners("peer", e);
                        readLoop(p, true);
                    } catch (IOException ex) {
                        break;
                    }
                }
            }).start();
            startBeacon(name, port);
            JSObject r = new JSObject();
            r.put("ip", localIp());
            r.put("port", port);
            call.resolve(r);
        } catch (Exception ex) {
            call.reject("host failed: " + ex.getMessage());
        }
    }

    private void readLoop(final Peer p, final boolean isHost) {
        new Thread(() -> {
            try {
                BufferedReader in = new BufferedReader(new InputStreamReader(p.s.getInputStream(), "UTF-8"));
                String line;
                while ((line = in.readLine()) != null) {
                    JSObject m = new JSObject();
                    m.put("data", line);
                    m.put("peer", p.id);
                    notifyListeners("message", m);
                }
            } catch (IOException ignored) {
            } finally {
                try { p.s.close(); } catch (IOException ignored) { }
                if (isHost) {
                    peers.remove(p);
                    JSObject e = new JSObject();
                    e.put("event", "leave");
                    e.put("peer", p.id);
                    notifyListeners("peer", e);
                } else if (client == p) {
                    client = null;
                    notifyListeners("closed", new JSObject());
                }
            }
        }).start();
    }

    @PluginMethod
    public void join(final PluginCall call) {
        final String ip = call.getString("ip");
        final int port = call.getInt("port", 47800);
        if (ip == null || ip.isEmpty()) {
            call.reject("no ip");
            return;
        }
        new Thread(() -> {
            try {
                Socket s = new Socket();
                s.connect(new InetSocketAddress(ip, port), 4000);
                Peer old = client;
                client = new Peer(0, s);
                if (old != null) { try { old.s.close(); } catch (IOException ignored) { } }
                readLoop(client, false);
                JSObject r = new JSObject();
                r.put("ok", true);
                call.resolve(r);
            } catch (Exception ex) {
                call.reject("join failed: " + ex.getMessage());
            }
        }).start();
    }

    @PluginMethod
    public void send(PluginCall call) {
        final String data = call.getString("data", "");
        final Integer to = call.getInt("peer");
        writer.execute(() -> {
            Peer c = client;
            if (c != null) write(c, data);
            for (Peer p : peers) {
                if (to == null || to == p.id) write(p, data);
            }
        });
        call.resolve();
    }

    private void write(Peer p, String d) {
        try {
            p.out.write(d);
            p.out.write('\n');
            p.out.flush();
        } catch (IOException ex) {
            try { p.s.close(); } catch (IOException ignored) { }
        }
    }

    @PluginMethod
    public void discover(final PluginCall call) {
        final int ms = call.getInt("ms", 2500);
        new Thread(() -> {
            JSArray found = new JSArray();
            Set<String> seen = new HashSet<>();
            acquireLock();
            DatagramSocket ds = null;
            try {
                ds = new DatagramSocket(null);
                ds.setReuseAddress(true);
                ds.setBroadcast(true);
                ds.bind(new InetSocketAddress(BEACON_PORT));
                ds.setSoTimeout(300);
                long end = System.currentTimeMillis() + ms;
                byte[] buf = new byte[512];
                while (System.currentTimeMillis() < end) {
                    try {
                        DatagramPacket pk = new DatagramPacket(buf, buf.length);
                        ds.receive(pk);
                        String msg = new String(pk.getData(), 0, pk.getLength(), "UTF-8");
                        if (!msg.startsWith("MSB|")) continue;
                        String[] parts = msg.split("\\|");
                        String ip = pk.getAddress().getHostAddress();
                        if (seen.add(ip)) {
                            JSObject g = new JSObject();
                            g.put("name", parts.length > 1 ? parts[1] : "Game");
                            g.put("ip", ip);
                            int port = 47800;
                            try { if (parts.length > 2) port = Integer.parseInt(parts[2].trim()); } catch (NumberFormatException ignored) { }
                            g.put("port", port);
                            found.put(g);
                        }
                    } catch (SocketTimeoutException ignored) {
                    }
                }
            } catch (Exception ignored) {
            } finally {
                if (ds != null) ds.close();
                releaseLock();
            }
            JSObject r = new JSObject();
            r.put("games", found);
            call.resolve(r);
        }).start();
    }

    private void startBeacon(final String name, final int port) {
        beaconOn = true;
        new Thread(() -> {
            DatagramSocket bs = null;
            try {
                bs = new DatagramSocket();
                bs.setBroadcast(true);
                byte[] msg = ("MSB|" + name.replace("|", " ") + "|" + port).getBytes("UTF-8");
                while (beaconOn) {
                    for (InetAddress b : broadcasts()) {
                        try { bs.send(new DatagramPacket(msg, msg.length, b, BEACON_PORT)); } catch (IOException ignored) { }
                    }
                    Thread.sleep(1000);
                }
            } catch (Exception ignored) {
            } finally {
                if (bs != null) bs.close();
            }
        }).start();
    }

    private List<InetAddress> broadcasts() {
        List<InetAddress> list = new ArrayList<>();
        try {
            list.add(InetAddress.getByName("255.255.255.255"));
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InterfaceAddress ia : ni.getInterfaceAddresses()) {
                    if (ia.getBroadcast() != null) list.add(ia.getBroadcast());
                }
            }
        } catch (Exception ignored) { }
        return list;
    }

    private String localIp() {
        String best = "";
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address && a.isSiteLocalAddress()) {
                        String n = ni.getName();
                        String ip = a.getHostAddress();
                        if (n.startsWith("wlan") || n.startsWith("ap") || n.startsWith("swlan")) return ip;
                        if (best.isEmpty()) best = ip;
                    }
                }
            }
        } catch (Exception ignored) { }
        return best;
    }

    private void acquireLock() {
        try {
            WifiManager wm = (WifiManager) getContext().getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm != null) {
                mlock = wm.createMulticastLock("msb");
                mlock.setReferenceCounted(false);
                mlock.acquire();
            }
        } catch (Exception ignored) { }
    }

    private void releaseLock() {
        try { if (mlock != null) mlock.release(); } catch (Exception ignored) { }
    }

    @PluginMethod
    public void stop(PluginCall call) {
        stopAll();
        call.resolve();
    }

    private void stopAll() {
        beaconOn = false;
        try { if (server != null) server.close(); } catch (IOException ignored) { }
        server = null;
        for (Peer p : peers) { try { p.s.close(); } catch (IOException ignored) { } }
        peers.clear();
        Peer c = client;
        client = null;
        if (c != null) { try { c.s.close(); } catch (IOException ignored) { } }
    }

    @Override
    protected void handleOnDestroy() {
        stopAll();
    }
}
