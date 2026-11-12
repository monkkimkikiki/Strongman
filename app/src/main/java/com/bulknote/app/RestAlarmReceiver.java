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
    public static final String WORKOUT_CHANNEL_ID = "workout_session_status_v2";
    public static final String ALARM_CHANNEL_ID = "rest_timer_alarm_v6";
    public static final String WEEKLY_CHANNEL_ID = "weekly_review_v1";
    public static final String ACTION_WEEKLY_REVIEW = "com.bulknote.app.WEEKLY_REVIEW";
    private static final int WEEKLY_NOTIFICATION_ID = 7301;
    public static final int WORKOUT_NOTIFICATION_ID = 4100;
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

        NotificationChannel workout = new NotificationChannel(
            WORKOUT_CHANNEL_ID, "운동 진행 상태", NotificationManager.IMPORTANCE_LOW);
        workout.setDescription("운동 세트를 시작하면 운동을 종료할 때까지 상태를 표시합니다.");
        workout.setSound(null, null);
        workout.enableVibration(false);
        nm.createNotificationChannel(workout);

        // Rest end: alarm sound. Vibration is triggered manually for a stronger pattern.
        NotificationChannel alarm = new NotificationChannel(
            ALARM_CHANNEL_ID, "운동 및 휴식 종료 알람", NotificationManager.IMPORTANCE_HIGH);
        alarm.setDescription("휴식 시간이 끝나면 알람음과 강한 진동으로 알려줍니다.");
        Uri alarmSound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
        AudioAttributes attrs = new AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build();
        alarm.setSound(alarmSound, attrs);
        alarm.enableVibration(false);
        nm.createNotificationChannel(alarm);

        NotificationChannel weekly = new NotificationChannel(
            WEEKLY_CHANNEL_ID, "주간 총평 알림", NotificationManager.IMPORTANCE_DEFAULT);
        weekly.setDescription("일요일 밤에 한 주 운동 총평이 준비되면 알려줍니다.");
        nm.createNotificationChannel(weekly);
    }

    /** 매주 일요일 22시에 주간 총평 알림을 띄우도록 예약한다. 앱을 열 때마다 다시 예약된다. */
    public static void scheduleWeeklyReview(Context context) {
        try {
            AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
            if (am == null) return;
            Intent intent = new Intent(context, RestAlarmReceiver.class);
            intent.setAction(ACTION_WEEKLY_REVIEW);
            PendingIntent pi = PendingIntent.getBroadcast(context, 7302, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0));
            java.util.Calendar c = java.util.Calendar.getInstance();
            c.set(java.util.Calendar.HOUR_OF_DAY, 22); c.set(java.util.Calendar.MINUTE, 0);
            c.set(java.util.Calendar.SECOND, 0); c.set(java.util.Calendar.MILLISECOND, 0);
            int shift = (java.util.Calendar.SUNDAY - c.get(java.util.Calendar.DAY_OF_WEEK) + 7) % 7;
            c.add(java.util.Calendar.DAY_OF_YEAR, shift);
            if (c.getTimeInMillis() <= System.currentTimeMillis()) c.add(java.util.Calendar.DAY_OF_YEAR, 7);
            am.setInexactRepeating(AlarmManager.RTC_WAKEUP, c.getTimeInMillis(), AlarmManager.INTERVAL_DAY * 7, pi);
        } catch (Exception ignored) {}
    }

    private static void showWeeklyReview(Context context) {
        ensureChannels(context);
        NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        Intent open = new Intent(context, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        open.putExtra("open_weekly_review", true);
        PendingIntent pi = PendingIntent.getActivity(context, 7303, open,
            PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0));
        Notification n = (Build.VERSION.SDK_INT >= 26
            ? new Notification.Builder(context, WEEKLY_CHANNEL_ID)
            : new Notification.Builder(context))
            .setSmallIcon(android.R.drawable.ic_menu_my_calendar)
            .setContentTitle("주간 총평이 준비되오")
            .setContentText("이번 주 운동을 돌아보시오. 눌러서 확인하시오.")
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build();
        nm.notify(WEEKLY_NOTIFICATION_ID, n);
    }

    private static PendingIntent contentIntent(Context context) {
        Intent open = new Intent(context, MainActivity.class);
        open.putExtra("open_workout_next_set", true);
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return PendingIntent.getActivity(context, 4102, open,
            PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0));
    }

    private static PendingIntent alarmScreenIntent(Context context, boolean exerciseTimer) {
        Intent open = new Intent(context, RestAlarmActivity.class);
        open.putExtra("exercise_timer", exerciseTimer);
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
        ensureChannels(context);
        Notification.Builder b = (Build.VERSION.SDK_INT >= 26
            ? new Notification.Builder(context, STATUS_CHANNEL_ID)
            : builder(context, false))
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

    /** Persistent, silent workout status. It is removed only when the workout is explicitly ended. */
    public static void showWorkoutStatus(Context context, String title, String detail) {
        NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        ensureChannels(context);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
            ? new Notification.Builder(context, WORKOUT_CHANNEL_ID)
            : builder(context, false);
        b.setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(title == null || title.trim().isEmpty() ? "운동 진행 중" : title)
            .setContentText(detail == null || detail.trim().isEmpty() ? "운동을 종료하면 사라집니다." : detail)
            .setContentIntent(contentIntent(context))
            .setCategory(Notification.CATEGORY_PROGRESS)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setAutoCancel(false)
            .setOnlyAlertOnce(true)
            .setShowWhen(false);
        nm.notify(WORKOUT_NOTIFICATION_ID, b.build());
    }

    public static void stopWorkoutStatus(Context context) {
        NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.cancel(WORKOUT_NOTIFICATION_ID);
    }

    @Override public void onReceive(Context context, Intent intent) {
        if (intent != null && ACTION_WEEKLY_REVIEW.equals(intent.getAction())) { showWeeklyReview(context); return; }
        NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        boolean exerciseTimer = intent != null && intent.getBooleanExtra("exercise_timer", false);
        Notification notification = builder(context, true)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(exerciseTimer ? "운동 시간 완료" : "휴식 종료")
            .setContentText(exerciseTimer ? "설정한 운동 시간이 끝났습니다." : "다음 세트를 시작할 시간입니다.")
            .setContentIntent(alarmScreenIntent(context, exerciseTimer))
            // Keep the finished-rest alert visible until the user explicitly dismisses it.
            .setAutoCancel(false)
            .setOngoing(true)
            .setOnlyAlertOnce(false)
            .setStyle(new Notification.BigTextStyle().bigText(exerciseTimer
                ? "설정한 운동 시간이 끝났습니다. 알람을 확인하고 운동으로 돌아가세요."
                : "휴식 시간이 끝났습니다. 다음 세트를 시작할 시간입니다."))
            .setCategory(Notification.CATEGORY_ALARM)
            .setFullScreenIntent(alarmScreenIntent(context, exerciseTimer), true)
            .setTicker(exerciseTimer ? "운동 시간 완료" : "휴식 종료")
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
        schedule(context, endAtMillis, false);
    }

    public static void schedule(Context context, long endAtMillis, boolean exerciseTimer) {
        if (endAtMillis <= System.currentTimeMillis()) return;
        if (!exerciseTimer) showCountdown(context, endAtMillis);
        Intent intent = new Intent(context, RestAlarmReceiver.class);
        intent.putExtra("exercise_timer", exerciseTimer);
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

    /** Cancels only the scheduled alarm, preserving an alert that may already have fired. */
    public static void cancelScheduled(Context context) {
        Intent intent = new Intent(context, RestAlarmReceiver.class);
        PendingIntent pi = PendingIntent.getBroadcast(context, NOTIFICATION_ID, intent,
            PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0));
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (am != null) am.cancel(pi);
    }

    public static void cancel(Context context) {
        cancelScheduled(context);
        NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.cancel(NOTIFICATION_ID);
    }
}
