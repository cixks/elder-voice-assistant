package cn.family.littlecompanion;

import android.app.Activity;
import android.app.Service;
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

/** A fresh system-authorized, single-frame session. Never keeps capturing in idle. */
public final class ScreenCaptureService extends Service {
    static final String EXTRA_RESULT = "capture_result";
    static final String EXTRA_RECEIVER = "receiver";
    private final Handler handler = new Handler(Looper.getMainLooper());
    private MediaProjection projection;
    private VirtualDisplay display;
    private ImageReader reader;
    private ResultReceiver receiver;
    private boolean finished;
    // Same-process hand-off avoids putting a large image through Binder's transaction limit.
    private static String pendingImage;
    private static final Handler imageExpiry = new Handler(Looper.getMainLooper());
    static synchronized String takeImage() { String image = pendingImage; pendingImage = null; return image; }
    private static synchronized void putImage(String image) {
        pendingImage = image;
        imageExpiry.removeCallbacksAndMessages(null);
        imageExpiry.postDelayed(ScreenCaptureService::takeImage, 10000);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || projection != null) { stopSelf(); return START_NOT_STICKY; }
        receiver = parcel(intent, EXTRA_RECEIVER, ResultReceiver.class);
        Intent permission = parcel(intent, EXTRA_RESULT, Intent.class);
        if (receiver == null || permission == null) { fail("屏幕授权失效，请重试"); return START_NOT_STICKY; }
        android.app.Notification notification = Notifications.make(this, "capture", "临时读取屏幕", "只读取这次问题需要的画面", null);
        if (Build.VERSION.SDK_INT >= 29) startForeground(11, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        else startForeground(11, notification);
        try {
            projection = getSystemService(MediaProjectionManager.class).getMediaProjection(Activity.RESULT_OK, permission);
            projection.registerCallback(new MediaProjection.Callback() {
                @Override public void onStop() { if (!finished) fail("屏幕读取已停止"); }
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
                    String image = encode(frame);
                    frame.close(); frame = null;
                    finished = true;
                    putImage(image);
                    receiver.send(Activity.RESULT_OK, new Bundle());
                    cleanup(); stopSelf();
                } catch (Exception | OutOfMemoryError ignored) { fail("没能读取画面，请重试"); }
                finally { if (frame != null) frame.close(); }
            }, handler);
            // Wait for the consent dialog to leave before creating the surface. Do not drop the
            // first frame: a static shopping page might not produce another frame afterwards.
            handler.postDelayed(() -> {
                if (finished || projection == null || reader == null) return;
                try {
                    display = projection.createVirtualDisplay("one-question", width, height, metrics.densityDpi,
                            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.getSurface(), null, handler);
                } catch (RuntimeException ignored) { fail("无法读取这个页面，请重试"); }
            }, 500);
            handler.postDelayed(() -> { if (!finished) fail("没能读取画面，请重试"); }, 6000);
        } catch (Exception ignored) { fail("无法读取这个页面，请重试或换个页面"); }
        return START_NOT_STICKY;
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
            try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                cropped.compress(Bitmap.CompressFormat.JPEG, 85, output);
                return Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP);
            }
        } finally {
            if (cropped != null && cropped != padded) cropped.recycle();
            if (padded != null) padded.recycle();
        }
    }

    private void fail(String message) {
        if (finished) return;
        finished = true;
        if (receiver != null) { Bundle error = new Bundle(); error.putString("error", message); receiver.send(Activity.RESULT_CANCELED, error); }
        cleanup(); stopSelf();
    }
    private void cleanup() {
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
    @Override public void onDestroy() { finished = true; cleanup(); super.onDestroy(); }
    @Override public IBinder onBind(Intent intent) { return null; }
}
