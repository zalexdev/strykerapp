package com.zalexdev.stryker.handshakes.utils;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.AsyncTask;
import android.os.Build;
import android.widget.TextView;

import androidx.annotation.RequiresApi;
import androidx.core.app.NotificationCompat;

import com.zalexdev.stryker.MainActivity;
import com.zalexdev.stryker.R;
import com.zalexdev.stryker.custom.WiFINetwork;
import com.zalexdev.stryker.engine.GuestExec;
import com.zalexdev.stryker.logger.Logger;
import com.zalexdev.stryker.utils.Core;
import com.zalexdev.stryker.utils.Utils;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class BruteHandshake extends AsyncTask<Void, String, WiFINetwork> {
    public String exec = Core.EXECUTE;
    public String path;
    public String wordlist;
    public Core core;
    public Activity activity;
    public TextView progress;
    public TextView time;
    public Context context;
    public int id;
    public Process process;
    public GuestExec.Session guestSession;
    public Logger logger;

    public BruteHandshake(String p, String w, Core c, Activity a, Context con, TextView pr, TextView t, int i) {
        core = c;
        path = p;
        wordlist = w;
        activity = a;
        progress = pr;
        time = t;
        context = con;
        id = i;
        logger = new Logger();
    }

    @Override
    protected void onPreExecute() {
        super.onPreExecute();

    }

    private String guestCapture() {
        if (path != null && path.startsWith("/")) return path;
        return core.guestShare() + "/captured/" + path;
    }

    @SuppressLint("WrongThread")
    @Override
    protected WiFINetwork doInBackground(Void... command) {
        String line;
        WiFINetwork result = new WiFINetwork();
        logger.writeLine("Starting brute handshake",1);
        try {
            if (core.isRootless()) {
                String guestCmd = "aircrack-ng -w " + WordlistStore.quoteForShell(wordlist) + " " + guestCapture() + " ";
                guestSession = core.guest().openStream(guestCmd);
                BufferedReader gbr = guestSession.reader;
                while ((line = gbr.readLine()) != null) {
                    if (line.startsWith(GuestExec.Session.SENTINEL)) break;
                    logger.writeLine(line,2);
                    onProgressUpdate(line);
                    if (line.contains("KEY FOUND! [ ")) {
                        Pattern pattern = Pattern.compile("\\[ (.*?)\\]");
                        Matcher matcher = pattern.matcher(line);
                        if (matcher.find()) {
                            result.setPsk(matcher.group(1));
                        }
                        result.setOK(true);
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            CreateNotification("Success", "Password found: " + result.getPsk(), 100, 100);
                        }
                    }
                }
                guestSession.close();
            } else {
            process = Runtime.getRuntime().exec("su");
            OutputStream stdin = process.getOutputStream();
            InputStream stderr = process.getErrorStream();
            InputStream stdout = process.getInputStream();
            stdin.write((exec + "'aircrack-ng -w " + wordlist + " " + guestCapture() + " '" + '\n').getBytes());
            stdin.flush();
            stdin.close();

            BufferedReader br = new BufferedReader(new InputStreamReader(stdout));
            while ((line = br.readLine()) != null) {

                logger.writeLine(line,2);
                onProgressUpdate(line);
                if (line.contains("KEY FOUND! [ ")) {
                    Pattern pattern = Pattern.compile("\\[ (.*?)\\]");
                    Matcher matcher = pattern.matcher(line);
                    if (matcher.find()) {
                        result.setPsk(matcher.group(1));
                    }
                    result.setOK(true);
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        CreateNotification("Success", "Password found: " + result.getPsk(), 100, 100);
                    }
                }
            }
            br.close();
            br = new BufferedReader(new InputStreamReader(stderr));
            while ((line = br.readLine()) != null) {

                logger.writeLine(line,3);
            }
            br.close();
            process.waitFor();
            process.destroy();
            }

        } catch (IOException | InterruptedException e) {
        }
        if (!result.getOK()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                CreateNotification("Failed", "Password Not Found", 100, 100);
            }
        }
        return result;
    }

    @Override
    protected void onPostExecute(WiFINetwork result) {
        super.onPostExecute(result);


    }

    public void kill() {
        if (process != null) {
            process.destroy();
        }
        if (guestSession != null) {
            guestSession.close();
        }
    }

    @Override
    protected void onProgressUpdate(String... values) {
        super.onProgressUpdate(values);
        activity.runOnUiThread(() -> {

            String rem = "";
            Matcher matcher = Pattern.compile("\\d+/\\d+").matcher(values[0]);
            Matcher matcher2 = Pattern.compile("\\d+ hours").matcher(values[0]);
            Matcher matcher3 = Pattern.compile("\\d+ minutes").matcher(values[0]);
            Matcher matcher4 = Pattern.compile("\\d+ seconds").matcher(values[0]);
            if (matcher2.find()) {
                rem = rem + matcher2.group(0) + " ";
            }
            if (matcher3.find()) {
                rem = rem + matcher3.group(0) + " ";
            }
            if (matcher4.find()) {
                rem = rem + matcher4.group(0) + " ";
            }
            int pr = 0;
            int all = 0;
            if (matcher.find()) {
                pr = Integer.parseInt(Objects.requireNonNull(matcher.group(0)).split("/")[0]);
                all = Integer.parseInt(Objects.requireNonNull(matcher.group(0)).split("/")[1]);
                progress.setText("Progress: " + matcher.group(0) + " k/s");
            }
            if (rem.length() != 0) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    CreateNotification(matcher.group(0), rem, pr, all);
                }
                time.setText("Time remaining: " + rem);
            }

        });
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    public void CreateNotification(String key, String left, int prog, int max) {
        Intent intent = new Intent(context, MainActivity.class);
        PendingIntent contentIntent = PendingIntent.getActivity(context, 0, intent, Utils.setPendingIntentFlag());
        String CHANNEL_ID = "BruteForce";
        NotificationChannel notificationChannel = new NotificationChannel(CHANNEL_ID, "BruteForce", NotificationManager.IMPORTANCE_LOW);

        NotificationCompat.Builder b = new NotificationCompat.Builder(context);

        b.setAutoCancel(true)
                .setDefaults(Notification.DEFAULT_ALL)
                .setWhen(System.currentTimeMillis())
                .setSmallIcon(R.drawable.bolt)
                .setTicker("Brute")
                .setContentTitle(left)
                .setContentText(key)
                .setChannelId(CHANNEL_ID)
                .setDefaults(Notification.DEFAULT_LIGHTS | Notification.DEFAULT_SOUND)
                .setContentIntent(contentIntent)
                .setProgress(max, prog, false)
                .setContentInfo("Info");


        NotificationManager notificationManager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        notificationManager.createNotificationChannel(notificationChannel);
        notificationManager.notify(id, b.build());
    }


}
