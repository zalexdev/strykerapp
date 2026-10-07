package com.zalexdev.stryker.handshakes;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.AsyncTask;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.core.content.FileProvider;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;
import com.zalexdev.stryker.R;
import com.zalexdev.stryker.custom.WiFINetwork;
import com.zalexdev.stryker.handshakes.utils.BruteHandshake;
import com.zalexdev.stryker.utils.Core;
import com.zalexdev.stryker.wordlists.WordlistCategory;
import com.zalexdev.stryker.wordlists.WordlistPickerDialog;
import com.zalexdev.stryker.wordlists.WordlistStore;

import java.io.File;
import java.util.ArrayList;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import com.stryker.terminal.bridge.StrykerLog;

public class HandshakesAdapter extends RecyclerView.Adapter<HandshakesAdapter.ViewHolder> {

    private static final Pattern MAC_PATTERN = Pattern.compile("((\\w{2}:){5}\\w{2})");

    private static final String PREF_LAST_WORDLIST = "handshake_last_wordlist";

    public ArrayList<String> hslist;
    public Context context;
    public Activity activity;
    public Core core;
    public Runnable onChangeListener;
    public int id = 0;

    public HandshakesAdapter(Context context, Activity activity, ArrayList<String> hsList) {
        this.context = context;
        this.hslist = hsList;
        this.activity = activity;
        this.core = new Core(context);
        rebuildRows();
        scanCaptures();
    }

    public void setOnChangeListener(Runnable listener) {
        this.onChangeListener = listener;
    }

    private static final class Row {
        final String header;
        final String path;
        final int index;

        Row(String header, String path, int index) {
            this.header = header;
            this.path = path;
            this.index = index;
        }
    }

    private static final int TYPE_HEADER = 1;
    private static final int TYPE_CAPTURE = 0;

    private final ArrayList<Row> rows = new ArrayList<>();

    private void rebuildRows() {
        final java.util.HashMap<String, Long> when = new java.util.HashMap<>();
        for (String p : hslist) when.put(p, captureFile(p).lastModified());

        java.util.Collections.sort(hslist, (a, b) -> {
            long ta = when.containsKey(a) ? when.get(a) : 0L;
            long tb = when.containsKey(b) ? when.get(b) : 0L;
            return Long.compare(tb, ta);
        });

        rows.clear();
        String current = null;
        for (int i = 0; i < hslist.size(); i++) {
            String path = hslist.get(i);
            String day = dayLabel(when.containsKey(path) ? when.get(path) : 0L);
            if (!day.equals(current)) {
                current = day;
                rows.add(new Row(day, null, -1));
            }
            rows.add(new Row(null, path, i));
        }
    }

    private String dayLabel(long millis) {
        if (millis <= 0L) return context.getString(R.string.hs_group_unknown);
        java.util.Calendar then = java.util.Calendar.getInstance();
        then.setTimeInMillis(millis);
        java.util.Calendar now = java.util.Calendar.getInstance();
        if (sameDay(then, now)) return context.getString(R.string.hs_group_today);
        now.add(java.util.Calendar.DAY_OF_YEAR, -1);
        if (sameDay(then, now)) return context.getString(R.string.hs_group_yesterday);
        return android.text.format.DateFormat.getMediumDateFormat(context)
                .format(new java.util.Date(millis));
    }

    private static boolean sameDay(java.util.Calendar a, java.util.Calendar b) {
        return a.get(java.util.Calendar.YEAR) == b.get(java.util.Calendar.YEAR)
                && a.get(java.util.Calendar.DAY_OF_YEAR) == b.get(java.util.Calendar.DAY_OF_YEAR);
    }

    private void refreshRows() {
        rebuildRows();
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        int layout = viewType == TYPE_HEADER
                ? R.layout.handshake_date_header
                : R.layout.handshake_item;
        return new ViewHolder(LayoutInflater.from(context).inflate(layout, parent, false));
    }

