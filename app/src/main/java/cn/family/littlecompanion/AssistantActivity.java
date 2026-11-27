package cn.family.littlecompanion;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.media.projection.MediaProjectionManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ResultReceiver;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class AssistantActivity extends Activity {
    private enum State { IDLE, SPEAKING, LISTENING, WORKING, CONFIRMING, CAPTURING, CHOOSING_DEST, NAVIGATING }
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private Settings settings;
    private VoiceInput input;
    private VoiceOutput output;
    private TextView status, detail, naviSummary;
    private Button action, replay, replayNavi, stopNavi;
    private LinearLayout panel, confirmationButtons, destinationChoices, naviPanel;
    private AssistantBrain.Intent pending;
    private ApiClient api;
    private State state = State.IDLE;
    private boolean captureInProgress, destroyed, holdingMic;
    private int generation;
    private String currentAnswer = "";
    private String pendingNavigationPlace = "";
    private static final int CAPTURE_REQUEST = 101;
    private static final int MIC_REQUEST = 102;
    private static final int LOCATION_REQUEST = 103;

    private final WalkNaviService.Listener naviUiListener = snapshot -> {
        if (destroyed || isFinishing()) return;
        if (snapshot.active) {
            setState(State.NAVIGATING,
                    snapshot.destination.isEmpty() ? "正在步行导航" : "正在前往：" + snapshot.destination,
                    snapshot.instruction);
            naviSummary.setText(snapshot.summary.isEmpty() ? "跟随语音提示前行，点空白处可收起面板" : snapshot.summary);
        } else if (state == State.NAVIGATING) {
            setState(State.IDLE,
                    snapshot.instruction.isEmpty() ? "步行导航已结束" : snapshot.instruction,
                    "点空白处关闭，或点麦克风继续提问");
        }
    };

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        settings = new Settings(this);
        input = new VoiceInput(this, settings);
        output = new VoiceOutput(this, settings, this::showSilentError, () -> {
            cancelWork(); setState(State.IDLE, "语音已暂停", "通话或其他声音结束后，点一下再问");
        });
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setGravity(Gravity.BOTTOM);
        getWindow().setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT);
        setFinishOnTouchOutside(true);

        panel = Ui.column(this);
        panel.setBackground(Ui.background(Color.rgb(245, 250, 247), 24, this));
        panel.addView(Ui.text(this, "小伴", 15));
        status = Ui.text(this, "准备好了", 25); panel.addView(status);
        detail = Ui.text(this, "", 18); panel.addView(detail);

        action = Ui.button(this, "麦克风", v -> {
            if (!settings.bool("hold_to_talk", false)) {
                if (recordingNow()) input.finish(); else startRecording();
            }
        });
        action.setTextSize(23);
        action.setMinHeight(Ui.dp(this, 88));
        action.setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_companion, 0, 0, 0);
        action.setCompoundDrawablePadding(Ui.dp(this, 10));
        action.setOnTouchListener((view, event) -> {
            if (!settings.bool("hold_to_talk", false)) return false;
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    if (state == State.IDLE && action.isEnabled()) { holdingMic = true; action.setPressed(true); startRecording(); }
                    return true;
                case MotionEvent.ACTION_UP:
                    action.setPressed(false);
                    if (holdingMic) { holdingMic = false; if (recordingNow()) input.finish(); }
                    view.performClick();
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    action.setPressed(false);
                    if (holdingMic) { holdingMic = false; input.cancel(); ready(); }
                    return true;
                default: return true;
            }
        });
        panel.addView(action);

        replay = Ui.button(this, "重新播放当前语音", v -> {
            if (!currentAnswer.isEmpty()) say(currentAnswer, this::showAnswerReady);
        });
        replay.setTextSize(21);
        replay.setTextColor(Ui.GREEN);
        replay.setMinHeight(Ui.dp(this, 68));
        replay.setVisibility(View.GONE);
        panel.addView(replay);

        confirmationButtons = new LinearLayout(this);
        confirmationButtons.setOrientation(LinearLayout.HORIZONTAL);
        Button yes = Ui.button(this, "✓\n对，继续", v -> {
            if (state != State.CONFIRMING || pending == null) return;
            setState(State.WORKING, "正在处理", "稍等一下");
            executePending();
        });
        Button no = Ui.button(this, "✕\n不对，重说", v -> {
            if (state != State.CONFIRMING) return;
            pending = null;
            ready();
            status.setText("请重新说需求");
        });
        yes.setTextColor(Ui.GREEN);
        no.setTextColor(Color.rgb(166, 39, 39));
        for (Button button : new Button[]{yes, no}) {
            button.setTextSize(26);
            button.setMinHeight(Ui.dp(this, 112));
            LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            layout.setMargins(Ui.dp(this, 4), Ui.dp(this, 8), Ui.dp(this, 4), Ui.dp(this, 8));
            confirmationButtons.addView(button, layout);
        }
        yes.setContentDescription("理解正确，继续执行");
        no.setContentDescription("理解不对，重新说需求");
        confirmationButtons.setVisibility(View.GONE);
        panel.addView(confirmationButtons);

        destinationChoices = new LinearLayout(this);
        destinationChoices.setOrientation(LinearLayout.VERTICAL);
        destinationChoices.setVisibility(View.GONE);
        panel.addView(destinationChoices);

        naviPanel = new LinearLayout(this);
        naviPanel.setOrientation(LinearLayout.VERTICAL);
        naviSummary = Ui.text(this, "", 17);
        naviPanel.addView(naviSummary);
        LinearLayout naviActions = new LinearLayout(this);
        naviActions.setOrientation(LinearLayout.HORIZONTAL);
        replayNavi = Ui.button(this, "重播指引", v -> startService(
                new Intent(this, WalkNaviService.class).setAction(WalkNaviService.ACTION_REPEAT_PROMPT)));
        stopNavi = Ui.button(this, "结束步行导航", v -> startService(
                new Intent(this, WalkNaviService.class).setAction(WalkNaviService.ACTION_STOP)));
        stopNavi.setTextColor(Color.rgb(166, 39, 39));
        for (Button button : new Button[]{replayNavi, stopNavi}) {
            button.setTextSize(21);
            button.setMinHeight(Ui.dp(this, 76));
            LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            layout.setMargins(Ui.dp(this, 4), Ui.dp(this, 8), Ui.dp(this, 4), Ui.dp(this, 8));
            naviActions.addView(button, layout);
        }
        naviPanel.addView(naviActions);
        naviPanel.addView(Ui.button(this, "问其他问题", v -> {
            cancelWork();
            ready();
        }));
        naviPanel.setVisibility(View.GONE);
        panel.addView(naviPanel);

        panel.addView(Ui.button(this, "关闭 / 收起", v -> finish()));
        setContentView(panel);
        getWindow().setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT);
        WalkNaviService.setUiListener(naviUiListener);

        if (WalkNaviService.isRunning()) {
            naviUiListener.onUpdate(WalkNaviService.snapshot());
        } else if (!settings.configured()) {
            setState(State.IDLE, "请先让家人配置接口", "打开小伴的设置页面填写密钥和模型名称");
        } else {
            ensureMicrophone();
        }
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (captureInProgress) return;
        if (WalkNaviService.isRunning()) naviUiListener.onUpdate(WalkNaviService.snapshot());
        else ensureMicrophone();
    }

    private void ensureMicrophone() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, MIC_REQUEST);
        else begin();
    }

    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(request, permissions, results);
        if (request == MIC_REQUEST) {
            if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) begin();
            else showSilentError("需要麦克风权限才能听您讲话");
        } else if (request == LOCATION_REQUEST) {
            boolean granted = false;
            for (int r : results) if (r == PackageManager.PERMISSION_GRANTED) { granted = true; break; }
            if (granted && !pendingNavigationPlace.isEmpty()) {
                String place = pendingNavigationPlace;
                pendingNavigationPlace = "";
                beginAmapNavigation(place);
            } else {
                pendingNavigationPlace = "";
                error("需要定位权限才能开始无地图步行导航");
            }
        }
    }

    private void begin() {
        cancelWork(); pending = null; currentAnswer = ""; pendingNavigationPlace = "";
        if (!settings.configured()) { showSilentError("请先让家人配置接口"); return; }
        ready();
    }

    private boolean recordingNow() { return state == State.LISTENING; }
    private void startRecording() {
        if (state != State.IDLE) return;
        if (!settings.configured()) { showSilentError("请先让家人配置接口"); return; }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) { ensureMicrophone(); return; }
        listen();
    }

    private void ready() {
        if (destroyed || isFinishing()) return;
        setState(State.IDLE, "准备好了", recordingHint());
    }

    private void showAnswerReady() {
        if (destroyed || isFinishing()) return;
        setState(State.IDLE, currentAnswer.isEmpty() ? "播报完成" : currentAnswer, "点空白处关闭，或重听/继续问");
    }

    private void showConfirmation() {
        if (destroyed || isFinishing() || pending == null) return;
        input.cancel();
        setState(State.CONFIRMING, pending.confirmation, "理解对了点勾，不对点叉重新说");
    }

    private String recordingHint() {
        return settings.bool("hold_to_talk", false) ? "按住麦克风说话，松开发送" : "点麦克风开始，再点一次结束";
    }

    private void listen() {
        if (destroyed || isFinishing()) return;
        currentAnswer = "";
        setState(State.LISTENING, "正在录音", settings.bool("hold_to_talk", false) ? "松开发送" : "再点麦克风结束录音");
        input.listen(new VoiceInput.Callback() {
            @Override public void processing() { setState(State.WORKING, "正在听懂您的话", "稍等一下"); }
            @Override public void result(String words) { heard(words); }
            @Override public void error(String message) { AssistantActivity.this.error(message); }
            @Override public void level(float value) { action.setAlpha(0.7f + value * 0.3f); }
        }, true);
    }

    private static String normalized(String text) { return text.replaceAll("[\\p{P}\\p{Z}\\s]", ""); }
    private void heard(String words) {
        detail.setText("您说：" + words);
        String simple = normalized(words);
        if (simple.matches("(算了|不问了|取消|别说了|停止|不用了|退出)")) { finish(); return; }
        if (simple.matches("(再说一遍|再说一次|重复一下|没听清|刚刚没听见)")) {
            String last = ((CompanionApp) getApplication()).lastAnswer();
            if (last.isEmpty()) say("还没有上一条回答。", this::ready);
            else { currentAnswer = last; say(last, this::showAnswerReady); }
            return;
        }
        final String previous = pending == null ? "" : pending.request;
        runTask(client -> AssistantBrain.understand(client, settings, words, previous), result -> {
            pending = result;
            if ("cancel".equals(result.type)) { finish(); return; }
            if ("repeat".equals(result.type)) {
                String last = ((CompanionApp) getApplication()).lastAnswer();
                if (last.isEmpty()) say("还没有上一条回答。", this::ready);
                else { currentAnswer = last; say(last, this::showAnswerReady); }
                return;
            }
            say(result.confirmation, this::showConfirmation);
        });
    }

    private void executePending() {
        if (pending == null) { begin(); return; }
        switch (pending.type) {
            case "screen":
                if (ScreenCaptureService.hasSession()) {
                    setCaptureHidden(true);
                    setState(State.CAPTURING, "读取当前页面", "");
                    startCapture(null);
                    break;
                }
                say("需要读取当前页面，请在系统提示中允许共享整个屏幕。", () -> {
                    setCaptureHidden(true);
                    setState(State.CAPTURING, "读取当前页面", "");
                    try { startActivityForResult(getSystemService(MediaProjectionManager.class).createScreenCaptureIntent(), CAPTURE_REQUEST); }
                    catch (RuntimeException ignored) { setCaptureHidden(false); error("这台手机暂时无法读取屏幕"); }
                });
                break;
            case "navigate":
                String destination = pending.destination;
                if (destination.trim().isEmpty() || destination.matches("(那里|那个地方|上次那个地方|某某地方|附近|那个医院|上次那个医院)")) {
                    say("请告诉我具体地名。", this::ready); return;
                }
                if (destination.matches("(家|回家|我家|家里)")) {
                    destination = settings.get("home", "");
                    if (destination.trim().isEmpty()) { error("还没设置家的地址，请让家人设置"); return; }
                }
                final String place = destination;
                if (AmapNavigator.configured(settings)) {
                    beginAmapNavigation(place);
                } else {
                    say("尚未配置高德密钥，现在为您打开外部地图确认地点。", () -> {
                        try { MapLauncher.open(this, settings, place); finish(); }
                        catch (Exception exception) { error(exception.getMessage()); }
                    });
                }
                break;
            default: answer(null); break;
        }
    }

    private void beginAmapNavigation(String place) {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED
                && checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            pendingNavigationPlace = place;
            say("需要定位权限才能为您语音步行导航，请在提示中允许。", () -> requestPermissions(
                    new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION}, LOCATION_REQUEST));
            return;
        }
        if (!AmapNavigator.privacyAgreed(settings)) {
            new AlertDialog.Builder(this)
                    .setTitle("高德步行导航说明")
                    .setMessage("使用无地图语音步行导航时，高德SDK会读取当前位置并联网搜索目的地，仅在导航期间定位。是否同意并继续？")
                    .setPositiveButton("同意并开始", (dialog, which) -> {
                        settings.putBool("amap_privacy_agreed", true);
                        searchAmapDestination(place);
                    })
                    .setNegativeButton("取消", (dialog, which) -> ready())
                    .show();
            return;
        }
        searchAmapDestination(place);
    }

    private void searchAmapDestination(String place) {
        final int ticket = generation;
        setState(State.WORKING, "正在查找：" + place, "请稍等，正在确认地点");
        AmapNavigator.searchDestination(this, settings, place, new AmapNavigator.SearchCallback() {
            @Override public void onSuccess(AmapNavigator.SearchResult result) {
                if (destroyed || ticket != generation) return;
                if (result.candidates.isEmpty()) {
                    error("没有找到叫“" + place + "”的地方，请换个具体名称");
                    return;
                }
                if (result.candidates.size() == 1) {
                    AmapNavigator.Candidate only = result.candidates.get(0);
                    say("找到" + only.title + "，正在开始步行导航", () -> startWalkNavi(only, result));
                    return;
                }
                showDestinationChoices(result);
            }

            @Override public void onError(String message) {
                if (destroyed || ticket != generation) return;
                error(message);
            }
        });
    }

    private void showDestinationChoices(AmapNavigator.SearchResult result) {
        destinationChoices.removeAllViews();
        for (int i = 0; i < result.candidates.size(); i++) {
            final AmapNavigator.Candidate candidate = result.candidates.get(i);
            Button choice = Ui.button(this, candidate.label(i + 1), v -> {
                destinationChoices.removeAllViews();
                say("好，为您步行导航到" + candidate.title, () -> startWalkNavi(candidate, result));
            });
            choice.setTextSize(19);
            choice.setAllCaps(false);
            choice.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            choice.setMinHeight(Ui.dp(this, 72));
            destinationChoices.addView(choice);
        }
        setState(State.CHOOSING_DEST, "找到几个地点", "请点选您要去哪一个");
        output.say("找到几个地点，请点选您要去哪一个", null);
    }

    private void startWalkNavi(AmapNavigator.Candidate candidate, AmapNavigator.SearchResult origin) {
        Intent service = new Intent(this, WalkNaviService.class)
                .setAction(WalkNaviService.ACTION_START)
                .putExtra(WalkNaviService.EXTRA_DEST_NAME, candidate.title)
                .putExtra(WalkNaviService.EXTRA_DEST_LAT, candidate.lat)
                .putExtra(WalkNaviService.EXTRA_DEST_LNG, candidate.lng)
                .putExtra(WalkNaviService.EXTRA_HAS_FROM, origin.hasOrigin)
                .putExtra(WalkNaviService.EXTRA_FROM_LAT, origin.originLat)
                .putExtra(WalkNaviService.EXTRA_FROM_LNG, origin.originLng);
        try {
            startForegroundService(service);
            setState(State.NAVIGATING, "正在前往：" + candidate.title, "正在规划步行路线");
            naviSummary.setText("无需打开地图页面，跟随语音提示前行即可");
        } catch (RuntimeException ignored) {
            error("步行导航服务无法启动，请重试");
        }
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != CAPTURE_REQUEST) return;
        if (result != RESULT_OK || data == null) {
            setCaptureHidden(false); error("没有读取屏幕，您可以稍后再问"); return;
        }
        startCapture(data);
    }

    private void startCapture(Intent data) {
        final int ticket = generation;
        ResultReceiver receiver = new ResultReceiver(handler) {
            @Override protected void onReceiveResult(int code, Bundle bundle) {
                if (destroyed || ticket != generation) { ScreenCaptureService.takeImage(); return; }
                setCaptureHidden(false);
                String image = ScreenCaptureService.takeImage();
                if (code == RESULT_OK && image != null) answer(image);
                else error(bundle == null ? "没能读取画面" : bundle.getString("error", "没能读取画面"));
            }
        };
        Intent service = new Intent(this, ScreenCaptureService.class)
                .putExtra(ScreenCaptureService.EXTRA_RECEIVER, receiver);
        if (data != null) service.putExtra(ScreenCaptureService.EXTRA_RESULT, data);
        try { startForegroundService(service); }
        catch (RuntimeException ignored) { setCaptureHidden(false); error("屏幕读取无法启动，请重试"); }
    }

    private void setCaptureHidden(boolean hidden) {
        captureInProgress = hidden;
        setFinishOnTouchOutside(!hidden);
        panel.setVisibility(hidden ? View.INVISIBLE : View.VISIBLE);
        getWindow().getDecorView().setVisibility(hidden ? View.INVISIBLE : View.VISIBLE);
    }

    private void answer(String image) {
        String request = pending.request;
        String previous = ((CompanionApp) getApplication()).lastAnswer();
        runTask(client -> AssistantBrain.answer(client, request, image, previous), result -> {
            ((CompanionApp) getApplication()).remember(result);
            currentAnswer = result;
            say(result, this::showAnswerReady);
        });
    }

    private interface Task<T> { T run(ApiClient client) throws Exception; }
    private interface Result<T> { void accept(T result); }
    private <T> void runTask(Task<T> task, Result<T> result) {
        setState(State.WORKING, "正在帮您看", "稍等一下");
        final int ticket = generation;
        final ApiClient client = new ApiClient(settings); api = client;
        worker.execute(() -> {
            try {
                T value = task.run(client);
                handler.post(() -> { if (!destroyed && ticket == generation) { api = null; result.accept(value); } });
            } catch (Exception exception) {
                handler.post(() -> { if (!destroyed && ticket == generation) { api = null; error(VoiceInput.friendly(exception)); } });
            }
        });
    }

    private void say(String text, Runnable done) { holdingMic = false; setState(State.SPEAKING, text, "正在播报；点空白处可关闭"); output.say(text, done); }
    private void error(String message) { holdingMic = false; say(message, () -> setState(State.IDLE, message, recordingHint())); }
    private void showSilentError(String message) { setState(State.IDLE, message, "请让家人检查设置"); }

    private void setState(State value, String title, String subtitle) {
        state = value; status.setText(title); detail.setText(subtitle); action.setAlpha(1f);
        confirmationButtons.setVisibility(value == State.CONFIRMING ? View.VISIBLE : View.GONE);
        destinationChoices.setVisibility(value == State.CHOOSING_DEST ? View.VISIBLE : View.GONE);
        naviPanel.setVisibility(value == State.NAVIGATING ? View.VISIBLE : View.GONE);
        boolean micVisible = value == State.IDLE || value == State.LISTENING;
        action.setVisibility(micVisible ? View.VISIBLE : View.GONE);
        action.setEnabled(micVisible && settings.configured());
        boolean replayVisible = !currentAnswer.isEmpty() && (value == State.IDLE || value == State.SPEAKING);
        replay.setVisibility(replayVisible ? View.VISIBLE : View.GONE);
        boolean recording = value == State.LISTENING;
        action.setText(recording ? (settings.bool("hold_to_talk", false) ? "松开发送" : "结束录音")
                : (settings.bool("hold_to_talk", false) ? "按住说话" : "点击录音"));
        action.setContentDescription(action.getText());
    }

    private void cancelWork() {
        generation++; holdingMic = false; input.cancel(); output.stop();
        if (api != null) { api.cancel(); api = null; }
        if (captureInProgress) { stopService(new Intent(this, ScreenCaptureService.class)); ScreenCaptureService.takeImage(); setCaptureHidden(false); }
    }

    @Override protected void onPause() {
        super.onPause();
        if (!captureInProgress && !isFinishing()) {
            if (WalkNaviService.isRunning() && state == State.NAVIGATING) return;
            cancelWork();
            setState(State.IDLE, currentAnswer.isEmpty() ? "已暂停" : currentAnswer, "点空白处关闭，或点一下重新说话");
        }
    }

    @Override public void finish() { cancelWork(); super.finish(); }
    @Override protected void onDestroy() {
        destroyed = true;
        WalkNaviService.setUiListener(null);
        cancelWork(); input.close(); output.close(); worker.shutdownNow();
        super.onDestroy();
    }
}
