package projekt.substratum.services;

import android.app.AlarmManager;
import android.app.IntentService;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.WallpaperManager;
import android.app.admin.DevicePolicyManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Environment;
import android.os.PowerManager;
import android.support.v4.app.NotificationCompat;
import android.support.v7.preference.PreferenceManager;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.List;

import projekt.substratum.ProfileErrorInfoActivity;
import projekt.substratum.R;
import projekt.substratum.config.MasqueradeService;
import projekt.substratum.config.References;
import projekt.substratum.util.ReadOverlaysFile;

import static projekt.substratum.fragments.ProfileFragment.DAY_PROFILE;
import static projekt.substratum.fragments.ProfileFragment.DAY_PROFILE_HOUR;
import static projekt.substratum.fragments.ProfileFragment.DAY_PROFILE_MINUTE;
import static projekt.substratum.fragments.ProfileFragment.NIGHT;
import static projekt.substratum.fragments.ProfileFragment.NIGHT_PROFILE;
import static projekt.substratum.fragments.ProfileFragment.NIGHT_PROFILE_HOUR;
import static projekt.substratum.fragments.ProfileFragment.NIGHT_PROFILE_MINUTE;
import static projekt.substratum.fragments.ProfileFragment.SCHEDULED_PROFILE_CURRENT_PROFILE;
import static projekt.substratum.fragments.ProfileFragment.SCHEDULED_PROFILE_TYPE_EXTRA;

public class ScheduledProfileService extends IntentService {

    private final int NOTIFICATION_ID = 1023;
    private Context mContext;
    private SharedPreferences prefs;
    private String extra;
    private NotificationManager mNotifyManager;
    private NotificationCompat.Builder mBuilder;

    public ScheduledProfileService() {
        super("ScheduledProfileService");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        mContext = this;
        prefs = PreferenceManager.getDefaultSharedPreferences(mContext);
        mNotifyManager = (NotificationManager) mContext.getSystemService(
                Context.NOTIFICATION_SERVICE);
        mBuilder = new NotificationCompat.Builder(mContext);

        return super.onStartCommand(intent, flags, startId);
    }