    @SuppressLint({"SetTextI18n", "RecyclerView"})
    @Override
    public void onBindViewHolder(@NonNull ViewHolder h, int position) {
        Row row = rows.get(position);
        if (row.header != null) {
            h.dateHeader.setText(row.header);
            return;
        }
        String path = row.path;
        final int index = row.index;
        String displayName = new File(path).getName();

        h.brute.setVisibility(View.VISIBLE);
        h.cancel.setVisibility(View.GONE);
        h.stateChip.setVisibility(View.GONE);
        h.progress.setText("");
        h.timeLeft.setVisibility(View.GONE);
        h.timeLeft.setText("");
        h.itemView.setOnClickListener(null);

        CaptureInfo info = CaptureInfo.of(captureFile(path));
        String mac = info.bssid;
        if (mac.isEmpty()) {
            Matcher m = MAC_PATTERN.matcher(path);
            mac = m.find() ? m.group(0) : "";
        }
        final String finalMac = mac;

        String stored = core.getString(mac);
        boolean cracked = stored != null && stored.length() > 0;

        h.name.setText(info.ssid.isEmpty() ? displayName : info.ssid);
        paintKind(h, info);

        if (cracked) {
            h.stateChip.setVisibility(View.VISIBLE);
            h.stateChip.setText(R.string.hs_state_cracked);
            h.stateChip.setTextColor(Color.parseColor("#388E3C"));
            h.progress.setText(context.getResources().getString(R.string.pass_founded) + stored);
            h.progress.setTextColor(Color.parseColor("#388E3C"));
            h.itemView.setOnClickListener(v -> copyPassword(stored));
        } else {
            h.progress.setTextColor(Color.parseColor("#9E9E9E"));
            File f = captureFile(path);
            StringBuilder line = new StringBuilder();
            if (f.exists()) line.append(humanSize(f.length()));
            if (!finalMac.isEmpty()) {
                if (line.length() > 0) line.append("  ·  ");
                line.append(core.getBoolean("hide") ? Core.HIDDEN_MAC : finalMac);
            }
            if (info.kind == CaptureInfo.Kind.HANDSHAKE && info.pmkid) {
                line.append("  ·  ").append(context.getString(R.string.hs_kind_pmkid));
            }
            h.progress.setText(line.toString());
        }

        h.brute.setOnClickListener(v -> startBrute(h, path, finalMac));
        h.overflow.setOnClickListener(v -> showOverflow(v, index, path, displayName, finalMac));
    }

    private void scanCaptures() {
        final java.util.ArrayList<String> snapshot = new java.util.ArrayList<>(hslist);
        new Thread(() -> {
            for (String p : snapshot) {
                if (p == null) continue;
                CaptureInfo.of(captureFile(p));
            }
            if (activity != null) activity.runOnUiThread(this::refreshRows);
        }, "hs-scan").start();
    }

    private void paintKind(ViewHolder h, CaptureInfo info) {
        switch (info.kind) {
            case HANDSHAKE:
                h.icon.setImageResource(R.drawable.handshake_interface);
                h.icon.setColorFilter(Color.parseColor("#2E7D32"));
                h.stateChip.setVisibility(View.VISIBLE);
                h.stateChip.setText(R.string.hs_kind_handshake);
                h.stateChip.setTextColor(Color.parseColor("#2E7D32"));
                break;
            case PMKID:
                h.icon.setImageResource(R.drawable.key);
                h.icon.setColorFilter(Color.parseColor("#0277BD"));
                h.stateChip.setVisibility(View.VISIBLE);
                h.stateChip.setText(R.string.hs_kind_pmkid);
                h.stateChip.setTextColor(Color.parseColor("#0277BD"));
                break;
            case EMPTY:
                h.icon.setImageResource(R.drawable.close);
                h.icon.setColorFilter(Color.parseColor("#C62828"));
                h.stateChip.setVisibility(View.VISIBLE);
                h.stateChip.setText(R.string.hs_kind_empty);
                h.stateChip.setTextColor(Color.parseColor("#C62828"));
                break;
            default:
                h.icon.setImageResource(R.drawable.file);
                h.icon.setColorFilter(Color.parseColor("#FB8C00"));
                h.stateChip.setVisibility(View.GONE);
                break;
        }
    }

