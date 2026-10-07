package com.zalexdev.stryker.appintro.slides;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.graphics.PorterDuff;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager2.widget.ViewPager2;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.zalexdev.stryker.BuildConfig;
import com.zalexdev.stryker.R;
import com.zalexdev.stryker.appintro.install.InstallLogDialog;
import com.zalexdev.stryker.appintro.install.LogAdapter;
import com.zalexdev.stryker.appintro.install.LogLevel;
import com.zalexdev.stryker.appintro.install.LogLine;
import com.zalexdev.stryker.appintro.AppIntroActivity;
import com.zalexdev.stryker.appintro.IntroPage;
import com.zalexdev.stryker.engine.DeviceCapabilities;
import com.zalexdev.stryker.engine.EngineType;
import com.zalexdev.stryker.engine.Engines;
import com.zalexdev.stryker.engine.GuestEngine;
import com.zalexdev.stryker.engine.QemuInstaller;
import com.zalexdev.stryker.engine.RootlessEngine;
import com.zalexdev.stryker.engine.VmSpecs;
import com.zalexdev.stryker.utils.Core;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

public class SlideQemuInstall extends Fragment implements IntroPage {

    private Activity activity;
    private Context context;
    private Core core;
    private ViewPager2 mPager;

    private TextView statusTitle;
    private TextView statusSubtitle;
    private ImageView statusIcon;
    private ProgressBar statusSpinner;

    private LinearProgressIndicator progress;
    private TextView downloadText;

    private LogAdapter logAdapter;
    private MaterialButton detailsToggle;
    private com.airbnb.lottie.LottieAnimationView working;
    private InstallLogDialog logDialog;

    private boolean started = false;
    private boolean failed = false;

    private boolean offline = false;

    private boolean replacePayload = false;
    private boolean wiped = false;

    @Nullable
    @Override
    public View onCreateView(LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.new_slide_qemu, container, false);
        activity = getActivity();
        context = getContext();
        core = new Core(context);
        mPager = activity.findViewById(R.id.view_pager);
        replacePayload = activity instanceof AppIntroActivity
                && ((AppIntroActivity) activity).replacesPayload();

        statusTitle = view.findViewById(R.id.status_title);
        statusSubtitle = view.findViewById(R.id.status_subtitle);
        statusIcon = view.findViewById(R.id.status_icon);
        statusSpinner = view.findViewById(R.id.status_spinner);

        progress = view.findViewById(R.id.slide_install_progress);
        downloadText = view.findViewById(R.id.download_text);

