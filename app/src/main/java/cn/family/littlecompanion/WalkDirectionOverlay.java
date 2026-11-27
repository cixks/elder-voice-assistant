package cn.family.littlecompanion;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.hardware.GeomagneticField;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.Surface;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.amap.api.navi.model.AMapNaviLocation;

/** Small noninteractive top overlay. Sensors run only during navigation with the screen on. */
final class WalkDirectionOverlay implements SensorEventListener {
    private final Context context;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final SensorManager sensors;
    private final WindowManager windows;
    private final PowerManager power;
    private final Sensor rotation;
    private final float[] matrix = new float[9], remapped = new float[9];
    private LinearLayout panel;
    private TextView distance, status;
    private Arrow arrow;
    private boolean closed, listening, headingValid, screenOn;
    private long headingAt, routeAt;
    private float heading, declination, angle;
    private boolean hasAngle;
    private int panelScreenWidth;
    private WalkRouteDirection route;
    private String waiting = "正在等待路线和定位";

    WalkDirectionOverlay(Context context) {
        this.context = context;
        sensors = context.getSystemService(SensorManager.class);
        windows = context.getSystemService(WindowManager.class);
        power = context.getSystemService(PowerManager.class);
        rotation = sensors == null ? null : sensors.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
        tick.run();
    }

    void update(WalkRouteDirection route, AMapNaviLocation location) {
        if (closed) return;
        this.route = route;
        routeAt = SystemClock.elapsedRealtime();
        if (route != null && location != null && location.getCoord() != null) {
            declination = new GeomagneticField((float) location.getCoord().getLatitude(),
                    (float) location.getCoord().getLongitude(), (float) location.getAltitude(),
                    System.currentTimeMillis()).getDeclination();
        }
        waiting = "定位或路线方向确认中";
        render();
    }