    @Override
    public void onHandleIntent(Intent intent) {
        extra = intent.getStringExtra(SCHEDULED_PROFILE_TYPE_EXTRA);

        if (extra != null && !extra.isEmpty()) {
            String title_parse = String.format(getString(R.string.profile_notification_title), extra);
            mBuilder.setContentTitle(title_parse)
                    .setSmallIcon(R.drawable.ic_substratum)
                    .setPriority(Notification.PRIORITY_DEFAULT)
                    .setContentText(getString(R.string.profile_pending_notification))
                    .setOngoing(true);
            mNotifyManager.notify(NOTIFICATION_ID, mBuilder.build());

            PowerManager powerManager = (PowerManager) getSystemService(POWER_SERVICE);
            while (powerManager.isInteractive()) {
                try {
                    Thread.sleep(3000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            applyScheduledProfile(intent);
        } else {
            mBuilder.setContentTitle(getString(R.string.scheduled_night_profile))
                    .setSmallIcon(R.drawable.ic_substratum)
                    .setPriority(Notification.PRIORITY_DEFAULT)
                    .setContentText(getString(R.string.profile_failed_notification));
            mNotifyManager.notify(NOTIFICATION_ID, mBuilder.build());
            ScheduledProfileReceiver.completeWakefulIntent(intent);
        }
    }

    private int getDeviceEncryptionStatus() {
        // 0: ENCRYPTION_STATUS_UNSUPPORTED
        // 1: ENCRYPTION_STATUS_INACTIVE
        // 2: ENCRYPTION_STATUS_ACTIVATING
        // 3: ENCRYPTION_STATUS_ACTIVE_DEFAULT_KEY
        // 4: ENCRYPTION_STATUS_ACTIVE
        // 5: ENCRYPTION_STATUS_ACTIVE_PER_USER
        int status = DevicePolicyManager.ENCRYPTION_STATUS_UNSUPPORTED;
        final DevicePolicyManager dpm = (DevicePolicyManager)
                this.getSystemService(Context.DEVICE_POLICY_SERVICE);
        if (dpm != null) {
            status = dpm.getStorageEncryptionStatus();
        }
        return status;
    }

    private void applyScheduledProfile(Intent intent) {
        String type;

        // Cancel ongoing notification
        mNotifyManager.cancel(NOTIFICATION_ID);
        mBuilder.setOngoing(false)
                .setPriority(Notification.PRIORITY_MAX)
                .setSmallIcon(R.drawable.ic_substratum);
        mBuilder.setContentText(getString(R.string.profile_success_notification));

        if (extra.equals(NIGHT)) {
            type = NIGHT_PROFILE;
        } else {
            type = DAY_PROFILE;
        }

        String processed = prefs.getString(type, "");

        File current_overlays = new File(Environment
                .getExternalStorageDirectory().getAbsolutePath() +
                "/.substratum/current_overlays.xml");
        if (current_overlays.exists()) {
            References.delete(Environment
                    .getExternalStorageDirectory().getAbsolutePath() +
                    "/.substratum/current_overlays.xml");
        }
        References.copy("/data/system/overlays.xml",
                Environment.getExternalStorageDirectory().getAbsolutePath() +
                        "/.substratum/current_overlays.xml");

        String[] commandsSystem4 = {Environment.getExternalStorageDirectory()
                .getAbsolutePath() +
                "/.substratum/current_overlays.xml", "4"};

        String[] commandsSystem5 = {Environment
                .getExternalStorageDirectory().getAbsolutePath() +
                "/.substratum/current_overlays.xml", "5"};

        String[] commands = {Environment.getExternalStorageDirectory()
                .getAbsolutePath() +
                "/substratum/profiles/" + processed + ".substratum", "5"};

        List<List<String>> profile = ReadOverlaysFile.withTargetPackage(commands);
        List<String> system = ReadOverlaysFile.main(commandsSystem4);
        system.addAll(ReadOverlaysFile.main(commandsSystem5));
        List<String> to_be_run = new ArrayList<>();

        // Disable everything enabled first
        String to_be_disabled = References.disableAllOverlays();

        // Now process the overlays to be enabled
        List<List<String>> cannot_run_overlays = new ArrayList<>();
        for (int i = 0, size = profile.size(); i < size; i++) {
            String packageName = profile.get(i).get(0);
            String targetPackage = profile.get(i).get(1);
            if (References.isPackageInstalled(mContext, targetPackage)) {
                if (!packageName.endsWith(".icon")) {
                    if (system.contains(packageName)) {
                        to_be_run.add(packageName);
                    } else {
                        cannot_run_overlays.add(profile.get(i));
                    }
                }
            }
        }
        String dialog_message = "";
        for (int i = 0; i < cannot_run_overlays.size(); i++) {
            String packageName = cannot_run_overlays.get(i).get(0);
            String targetPackage = cannot_run_overlays.get(i).get(1);
            String packageDetail = packageName.replace(targetPackage + ".", "");
            String detailSplit = Arrays.toString(packageDetail.split("\\."))
                    .replace("[", "")
                    .replace("]", "")
                    .replace(",", " ");

            if (dialog_message.length() == 0) {
                dialog_message = dialog_message + "\u2022 " + targetPackage + " (" +
                        detailSplit + ")";
            } else {
                dialog_message = dialog_message + "\n" + "\u2022 " + targetPackage
                        + " (" + detailSplit + ")";
            }
        }

        String to_be_run_commands = "";
        for (int i = 0; i < to_be_run.size(); i++) {
            if (!to_be_run.get(i).equals("substratum.helper")) {
                if (i == 0) {
                    to_be_run_commands = References.enableOverlay() + " " +
                            to_be_run.get(i);
                } else {
                    if (i > 0 && to_be_run_commands.length() == 0) {
                        to_be_run_commands = References.enableOverlay() + " " +
                                to_be_run.get(i);
                    } else {
                        to_be_run_commands = to_be_run_commands + " " + to_be_run
                                .get(i);
                    }
                }
            }
        }
        if (to_be_run_commands.length() > 0) {
            to_be_run_commands = to_be_run_commands + " && cp /data/system/overlays.xml " +
                    Environment.getExternalStorageDirectory().getAbsolutePath() +
                    "/.substratum/current_overlays.xml";
            if (to_be_disabled.length() > 0) {
                to_be_run_commands = to_be_disabled + " && " + to_be_run_commands;
            }
        } else if (to_be_disabled.length() > 0) {
            to_be_run_commands = to_be_disabled + to_be_run_commands;
        }

        File theme = new File(Environment.getExternalStorageDirectory().getAbsolutePath() +
                "/substratum/profiles/" + processed + "/");
        if (theme.length() > 0) {
            // Restore the whole backed up profile back to /data/system/theme/
            to_be_run_commands = to_be_run_commands + " && rm -r " + "/data/system/theme";

            // Set up work directory again
            to_be_run_commands = to_be_run_commands + " && cp -rf " +
                    Environment.getExternalStorageDirectory().getAbsolutePath() +
                    "/substratum/profiles/" + processed + "/ /data/system/theme/";
            to_be_run_commands = to_be_run_commands + " && chmod 755 /data/system/theme/";

            // Boot Animation
            File bootanimation = new File(Environment.getExternalStorageDirectory()
                    .getAbsolutePath() + "/substratum/profiles/" + processed +
                    "/bootanimation.zip");
            if (bootanimation.exists()) {
                if (getDeviceEncryptionStatus() <= 1) {
                    to_be_run_commands = to_be_run_commands +
                            " && chmod 644 /data/system/theme/bootanimation.zip";
                } else {
                    File bootanimationBackup = new File(
                            "/system/media/bootanimation-backup.zip");
                    to_be_run_commands = to_be_run_commands +
                            " && mount -o rw,remount /system";
                    if (!bootanimationBackup.exists()) {
                        to_be_run_commands = to_be_run_commands +
                                " && mv -f /system/media/bootanimation.zip" +
                                " /system/media/bootanimation-backup.zip";
                    }
                    to_be_run_commands = to_be_run_commands + " && cp -f " +
                            Environment.getExternalStorageDirectory().getAbsolutePath() +
                            "/substratum/profiles/" + processed + "/bootanimation.zip" +
                            " /system/media/bootanimation.zip";
                    to_be_run_commands = to_be_run_commands +
                            " && chmod 644 /system/media/bootanimation.zip" +
                            " && mount -o ro,remount /system";
                }
            }

            // Fonts
            File fonts = new File(Environment.getExternalStorageDirectory()
                    .getAbsolutePath() +
                    "/substratum/profiles/" + processed + "/fonts/");
            if (fonts.exists()) {
                to_be_run_commands = to_be_run_commands + " && chmod -R 747 /data/system" +
                        "/theme/fonts/";
                to_be_run_commands = to_be_run_commands + " && chmod 775 /data/system" +
                        "/theme" +
                        "/fonts/";
            }

            // Sounds
            File sounds = new File(Environment.getExternalStorageDirectory()
                    .getAbsolutePath() +
                    "/substratum/profiles/" + processed + "/audio/");
            if (sounds.exists()) {
                File alarms = new File(Environment.getExternalStorageDirectory()
                        .getAbsolutePath() +
                        "/substratum/profiles/" + processed + "/audio/alarms/");
                if (alarms.exists()) {
                    to_be_run_commands = to_be_run_commands +
                            " && chmod -R 644 /data/system/theme/audio/alarms/";
                    to_be_run_commands = to_be_run_commands +
                            " && chmod 755 /data/system/theme/audio/alarms/";
                }

                File notifications = new File(Environment.getExternalStorageDirectory()
                        .getAbsolutePath() +
                        "/substratum/profiles/" + processed + "/audio/notifications/");
                if (notifications.exists()) {
                    to_be_run_commands = to_be_run_commands +
                            " && chmod -R 644 /data/system/theme/audio/notifications/";
                    to_be_run_commands = to_be_run_commands +
                            " && chmod 755 /data/system/theme/audio/notifications/";
                }

                File ringtones = new File(Environment.getExternalStorageDirectory()
                        .getAbsolutePath() +

                        "/substratum/profiles/" + processed + "/audio/ringtones/");
                if (ringtones.exists()) {
                    to_be_run_commands = to_be_run_commands +
                            " && chmod -R 644 /data/system/theme/audio/ringtones/";
                    to_be_run_commands = to_be_run_commands +
                            " && chmod 755 /data/system/theme/audio/ringtones/";
                }

                File ui = new File(Environment.getExternalStorageDirectory()
                        .getAbsolutePath() +
                        "/substratum/profiles/" + processed + "/audio/ui/");
                if (ui.exists()) {
                    to_be_run_commands = to_be_run_commands +
                            " && chmod -R 644 /data/system/theme/audio/ui/";
                    to_be_run_commands = to_be_run_commands +
                            " && chmod 755 /data/system/theme/audio/ui/";
                }

                to_be_run_commands = to_be_run_commands +
                        " && chmod 755 /data/system/theme/audio/";
            }

            // Final touch ups
            to_be_run_commands = to_be_run_commands + " && chcon -R " +
                    "u:object_r:system_file:s0" +
                    " " +
                    "/data/system/theme";
            to_be_run_commands = to_be_run_commands + " && setprop sys.refresh_theme 1";

        }
        if (!prefs.getBoolean("systemui_recreate", false)) {
            to_be_run_commands = to_be_run_commands + " && pkill -f com.android.systemui";
        }

        if (cannot_run_overlays.size() == 0) {
            Intent runCommand = MasqueradeService.getMasquerade(mContext);
            runCommand.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
            runCommand.setAction("masquerade.substratum.COMMANDS");
            runCommand.putExtra("om-commands", to_be_run_commands);
            mContext.sendBroadcast(runCommand);
        } else {
            Intent notifyIntent = new Intent(mContext, ProfileErrorInfoActivity.class);
            notifyIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            notifyIntent.putExtra("dialog_message", dialog_message);
            PendingIntent contentIntent = PendingIntent.getActivity(mContext, 0, notifyIntent, 0);

            mBuilder.setContentTitle(getString(R.string.profile_failed_notification))
                    .setContentText(getString(R.string.profile_failed_info_notification))
                    .setContentIntent(contentIntent);
        }

        // Restore wallpapers
        String homeWallPath = Environment.getExternalStorageDirectory().getAbsolutePath() +
                "/substratum/profiles/" + processed + "/" +
                "/wallpaper.png";
        String lockWallPath = Environment.getExternalStorageDirectory().getAbsolutePath() +
                "/substratum/profiles/" + processed + "/" +
                "/wallpaper_lock.png";
        File homeWall = new File(homeWallPath);
        File lockWall = new File(lockWallPath);
        if (homeWall.exists() || lockWall.exists()) {
            WallpaperManager wm = WallpaperManager.getInstance(mContext);
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    wm.setStream(new FileInputStream(homeWallPath), null, true,
                            WallpaperManager.FLAG_SYSTEM);
                    wm.setStream(new FileInputStream(lockWallPath), null, true,
                            WallpaperManager.FLAG_LOCK);
                } else {
                    wm.setStream(new FileInputStream(homeWallPath));
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        Log.d("ScheduledProfile", to_be_run_commands);

        //create new alarm
        boolean isNight = extra.equals(NIGHT);
        int hour = isNight ? prefs.getInt(NIGHT_PROFILE_HOUR, 0) :
                prefs.getInt(DAY_PROFILE_HOUR, 0);
        int minute = isNight ? prefs.getInt(NIGHT_PROFILE_MINUTE, 0) :
                prefs.getInt(DAY_PROFILE_MINUTE, 0);

        Calendar calendar = Calendar.getInstance();
        calendar.setTimeInMillis(System.currentTimeMillis());
        calendar.set(Calendar.HOUR_OF_DAY, hour);
        calendar.set(Calendar.MINUTE, minute);
        calendar.add(Calendar.DAY_OF_YEAR, 1);

        Intent i = new Intent(mContext, ScheduledProfileReceiver.class);
        i.putExtra(SCHEDULED_PROFILE_TYPE_EXTRA, extra);
        PendingIntent newIntent = PendingIntent.getBroadcast(mContext, isNight ? 0 : 1, i,
                PendingIntent.FLAG_UPDATE_CURRENT);

        AlarmManager alarmMgr = (AlarmManager) mContext.getSystemService(Context.ALARM_SERVICE);
        alarmMgr.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, calendar.getTimeInMillis(),
                newIntent);

        //save current profile
        prefs.edit().putString(SCHEDULED_PROFILE_CURRENT_PROFILE, extra).apply();

        //all set, notify user the output
        mNotifyManager.notify(NOTIFICATION_ID, mBuilder.build());
        ScheduledProfileReceiver.completeWakefulIntent(intent);
    }
}
