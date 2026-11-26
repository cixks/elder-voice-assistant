package cn.family.littlecompanion;

import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.IBinder;
import android.provider.Settings;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

public final class OverlayService extends Service {
    static final String ACTION_STOP = "cn.family.littlecompanion.STOP_OVERLAY";
    private WindowManager manager;
    private TextView ball;
    private WindowManager.LayoutParams params;
    private cn.family.littlecompanion.Settings settings;

    @Override public void onCreate() { super.onCreate(); settings = new cn.family.littlecompanion.Settings(this); }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) { stopSelf(); return START_NOT_STICKY; }
        if (!Settings.canDrawOverlays(this)) { stopSelf(); return START_NOT_STICKY; }
        PendingIntent stop = PendingIntent.getService(this, 1, new Intent(this, OverlayService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        android.app.Notification notification = Notifications.make(this, "floating", "悬浮球", "点击悬浮球说话；待机时不录音", stop);
        if (Build.VERSION.SDK_INT >= 34) startForeground(10, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        else startForeground(10, notification);
        if (ball != null) { manager.removeView(ball); ball = null; }
        showBall();
        return START_NOT_STICKY;
    }

    private void showBall() {
        manager = (WindowManager) getSystemService(WINDOW_SERVICE);
        int size = Ui.dp(this, settings.number("ball_size", 64));
        params = new WindowManager.LayoutParams(size, size, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.LEFT;
        params.x = settings.number("ball_x", 0);
        params.y = settings.number("ball_y", Ui.dp(this, 240));
        clamp();
        ball = new TextView(this);
        ball.setText("问"); ball.setTextSize(25); ball.setTextColor(Color.WHITE); ball.setGravity(Gravity.CENTER);
        ball.setBackground(Ui.background(Ui.GREEN, 100, this));
        ball.setContentDescription("点一下说话；按住可以移动");
        ball.setElevation(Ui.dp(this, 8));
        ball.setOnClickListener(view -> {
            try { startActivity(new Intent(this, AssistantActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP)); }
            catch (RuntimeException ignored) { android.widget.Toast.makeText(this, "请打开小伴后再试", android.widget.Toast.LENGTH_SHORT).show(); }
        });
        ball.setOnTouchListener(new View.OnTouchListener() {
            float startX, startY; int x, y; boolean moved;
            @Override public boolean onTouch(View view, MotionEvent event) {
                if (event.getAction() == MotionEvent.ACTION_DOWN) {
                    startX = event.getRawX(); startY = event.getRawY(); x = params.x; y = params.y; moved = false; return true;
                }
                if (event.getAction() == MotionEvent.ACTION_MOVE) {
                    float dx = event.getRawX() - startX, dy = event.getRawY() - startY;
                    if (Math.abs(dx) + Math.abs(dy) > Ui.dp(OverlayService.this, 12)) moved = true;
                    if (moved) { params.x = x + (int) dx; params.y = y + (int) dy; clamp(); manager.updateViewLayout(ball, params); }
                    return true;
                }
                if (event.getAction() == MotionEvent.ACTION_UP) {
                    if (!moved) view.performClick();
                    else { settings.putNumber("ball_x", params.x); settings.putNumber("ball_y", params.y); }
                    return true;
                }
                return event.getAction() == MotionEvent.ACTION_CANCEL;
            }
        });
        try { manager.addView(ball, params); }
        catch (RuntimeException ignored) { ball = null; stopSelf(); }
    }

    @SuppressWarnings("deprecation") private void clamp() {
        android.util.DisplayMetrics metrics = new android.util.DisplayMetrics();
        manager.getDefaultDisplay().getMetrics(metrics);
        params.x = Math.max(0, Math.min(params.x, metrics.widthPixels - params.width));
        params.y = Math.max(Ui.dp(this, 28), Math.min(params.y, metrics.heightPixels - params.height - Ui.dp(this, 40)));
    }

    @Override public void onConfigurationChanged(android.content.res.Configuration config) {
        super.onConfigurationChanged(config);
        if (ball != null) { clamp(); manager.updateViewLayout(ball, params); }
    }
    @Override public void onDestroy() {
        if (ball != null) { manager.removeView(ball); ball = null; }
        super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent) { return null; }
}