    void waiting(String message) {
        if (closed) return;
        route = null;
        waiting = message;
        render();
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (closed) return;
            screenOn = power == null || power.isInteractive();
            boolean permitted = android.provider.Settings.canDrawOverlays(context);
            if (screenOn && permitted) {
                if (panel != null && panelScreenWidth != context.getResources().getDisplayMetrics().widthPixels) hide();
                show();
                if (!listening && rotation != null && sensors != null) {
                    listening = sensors.registerListener(WalkDirectionOverlay.this, rotation,
                            SensorManager.SENSOR_DELAY_UI, handler);
                }
            } else {
                stopSensor();
                hide();
            }
            render();
            handler.postDelayed(this, 1000);
        }
    };

    private void show() {
        if (panel != null || windows == null) return;
        LinearLayout root = new LinearLayout(context);
        root.setGravity(Gravity.CENTER_VERTICAL);
        int pad = Ui.dp(context, 10);
        root.setPadding(pad, pad, pad, pad);
        root.setBackground(Ui.background(Color.rgb(248, 255, 251), 18, context));
        root.setElevation(Ui.dp(context, 6));
        arrow = new Arrow(context);
        root.addView(arrow, new LinearLayout.LayoutParams(Ui.dp(context, 68), Ui.dp(context, 68)));
        LinearLayout labels = new LinearLayout(context);
        labels.setOrientation(LinearLayout.VERTICAL);
        distance = Ui.text(context, "本方向距离确认中", 22);
        status = Ui.text(context, "请稍候", 14);
        labels.addView(distance);
        labels.addView(status);
        root.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
        int width = Math.min(Ui.dp(context, 360), context.getResources().getDisplayMetrics().widthPixels - Ui.dp(context, 24));
        panelScreenWidth = context.getResources().getDisplayMetrics().widthPixels;
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(width, -2,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        params.y = Ui.dp(context, 12);
        if (Build.VERSION.SDK_INT >= 28) params.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_NEVER;
        try { windows.addView(root, params); panel = root; }
        catch (RuntimeException ignored) { panel = null; }
    }

    @Override public void onSensorChanged(SensorEvent event) {
        if (closed || !screenOn || event.sensor.getType() != Sensor.TYPE_ROTATION_VECTOR) return;
        headingAt = SystemClock.elapsedRealtime();
        headingValid = event.accuracy >= SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM;
        // Rotation-vector heading error (radians), when supplied by the device.
        if (event.values.length >= 5 && (event.values[4] < 0 || event.values[4] > Math.toRadians(25))) headingValid = false;
        SensorManager.getRotationMatrixFromVector(matrix, event.values);
        int display = windows == null ? Surface.ROTATION_0 : windows.getDefaultDisplay().getRotation();
        int x = SensorManager.AXIS_X, y = SensorManager.AXIS_Y;
        if (display == Surface.ROTATION_90) { x = SensorManager.AXIS_Y; y = SensorManager.AXIS_MINUS_X; }
        else if (display == Surface.ROTATION_180) { x = SensorManager.AXIS_MINUS_X; y = SensorManager.AXIS_MINUS_Y; }
        else if (display == Surface.ROTATION_270) { x = SensorManager.AXIS_MINUS_Y; y = SensorManager.AXIS_X; }
        if (!SensorManager.remapCoordinateSystem(matrix, x, y, remapped)) headingValid = false;
        // Project the screen's top edge onto the ground, not the camera-facing direction.
        if (Math.hypot(remapped[1], remapped[4]) < 0.35) headingValid = false;
        heading = (float) Math.toDegrees(Math.atan2(remapped[1], remapped[4]));
        render();
    }

    @Override public void onAccuracyChanged(Sensor sensor, int accuracy) {
        if (accuracy < SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM) { headingValid = false; render(); }
    }

    private void render() {
        if (panel == null) return;
        long now = SystemClock.elapsedRealtime();
        boolean routeReady = route != null && now - routeAt <= 10000;
        boolean ready = routeReady && headingValid && now - headingAt <= 3000;
        distance.setText(routeReady ? "本方向约 " + AmapNavigator.formatDistance(route.meters) : "本方向距离确认中");
        if (ready) {
            float target = WalkRouteDirection.delta(route.bearing - (heading + declination));
            angle = hasAngle ? angle + 0.3f * WalkRouteDirection.delta(target - angle) : target;
            hasAngle = true;
            arrow.angle = angle;
            status.setText("至前方转弯 / 本段终点 · 注意路况");
        } else {
            hasAngle = false;
            if (!routeReady) status.setText(route != null ? "定位更新中，请稍候" : waiting);
            else if (rotation == null || !listening) status.setText("方向传感器不可用，请听语音指引");
            else status.setText("方向校准中：放平手机，远离磁铁");
        }
        arrow.ready = ready;
        arrow.setContentDescription(ready ? "接下来路线方向" : "方向暂不可用");
        arrow.invalidate();
    }

    private void stopSensor() {
        if (sensors != null && listening) sensors.unregisterListener(this);
        listening = false;
        headingValid = false;
    }

    private void hide() {
        if (panel != null && windows != null) {
            try { windows.removeView(panel); } catch (RuntimeException ignored) { }
        }
        panel = null;
    }

    void close() {
        closed = true;
        handler.removeCallbacksAndMessages(null);
        stopSensor();
        hide();
        route = null;
    }

    private static final class Arrow extends View {
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Path path = new Path();
        float angle;
        boolean ready;
        Arrow(Context context) { super(context); }
        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float size = Math.min(getWidth(), getHeight());
            canvas.save();
            canvas.translate(getWidth() / 2f, getHeight() / 2f);
            paint.setColor(ready ? Ui.GREEN : Color.GRAY);
            if (ready) {
                canvas.rotate(angle);
                path.reset();
                path.moveTo(0, -size * 0.4f);
                path.lineTo(size * 0.30f, -size * 0.04f);
                path.lineTo(size * 0.12f, -size * 0.04f);
                path.lineTo(size * 0.12f, size * 0.36f);
                path.lineTo(-size * 0.12f, size * 0.36f);
                path.lineTo(-size * 0.12f, -size * 0.04f);
                path.lineTo(-size * 0.30f, -size * 0.04f);
                path.close();
                canvas.drawPath(path, paint);
            } else {
                paint.setTextSize(size * 0.6f);
                paint.setTextAlign(Paint.Align.CENTER);
                canvas.drawText("?", 0, size * 0.20f, paint);
            }
            canvas.restore();
        }
    }
}
