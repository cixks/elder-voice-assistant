package cn.family.littlecompanion;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import com.amap.api.navi.AMapNavi;
import com.amap.api.navi.SimpleNaviListener;
import com.amap.api.navi.enums.NaviType;
import com.amap.api.navi.model.AMapCalcRouteResult;
import com.amap.api.navi.model.NaviInfo;
import com.amap.api.navi.model.NaviLatLng;

/** Mapless foreground walking navigation powered by Amap Navi SDK + system Chinese TTS. */
public final class WalkNaviService extends Service {
    static final String ACTION_START = "cn.family.littlecompanion.START_WALK_NAVI";
    static final String ACTION_STOP = "cn.family.littlecompanion.STOP_WALK_NAVI";
    static final String ACTION_REPEAT_PROMPT = "cn.family.littlecompanion.REPEAT_WALK_PROMPT";
    static final String EXTRA_DEST_NAME = "dest_name";
    static final String EXTRA_DEST_LAT = "dest_lat";
    static final String EXTRA_DEST_LNG = "dest_lng";
    static final String EXTRA_FROM_LAT = "from_lat";
    static final String EXTRA_FROM_LNG = "from_lng";
    static final String EXTRA_HAS_FROM = "has_from";
    private static final int NOTIFICATION_ID = 12;

    static final class Snapshot {
        final boolean active;
        final String destination, instruction, summary;
        Snapshot(boolean active, String destination, String instruction, String summary) {
            this.active = active;
            this.destination = destination;
            this.instruction = instruction;
            this.summary = summary;
        }
    }

    interface Listener { void onUpdate(Snapshot snapshot); }

    private static volatile Snapshot currentSnapshot = new Snapshot(false, "", "", "");
    private static Listener uiListener;
    static Snapshot snapshot() { return currentSnapshot; }
    static boolean isRunning() { return currentSnapshot.active; }
    static void setUiListener(Listener listener) {
        uiListener = listener;
        if (listener != null) listener.onUpdate(currentSnapshot);
    }

    private final Handler handler = new Handler(Looper.getMainLooper());
    private Settings settings;
    private VoiceOutput voice;
    private AMapNavi navi;
    private PowerManager.WakeLock wakeLock;
    private String destName = "";
    private double destLat, destLng, fromLat, fromLng;
    private boolean hasFrom, routeStarted, destroyed;
    private String lastInstruction = "正在规划步行路线";
    private String lastSummary = "请保持手机定位开启";

