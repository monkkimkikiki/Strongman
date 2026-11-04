package com.bulknote.app;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;

public class RestAlarmReceiver extends BroadcastReceiver {
    public static final String STATUS_CHANNEL_ID = "rest_timer_status_v4";
    public static final String ALARM_CHANNEL_ID = "rest_timer_alarm_v4";
    public static final int NOTIFICATION_ID = 4101;

    private static void ensureChannels(Context context) {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;

        // Rest start: silent ongoing countdown only.
        NotificationChannel status = new NotificationChannel(
            STATUS_CHANNEL_ID, "휴식 시간 표시", NotificationManager.IMPORTANCE_LOW);
        status.setDescription("휴식 중 남은 시간만 조용히 표시합니다.");
        status.setSound(null, null);
        status.enableVibration(false);
        nm.createNotificationChannel(status);

        // Rest end: alarm sound. Vibration is triggered manually for a stronger pattern.
        NotificationChannel alarm = new NotificationChannel(
            ALARM_CHANNEL_ID, "휴식 종료 알람", NotificationManager.IMPORTANCE_HIGH);
        alarm.setDescription("휴식 시간이 끝나면 알람음과 강한 진동으로 알려줍니다.");
        Uri alarmSound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
        AudioAttributes attrs = new AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build();
        alarm.setSound(alarmSound, attrs);
        alarm.enableVibration(false);
        nm.createNotificationChannel(alarm);
    }

    private static PendingIntent contentIntent(Context context) {
        Intent open = new Intent(context, MainActivity.class);
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return PendingIntent.getActivity(context, 4102, open,
            PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0));
    }

    private static PendingIntent alarmScreenIntent(Context context) {
        Intent open = new Intent(context, RestAlarmActivity.class);
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return PendingIntent.getActivity(context, 4103, open,
            PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0));
    }

    private static Notification.Builder builder(Context context, boolean alarm) {
        ensureChannels(context);
        if (Build.VERSION.SDK_INT >= 26)
            return new Notification.Builder(context, alarm ? ALARM_CHANNEL_ID : STATUS_CHANNEL_ID);
        Notification.Builder b = new Notification.Builder(context);
        if (alarm) {
            b.setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM));
            b.setPriority(Notification.PRIORITY_HIGH);
        } else {
            b.setSound(null);
            b.setVibrate(new long[]{0});
            b.setPriority(Notification.PRIORITY_LOW);
        }
        return b;
    }

    /** Persistent, silent countdown in the notification shade. */
    public static void showCountdown(Context context, long endAtMillis) {
        NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null || endAtMillis <= System.currentTimeMillis()) return;
        Notification.Builder b = builder(context, false)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("세트 휴식 중")
            .setContentText("다음 세트까지 남은 시간")
            .setContentIntent(contentIntent(context))
            .setCategory(Notification.CATEGORY_PROGRESS)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setWhen(endAtMillis)
            .setUsesChronometer(true);
        if (Build.VERSION.SDK_INT >= 24) b.setChronometerCountDown(true);
        nm.notify(NOTIFICATION_ID, b.build());
    }

    @Override public void onReceive(Context context, Intent intent) {
        NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        Notification notification = builder(context, true)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("휴식 종료")
            .setContentText("다음 세트를 시작할 시간입니다.")
            .setContentIntent(contentIntent(context))
            .setAutoCancel(true)
            .setOngoing(false)
            .setOnlyAlertOnce(false)
            .setCategory(Notification.CATEGORY_ALARM)
            .setFullScreenIntent(alarmScreenIntent(context), true)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setPriority(Notification.PRIORITY_MAX)
            .build();
        nm.notify(NOTIFICATION_ID, notification);
        vibrateStrong(context);
    }

    private static void vibrateStrong(Context context) {
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                VibratorManager manager = (VibratorManager) context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
                if (manager != null) {
                    Vibrator v = manager.getDefaultVibrator();
                    if (v != null && v.hasVibrator())
                        v.vibrate(VibrationEffect.createWaveform(new long[]{0, 450, 140, 450, 140, 650}, -1));
                }
            } else {
                Vibrator v = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
                if (v != null && v.hasVibrator()) {
                    if (Build.VERSION.SDK_INT >= 26)
                        v.vibrate(VibrationEffect.createWaveform(new long[]{0, 450, 140, 450, 140, 650}, -1));
                    else v.vibrate(new long[]{0, 450, 140, 450, 140, 650}, -1);
                }
            }
        } catch (Exception ignored) {}
    }

    public static void schedule(Context context, long endAtMillis) {
        if (endAtMillis <= System.currentTimeMillis()) return;
        showCountdown(context, endAtMillis);
        Intent intent = new Intent(context, RestAlarmReceiver.class);
        PendingIntent pi = PendingIntent.getBroadcast(context, NOTIFICATION_ID, intent,
            PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0));
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (am != null) {
            if (Build.VERSION.SDK_INT >= 31 && am.canScheduleExactAlarms()) {
                // Alarm-clock alarms are exact and are allowed to wake the device from Doze.
                am.setAlarmClock(new AlarmManager.AlarmClockInfo(endAtMillis, contentIntent(context)), pi);
            } else if (Build.VERSION.SDK_INT < 31) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, endAtMillis, pi);
            } else {
                // The app asks the user to grant exact-alarm access before relying on this fallback.
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, endAtMillis, pi);
            }
        }
    }

    public static void cancel(Context context) {
        Intent intent = new Intent(context, RestAlarmReceiver.class);
        PendingIntent pi = PendingIntent.getBroadcast(context, NOTIFICATION_ID, intent,
            PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0));
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (am != null) am.cancel(pi);
        NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.cancel(NOTIFICATION_ID);
    }
}
