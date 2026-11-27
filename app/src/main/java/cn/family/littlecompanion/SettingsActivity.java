package cn.family.littlecompanion;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ResultReceiver;
import android.text.InputType;
import android.util.Base64;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import java.util.LinkedHashMap;
import java.util.Map;

public final class SettingsActivity extends Activity {
    private static final int CAPTURE_PREVIEW_REQUEST = 201;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Settings settings;
    private LinearLayout content;
    private final Map<String, EditText> fields = new LinkedHashMap<>();
    private EditText keyField, amapKeyField;
    private final Map<String, EditText> serviceKeys = new LinkedHashMap<>();
    private final Map<String, CheckBox> reuseOptions = new LinkedHashMap<>();
    private CheckBox cloudSpeech, amapPrivacy;
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
        keyField = passwordField("API密钥（留空保留原密钥）", settings.apiKey().trim().isEmpty());
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

        section("高德无地图步行导航");
        content.addView(Ui.text(this, "填写高德开放平台 Android Key（需绑定包名 cn.family.littlecompanion 与固定签名 SHA1），可在不打开高德地图页面的情况下直接语音步行导航。仅供家庭非商业自用，请遵守高德个人开发者配额规定。", 14));
        amapPrivacy = new CheckBox(this);
        amapPrivacy.setText("同意高德定位、地点搜索与步行导航隐私说明（仅导航时使用位置）");
        amapPrivacy.setChecked(settings.bool("amap_privacy_agreed", false));
        content.addView(amapPrivacy);
        amapKeyField = passwordField("高德 Android API Key（留空保留原密钥）", settings.readKey("amap_api_key").trim().isEmpty());
        content.addView(Ui.button(this, "高德诊断信息（包名 / SHA1）", v -> showAmapDiagnostics()));
        content.addView(Ui.button(this, "允许定位和通知", v -> {
            if (Build.VERSION.SDK_INT >= 33) {
                requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.POST_NOTIFICATIONS}, 2);
            } else {
                requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION}, 2);
            }
        }));

        section("常用地点与外部地图备用");
        field("home", "家的完整地址（建议包含城市、小区和门牌）", settings.get("home", ""), true);
        String[] mapLabels = new String[MapLauncher.NAMES.length];
        int selectedMap = 0;
        for (int i = 0; i < mapLabels.length; i++) {
            mapLabels[i] = MapLauncher.NAMES[i] + (MapLauncher.installed(this, MapLauncher.PACKAGES[i]) ? "（已安装）" : "（未安装）");
            if (MapLauncher.PACKAGES[i].equals(settings.get("map_package", ""))) selectedMap = i;
        }
        maps = spinner(mapLabels, selectedMap);
        content.addView(Ui.text(this, "未填写高德密钥时，将回退到此处选择的外部地图入口。", 14));

        section("外观与播报");
        content.addView(Ui.text(this, "麦克风录音方式", 16));
        recordingMode = spinner(new String[]{"点击开始，再点击结束", "按住说话，松开发送"}, settings.bool("hold_to_talk", false) ? 1 : 0);
        content.addView(Ui.text(this, "录完后助手复述需求，再显示大勾/大叉按钮；回答完成后保留界面并支持重新播放，点击空白处关闭。", 14));
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
        content.addView(Ui.button(this, "保存设置", v -> { if (save()) toast("已保存；修改高德密钥或悬浮球大小后建议重启生效"); }));

        section("权限与维护");
        checks = Ui.text(this, "", 15); content.addView(checks);
        content.addView(Ui.button(this, "截图预览测试（诊断黑屏/受保护页）", v -> startCapturePreviewTest()));
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
            ((CompanionApp) getApplication()).remember(""); toast("上次回答已清除，录音和截图不长期保存");
        }));
        content.addView(Ui.button(this, "删除全部已保存的API密钥", v -> new AlertDialog.Builder(this)
                .setMessage("删除后需要家人重新填写才能使用。")
                .setPositiveButton("删除", (dialog, which) -> {
                    try {
                        settings.saveApiKey(""); keyField.setText(""); keyField.setHint("尚未配置");
                        settings.saveKey("amap_api_key", ""); amapKeyField.setText(""); amapKeyField.setHint("尚未配置");
                        for (Map.Entry<String, EditText> entry : serviceKeys.entrySet()) {
                            settings.saveKey(entry.getKey() + "_api_key", ""); entry.getValue().setText(""); entry.getValue().setHint("尚未配置");
                        }
                        toast("已删除全部密钥");
                    }
                    catch (Exception ignored) { toast("删除失败，请重试"); }
                }).setNegativeButton("取消", null).show()));
        content.addView(Ui.text(this, "版本 0.4.0 · 家庭试用原型\n屏幕共享首次授权后复用，关闭悬浮窗或共享通知可结束。导航为家庭试用能力，请勿让长辈首次无人陪同依赖导航。API费用以服务商为准，请在服务商设置预算和额度限制。", 14));
    }

    private void startCapturePreviewTest() {
        if (ScreenCaptureService.hasSession()) {
            runCapturePreview(null);
            return;
        }
        try {
            startActivityForResult(getSystemService(MediaProjectionManager.class).createScreenCaptureIntent(), CAPTURE_PREVIEW_REQUEST);
        } catch (RuntimeException ignored) {
            toast("这台手机暂时无法发起屏幕预览");
        }
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != CAPTURE_PREVIEW_REQUEST) return;
        if (result != RESULT_OK || data == null) { toast("未授予屏幕读取权限"); return; }
        runCapturePreview(data);
    }

    private void runCapturePreview(Intent permissionData) {
        ResultReceiver receiver = new ResultReceiver(handler) {
            @Override protected void onReceiveResult(int code, Bundle bundle) {
                if (isFinishing() || isDestroyed()) { ScreenCaptureService.takeImage(); return; }
                String image = ScreenCaptureService.takeImage();
                String diag = ScreenCaptureService.lastDiagnostic();
                if (code == RESULT_OK && image != null) showPreviewDialog(image, diag);
                else {
                    String err = bundle == null ? "没能读取画面" : bundle.getString("error", "没能读取画面");
                    new AlertDialog.Builder(SettingsActivity.this)
                            .setTitle("截图预览结果")
                            .setMessage(err + (diag.isEmpty() ? "" : "\n\n诊断信息：" + diag))
                            .setPositiveButton("知道了", null).show();
                }
            }
        };
        Intent service = new Intent(this, ScreenCaptureService.class).putExtra(ScreenCaptureService.EXTRA_RECEIVER, receiver);
        if (permissionData != null) service.putExtra(ScreenCaptureService.EXTRA_RESULT, permissionData);
        try { startForegroundService(service); }
        catch (RuntimeException ignored) { toast("截图预览服务无法启动"); }
    }

    private void showPreviewDialog(String base64Image, String diag) {
        byte[] bytes = Base64.decode(base64Image, Base64.NO_WRAP);
        Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
        if (bitmap == null) { toast("预览解码失败"); return; }
        LinearLayout box = Ui.column(this);
        box.addView(Ui.text(this, diag.isEmpty() ? "捕获成功（仅内存预览，不保存）" : "诊断：" + diag, 14));
        ImageView preview = new ImageView(this);
        preview.setAdjustViewBounds(true);
        preview.setMaxHeight(Ui.dp(this, 360));
        preview.setImageBitmap(bitmap);
        box.addView(preview);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("截图预览（未落盘）")
                .setView(box)
                .setPositiveButton("关闭并释放", null)
                .create();
        dialog.setOnDismissListener(d -> {
            preview.setImageDrawable(null);
            if (!bitmap.isRecycled()) bitmap.recycle();
        });
        dialog.show();
    }

    private EditText passwordField(String label, boolean empty) {
        content.addView(Ui.text(this, label, 16));
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        input.setHint(empty ? "尚未配置" : "已加密保存，输入可替换");
        input.setImportantForAutofill(android.view.View.IMPORTANT_FOR_AUTOFILL_NO);
        input.setSaveEnabled(false);
        content.addView(input);
        return input;
    }

    private void showAmapDiagnostics() {
        String report = AmapDiagnostics.report(this, settings);
        if (!amapKeyField.getText().toString().trim().isEmpty()) {
            report = "注意：密钥输入框有尚未保存的内容，以下报告只反映已保存配置。\n\n" + report;
        }
        final String copiedReport = report;
        TextView text = Ui.text(this, report, 16);
        text.setTextIsSelectable(true);
        int padding = Ui.dp(this, 18); text.setPadding(padding, padding, padding, padding);
        ScrollView scroll = new ScrollView(this); scroll.addView(text);
        new AlertDialog.Builder(this).setTitle("高德本机诊断").setView(scroll)
                .setPositiveButton("复制诊断信息", (dialog, which) -> {
                    android.content.ClipboardManager clipboard = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                    if (clipboard != null) {
                        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("高德诊断信息", copiedReport));
                        toast("已复制，不含API密钥");
                    }
                }).setNegativeButton("关闭", null).show();
    }

    private void section(String title) { content.addView(Ui.text(this, "\n" + title, 22)); }
    private void serviceFields(String service, String label) {
        CheckBox reuse = new CheckBox(this);
        reuse.setText("沿用对话接口地址和密钥（仅服务商支持该功能时）");
        reuse.setChecked(settings.bool(service + "_reuse", false));
        reuseOptions.put(service, reuse); content.addView(reuse);
        field(service + "_base_url", label + "独立接口地址（HTTPS）", settings.get(service + "_base_url", ""), false);
        EditText key = passwordField(label + "独立API密钥（留空保留）", settings.readKey(service + "_api_key").isEmpty());
        serviceKeys.put(service, key);
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
            String amapKey = amapKeyField.getText().toString().trim();
            if (!amapKey.isEmpty()) { settings.saveKey("amap_api_key", amapKey); amapKeyField.setText(""); amapKeyField.setHint("已加密保存，输入可替换"); }
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
            settings.putBool("amap_privacy_agreed", amapPrivacy.isChecked());
            settings.put("map_package", MapLauncher.PACKAGES[maps.getSelectedItemPosition()]);
            settings.putNumber("ball_size", 64 + size.getSelectedItemPosition() * 12);
            settings.putNumber("speech_rate", rate.getProgress() + 50);
            settings.putBool("hold_to_talk", recordingMode.getSelectedItemPosition() == 1);
            return true;
        } catch (Exception ignored) { toast("密钥保存失败，请检查手机安全存储"); return false; }
    }
    @Override protected void onResume() {
        super.onResume();
        if (checks != null) {
            boolean loc = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                    || checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
            checks.setText("悬浮窗：" + (android.provider.Settings.canDrawOverlays(this) ? "已允许" : "未允许")
                    + "\n麦克风：" + (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED ? "已允许" : "未允许")
                    + "\n定位：" + (loc ? "已允许" : "未允许")
                    + "\n屏幕读取：首次授权后当前会话复用");
        }
    }
    private void toast(String text) { Toast.makeText(this, text, Toast.LENGTH_LONG).show(); }
}
