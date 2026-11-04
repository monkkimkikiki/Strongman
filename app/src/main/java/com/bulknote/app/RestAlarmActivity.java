package com.bulknote.app;

import android.app.Activity;
import android.app.NotificationManager;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

public class RestAlarmActivity extends Activity {
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON |
            WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON |
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED |
            WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
        );
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        }
        renderAlarm();
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        renderAlarm();
    }

    private void renderAlarm() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setPadding(36, 48, 36, 48);
        root.setBackgroundColor(Color.rgb(12, 18, 24));

        TextView icon = new TextView(this);
        icon.setText("⏱");
        icon.setTextSize(64);
        icon.setGravity(Gravity.CENTER);
        root.addView(icon, new LinearLayout.LayoutParams(-1, -2));

        TextView title = new TextView(this);
        title.setText("휴식 종료");
        title.setTextColor(Color.WHITE);
        title.setTextSize(34);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(-1, -2);
        titleLp.topMargin = 18;
        root.addView(title, titleLp);

        TextView desc = new TextView(this);
        desc.setText("다음 세트를 시작할 시간입니다.");
        desc.setTextColor(Color.LTGRAY);
        desc.setTextSize(17);
        desc.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams descLp = new LinearLayout.LayoutParams(-1, -2);
        descLp.topMargin = 12;
        root.addView(desc, descLp);

        Button go = new Button(this);
        go.setText("운동으로 돌아가기");
        LinearLayout.LayoutParams goLp = new LinearLayout.LayoutParams(-1, -2);
        goLp.topMargin = 42;
        root.addView(go, goLp);
        go.setOnClickListener(v -> {
            dismissAlarm();
            Intent app = new Intent(this, MainActivity.class);
            app.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(app);
            finish();
        });

        Button close = new Button(this);
        close.setText("알람 닫기");
        LinearLayout.LayoutParams closeLp = new LinearLayout.LayoutParams(-1, -2);
        closeLp.topMargin = 12;
        root.addView(close, closeLp);
        close.setOnClickListener(v -> {
            dismissAlarm();
            finish();
        });

        setContentView(root);
    }

    private void dismissAlarm() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) nm.cancel(RestAlarmReceiver.NOTIFICATION_ID);
    }

    @Override public void onBackPressed() {
        // Back only closes the full-screen view; the persistent notification remains
        // until the user chooses one of the explicit alarm actions.
        super.onBackPressed();
    }
}
