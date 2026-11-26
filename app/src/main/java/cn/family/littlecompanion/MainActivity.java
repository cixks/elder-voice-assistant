package cn.family.littlecompanion;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.widget.LinearLayout;
import android.widget.Toast;

/** The everyday home screen deliberately contains no editable configuration. */
public final class MainActivity extends Activity {
    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout page = Ui.column(this);
        page.setBackgroundColor(Color.rgb(242, 247, 245));
        page.addView(Ui.text(this, "小伴", 34));
        page.addView(Ui.text(this, "打开悬浮窗后，点悬浮球，再用麦克风说话。", 19));
        page.addView(Ui.button(this, "打开悬浮窗", v -> openOverlay()));
        page.addView(Ui.button(this, "关闭悬浮窗", v -> {
            stopService(new Intent(this, OverlayService.class));
            stopService(new Intent(this, ScreenCaptureService.class)); toast("悬浮窗和屏幕共享已关闭");
        }));
        page.addView(Ui.button(this, "设置", v -> startActivity(new Intent(this, SettingsActivity.class))));
        setContentView(page);
    }

    private void openOverlay() {
        if (!new Settings(this).configured()) { toast("请先让家人在设置中配置对话接口"); return; }
        if (!android.provider.Settings.canDrawOverlays(this)) {
            new AlertDialog.Builder(this).setMessage("请允许显示在其他应用上层，然后返回再次打开悬浮窗。")
                    .setPositiveButton("去设置", (dialog, which) -> startActivity(new Intent(
                            android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName()))))
                    .setNegativeButton("取消", null).show(); return;
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 1); return;
        }
        startForegroundService(new Intent(this, OverlayService.class)); toast("悬浮窗已打开");
    }
    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(request, permissions, results);
        if (request == 1 && results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) openOverlay();
        else toast("需要麦克风权限才能录音");
    }
    private void toast(String message) { Toast.makeText(this, message, Toast.LENGTH_LONG).show(); }
}
