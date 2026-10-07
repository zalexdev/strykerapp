package com.zalexdev.stryker.localnetwork.nonroot;

import android.content.Context;

import com.zalexdev.stryker.custom.Device;
import com.zalexdev.stryker.custom.Port;
import com.zalexdev.stryker.logger.Logger;
import com.zalexdev.stryker.utils.Core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public final class NonRootScanner {

    public interface Callback {
        void onStatus(String message);

        void onProgress(int percent);

        void onDeviceAdded(Device device, int index);

        void onDeviceChanged(Device device, int index);

        void onFinished(ArrayList<Device> devices);
    }

    private static final long DISCOVERY_BUDGET_MS = 40000L;
    private static final long ENRICH_BUDGET_MS = 40000L;
    private static final long SWEEP_JOIN_MS = 8000L;
    private static final long DRAIN_MS = 2000L;
    private static final long CAST_CAP_MS = 12000L;
    private static final long TTL_CAP_MS = 12000L;
    private static final long DEEP_CAP_MS = 30000L;
    private static final long BANNER_CAP_MS = 15000L;
    private static final long GUEST_CAP_MS = 12000L;
    private static final long NAMES_CAP_MS = 6000L;
    private static final long CAST_RESERVE_MS = 30000L;
    private static final long TTL_RESERVE_MS = 22000L;
    private static final long DEEP_RESERVE_MS = 12000L;
    private static final long BANNER_RESERVE_MS = 6000L;
    private static final long GUEST_RESERVE_MS = 3000L;
    private static final int TTL_THREADS = 6;
    private static final int BANNER_THREADS = 8;
    private static final int BANNER_PORTS = 4;
    private static final int CAST_THREADS = 6;
    private static final int MIN_DEEP_INFLIGHT = 32;
    private static final double TTL_RANK_LIMIT = 0.3;
    private static final int TERMINAL_PERCENT = 110;

    private final Context context;
    private final Core core;
    private final Callback callback;
    private final Logger logger;
    private final ScanLocks locks = new ScanLocks();
    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private final AtomicBoolean active = new AtomicBoolean(false);
    private final AtomicBoolean started = new AtomicBoolean(false);
    private final AtomicBoolean finished = new AtomicBoolean(false);

    private final LinkedHashMap<String, Node> nodes = new LinkedHashMap<>();
    private final ArrayList<String> order = new ArrayList<>();
    private final ArrayList<Device> devices = new ArrayList<>();
    private final Map<String, String> signatures = new ConcurrentHashMap<>();
    private final Map<String, String> arp = new ConcurrentHashMap<>();
    private final Set<String> tcpAlive =
            Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());
    private final Set<String> probed =
            Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());
    private final Map<String, Set<Integer>> tcpPorts = new ConcurrentHashMap<>();
    private final Object emitLock = new Object();
    private final Object progressLock = new Object();

    private NetworkContext net;
    private IcmpFinder icmp;
    private NetbiosResolver netbios;
    private MdnsResolver mdns;
    private SsdpResolver ssdp;
    private SnmpResolver snmp;
    private RdnsResolver rdns;

    private volatile int nbDone;
    private volatile int nbTotal;
    private volatile int tcpDone;
    private volatile int tcpTotal;
    private volatile int lastPercent = -1;
    private volatile long enrichDeadline;
    private volatile Thread worker;

    public NonRootScanner(Context context, Core core, Callback callback) {
        this.context = context == null ? null : context.getApplicationContext();
        this.core = core;
        this.callback = callback;
        this.logger = core != null ? core.getLogger() : new Logger();
    }

    public void start() {
        if (!started.compareAndSet(false, true)) return;
        Thread t = new Thread(this::run, "stryker-lan-scan");
        t.setDaemon(true);
        worker = t;
        t.start();
    }

    public void cancel() {
        cancelled.set(true);
        stopResolvers();
        Thread t = worker;
        if (t != null) t.interrupt();
    }

    public boolean isRunning() {
        return active.get();
    }

    private void run() {
        if (!active.compareAndSet(false, true)) return;
        try {
            locks.acquire(context);
            String override = core == null ? null : core.getString("local_scan_target");
            net = NetworkContext.capture(context, override);
            if (net == null || !net.valid()) {
                status("No usable IPv4 network");
                return;
            }
            status("Scanning " + net.range.cidr());
            log("Non-root discovery: " + net.describe());
            if (override != null && !override.trim().isEmpty() && !net.inRange(net.localIp)) {
                log("Scan target " + net.range.cidr() + " does not contain this device ("
                        + net.localIp + "), so only hosts the router routes to can answer. "
                        + "Clear the manual target to scan the subnet you are on.");
            }
            refreshArp();
            if (!arp.isEmpty()) log("Neighbour table readable: " + arp.size() + " entries");
            else if (!Neighbours.isAvailable()) log("Neighbour lookup unavailable, IP-only mode");
            else log("Neighbour table empty for now, will re-read after the sweep");

            startResolvers();
            seedGateway();
            discovery();
            refreshArp();
            if (!cancelled.get()) enrichmentPass();
            persist();
        } catch (Throwable t) {
            log("Scan failed: " + t);
            try {
                persist();
            } catch (Throwable ignored) {
            }
        } finally {
            stopResolvers();
            locks.release();
            finish();
            active.set(false);
        }
    }

    private void startResolvers() {
        icmp = new IcmpFinder(net, logger);
        netbios = new NetbiosResolver(net, logger);
        mdns = new MdnsResolver(net, logger);
        snmp = new SnmpResolver(net, logger);
        ssdp = new SsdpResolver(net, logger);
        rdns = new RdnsResolver(net, logger);
        icmp.start();
        netbios.start();
        mdns.start();
        snmp.start();
        ssdp.start();
        rdns.start();
        if (cancelled.get()) {
            stopResolvers();
            return;
        }
        if (net.gateway != null && !net.gateway.isEmpty()) {
            netbios.query(net.gateway);
            snmp.query(net.gateway);
            rdns.enqueue(net.gateway);
        }
        if (net.localIp != null && !net.localIp.isEmpty()) rdns.enqueue(net.localIp);
    }

    private void stopResolvers() {
        stopQuietly(icmp);
        stopQuietly(netbios);
        stopQuietly(mdns);
        stopQuietly(ssdp);
        stopQuietly(snmp);
        stopQuietly(rdns);
    }

    private void stopQuietly(DiscoveryResolver resolver) {
        if (resolver == null) return;
        try {
            resolver.stop();
        } catch (Throwable ignored) {
        }
    }

    private void seedGateway() {
        if (net.gateway == null || net.gateway.isEmpty() || !net.inRange(net.gateway)) return;
        Node gw = node(net.gateway);
        gw.up = true;
        gw.gateway = true;
        gw.addSource(Node.SRC_GATEWAY);
        enrich(gw);
        emit(gw);
    }

    private void discovery() {
        final ArrayList<String> hosts = net.range.hosts();
        if (hosts.isEmpty()) return;
        nbTotal = hosts.size() * ScanConfig.ROUNDS;
        tcpTotal = hosts.size() * ScanConfig.LIVENESS_PORTS.length;
        final long deadline = System.currentTimeMillis() + DISCOVERY_BUDGET_MS;

        Thread tcp = new Thread(() -> TcpConnectScanner.scan(hosts, ScanConfig.LIVENESS_PORTS,
                ScanConfig.TCP_PROBE_INFLIGHT, ScanConfig.TCP_PROBE_TIMEOUT_MS,
                new TcpConnectScanner.Sink() {
                    @Override
                    public void onOpen(String ip, int port) {
                        if (ip == null || !net.inRange(ip)) return;
                        tcpAlive.add(ip);
                        Set<Integer> set = tcpPorts.get(ip);
                        if (set == null) {
                            set = Collections.newSetFromMap(new ConcurrentHashMap<Integer, Boolean>());
                            Set<Integer> prev = tcpPorts.putIfAbsent(ip, set);
                            if (prev != null) set = prev;
                        }
                        set.add(port);
                        RdnsResolver names = rdns;
                        if (names != null) names.enqueue(ip);
                        SnmpResolver agents = snmp;
                        if (agents != null) agents.query(ip);
                    }

                    @Override
                    public void onProgress(int done, int total) {
                        tcpDone = done;
                        publishProgress();
                    }
                }, cancelled), "stryker-lan-tcp");
        tcp.setDaemon(true);
        tcp.start();

        Thread sweep = null;
        for (int round = 0; round < ScanConfig.ROUNDS && !cancelled.get(); round++) {
            if (System.currentTimeMillis() >= deadline) break;
            log("Discovery round " + (round + 1) + "/" + ScanConfig.ROUNDS);
            if (sweep == null || !sweep.isAlive()) {
                sweep = new Thread(() -> icmp.sweep(), "stryker-lan-icmp-" + round);
                sweep.setDaemon(true);
                sweep.start();
            }
            for (int i = 0; i < hosts.size() && !cancelled.get(); i++) {
                netbios.query(hosts.get(i));
                nbDone++;
                pause(ScanConfig.NETBIOS_PACE_MS);
                if ((i + 1) % ScanConfig.NETBIOS_BLOCK == 0) pause(ScanConfig.NETBIOS_BLOCK_PAUSE_MS);
                if ((i + 1) % ScanConfig.MERGE_EVERY == 0) {
                    merge();
                    publishProgress();
                    if (System.currentTimeMillis() >= deadline) break;
                }
            }
            joinQuietly(sweep, SWEEP_JOIN_MS);
            merge();
            publishProgress();
        }
        if (sweep != null && sweep.isAlive()) {
            stopQuietly(icmp);
            joinQuietly(sweep, DRAIN_MS);
        }
        long left = deadline - System.currentTimeMillis();
        joinQuietly(tcp, left < DRAIN_MS ? DRAIN_MS : left);
        if (tcp.isAlive()) {
            tcp.interrupt();
            joinQuietly(tcp, DRAIN_MS);
        }
        merge();
    }

    private void enrichmentPass() {
        enrichDeadline = System.currentTimeMillis() + ENRICH_BUDGET_MS;
        report(92);
        refreshArp();
        probeCast();
        report(94);
        probeTtl();
        report(96);
        deepScan();
        report(98);
        grabBanners();
        guestAssist();
        waitForNames(stageBudget(NAMES_CAP_MS, 0L));
        merge();
        report(100);
    }

    private void refreshArp() {
        try {
            int before = arp.size();
            Map<String, String> table = HostProbes.arpTable();
            if (!table.isEmpty()) arp.putAll(table);
            int gained = arp.size() - before;
            if (gained > 0) log("Neighbour table: +" + gained + " MAC (" + arp.size() + " total)");
        } catch (Throwable ignored) {
        }
    }

    private long stageBudget(long cap, long reserve) {
        long left = enrichDeadline - System.currentTimeMillis() - reserve;
        if (left <= 0L) return 0L;
        return Math.min(cap, left);
    }

    private void waitForNames(long budgetMs) {
        if (rdns == null || budgetMs <= 0L) return;
        long deadline = System.currentTimeMillis() + budgetMs;
        while (!cancelled.get() && System.currentTimeMillis() < deadline) {
            if (rdns.queued() <= 0) return;
            pause(250);
        }
    }

    private void probeCast() {
        if (mdns == null || cancelled.get()) return;
        if (stageBudget(CAST_CAP_MS, CAST_RESERVE_MS) <= 0L) return;
        try {
            Set<String> candidates = new LinkedHashSet<>();
            for (String ip : mdns.hostsWithService("_googlecast")) {
                if (net.inRange(ip)) candidates.add(ip);
            }
            for (Node n : snapshot()) {
                if (n.hasPort(8008) || n.hasPort(8009)) candidates.add(n.ip);
            }
            if (candidates.isEmpty()) return;
            Map<String, Node.Cast> found = CastProbe.probeAll(candidates, CAST_THREADS);
            for (Map.Entry<String, Node.Cast> e : found.entrySet()) {
                if (!net.inRange(e.getKey())) continue;
                Node n = node(e.getKey());
                n.cast = e.getValue();
                n.up = true;
                n.addSource(Node.SRC_CAST);
                n.applyMac(e.getValue().mac, Node.SRC_CAST);
            }
        } catch (Exception ignored) {
        }
    }

    private void probeTtl() {
        if (cancelled.get()) return;
        long budget = stageBudget(TTL_CAP_MS, TTL_RESERVE_MS);
        if (budget <= 0L) return;
        List<Node> targets = new ArrayList<>();
        for (Node n : snapshot()) {
            if (n.ttl <= 0 && n.rank < TTL_RANK_LIMIT) targets.add(n);
        }
        if (targets.isEmpty()) return;
        ExecutorService pool = newPool(TTL_THREADS, "stryker-ttl");
        final CountDownLatch latch = new CountDownLatch(targets.size());
        for (final Node n : targets) {
            pool.submit(() -> {
                try {
                    if (cancelled.get() || Thread.currentThread().isInterrupted()) return;
                    int ttl = HostProbes.ttl(n.ip);
                    if (ttl > 0) {
                        n.ttl = ttl;
                        n.hops = HostProbes.hopsFromTtl(ttl);
                        n.up = true;
                        n.addSource(Node.SRC_ICMP);
                    }
                } catch (Throwable ignored) {
                } finally {
                    latch.countDown();
                }
            });
        }
        awaitPool(pool, latch, budget);
    }

    private void deepScan() {
        if (core == null || cancelled.get() || !core.getBoolean("autoScan")) return;
        long budget = stageBudget(DEEP_CAP_MS, DEEP_RESERVE_MS);
        if (budget <= 0L) return;
        final List<Node> targets = snapshot();
        if (targets.isEmpty()) return;
        int threads = core.getInt("max_par", 3);
        if (threads < 1) threads = 3;
        if (threads > 8) threads = 8;
        int share = ScanConfig.TCP_SCAN_INFLIGHT / threads;
        final int inflight = share < MIN_DEEP_INFLIGHT ? MIN_DEEP_INFLIGHT : share;
        ExecutorService pool = newPool(threads, "stryker-deep");
        final CountDownLatch latch = new CountDownLatch(targets.size());
        for (final Node n : targets) {
            pool.submit(() -> {
                try {
                    if (cancelled.get()) return;
                    TcpConnectScanner.scan(Collections.singletonList(n.ip), ScanConfig.SCAN_PORTS,
                            inflight, ScanConfig.TCP_SCAN_TIMEOUT_MS,
                            new TcpConnectScanner.Sink() {
                                @Override
                                public void onOpen(String ip, int port) {
                                    if (ip == null || !ip.equals(n.ip)) return;
                                    n.addPort(port, HostProbes.serviceName(port), "");
                                    n.addSource(Node.SRC_TCP);
                                }

                                @Override
                                public void onProgress(int done, int total) {
                                }
                            }, cancelled);
                } catch (Throwable ignored) {
                } finally {
                    latch.countDown();
                }
            });
        }
        awaitPool(pool, latch, budget);
    }

    private void grabBanners() {
        if (core != null && !core.isBannerScanEnabled()) return;
        if (cancelled.get()) return;
        long budget = stageBudget(BANNER_CAP_MS, BANNER_RESERVE_MS);
        if (budget <= 0L) return;
        final List<Node> targets = snapshot();
        if (targets.isEmpty()) return;
        ExecutorService pool = newPool(BANNER_THREADS, "stryker-banner");
        final CountDownLatch latch = new CountDownLatch(targets.size());
        for (final Node n : targets) {
            pool.submit(() -> {
                try {
                    int taken = 0;
                    List<Node.OpenPort> copy;
                    synchronized (n.ports) {
                        copy = new ArrayList<>(n.ports);
                    }
                    for (Node.OpenPort p : copy) {
                        if (cancelled.get() || Thread.currentThread().isInterrupted()) break;
                        if (taken >= BANNER_PORTS) break;
                        if (!p.banner.isEmpty()) continue;
                        String banner = TcpConnectScanner.grabBanner(n.ip, p.number, 1200,
                                ScanConfig.BANNER_READ_MS, ScanConfig.BANNER_BYTES);
                        if (banner != null && !banner.isEmpty()) p.banner = banner;
                        taken++;
                    }
                } catch (Throwable ignored) {
                } finally {
                    latch.countDown();
                }
            });
        }
        awaitPool(pool, latch, budget);
    }

    private void guestAssist() {
        if (core == null || cancelled.get()) return;
        long budget = stageBudget(GUEST_CAP_MS, GUEST_RESERVE_MS);
        if (budget <= 0L || !GuestAssist.available(core)) return;
        long deadline = System.currentTimeMillis() + budget;
        log("Guest tooling available, enriching hosts with nmap -sV");
        for (Node n : snapshot()) {
            if (cancelled.get() || System.currentTimeMillis() >= deadline) break;
            if (n.ports.isEmpty()) continue;
            try {
                GuestAssist.enrich(core, n);
            } catch (Throwable ignored) {
            }
        }
    }

    private static ExecutorService newPool(int size, final String name) {
        int threads = size < 1 ? 1 : size;
        return Executors.newFixedThreadPool(threads, new ThreadFactory() {
            private final AtomicInteger seq = new AtomicInteger(1);

            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, name + "-" + seq.getAndIncrement());
                t.setDaemon(true);
                return t;
            }
        });
    }

    private void awaitPool(ExecutorService pool, CountDownLatch latch, long ms) {
        try {
            latch.await(ms, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            try {
                pool.shutdownNow();
            } catch (Throwable ignored) {
            }
            try {
                pool.awaitTermination(DRAIN_MS, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Throwable ignored) {
            }
        }
    }

    private void merge() {
        Set<String> present = new LinkedHashSet<>();
        addAll(present, icmp);
        addAll(present, netbios);
        addAll(present, mdns);
        addAll(present, ssdp);
        addAll(present, snmp);
        present.addAll(tcpAlive);
        for (String ip : arp.keySet()) {
            if (net.inRange(ip)) present.add(ip);
        }
        synchronized (nodes) {
            present.addAll(nodes.keySet());
        }
        List<String> sorted = new ArrayList<>(present);
        Collections.sort(sorted, (a, b) -> IpRange.compare(a, b));
        for (String ip : sorted) {
            if (!net.inRange(ip)) continue;
            Node n = node(ip);
            n.up = true;
            enrich(n);
            if (mdns != null && n.name.isEmpty() && n.hostname.isEmpty()) mdns.queryReverse(ip);
            if (rdns != null) rdns.enqueue(ip);
            if (snmp != null && n.snmp == null && probed.add(ip)) snmp.query(ip);
            emit(n);
        }
    }

    private void addAll(Set<String> sink, DiscoveryResolver resolver) {
        if (resolver == null) return;
        try {
            sink.addAll(resolver.answered());
        } catch (Exception ignored) {
        }
    }

    private void enrich(Node n) {
        try {
            if (net.isLocal(n.ip)) {
                n.self = true;
                n.addSource(Node.SRC_SELF);
                n.applyMac(net.localMac, Node.SRC_SELF);
            }
            if (net.gateway != null && net.gateway.equals(n.ip)) {
                n.gateway = true;
                n.addSource(Node.SRC_GATEWAY);
            }
            String arpMac = arp.get(n.ip);
            if (arpMac != null) {
                n.applyMac(arpMac, Node.SRC_ARP);
                n.addSource(Node.SRC_ARP);
            }
            if (icmp != null && icmp.answered().contains(n.ip)) n.addSource(Node.SRC_ICMP);
            if (tcpAlive.contains(n.ip)) n.addSource(Node.SRC_TCP);

            Set<Integer> open = tcpPorts.get(n.ip);
            if (open != null) {
                for (Integer p : new ArrayList<>(open)) {
                    n.addPort(p, HostProbes.serviceName(p), "");
                }
            }
            if (netbios != null) {
                Node.Netbios nb = netbios.results().get(n.ip);
                if (nb != null) {
                    n.netbios = nb;
                    n.addSource(Node.SRC_NETBIOS);
                    n.applyMac(nb.mac, Node.SRC_NETBIOS);
                    if (n.name.isEmpty() && nb.name != null) n.name = nb.name;
                }
            }
            if (mdns != null) {
                Node.Bonjour b = mdns.results().get(n.ip);
                if (b != null) {
                    n.bonjour = b;
                    n.addSource(Node.SRC_MDNS);
                }
            }
            if (ssdp != null) {
                Node.Upnp u = ssdp.results().get(n.ip);
                if (u != null) {
                    n.upnp = u;
                    n.addSource(Node.SRC_SSDP);
                }
            }
            if (snmp != null) {
                Node.Snmp s = snmp.results().get(n.ip);
                if (s != null) {
                    n.snmp = s;
                    n.addSource(Node.SRC_SNMP);
                }
            }
            if (rdns != null) {
                String host = rdns.results().get(n.ip);
                if (host != null && !host.isEmpty()) {
                    n.hostname = host;
                    n.addSource(Node.SRC_RDNS);
                }
            }
            if (n.gateway && !n.hasMac()) n.applyMac(net.bssid, Node.SRC_BSSID);
            DeviceClassifier.classify(core, net, n);
        } catch (Exception ignored) {
        }
    }

    private void emit(Node n) {
        Device device = toDevice(n);
        String signature = signatureOf(device);
        synchronized (emitLock) {
            int index;
            boolean added;
            synchronized (devices) {
                index = order.indexOf(n.ip);
                if (index < 0) {
                    order.add(n.ip);
                    devices.add(device);
                    index = devices.size() - 1;
                    added = true;
                } else {
                    if (index >= devices.size()) return;
                    if (signature.equals(signatures.get(n.ip))) return;
                    devices.set(index, device);
                    added = false;
                }
            }
            signatures.put(n.ip, signature);
            if (added) {
                log("Found " + n.ip + " " + DeviceClassifier.summary(n)
                        + (n.hasMac() ? " " + n.mac : "") + " via " + n.sources);
            }
            Callback cb = callback;
            if (cb == null || !live()) return;
            try {
                if (added) cb.onDeviceAdded(device, index);
                else cb.onDeviceChanged(device, index);
            } catch (Throwable ignored) {
            }
        }
    }

    private Device toDevice(Node n) {
        Device d = new Device();
        d.setIp(n.ip);
        d.setMac(n.hasMac() ? n.mac : "");
        d.setVendor(n.vendor == null ? "" : n.vendor);
        String label = n.name == null ? "" : n.name;
        if (label.isEmpty() && n.hostname != null) label = shortHost(n.hostname);
        d.setSubname(label);
        d.setOs(DeviceClassifier.summary(n));
        d.setImage(DeviceClassifier.icon(n));
        ArrayList<Port> ports = new ArrayList<>();
        List<Node.OpenPort> copy;
        synchronized (n.ports) {
            copy = new ArrayList<>(n.ports);
        }
        Collections.sort(copy, (a, b) -> Integer.compare(a.number, b.number));
        for (Node.OpenPort p : copy) {
            ports.add(new Port(String.valueOf(p.number), p.service, p.banner));
        }
        d.setPorts(ports);
        d.setNmapoutput(evidence(n));
        d.setShim(false);
        return d;
    }

    private ArrayList<String> evidence(Node n) {
        ArrayList<String> out = new ArrayList<>();
        out.add("Host " + n.ip + " is up");
        out.add("Detected by: " + n.sources);
        if (n.hasMac()) out.add("MAC Address: " + n.mac + " (" + n.macSource + ")");
        if (n.vendor != null && !n.vendor.isEmpty()) out.add("Vendor: " + n.vendor);
        if (n.hostname != null && !n.hostname.isEmpty()) out.add("Reverse DNS: " + n.hostname);
        if (n.name != null && !n.name.isEmpty()) out.add("Name: " + n.name);
        if (n.model != null && !n.model.isEmpty()) out.add("Model: " + n.model);
        if (n.type != null && !n.type.isEmpty()) out.add("Type: " + n.type);
        if (n.os != null && !n.os.isEmpty()) out.add("Running: " + n.os);
        if (n.ttl > 0) out.add("TTL: " + n.ttl + " (about " + n.hops + " hops)");
        Node.Netbios nb = n.netbios;
        if (nb != null) {
            out.add("NetBIOS: " + nb.name
                    + (nb.workgroup.isEmpty() ? "" : " workgroup=" + nb.workgroup)
                    + (nb.user.isEmpty() ? "" : " user=" + nb.user)
                    + (nb.fileServer ? " file-server" : "")
                    + (nb.domainController ? " domain-controller" : ""));
        }
        Node.Bonjour bonjour = n.bonjour;
        if (bonjour != null) {
            if (!bonjour.name.isEmpty()) out.add("Bonjour: " + bonjour.name);
            if (!bonjour.model.isEmpty()) out.add("Bonjour model: " + bonjour.model);
            synchronized (bonjour.services) {
                for (String s : bonjour.services) out.add("Service: " + s);
            }
        }
        Node.Upnp upnp = n.upnp;
        if (upnp != null) {
            out.add("UPnP: " + upnp.friendlyName + " " + upnp.modelName + " " + upnp.manufacturer);
            if (!upnp.deviceType.isEmpty()) out.add("UPnP type: " + upnp.deviceType);
            if (!upnp.serialNumber.isEmpty()) out.add("UPnP serial: " + upnp.serialNumber);
            if (!upnp.location.isEmpty()) out.add("UPnP location: " + upnp.location);
        }
        Node.Snmp agent = n.snmp;
        if (agent != null) {
            if (!agent.sysDescr.isEmpty()) out.add("SNMP sysDescr: " + agent.sysDescr);
            if (!agent.sysName.isEmpty()) out.add("SNMP sysName: " + agent.sysName);
            if (!agent.sysLocation.isEmpty()) out.add("SNMP sysLocation: " + agent.sysLocation);
        }
        Node.Cast cast = n.cast;
        if (cast != null) {
            out.add("Cast: " + cast.name + " " + cast.model + " " + cast.build);
        }
        List<Node.OpenPort> copy;
        synchronized (n.ports) {
            copy = new ArrayList<>(n.ports);
        }
        Collections.sort(copy, (a, b) -> Integer.compare(a.number, b.number));
        for (Node.OpenPort p : copy) {
            out.add(p.number + "/tcp open " + p.service + (p.banner.isEmpty() ? "" : "  " + p.banner));
        }
        return out;
    }

    private String signatureOf(Device d) {
        return d.getIp() + "|" + d.getMac() + "|" + d.getVendor() + "|" + d.getOs() + "|"
                + d.getSubname() + "|" + d.portsArrayToString() + "|" + d.getImage();
    }

    private List<Node> snapshot() {
        synchronized (nodes) {
            return new ArrayList<>(nodes.values());
        }
    }

    private Node node(String ip) {
        synchronized (nodes) {
            Node n = nodes.get(ip);
            if (n == null) {
                n = new Node(ip);
                nodes.put(ip, n);
            }
            return n;
        }
    }

    private void persist() {
        if (core == null) return;
        try {
            ArrayList<Device> copy;
            synchronized (devices) {
                copy = new ArrayList<>(devices);
            }
            if (copy.isEmpty()) return;
            core.saveLastNetworkScan(copy);
        } catch (Exception ignored) {
        }
    }

    private void finish() {
        if (!finished.compareAndSet(false, true)) return;
        ArrayList<Device> copy;
        synchronized (devices) {
            copy = new ArrayList<>(devices);
        }
        synchronized (progressLock) {
            lastPercent = TERMINAL_PERCENT;
        }
        Callback cb = callback;
        if (cb != null) {
            try {
                cb.onProgress(TERMINAL_PERCENT);
            } catch (Throwable ignored) {
            }
            try {
                cb.onFinished(copy);
            } catch (Throwable ignored) {
            }
        }
        log("Scan finished, " + copy.size() + " hosts");
    }

    private void report(int percent) {
        pushProgress(percent);
    }

    private void publishProgress() {
        int a = nbTotal <= 0 ? 0 : (int) (45L * nbDone / nbTotal);
        int b = tcpTotal <= 0 ? 0 : (int) (45L * tcpDone / tcpTotal);
        int pct = a + b;
        if (pct > 90) pct = 90;
        pushProgress(pct);
    }

    private void pushProgress(int percent) {
        Callback cb = callback;
        synchronized (progressLock) {
            if (percent <= lastPercent) return;
            lastPercent = percent;
            if (cb == null || !live()) return;
            try {
                cb.onProgress(percent);
            } catch (Throwable ignored) {
            }
        }
    }

    private boolean live() {
        return !cancelled.get() && !finished.get();
    }

    private void status(String message) {
        log(message);
        Callback cb = callback;
        if (cb == null || !live()) return;
        try {
            cb.onStatus(message);
        } catch (Throwable ignored) {
        }
    }

    private void log(String message) {
        if (logger != null) logger.writeLine(message, 2);
    }

    private static String shortHost(String host) {
        if (host == null) return "";
        int dot = host.indexOf('.');
        return dot > 0 ? host.substring(0, dot) : host;
    }

    private void pause(long ms) {
        if (ms <= 0) return;
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void joinQuietly(Thread thread, long ms) {
        if (thread == null) return;
        try {
            thread.join(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
