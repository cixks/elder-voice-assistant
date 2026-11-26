package cn.family.littlecompanion;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.ArrayAdapter;
import android.widget.TextView;
import android.widget.Toast;
import java.util.LinkedHashMap;
import java.util.Map;

public final class MainActivity extends Activity {
    private Settings settings;
    private LinearLayout content;
    private final Map<String, EditText> fields = new LinkedHashMap<>();
    private EditText keyField;
    private CheckBox cloudSpeech;
    private Spinner maps, size, rate;
    private TextView checks;

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        settings = new Settings(this);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true); scroll.setBackgroundColor(Color.rgb(242, 247, 245));
        content = Ui.column(this); scroll.addView(content); setContentView(scroll);
        content.addView(Ui.text(this, "小伴", 34));
        content.addView(Ui.text(this, "家人设置 · 长辈只需点悬浮球", 18));
        content.addView(Ui.text(this, "点击才录音，按需读取屏幕。语音、问题及截图会发送给您配置的云端服务。请先确认服务商可信。", 15));
        content.addView(Ui.button(this, "打开悬浮球", v -> {
            if (!save()) return;
            if (!android.provider.Settings.canDrawOverlays(this)) {
                new AlertDialog.Builder(this).setMessage("请允许小伴显示在其他应用上层，然后返回这里再次打开悬浮球。")
                        .setPositiveButton("去设置", (dialog, which) -> startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:" + getPackageName())))).setNegativeButton("取消", null).show(); return;
            }
            if (!settings.configured()) { toast("请先填写密钥和模型名称"); return; }
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 1); return;
            }
            startForegroundService(new Intent(this, OverlayService.class)); toast("悬浮球已开启，可以返回购物页面");
        }));
        content.addView(Ui.button(this, "关闭悬浮球", v -> stopService(new Intent(this, OverlayService.class))));
        content.addView(Ui.button(this, "在这里试问一句", v -> {
            if (save() && settings.configured()) startActivity(new Intent(this, AssistantActivity.class));
            else toast("请先填写并保存接口配置");
        }));
        section("接口配置");
        content.addView(Ui.text(this, "使用 OpenAI 兼容接口。地址一般以 /v1 结尾，不包含 /chat/completions。识图模型必须支持图片；云端识别需要 /audio/transcriptions。", 14));
        field("base_url", "接口地址（HTTPS）", settings.baseUrl(), false);
        content.addView(Ui.text(this, "API密钥（留空保留原密钥）", 16));
        keyField = new EditText(this); keyField.setSingleLine(true);
        keyField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        keyField.setHint(settings.apiKey().trim().isEmpty() ? "尚未配置" : "已加密保存，输入可替换");
        keyField.setImportantForAutofill(android.view.View.IMPORTANT_FOR_AUTOFILL_NO);
        keyField.setSaveEnabled(false); content.addView(keyField);
        field("model", "对话模型名称", settings.model(), false);
        field("vision_model", "识图模型名称（留空使用对话模型）", settings.get("vision_model", ""), false);
        cloudSpeech = new CheckBox(this); cloudSpeech.setText("使用云端语音识别（关闭则用手机系统识别）");
        cloudSpeech.setChecked(settings.cloudSpeech()); content.addView(cloudSpeech);
        field("asr_model", "语音识别模型名称", settings.get("asr_model", "whisper-1"), false);
        field("speech_hints", "常用地名、人名和方言说明（不要填密钥）", settings.get("speech_hints", ""), true);
        section("地图和常用地点");
        String[] mapLabels = new String[MapLauncher.NAMES.length];
        int selectedMap = 0;
        for (int i = 0; i < mapLabels.length; i++) {
            mapLabels[i] = MapLauncher.NAMES[i] + (MapLauncher.installed(this, MapLauncher.PACKAGES[i]) ? "（已安装）" : "（未安装）");
            if (MapLauncher.PACKAGES[i].equals(settings.get("map_package", ""))) selectedMap = i;
        }
        maps = spinner(mapLabels, selectedMap);
        content.addView(Ui.text(this, "地图入口需要在真机确认，可能还需在地图中选择地点并点击开始导航。此版本不承诺无地图界面导航。", 14));
        field("home", "家的完整地址（建议包含城市、小区和门牌）", settings.get("home", ""), true);
        section("外观与播报");
        content.addView(Ui.text(this, "悬浮球大小", 16));
        size = spinner(new String[]{"标准 64", "大号 76", "特大 88"}, Math.max(0, Math.min(2, (settings.number("ball_size", 64) - 64) / 12)));
        content.addView(Ui.text(this, "语速", 16));
        int savedRate = settings.number("speech_rate", 90);
        rate = spinner(new String[]{"慢一些", "正常", "快一些"}, savedRate == 75 ? 0 : savedRate == 105 ? 2 : 1);
        content.addView(Ui.button(this, "保存设置", v -> { if (save()) toast("已保存；大小变更后请重新打开悬浮球"); }));
        section("权限与维护");
        checks = Ui.text(this, "", 15); content.addView(checks);
        content.addView(Ui.button(this, "允许麦克风和通知", v -> {
            if (Build.VERSION.SDK_INT >= 33) requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS}, 1);
            else requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 1);
        }));
        content.addView(Ui.button(this, "打开系统应用设置", v -> startActivity(new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName())))));
        content.addView(Ui.text(this, "小米、OPPO可能需要手动允许后台运行、悬浮窗或后台弹出界面。建议只给必要权限。此版本不自动开机启动。", 14));
        content.addView(Ui.button(this, "恢复悬浮球位置", v -> {
            settings.putNumber("ball_x", 0); settings.putNumber("ball_y", Ui.dp(this, 240)); toast("位置已恢复，请重新打开悬浮球");
        }));
        content.addView(Ui.button(this, "清除临时记录", v -> {
            // Do not delete an in-flight recording; only the memory-only answer is retained by this app.
            ((CompanionApp) getApplication()).remember(""); toast("上次回答已清除，录音和截图不长期保存");
        }));
        content.addView(Ui.button(this, "删除已保存的API密钥", v -> new AlertDialog.Builder(this)
                .setMessage("删除后需要家人重新填写才能使用。")
                .setPositiveButton("删除", (dialog, which) -> {
                    try { settings.saveApiKey(""); keyField.setText(""); keyField.setHint("尚未配置"); toast("已删除"); }
                    catch (Exception ignored) { toast("删除失败，请重试"); }
                }).setNegativeButton("取消", null).show()));
        content.addView(Ui.text(this, "版本 0.1.0 · 家庭试用原型\n未包含天气查询、自动购买、付款、拨号或独立导航。API费用以服务商为准，请在服务商设置预算和额度限制。", 14));
    }

    private void section(String title) { content.addView(Ui.text(this, "\n" + title, 22)); }
    private void field(String key, String label, String value, boolean multiline) {
        content.addView(Ui.text(this, label, 16));
        EditText input = new EditText(this); input.setText(value); input.setTextSize(16);
        input.setSingleLine(!multiline); input.setInputType(InputType.TYPE_CLASS_TEXT | (multiline ? InputType.TYPE_TEXT_FLAG_MULTI_LINE : InputType.TYPE_TEXT_VARIATION_NORMAL));
        fields.put(key, input); content.addView(input);
    }
    private Spinner spinner(String[] labels, int selected) {
        Spinner spinner = new Spinner(this);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, labels);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter); spinner.setSelection(selected); content.addView(spinner); return spinner;
    }
    private boolean save() {
        String base = fields.get("base_url").getText().toString().trim();
        Uri uri = Uri.parse(base);
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) {
            toast("请填写有效的HTTPS接口地址，不含查询参数"); return false;
        }
        try {
            String key = keyField.getText().toString().trim();
            if (!key.isEmpty()) { settings.saveApiKey(key); keyField.setText(""); keyField.setHint("已加密保存，输入可替换"); }
            for (Map.Entry<String, EditText> entry : fields.entrySet()) settings.put(entry.getKey(), entry.getValue().getText().toString().trim());
            settings.putBool("cloud_speech", cloudSpeech.isChecked());
            settings.put("map_package", MapLauncher.PACKAGES[maps.getSelectedItemPosition()]);
            settings.putNumber("ball_size", 64 + size.getSelectedItemPosition() * 12);
            settings.putNumber("speech_rate", new int[]{75, 90, 105}[rate.getSelectedItemPosition()]);
            return true;
        } catch (Exception ignored) { toast("密钥保存失败，请检查手机安全存储"); return false; }
    }
    @Override protected void onResume() {
        super.onResume();
        if (checks != null) checks.setText("悬浮窗：" + (android.provider.Settings.canDrawOverlays(this) ? "已允许" : "未允许")
                + "\n麦克风：" + (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED ? "已允许" : "未允许")
                + "\n屏幕读取：每次需要时由系统授权");
    }
    private void toast(String text) { Toast.makeText(this, text, Toast.LENGTH_LONG).show(); }
}