        logAdapter = new LogAdapter(context);
        working = view.findViewById(R.id.install_working);
        com.zalexdev.stryker.appintro.IntroLayout.centerOn(
                working, view.findViewById(R.id.install_eyebrow));
        detailsToggle = view.findViewById(R.id.install_details_toggle);
        detailsToggle.setOnClickListener(v -> showLog());
        return view;
    }

    @Override
    public void onResume() {
        super.onResume();
        if (!started) startInstall();
    }

    @Override
    public void onDestroyView() {
        if (logDialog != null) logDialog.dismiss();
        logDialog = null;
        super.onDestroyView();
    }


    private static final long BOOT_DEADLINE_MS = 200_000;
    private static final long BOOT_HARD_DEADLINE_MS = 600_000;
    private static final long BOOT_STALL_MS = 60_000;
    private static final long BOOT_POLL_MS = 500;

    private final java.util.concurrent.atomic.AtomicLong lastBootLine =
            new java.util.concurrent.atomic.AtomicLong();

    private static final long READY_CONFIRM_MS = 20_000;

    private static final long STOP_GRACE_MS = 15_000;

    private void startInstall() {
        started = true;
        failed = false;
        offline = false;
        refreshChrome();
        log(LogLevel.INFO, "Stryker " + BuildConfig.VERSION_NAME + " · build " + BuildConfig.VERSION_CODE);

        runOnUi(() -> {
            progress.setVisibility(View.VISIBLE);
            progress.setIndeterminate(true);
        });

        new Thread(this::runPlan, "engine-install").start();
    }

    private void showLog() {
        if (logDialog != null && logDialog.isShowing()) return;
        logDialog = InstallLogDialog.show(context, logAdapter);
    }

    private void closeLog() {
        if (logDialog != null) logDialog.dismiss();
        logDialog = null;
    }

    private void refreshChrome() {
        if (getActivity() instanceof AppIntroActivity) {
            ((AppIntroActivity) getActivity()).refreshPrimary();
        }
    }

    private void runPlan() {
        dropStalePayload();
        List<EngineType> plan = installPlan();
        if (plan.isEmpty()) {
            allFailed();
            return;
        }
        log(LogLevel.INFO, "Plan: " + planNames(plan)
                + " · an engine counts as working only once the guest answers over SSH");
        for (int i = 0; i < plan.size(); i++) {
            EngineType candidate = plan.get(i);
            if (i > 0) announceFallback(plan.get(i - 1), candidate);
            Attempt result = attempt(candidate);
            if (result == Attempt.BOOTED) {
                succeed(candidate);
                return;
            }
            if (result == Attempt.OFFLINE) {
                noNetwork();
                return;
            }
        }
        allFailed();
    }

    private void dropStalePayload() {
        if (!replacePayload || wiped) return;
        wiped = true;
        setStatus(StatusKind.RUNNING, context.getString(R.string.setup_update_title),
                context.getString(R.string.setup_update_body));
        log(LogLevel.STEP, context.getString(R.string.setup_update_body));
        Engines.stopAll(context);
        long freed = com.zalexdev.stryker.engine.EnginePayload.wipe(context);
        if (freed > 0) {
            log(LogLevel.INFO, context.getString(R.string.setup_update_freed, formatMb(freed)));
        }
    }

    private String planNames(List<EngineType> plan) {
        StringBuilder sb = new StringBuilder();
        for (EngineType t : plan) {
            if (sb.length() > 0) sb.append(" → ");
            sb.append(Engines.active(context, t).displayName());
        }
        return sb.toString();
    }

    private EngineType leadEngine(EngineType recorded) {
        if (!replacePayload || recorded != EngineType.ROOTLESS) return recorded;
        if (!EngineType.rootlessSupported(context)) return recorded;

        String umlName = Engines.active(context, EngineType.UML).displayName();
        setStatus(StatusKind.RUNNING, context.getString(R.string.setup_caps_title),
                context.getString(R.string.setup_caps_running, umlName));
        log(LogLevel.STEP, "This install picked the virtual machine before " + umlName
                + " existed — testing it first");

        com.zalexdev.stryker.engine.UmlProbe.Result probe =
                com.zalexdev.stryker.engine.UmlProbe.run(context);
        DeviceCapabilities.rememberUmlNotes(core, probe);
        if (probe.ruledOut()) {
            log(LogLevel.INFO, umlName + " will not run here: " + probe.detail
                    + " — staying on the virtual machine");
            return recorded;
        }
        log(LogLevel.SUCCESS, umlName + ": " + probe.detail
                + " — it boots faster, and the virtual machine stays as the fallback");
        return EngineType.UML;
    }

    private List<EngineType> installPlan() {
        EngineType chosen = leadEngine(EngineType.active(core));
        List<EngineType> detected = DeviceCapabilities.plan(core);
        List<EngineType> plan = new ArrayList<>();

        if (chosen != EngineType.CHROOT) plan.add(chosen);
        for (EngineType t : detected) {
            if (t != EngineType.CHROOT && !plan.contains(t)) plan.add(t);
        }

        if (detected.isEmpty()) {
            if (plan.isEmpty()) plan.add(EngineType.UML);
            EngineType other =
                    plan.get(0) == EngineType.UML ? EngineType.ROOTLESS : EngineType.UML;
            if (!plan.contains(other)) plan.add(other);
        }
        return plan;
    }

    private enum Attempt { BOOTED, FAILED, OFFLINE }

    private Attempt attempt(EngineType candidate) {
        GuestEngine engine = Engines.active(context, candidate);

        EngineType.persist(core, candidate);

        runOnUi(() -> {
            progress.setVisibility(View.VISIBLE);
            progress.setIndeterminate(true);
        });
        setStatus(StatusKind.RUNNING, engine.displayName(), "Installing…");
        log(LogLevel.STEP, "Installing " + engine.displayName());
        log(LogLevel.INFO, QemuInstaller.assetsPresent(context)
                ? "Artifacts bundled in the APK — installing offline"
                : "Artifacts not bundled — downloading (~500 MB)");

        QemuInstaller.Outcome installed = QemuInstaller.install(context, installProgress(), candidate);
        if (installed == QemuInstaller.Outcome.OFFLINE) {
            log(LogLevel.ERROR, engine.displayName() + ": nothing downloaded — the phone is offline");
            return Attempt.OFFLINE;
        }
        if (installed != QemuInstaller.Outcome.OK) {
            log(LogLevel.ERROR, engine.displayName() + ": install failed — see log");
            return Attempt.FAILED;
        }
        prefetchFallback(candidate);

        seedDefaults();
        log(LogLevel.SUCCESS, "Defaults written (wlan0, "
                + core.getInt("rootless_cpus", VmSpecs.DEFAULT_CPUS) + " vCPU, "
                + core.getInt("rootless_ram", VmSpecs.DEFAULT_RAM_MB) + " MB)");

        runOnUi(() -> downloadText.setText(""));
        setStatus(StatusKind.RUNNING, engine.displayName(), "Booting — the first boot is slow");
        log(LogLevel.STEP, "Booting " + engine.displayName()
                + " for the first time · waiting for the guest's SSH");
        if (candidate == EngineType.UML) logUmlProbeNotes();
        return bootWithDeadline(engine) ? Attempt.BOOTED : Attempt.FAILED;
    }

    private void logUmlProbeNotes() {
        List<String> notes = DeviceCapabilities.umlNotes(core);
        if (notes.isEmpty()) return;
        log(LogLevel.INFO, "Kernel probe said:");
        for (String note : notes) log(LogLevel.INFO, "  " + note);
    }

    private void prefetchFallback(EngineType candidate) {
        if (candidate != EngineType.UML || !EngineType.rootlessSupported(context)) return;
        if (Engines.active(context, EngineType.ROOTLESS).isInstalled()) return;

        log(LogLevel.STEP, "Fetching the virtual machine as a fallback");
        boolean ok = QemuInstaller.install(context, installProgress(), EngineType.ROOTLESS).ok();
        log(ok ? LogLevel.SUCCESS : LogLevel.WARN, ok
                ? "Fallback engine ready if this one will not boot"
                : "Fallback engine could not be fetched — it will be downloaded only if needed");
    }

    private QemuInstaller.Progress installProgress() {
        return new QemuInstaller.Progress() {
            @Override public void onStage(QemuInstaller.Stage stage) {
                runOnUi(() -> statusSubtitle.setText(stage.title));
            }
            @Override public void onBytes(String label, long done) {
                runOnUi(() -> downloadText.setText(label + " · " + formatMb(done)));
            }
            @Override public void onLog(int level, String message) { log(mapLevel(level), message); }
        };
    }

    private boolean bootWithDeadline(GuestEngine engine) {
        AtomicBoolean booted = new AtomicBoolean(false);
        Thread boot = new Thread(() -> booted.set(engine.startBlocking(bootListener())),
                "engine-boot");
        boot.setDaemon(true);
        boot.start();
        long started = System.currentTimeMillis();
        lastBootLine.set(started);
        try {
            while (boot.isAlive()) {
                long now = System.currentTimeMillis();
                long elapsed = now - started;
                if (elapsed >= BOOT_HARD_DEADLINE_MS) break;
                if (elapsed >= BOOT_DEADLINE_MS
                        && now - lastBootLine.get() >= BOOT_STALL_MS) break;
                boot.join(BOOT_POLL_MS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        if (boot.isAlive()) {
            log(LogLevel.ERROR, engine.displayName() + " did not finish booting within "
                    + ((System.currentTimeMillis() - started) / 1000) + "s");
            forceStop(engine);
            return false;
        }
        if (!booted.get()) {
            String why = engine.lastError();
            log(LogLevel.ERROR, engine.displayName() + " did not boot"
                    + (why == null || why.isEmpty() ? "" : ": " + why));
            forceStop(engine);
            return false;
        }
        if (!confirmReady(engine)) {
            log(LogLevel.ERROR, engine.displayName()
                    + " started but the guest does not stay reachable over SSH");
            forceStop(engine);
            return false;
        }
        return true;
    }

    private static final int READY_CONFIRMATIONS = 2;
    private static final long READY_GAP_MS = 4_000;

    private boolean confirmReady(GuestEngine engine) {
        long deadline = System.currentTimeMillis() + READY_CONFIRM_MS;
        int seen = 0;
        long nextAt = 0;
        while (System.currentTimeMillis() < deadline) {
            if (System.currentTimeMillis() >= nextAt && engine.isReady()) {
                if (++seen >= READY_CONFIRMATIONS) return true;
                log(LogLevel.INFO, "Guest answered over SSH (" + seen + "/"
                        + READY_CONFIRMATIONS + ") — confirming it stays up");
                nextAt = System.currentTimeMillis() + READY_GAP_MS;
            }
            if (!engine.isRunning()) return false;
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    private void forceStop(GuestEngine engine) {
        Thread stopper = new Thread(() -> {
            try {
                engine.stop();
            } catch (Throwable ignored) {
            }
        }, "engine-stop");
        stopper.setDaemon(true);
        stopper.start();

        long deadline = System.currentTimeMillis() + STOP_GRACE_MS;
        while (System.currentTimeMillis() < deadline) {
            if (!engine.isRunning()) return;
            try {
                Thread.sleep(250);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        log(LogLevel.WARN, engine.displayName()
                + " would not shut down — the next engine may find its ports taken");
    }

    private GuestEngine.BootListener bootListener() {
        return new GuestEngine.BootListener() {
            @Override public void onBootLine(String line) {
                lastBootLine.set(System.currentTimeMillis());
                if (line != null && (line.contains("stryker") || line.contains("login")
                        || line.contains("Kernel panic") || line.contains("error"))) {
                    log(LogLevel.INFO, line);
                }
            }
            @Override public void onBooted() { log(LogLevel.SUCCESS, "Guest is up"); }
            @Override public void onFailed(String reason) { log(LogLevel.WARN, "Boot: " + reason); }
        };
    }

    private void announceFallback(EngineType failed, EngineType next) {
        String from = Engines.active(context, failed).displayName();
        String to = Engines.active(context, next).displayName();
        log(LogLevel.WARN, context.getString(R.string.setup_install_fallback, from, to));
        setStatus(StatusKind.RUNNING, context.getString(R.string.setup_install_fallback_title),
                context.getString(R.string.setup_install_fallback, from, to));
    }

    private void succeed(EngineType engineType) {
        GuestEngine engine = Engines.active(context, engineType);
        log(LogLevel.SUCCESS, engine.displayName() + " ready");
        log(LogLevel.STEP, "Deploying built-in scripts (CORE, exploits)");
        boolean coreOk = RootlessEngine.get(context).ensureGuestCore();
        log(coreOk ? LogLevel.SUCCESS : LogLevel.WARN,
                coreOk ? "Scripts deployed to the guest" : "Scripts deploy deferred to first use");

        EngineType.persist(core, engineType);
        core.putBoolean(EngineType.PREF_VERIFIED, true);
        core.putBoolean(EngineType.PREF_FORCED, false);
        com.zalexdev.stryker.engine.EnginePayload.mark(core);

        setStatus(StatusKind.SUCCESS, engine.displayName(), "Done — moving on");
        runOnUi(() -> {
            closeLog();
            progress.setVisibility(View.INVISIBLE);
            core.moveNext(mPager);
        });
    }

    private void allFailed() {
        core.putBoolean(EngineType.PREF_VERIFIED, false);
        setStatus(StatusKind.FAILED, context.getString(R.string.setup_install_all_failed_title),
                context.getString(R.string.setup_install_all_failed_body));
        log(LogLevel.ERROR, context.getString(R.string.setup_install_all_failed_body));
        runOnUi(() -> {
            progress.setIndeterminate(false);
            progress.setVisibility(View.INVISIBLE);
            failed = true;
            started = false;
            showLog();
            refreshChrome();
        });
    }

    private void noNetwork() {
        setStatus(StatusKind.FAILED, context.getString(R.string.setup_install_offline_title),
                context.getString(R.string.setup_install_offline_body));
        log(LogLevel.ERROR, context.getString(R.string.setup_install_offline_body));
        runOnUi(() -> {
            progress.setIndeterminate(false);
            progress.setVisibility(View.INVISIBLE);
            offline = true;
            failed = true;
            started = false;
            refreshChrome();
        });
    }

    private void seedDefaults() {
        if (core.getString("wlan_wifi").isEmpty())   core.putString("wlan_wifi", "wlan0");
        if (core.getString("wlan_scan").isEmpty())   core.putString("wlan_scan", "wlan0");
        if (core.getString("wlan_deauth").isEmpty()) core.putString("wlan_deauth", "wlan0");
        if (core.getString("wlan_wps").isEmpty())    core.putString("wlan_wps", "wlan0");
        if (core.getInt("rootless_ram", 0) <= 0)
            core.putInt("rootless_ram", VmSpecs.recommendedRamMb(context));
        if (core.getInt("rootless_cpus", 0) <= 0)
            core.putInt("rootless_cpus", VmSpecs.recommendedCpus());
    }

    private static String formatMb(long bytes) {
        if (bytes <= 0) return "0 MB";
        return String.format(Locale.US, "%.1f MB", bytes / 1024.0 / 1024.0);
    }

    private static LogLevel mapLevel(int level) {
        switch (level) {
            case 3: return LogLevel.ERROR;
            case 2: return LogLevel.SUCCESS;
            default: return LogLevel.STEP;
        }
    }

    private void runOnUi(Runnable r) {
        if (activity == null || !isAdded()) return;
        activity.runOnUiThread(() -> { if (isAdded()) r.run(); });
    }

    private void log(LogLevel level, String text) {
        runOnUi(() -> {
            logAdapter.append(new LogLine(level, text));
            if (logDialog != null && logDialog.isShowing()) logDialog.scrollToEnd();
        });
    }

    private enum StatusKind { RUNNING, SUCCESS, FAILED }

    @SuppressLint("SetTextI18n")
    private void setStatus(StatusKind kind, String title, String subtitle) {
        runOnUi(() -> {
            statusTitle.setText(title);
            statusSubtitle.setText(subtitle);
            if (working != null) {
                boolean busy = kind == StatusKind.RUNNING;
                working.setVisibility(busy ? View.VISIBLE : View.GONE);
                if (busy) working.playAnimation(); else working.cancelAnimation();
            }
            if (logDialog != null && logDialog.isShowing()) logDialog.setState(subtitle);
            switch (kind) {
                case SUCCESS:
                    statusSpinner.setVisibility(View.GONE);
                    statusIcon.setVisibility(View.VISIBLE);
                    statusIcon.setImageResource(R.drawable.done);
                    statusIcon.setColorFilter(ContextCompat.getColor(context, R.color.green), PorterDuff.Mode.SRC_IN);
                    break;
                case FAILED:
                    statusSpinner.setVisibility(View.GONE);
                    statusIcon.setVisibility(View.VISIBLE);
                    statusIcon.setImageResource(R.drawable.error);
                    statusIcon.setColorFilter(ContextCompat.getColor(context, R.color.red), PorterDuff.Mode.SRC_IN);
                    break;
                case RUNNING:
                default:
                    statusIcon.setVisibility(View.GONE);
                    statusIcon.clearColorFilter();
                    statusSpinner.setVisibility(View.VISIBLE);
                    break;
            }
        });
    }

    @Override
    public boolean primaryVisible() {
        return failed;
    }

    @Override
    public CharSequence primaryLabel(Context context) {
        return context.getString(offline
                ? R.string.intro_action_retry : R.string.setup_install_manual);
    }

    @Override
    public void onPrimary() {
        if (offline) {
            logAdapter.clear();
            startInstall();
            return;
        }
        core.putBoolean(EngineType.PREF_FORCED, true);
        core.moveNext(mPager);
    }
}