    private void showOverflow(View anchor, int position, String path, String displayName, String mac) {
        PopupMenu menu = new PopupMenu(context, anchor);
        String pwd = core.getString(mac);
        boolean cracked = pwd != null && !pwd.isEmpty();
        if (cracked) {
            menu.getMenu().add(0, 5, 0, R.string.hs_password_copy);
        }
        menu.getMenu().add(0, 1, 2, R.string.hs_action_upload);
        menu.getMenu().add(0, 2, 2, R.string.hs_action_share);
        menu.getMenu().add(0, 3, 3, R.string.hs_action_rename);
        menu.getMenu().add(0, 4, 4, R.string.hs_action_delete);
        menu.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 1: askEmailAndUpload(path, displayName); return true;
                case 2: shareFile(path); return true;
                case 3: renameFile(position, path, displayName); return true;
                case 4: deleteFile(position, path, displayName); return true;
                case 5: copyPassword(pwd); return true;
                default: return false;
            }
        });
        menu.show();
    }

    private void copyPassword(String password) {
        if (password == null || password.isEmpty()) return;
        ClipboardManager cm = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("psk", password));
        }
        toaster(context.getString(R.string.hs_password_copied));
    }

    private void startBrute(ViewHolder h, String path, String finalMac) {
        WordlistPickerDialog.show(context, activity, core,
                context.getString(R.string.hs_wordlist_title),
                new WordlistCategory[] { WordlistCategory.WIFI, WordlistCategory.PASSWORD },
                core.getString(PREF_LAST_WORDLIST),
                wordlist -> {
                    core.putString(PREF_LAST_WORDLIST, wordlist.getName());
                    launchBrute(h, path, finalMac, wordlist);
                });
    }

    private void launchBrute(ViewHolder h, String path, String finalMac,
                             com.zalexdev.stryker.wordlists.Wordlist wordlist) {
        h.progress.setVisibility(View.VISIBLE);
        h.progress.setTextColor(Color.parseColor("#9E9E9E"));
        h.timeLeft.setVisibility(View.VISIBLE);
        h.stateChip.setVisibility(View.VISIBLE);
        h.stateChip.setText(R.string.hs_state_brute);
        h.stateChip.setTextColor(Color.parseColor("#AB47BC"));
        h.brute.setVisibility(View.GONE);
        h.cancel.setVisibility(View.VISIBLE);
        h.progress.setText(R.string.hs_progress_starting);

        new Thread(() -> {
            try {
                String wordlistPath = new WordlistStore(core).reachablePath(wordlist);
                if (wordlistPath == null) {
                    activity.runOnUiThread(() -> {
                        h.progress.setText(activity.getString(
                                R.string.hs_wordlist_missing, wordlist.getName()));
                        h.progress.setTextColor(Color.parseColor("#E53935"));
                        h.timeLeft.setVisibility(View.GONE);
                        h.stateChip.setVisibility(View.GONE);
                        h.cancel.setVisibility(View.GONE);
                        h.brute.setVisibility(View.VISIBLE);
                        core.toaster(activity.getString(R.string.hs_wordlist_missing_toast));
                    });
                    return;
                }
                id++;
                String capRel = path.replace(core.getShareRoot(), core.guestShare());
                BruteHandshake br = new BruteHandshake(capRel, wordlistPath, core, activity, context, h.progress, h.timeLeft, id);
                activity.runOnUiThread(() -> h.cancel.setOnClickListener(v -> {
                    br.kill();
                    h.cancel.setVisibility(View.GONE);
                    h.brute.setVisibility(View.VISIBLE);
                    h.stateChip.setVisibility(View.GONE);
                    h.timeLeft.setVisibility(View.GONE);
                }));
                WiFINetwork w = br.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR).get();
                activity.runOnUiThread(() -> {
                    h.brute.setVisibility(View.VISIBLE);
                    h.cancel.setVisibility(View.GONE);
                    h.timeLeft.setVisibility(View.GONE);
                    if (w.getOK()) {
                        h.progress.setText(context.getResources().getString(R.string.pass_founded) + w.getPsk());
                        h.progress.setTextColor(Color.parseColor("#388E3C"));
                        h.stateChip.setText(R.string.hs_state_cracked);
                        h.stateChip.setTextColor(Color.parseColor("#388E3C"));
                        core.putString(finalMac, w.getPsk());
                        if (onChangeListener != null) onChangeListener.run();
                    } else {
                        h.progress.setText(R.string.pass_not_found);
                        h.progress.setTextColor(Color.parseColor("#D32F2F"));
                        h.stateChip.setVisibility(View.GONE);
                    }
                });
            } catch (ExecutionException | InterruptedException e) {
                e.printStackTrace();
            }
        }).start();
    }

    private void askEmailAndUpload(String path, String displayName) {
        final Dialog dialog = new Dialog(context);
        dialog.setContentView(R.layout.input_dialog);
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            dialog.getWindow().setLayout(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        }
        TextView title = dialog.findViewById(R.id.title);
        TextInputEditText valueEdit = dialog.findViewById(R.id.value);
        MaterialButton ok = dialog.findViewById(R.id.ok);
        MaterialButton dismiss = dialog.findViewById(R.id.cancel);
        title.setText(R.string.hs_upload_email_title);
        valueEdit.setHint(R.string.hs_upload_email_hint);
        dismiss.setOnClickListener(v -> dialog.dismiss());
        ok.setOnClickListener(v -> {
            String email = Objects.requireNonNull(valueEdit.getText()).toString().trim();
            dialog.dismiss();
            toaster("Uploading " + displayName);
            new Thread(() -> {
                ArrayList<String> result = core.customChrootCommand(
                        "curl -s -X POST -F \"email=" + email + "\" -F \"file=@"
                                + core.guestShare() + "/captured/" + displayName
                                + "\" https://api.onlinehashcrack.com");
                UploadResult outcome = parseUploadResult(result);
                activity.runOnUiThread(() -> {
                    switch (outcome.outcome) {
                        case ALREADY:
                            toaster(context.getString(R.string.file_was_uploaded));
                            break;
                        case SUCCESS:
                            toaster(context.getString(R.string.upload_success));
                            break;
                        default:
                            toaster(outcome.message.isEmpty()
                                    ? context.getString(R.string.error_upload)
                                    : outcome.message);
                            break;
                    }
                });
            }).start();
        });
        dialog.show();
    }

    private enum UploadOutcome {SUCCESS, ALREADY, FAILED}

    private static final class UploadResult {
        final UploadOutcome outcome;
        final String message;

        UploadResult(UploadOutcome outcome, String message) {
            this.outcome = outcome;
            this.message = message;
        }
    }

    private static UploadResult parseUploadResult(ArrayList<String> lines) {
        StringBuilder builder = new StringBuilder();
        if (lines != null) {
            for (String line : lines) {
                builder.append(line).append('\n');
            }
        }
        String body = builder.toString().trim();
        int start = body.indexOf('{');
        int end = body.lastIndexOf('}');
        if (start >= 0 && end > start) {
            try {
                org.json.JSONObject json = new org.json.JSONObject(body.substring(start, end + 1));
                String message = json.optString("message", "");
                if (json.optBoolean("success", false)) {
                    boolean already = message.toLowerCase(java.util.Locale.ROOT).contains("already");
                    return new UploadResult(already ? UploadOutcome.ALREADY : UploadOutcome.SUCCESS, message);
                }
                org.json.JSONObject accepted = json.optJSONObject("accepted");
                org.json.JSONObject skipped = json.optJSONObject("skipped");
                org.json.JSONObject rejected = json.optJSONObject("rejected");
                if (accepted != null || skipped != null || rejected != null) {
                    int acc = accepted != null ? accepted.optInt("count", 0) : 0;
                    int skp = skipped != null ? skipped.optInt("count", 0) : 0;
                    int rej = rejected != null ? rejected.optInt("count", 0) : 0;
                    if (acc > 0) {
                        return new UploadResult(UploadOutcome.SUCCESS, message);
                    }
                    if (skp > 0) {
                        String reason = skipped != null ? skipped.optString("reason", "") : "";
                        return new UploadResult(UploadOutcome.ALREADY, reason);
                    }
                    String reason = rejected != null ? rejected.optString("reason", "") : "";
                    return new UploadResult(UploadOutcome.FAILED, reason);
                }
                return new UploadResult(UploadOutcome.FAILED, message);
            } catch (org.json.JSONException ignored) {
            }
        }
        String lower = body.toLowerCase(java.util.Locale.ROOT);
        if (lower.contains("already")) {
            return new UploadResult(UploadOutcome.ALREADY, "");
        }
        if (lower.contains("success") || lower.contains("added") || lower.contains("uploaded")) {
            return new UploadResult(UploadOutcome.SUCCESS, "");
        }
        return new UploadResult(UploadOutcome.FAILED, "");
    }

    private void shareFile(String path) {
        File f = captureFile(path);
        if (!f.exists()) {
            toaster(context.getString(R.string.hs_share_failed));
            return;
        }
        try {
            Uri uri = FileProvider.getUriForFile(context, context.getPackageName() + ".provider", f);
            Intent share = new Intent(Intent.ACTION_SEND);
            share.setType("application/octet-stream");
            share.putExtra(Intent.EXTRA_STREAM, uri);
            share.putExtra(Intent.EXTRA_SUBJECT, f.getName());
            share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            Intent chooser = Intent.createChooser(share, context.getString(R.string.hs_action_share));
            chooser.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            context.startActivity(chooser);
        } catch (Exception e) {
            StrykerLog.w("HandshakesAdapter", "share failed", e);
            toaster(context.getString(R.string.hs_share_failed));
        }
    }

    private File captureFile(String name) {
        if (name != null && name.startsWith("/")) return new File(name);
        return new File(core.getShareRoot() + "/captured", name);
    }

    private void renameFile(int position, String path, String displayName) {
        final Dialog dialog = new Dialog(context);
        dialog.setContentView(R.layout.input_dialog);
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            dialog.getWindow().setLayout(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        }
        TextView title = dialog.findViewById(R.id.title);
        TextInputEditText valueEdit = dialog.findViewById(R.id.value);
        MaterialButton ok = dialog.findViewById(R.id.ok);
        MaterialButton dismiss = dialog.findViewById(R.id.cancel);
        title.setText(R.string.hs_rename_title);
        valueEdit.setHint(R.string.hs_rename_hint);
        valueEdit.setText(displayName);
        dismiss.setOnClickListener(v -> dialog.dismiss());
        ok.setOnClickListener(v -> {
            String newName = Objects.requireNonNull(valueEdit.getText()).toString().trim();
            if (newName.isEmpty() || newName.equals(displayName)) { dialog.dismiss(); return; }
            File src = captureFile(path);
            File dst = captureFile(newName);
            if (src.renameTo(dst)) {
                hslist.set(position, newName);
                refreshRows();
                if (onChangeListener != null) onChangeListener.run();
            }
            dialog.dismiss();
        });
        dialog.show();
    }

    private void deleteFile(int position, String path, String displayName) {
        new MaterialAlertDialogBuilder(context)
                .setTitle(R.string.hs_delete_title)
                .setMessage(context.getString(R.string.hs_delete_body, displayName))
                .setPositiveButton(R.string.hs_action_delete, (d, w) -> {
                    File f = captureFile(path);
                    boolean deleted = f.delete();
                    if (!deleted && f.exists()) {
                        core.customCommand("rm -f '" + f.getAbsolutePath() + "'");
                        deleted = !f.exists();
                    }
                    if (deleted) {
                        hslist.remove(position);
                        refreshRows();
                        if (onChangeListener != null) onChangeListener.run();
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    @Override
    public int getItemCount() {
        return rows.size();
    }

    @Override
    public long getItemId(int position) {
        Row row = rows.get(position);
        return row.header != null ? row.header.hashCode() : row.path.hashCode();
    }

    @Override
    public int getItemViewType(int position) {
        return rows.get(position).header != null ? TYPE_HEADER : TYPE_CAPTURE;
    }

    public void toaster(String msg) {
        if (activity == null) return;
        activity.runOnUiThread(() -> Toast.makeText(context, msg, Toast.LENGTH_SHORT).show());
    }

    private static String humanSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return (bytes / 1024) + " KB";
        return String.format(java.util.Locale.US, "%.1f MB", bytes / 1024.0 / 1024.0);
    }

    public static class ViewHolder extends RecyclerView.ViewHolder {
        public TextView name;
        public TextView progress;
        public TextView timeLeft;
        public TextView stateChip;
        public ImageView brute;
        public ImageView cancel;
        public ImageView overflow;
        public ImageView icon;
        public TextView dateHeader;

        public ViewHolder(View v) {
            super(v);
            dateHeader = v.findViewById(R.id.hs_date_header);
            name = v.findViewById(R.id.hs_name);
            progress = v.findViewById(R.id.hs_progress);
            timeLeft = v.findViewById(R.id.hs_time_left);
            stateChip = v.findViewById(R.id.hs_state_chip);
            brute = v.findViewById(R.id.hs_brute);
            cancel = v.findViewById(R.id.hs_cancel);
            overflow = v.findViewById(R.id.hs_overflow);
            icon = v.findViewById(R.id.hs_icon);
        }
    }
}
