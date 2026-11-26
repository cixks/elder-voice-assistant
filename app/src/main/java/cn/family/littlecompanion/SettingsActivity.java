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
import android.widget.SeekBar;
import java.util.LinkedHashMap;
import java.util.Map;

public final class SettingsActivity extends Activity {
    private Settings settings;
    private LinearLayout content;
    private final Map<String, EditText> fields = new LinkedHashMap<>();
    private EditText keyField;
    private final Map<String, EditText> serviceKeys = new LinkedHashMap<>();
    private final Map<String, CheckBox> reuseOptions = new LinkedHashMap<>();
    private CheckBox cloudSpeech;
    private Spinner maps, size, recordingMode;
    private SeekBar rate;
    private TextView checks;

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        settings = new Settings(this);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true); scroll.setBackgroundColor(Color.rgb(242, 247, 245));
        content = Ui.column(this); scroll.addView(content); setContentView(scroll);
        content.addView(Ui.text(this, "小伴", 34));
        content.addView(Ui.text(this, "家人设置 · 长辈只需点悬浮球", 18));
        content.addView(Ui.button(this, "返回首页", v -> finish()));
        content.addView(Ui.text(this, "点击才录音，按需读取屏幕。语音、问题及截图会发送给您配置的云端服务。请先确认服务商可信。", 15));
        content.addView(Ui.button(this, "在这里试问一句", v -> {
            if (save() && settings.configured()) startActivity(new Intent(this, AssistantActivity.class));
            else toast("请先填写并保存接口配置");
        }));
        section("对话接口配置");
        content.addView(Ui.text(this, "三种能力可接入不同服务商。填写接口基础地址，不含 /chat/completions。模型必须填API模型ID，不是品牌名；例如DeepSeek官方对话通常填 deepseek-chat。", 14));
        field("base_url", "对话接口地址（HTTPS）", settings.baseUrl(), false);
        content.addView(Ui.text(this, "API密钥（留空保留原密钥）", 16));
        keyField = new EditText(this); keyField.setSingleLine(true);
        keyField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        keyField.setHint(settings.apiKey().trim().isEmpty() ? "尚未配置" : "已加密保存，输入可替换");
        keyField.setImportantForAutofill(android.view.View.IMPORTANT_FOR_AUTOFILL_NO);
        keyField.setSaveEnabled(false); content.addView(keyField);
        field("model", "对话模型名称", settings.model(), false);
        section("页面识图接口配置");
        serviceFields("vision", "识图");
        field("vision_model", "视觉模型ID（仅沿用对话时可留空）", settings.get("vision_model", ""), false);
        section("语音识别配置");
        cloudSpeech = new CheckBox(this); cloudSpeech.setText("使用云端语音识别（关闭则用手机系统识别）");
        cloudSpeech.setChecked(settings.cloudSpeech()); content.addView(cloudSpeech);
        content.addView(Ui.text(this, "云端语音需要明确支持 /audio/transcriptions 的OpenAI兼容转写服务。仅支持聊天的API不可用，其他厂商专有实时语音协议暂不支持。关闭云端时以下配置不使用。", 14));
        serviceFields("asr", "云端语音");
        field("asr_model", "语音转写模型ID（按服务商文档填写）", settings.get("asr_model", ""), false);
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
        content.addView(Ui.text(this, "麦克风录音方式（确认需求时同样适用）", 16));
        recordingMode = spinner(new String[]{"点击开始，再点击结束", "按住说话，松开发送"}, settings.bool("hold_to_talk", false) ? 1 : 0);
        content.addView(Ui.text(this, "云端录音按按钮结束，最长60秒；系统语音服务可能自行提前结束，取决于手机实现。", 14));
        content.addView(Ui.text(this, "悬浮球大小", 16));
        size = spinner(new String[]{"标准 64", "大号 76", "特大 88"}, Math.max(0, Math.min(2, (settings.number("ball_size", 64) - 64) / 12)));
        content.addView(Ui.text(this, "语速", 16));
        int savedRate = Math.max(50, Math.min(150, settings.number("speech_rate", 90)));
        TextView rateLabel = Ui.text(this, "语速：" + savedRate + "%", 16); content.addView(rateLabel);
        rate = new SeekBar(this); rate.setMax(100); rate.setProgress(savedRate - 50); content.addView(rate);
        rate.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) { rateLabel.setText("语速：" + (progress + 50) + "%"); }
            @Override public void onStartTrackingTouch(SeekBar bar) { }
            @Override public void onStopTrackingTouch(SeekBar bar) { }
        });
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
        content.addView(Ui.button(this, "删除全部已保存的API密钥", v -> new AlertDialog.Builder(this)
                .setMessage("删除后需要家人重新填写才能使用。")
                .setPositiveButton("删除", (dialog, which) -> {
                    try {
                        settings.saveApiKey(""); keyField.setText(""); keyField.setHint("尚未配置");
                        for (Map.Entry<String, EditText> entry : serviceKeys.entrySet()) {
                            settings.saveKey(entry.getKey() + "_api_key", ""); entry.getValue().setText(""); entry.getValue().setHint("尚未配置");
                        }
                        toast("已删除全部密钥");
                    }
                    catch (Exception ignored) { toast("删除失败，请重试"); }
                }).setNegativeButton("取消", null).show()));
        content.addView(Ui.text(this, "版本 0.2.0 · 家庭试用原型\n未包含天气查询、自动购买、付款、拨号或独立导航。API费用以服务商为准，请在服务商设置预算和额度限制。", 14));
    }

    private void section(String title) { content.addView(Ui.text(this, "\n" + title, 22)); }
    private void serviceFields(String service, String label) {
        CheckBox reuse = new CheckBox(this);
        reuse.setText("沿用对话接口地址和密钥（仅服务商支持该功能时）");
        reuse.setChecked(settings.bool(service + "_reuse", false));
        reuseOptions.put(service, reuse); content.addView(reuse);
        field(service + "_base_url", label + "独立接口地址（HTTPS）", settings.get(service + "_base_url", ""), false);
        content.addView(Ui.text(this, label + "独立API密钥（留空保留）", 16));
        EditText key = new EditText(this); key.setSingleLine(true);
        key.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        key.setImportantForAutofill(android.view.View.IMPORTANT_FOR_AUTOFILL_NO); key.setSaveEnabled(false);
        key.setHint(settings.readKey(service + "_api_key").isEmpty() ? "尚未配置" : "已加密保存，输入可替换");
        serviceKeys.put(service, key); content.addView(key);
        EditText address = fields.get(service + "_base_url");
        reuse.setOnCheckedChangeListener((button, checked) -> { address.setEnabled(!checked); key.setEnabled(!checked); });
        address.setEnabled(!reuse.isChecked()); key.setEnabled(!reuse.isChecked());
    }
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
            for (Map.Entry<String, CheckBox> entry : reuseOptions.entrySet()) {
                String service = entry.getKey();
                if (!entry.getValue().isChecked()) {
                    String address = fields.get(service + "_base_url").getText().toString().trim();
                    if (!address.isEmpty()) {
                        Uri endpoint = Uri.parse(address);
                        if (!"https".equalsIgnoreCase(endpoint.getScheme()) || endpoint.getHost() == null
                                || endpoint.getUserInfo() != null || endpoint.getQuery() != null || endpoint.getFragment() != null) {
                            toast("请填写有效的独立HTTPS接口地址"); return false;
                        }
                    }
                }
            }
            String key = keyField.getText().toString().trim();
            if (!key.isEmpty()) { settings.saveApiKey(key); keyField.setText(""); keyField.setHint("已加密保存，输入可替换"); }
            for (Map.Entry<String, EditText> entry : fields.entrySet()) settings.put(entry.getKey(), entry.getValue().getText().toString().trim());
            for (Map.Entry<String, EditText> entry : serviceKeys.entrySet()) {
                String secret = entry.getValue().getText().toString().trim();
                if (!secret.isEmpty()) {
                    settings.saveKey(entry.getKey() + "_api_key", secret);
                    entry.getValue().setText(""); entry.getValue().setHint("已加密保存，输入可替换");
                }
            }
            for (Map.Entry<String, CheckBox> entry : reuseOptions.entrySet()) settings.putBool(entry.getKey() + "_reuse", entry.getValue().isChecked());
            settings.putBool("cloud_speech", cloudSpeech.isChecked());
            settings.put("map_package", MapLauncher.PACKAGES[maps.getSelectedItemPosition()]);
            settings.putNumber("ball_size", 64 + size.getSelectedItemPosition() * 12);
            settings.putNumber("speech_rate", rate.getProgress() + 50);
            settings.putBool("hold_to_talk", recordingMode.getSelectedItemPosition() == 1);
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