    @Override public void onCreate() {
        super.onCreate();
        settings = new Settings(this);
        voice = new VoiceOutput(this, settings, true,
                err -> publish(currentSnapshot.active, destName, lastInstruction, err),
                () -> { if (navi != null) navi.setTtsPlaying(false); });
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm != null) {
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "littlecompanion:walknavi");
            wakeLock.setReferenceCounted(false);
        }
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || ACTION_STOP.equals(intent.getAction())) {
            stopNavigation("步行导航已结束");
            return START_NOT_STICKY;
        }
        if (ACTION_REPEAT_PROMPT.equals(intent.getAction())) {
            if (!lastInstruction.isEmpty()) speak(lastInstruction);
            return START_NOT_STICKY;
        }
        if (!ACTION_START.equals(intent.getAction())) return START_NOT_STICKY;

        destName = safe(intent.getStringExtra(EXTRA_DEST_NAME));
        destLat = intent.getDoubleExtra(EXTRA_DEST_LAT, 0);
        destLng = intent.getDoubleExtra(EXTRA_DEST_LNG, 0);
        fromLat = intent.getDoubleExtra(EXTRA_FROM_LAT, 0);
        fromLng = intent.getDoubleExtra(EXTRA_FROM_LNG, 0);
        hasFrom = intent.getBooleanExtra(EXTRA_HAS_FROM, false);
        routeStarted = false;
        lastInstruction = "正在规划前往" + destName + "的步行路线";
        lastSummary = "无需打开地图页面，直接为您语音指路";

        Notification notification = buildNotification(destName.isEmpty() ? "步行导航" : "前往 " + destName, lastInstruction);
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
        else startForeground(NOTIFICATION_ID, notification);
        if (wakeLock != null && !wakeLock.isHeld()) wakeLock.acquire(4 * 60 * 60 * 1000L);
        publish(true, destName, lastInstruction, lastSummary);
        startAmapNavi();
        return START_NOT_STICKY;
    }

    private void startAmapNavi() {
        cleanupNavi();
        try {
            AmapNavigator.prepare(this, settings);
            navi = AMapNavi.getInstance(getApplicationContext());
            navi.addAMapNaviListener(naviListener);
        } catch (Exception e) {
            failNavigation(e.getMessage() != null ? e.getMessage() : "步行导航启动失败");
        }
    }

    private final SimpleNaviListener naviListener = new SimpleNaviListener() {
        @Override public void onInitNaviSuccess() {
            if (destroyed || navi == null) return;
            navi.setUseInnerVoice(false, true);
            navi.startGPS();
            boolean ok = hasFrom
                    ? navi.calculateWalkRoute(new NaviLatLng(fromLat, fromLng), new NaviLatLng(destLat, destLng))
                    : navi.calculateWalkRoute(new NaviLatLng(destLat, destLng));
            if (!ok) failNavigation("没能发起步行算路，请检查定位后重试");
        }

        @Override public void onInitNaviFailure() {
            failNavigation("高德导航初始化失败，请检查高德密钥与SHA1签名绑定");
        }

        @Override public void onCalculateRouteSuccess(AMapCalcRouteResult result) {
            if (destroyed || navi == null) return;
            routeStarted = true;
            lastInstruction = "路线已规划好，开始步行导航";
            lastSummary = "请跟随语音提示前行";
            publish(true, destName, lastInstruction, lastSummary);
            updateNotification("前往 " + destName, lastInstruction);
            navi.startNavi(NaviType.GPS);
        }

        @Override public void onCalculateRouteFailure(AMapCalcRouteResult result) {
            int code = result != null ? result.getErrorCode() : -1;
            failNavigation("步行路线规划失败（错误码 " + code + "），请确认定位和网络后重试");
        }

        @Override public void onGetNavigationText(int type, String text) {
            if (destroyed || text == null || text.trim().isEmpty()) return;
            lastInstruction = text.trim();
            publish(true, destName, lastInstruction, lastSummary);
            updateNotification("前往 " + destName, lastInstruction);
            speak(lastInstruction);
        }

        @Override public void onNaviInfoUpdate(NaviInfo info) {
            if (destroyed || info == null) return;
            String road = safe(info.getCurrentRoadName());
            int remainMeters = info.getPathRetainDistance();
            int remainMinutes = Math.max(1, Math.round(info.getPathRetainTime() / 60f));
            int stepMeters = info.getCurStepRetainDistance();
            StringBuilder sb = new StringBuilder();
            sb.append("全程约剩 ").append(AmapNavigator.formatDistance(remainMeters))
                    .append(" · 约").append(remainMinutes).append("分钟");
            if (!road.isEmpty()) sb.append("\n当前道路：").append(road);
            if (stepMeters > 0) sb.append("（本段剩余 ").append(AmapNavigator.formatDistance(stepMeters)).append("）");
            lastSummary = sb.toString();
            publish(true, destName, lastInstruction, lastSummary);
        }

        @Override public void onReCalculateRouteForYaw() {
            if (destroyed) return;
            lastInstruction = "检测到偏离路线，正在为您重新规划";
            publish(true, destName, lastInstruction, lastSummary);
            speak(lastInstruction);
        }

        @Override public void onArriveDestination() {
            if (destroyed) return;
            String msg = "已到达" + destName + "附近，步行导航结束";
            lastInstruction = msg;
            lastSummary = "到达目的地";
            publish(false, destName, msg, lastSummary);
            if (navi != null) { try { navi.stopNavi(); } catch (Exception ignored) { } }
            speakAndStop(msg);
        }
    };

    private void speak(String text) {
        if (navi != null) navi.setTtsPlaying(true);
        voice.say(text, () -> { if (navi != null) navi.setTtsPlaying(false); });
    }

    private void speakAndStop(String text) {
        if (navi != null) navi.setTtsPlaying(true);
        voice.say(text, () -> {
            if (navi != null) navi.setTtsPlaying(false);
            stopSelf();
        });
        handler.postDelayed(this::stopSelf, 8000);
    }

    private void failNavigation(String message) {
        if (routeStarted) return;
        lastInstruction = message;
        lastSummary = "步行导航未能开始";
        publish(false, destName, message, lastSummary);
        speakAndStop(message);
    }

    private void stopNavigation(String reason) {
        lastInstruction = reason;
        publish(false, destName, reason, "");
        stopSelf();
    }

    private void publish(boolean active, String dest, String instruction, String summary) {
        currentSnapshot = new Snapshot(active, dest, instruction, summary);
        handler.post(() -> {
            if (uiListener != null) uiListener.onUpdate(currentSnapshot);
        });
    }

    private Notification buildNotification(String title, String content) {
        PendingIntent open = PendingIntent.getActivity(this, 21,
                new Intent(this, AssistantActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 22,
                new Intent(this, WalkNaviService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder = new Notification.Builder(this, "walk_navi")
                .setSmallIcon(R.drawable.ic_companion)
                .setContentTitle(title)
                .setContentText(content)
                .setContentIntent(open)
                .setOngoing(true)
                .setShowWhen(false)
                .addAction(new Notification.Action.Builder(null, "结束导航", stop).build());
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.createNotificationChannel(new android.app.NotificationChannel("walk_navi", "步行语音导航",
                    NotificationManager.IMPORTANCE_LOW));
        }
        return builder.build();
    }

    private void updateNotification(String title, String content) {
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) manager.notify(NOTIFICATION_ID, buildNotification(title, content));
    }

    private void cleanupNavi() {
        if (navi != null) {
            try { navi.stopNavi(); } catch (Exception ignored) { }
            try { navi.removeAMapNaviListener(naviListener); } catch (Exception ignored) { }
            try { navi.destroy(); } catch (Exception ignored) { }
            navi = null;
        }
    }

    @Override public void onDestroy() {
        destroyed = true;
        handler.removeCallbacksAndMessages(null);
        cleanupNavi();
        if (voice != null) voice.close();
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        publish(false, destName, currentSnapshot.instruction, currentSnapshot.summary);
        stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
    private static String safe(String value) { return value == null ? "" : value.trim(); }
}
