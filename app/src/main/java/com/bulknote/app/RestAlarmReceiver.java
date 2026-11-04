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

public class RestAlarmReceiver extends BroadcastReceiver {
    public static final String CHANNEL_ID = "rest_timer";
    public static final int NOTIFICATION_ID = 4101;

    @Override public void onReceive(Context context, Intent intent) {
        NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "운동 휴식 타이머", NotificationManager.IMPORTANCE_DEFAULT);
            channel.setDescription("세트 사이 휴식 시간이 끝나면 알려줍니다.");
            channel.enableVibration(true);
            channel.setVibrationPattern(new long[]{0, 120, 70, 120});
            nm.createNotificationChannel(channel);
        }
        Intent open = new Intent(context, MainActivity.class);
        PendingIntent content = PendingIntent.getActivity(context, 4102, open,
            PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0));
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
            ? new Notification.Builder(context, CHANNEL_ID) : new Notification.Builder(context);
        Notification notification = builder
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("휴식 종료")
            .setContentText("다음 세트를 시작할 시간입니다.")
            .setContentIntent(content)
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_ALARM)
            .build();
        nm.notify(NOTIFICATION_ID, notification);
    }

    public static void schedule(Context context, long endAtMillis) {
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
    }
}