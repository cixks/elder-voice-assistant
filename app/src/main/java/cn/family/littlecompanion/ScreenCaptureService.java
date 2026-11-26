package cn.family.littlecompanion;

import android.app.Activity;
import android.app.Service;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.ResultReceiver;
import android.os.Bundle;
import android.util.Base64;
import android.util.DisplayMetrics;
import android.view.WindowManager;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;

/** Keep one explicitly authorized projection session; only encode frames on a user request. */
public final class ScreenCaptureService extends Service {
    static final String EXTRA_RESULT = "capture_result";
    static final String EXTRA_RECEIVER = "receiver";
    static final String ACTION_STOP = "cn.family.littlecompanion.STOP_CAPTURE";
    private static volatile boolean sessionActive;
    static boolean hasSession() { return sessionActive; }
    private final Handler handler = new Handler(Looper.getMainLooper());
    private MediaProjection projection;
    private VirtualDisplay display;
    private ImageReader reader;
    private ResultReceiver receiver;
    private boolean finished, requesting;
    private boolean sawBlankFrame;
    // Same-process hand-off avoids putting a large image through Binder's transaction limit.
    private static String pendingImage;
    private static final Handler imageExpiry = new Handler(Looper.getMainLooper());
    static synchronized String takeImage() { String image = pendingImage; pendingImage = null; return image; }
    private static synchronized void putImage(String image) {
        pendingImage = image;
        imageExpiry.removeCallbacksAndMessages(null);
        imageExpiry.postDelayed(ScreenCaptureService::takeImage, 10000);
    }
    private final Runnable captureTimeout = () -> {
        if (requesting) fail(sawBlankFrame
                ? "只读到了黑屏。该页面可能禁止截屏，请换一个普通页面再试"
                : "没能读取画面，请重试");
    };

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || ACTION_STOP.equals(intent.getAction())) { stopSelf(); return START_NOT_STICKY; }
        if (projection != null) {
            if (requesting) { fail("上次屏幕读取已取消"); }
            receiver = parcel(intent, EXTRA_RECEIVER, ResultReceiver.class);
            requestFrame();
            return START_NOT_STICKY;
        }
        receiver = parcel(intent, EXTRA_RECEIVER, ResultReceiver.class);
        Intent permission = parcel(intent, EXTRA_RESULT, Intent.class);
        if (receiver == null || permission == null) { fail("屏幕授权失效，请重试"); stopSelf(); return START_NOT_STICKY; }
        PendingIntent stop = PendingIntent.getService(this, 12, new Intent(this, ScreenCaptureService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        android.app.Notification notification = Notifications.make(this, "capture", "屏幕共享会话", "共享已开启；仅主动识图时处理画面。关闭可结束共享", stop);
        if (Build.VERSION.SDK_INT >= 29) startForeground(11, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        else startForeground(11, notification);
        try {
            projection = getSystemService(MediaProjectionManager.class).getMediaProjection(Activity.RESULT_OK, permission);
            projection.registerCallback(new MediaProjection.Callback() {
                @Override public void onStop() {
                    sessionActive = false;
                    if (requesting) fail("屏幕共享已停止，下次识图需重新授权");
                    stopSelf();
                }
            }, handler);
            DisplayMetrics metrics = new DisplayMetrics();
            ((WindowManager) getSystemService(WINDOW_SERVICE)).getDefaultDisplay().getRealMetrics(metrics);
            // Scale the display surface itself to limit memory before allocating image buffers.
            float scale = Math.min(1f, 1600f / Math.max(metrics.widthPixels, metrics.heightPixels));
            int width = Math.max(1, Math.round(metrics.widthPixels * scale));
            int height = Math.max(1, Math.round(metrics.heightPixels * scale));
            reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2);
            reader.setOnImageAvailableListener(source -> {
                if (finished) return;
                Image frame = null;
                try {
                    frame = source.acquireLatestImage();
                    if (frame == null) return;
                    if (!requesting) return;
                    String image = encode(frame);
                    // Some devices deliver an empty startup frame before the shared content.
                    // Keep waiting within this session rather than uploading an empty image.
                    if (image == null) { sawBlankFrame = true; return; }
                    frame.close(); frame = null;
                    requesting = false;
                    handler.removeCallbacks(captureTimeout);
                    putImage(image);
                    ResultReceiver target = receiver; receiver = null;
                    if (display != null) display.setSurface(null);
                    if (target != null) target.send(Activity.RESULT_OK, new Bundle());
                } catch (Exception | OutOfMemoryError ignored) { fail("没能读取画面，请重试"); }
                finally { if (frame != null) frame.close(); }
            }, handler);
            // Wait for the permission dialog and assistant panel to leave.
            requesting = true;
            handler.postDelayed(() -> {
                if (finished || projection == null || reader == null) return;
                try {
                    display = projection.createVirtualDisplay("user-authorized-screen", width, height, metrics.densityDpi,
                            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.getSurface(), null, handler);
                    sessionActive = true;
                } catch (RuntimeException ignored) { fail("无法读取这个页面，请重试"); stopSelf(); }
            }, 500);
            handler.postDelayed(captureTimeout, 6000);
        } catch (Exception ignored) { fail("无法读取这个页面，请重试或换个页面"); stopSelf(); }
        return START_NOT_STICKY;
    }

    private void requestFrame() {
        if (receiver == null) return;
        requesting = true; sawBlankFrame = false;
        handler.removeCallbacks(captureTimeout);
        // Replace just the output surface, not the virtual display or the authorization token.
        // This prompts a fresh frame even when the underlying shopping page is static.
        try {
            display.setSurface(null);
            try (Image stale = reader.acquireLatestImage()) { /* Discard any queued old frame. */ }
            handler.postDelayed(() -> {
                if (!finished && display != null && reader != null && requesting) {
                    try { display.setSurface(reader.getSurface()); }
                    catch (RuntimeException ignored) { fail("屏幕共享已失效，请重新授权"); stopSelf(); }
                }
            }, 500);
            handler.postDelayed(captureTimeout, 6000);
        } catch (RuntimeException ignored) { fail("屏幕共享失效，请关闭悬浮窗后重新开启"); stopSelf(); }
    }

    private String encode(Image frame) throws Exception {
        Image.Plane plane = frame.getPlanes()[0];
        ByteBuffer buffer = plane.getBuffer();
        int paddedWidth = plane.getRowStride() / plane.getPixelStride();
        Bitmap padded = null, cropped = null;
        try {
            padded = Bitmap.createBitmap(paddedWidth, frame.getHeight(), Bitmap.Config.ARGB_8888);
            padded.copyPixelsFromBuffer(buffer);
            cropped = Bitmap.createBitmap(padded, 0, 0, frame.getWidth(), frame.getHeight());
            if (isNearlyBlack(cropped)) return null;
            try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                cropped.compress(Bitmap.CompressFormat.JPEG, 85, output);
                return Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP);
            }
        } finally {
            if (cropped != null && cropped != padded) cropped.recycle();
            if (padded != null) padded.recycle();
        }
    }

    private static boolean isNearlyBlack(Bitmap bitmap) {
        int dark = 0, samples = 0;
        // Ignore the outer margins (status/navigation bars and the floating button).
        for (int row = 1; row < 20; row++) {
            for (int column = 1; column < 20; column++) {
                int color = bitmap.getPixel(Math.min(bitmap.getWidth() - 1, bitmap.getWidth() * column / 20),
                        Math.min(bitmap.getHeight() - 1, bitmap.getHeight() * row / 20));
                if (android.graphics.Color.red(color) < 12 && android.graphics.Color.green(color) < 12
                        && android.graphics.Color.blue(color) < 12) dark++;
                samples++;
            }
        }
        return dark >= samples * 0.98;
    }

    private void fail(String message) {
        requesting = false;
        if (display != null) { try { display.setSurface(null); } catch (RuntimeException ignored) { } }
        handler.removeCallbacks(captureTimeout);
        ResultReceiver target = receiver; receiver = null;
        if (target != null) { Bundle error = new Bundle(); error.putString("error", message); target.send(Activity.RESULT_CANCELED, error); }
    }
    private void cleanup() {
        sessionActive = false;
        handler.removeCallbacksAndMessages(null);
        if (display != null) { display.release(); display = null; }
        if (reader != null) { reader.close(); reader = null; }
        if (projection != null) { MediaProjection current = projection; projection = null; current.stop(); }
        stopForeground(STOP_FOREGROUND_REMOVE);
    }
    @SuppressWarnings("deprecation") private static <T> T parcel(Intent intent, String name, Class<T> type) {
        Object value = intent.getParcelableExtra(name);
        return type.isInstance(value) ? type.cast(value) : null;
    }
    @Override public void onDestroy() { finished = true; if (requesting) fail("屏幕共享已结束"); cleanup(); super.onDestroy(); }
    @Override public IBinder onBind(Intent intent) { return null; }
}
