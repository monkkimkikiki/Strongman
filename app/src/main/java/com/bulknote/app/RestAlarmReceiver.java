package com.bulknote.app;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;

public class RestAlarmReceiver extends BroadcastReceiver {
    // v2: use a fresh channel so the stronger vibration settings apply to existing installs.
    public static final String CHANNEL_ID = "rest_timer_v2";
    public static final int NOTIFICATION_ID = 4101;

    private static void ensureChannel(Context context) {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "운동 휴식 타이머", NotificationManager.IMPORTANCE_HIGH);
            channel.setDescription("세트 사이 남은 휴식 시간과 종료 알림을 표시합니다.");
            channel.enableVibration(true);
            channel.setVibrationPattern(new long[]{0, 450, 140, 450, 140, 650});
            nm.createNotificationChannel(channel);
        }
    }

    private static PendingIntent contentIntent(Context context) {
        Intent open = new Intent(context, MainActivity.class);
        return PendingIntent.getActivity(context, 4102, open,
            PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0));
    }

    private static Notification.Builder builder(Context context) {
        ensureChannel(context);
        return Build.VERSION.SDK_INT >= 26
            ? new Notification.Builder(context, CHANNEL_ID) : new Notification.Builder(context);
    }

    /** Show a persistent status/notification-shade countdown. Android updates the chronometer itself. */
    public static void showCountdown(Context context, long endAtMillis) {
        NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null || endAtMillis <= System.currentTimeMillis()) return;
        Notification.Builder b = builder(context)
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
        Notification notification = builder(context)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("휴식 종료")
            .setContentText("다음 세트를 시작할 시간입니다.")
            .setContentIntent(contentIntent(context))
            .setAutoCancel(true)
            .setOngoing(false)
            .setOnlyAlertOnce(false)
            .setCategory(Notification.CATEGORY_ALARM)
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
        if (am != null) am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, endAtMillis, pi);
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
