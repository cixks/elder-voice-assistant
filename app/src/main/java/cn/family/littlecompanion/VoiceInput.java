package cn.family.littlecompanion;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import java.io.File;
import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class VoiceInput {
    interface Callback {
        void processing();
        void result(String text);
        void error(String message);
        void level(float value);
    }
    private final Context context;
    private final Settings settings;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private SpeechRecognizer recognizer;
    private MediaRecorder recorder;
    private ApiClient api;
    private File recording;
    private Callback callback;
    private boolean listening, manualStop;
    private int generation;
    private long started, lastSound;
    private boolean heardSound;
    private final Runnable systemTimeout = () -> {
        if (listening) fail("没听清，您再说一遍");
    };
    private final Runnable meter = new Runnable() {
        @Override public void run() {
            if (!listening || recorder == null) return;
            long now = android.os.SystemClock.elapsedRealtime();
            int amplitude;
            try { amplitude = recorder.getMaxAmplitude(); } catch (RuntimeException ignored) { fail("录音中断了，请再试一次"); return; }
            callback.level(Math.min(1f, amplitude / 10000f));
            // Simple local silence detection; actual threshold needs testing on the family's handset.
            if (amplitude > 650) { lastSound = now; heardSound = true; }
            if (now - started > 60000 || (!manualStop && heardSound && now - started > 1500 && now - lastSound > 1800)) {
                finishCloud(); return;
            }
            if (!manualStop && !heardSound && now - started > 9000) { fail("没听清，您再说一遍"); return; }
            handler.postDelayed(this, 120);
        }
    };

    VoiceInput(Context context, Settings settings) { this.context = context; this.settings = settings; }

    void listen(Callback callback) {
        listen(callback, false);
    }

    void listen(Callback callback, boolean manualStop) {
        cancel(); this.callback = callback;
        this.manualStop = manualStop;
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            callback.error("请先允许麦克风权限"); return;
        }
        listening = true;
        if (settings.cloudSpeech()) startCloud(); else startSystem();
    }

    @SuppressWarnings("deprecation")
    private void startCloud() {
        try {
            recording = File.createTempFile("voice-", ".m4a", context.getCacheDir());
            recorder = new MediaRecorder();
            recorder.setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION);
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            recorder.setAudioSamplingRate(16000);
            recorder.setAudioEncodingBitRate(32000);
            recorder.setOutputFile(recording.getAbsolutePath());
            recorder.prepare(); recorder.start();
            started = lastSound = android.os.SystemClock.elapsedRealtime(); heardSound = false;
            handler.postDelayed(meter, 120);
        } catch (Exception ignored) { fail("无法录音，请检查麦克风权限或是否正在通话"); }
    }

    void finish() {
        if (!listening) return;
        if (settings.cloudSpeech()) finishCloud();
        else if (recognizer != null) recognizer.stopListening();
    }

    private void finishCloud() {
        if (!listening || recorder == null) return;
        listening = false; handler.removeCallbacks(meter);
        try { recorder.stop(); }
        catch (RuntimeException ignored) { fail("录音太短，您再说一遍"); return; }
        finally { if (recorder != null) { recorder.release(); recorder = null; } }
        final File file = recording; recording = null;
        if (!heardSound) { file.delete(); fail("没听到声音，您再说一遍"); return; }
        final int ticket = generation;
        final Callback target = callback;
        final ApiClient request = new ApiClient(settings); api = request;
        target.processing();
        worker.execute(() -> {
            try {
                String text = request.transcribe(file);
                handler.post(() -> { if (ticket == generation) target.result(text); });
            } catch (Exception exception) {
                handler.post(() -> { if (ticket == generation) target.error(friendly(exception)); });
            } finally { file.delete(); }
        });
    }

    private void startSystem() {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            fail("手机没有系统语音识别，请在设置中改用云端识别"); return;
        }
        int ticket = generation;
        recognizer = SpeechRecognizer.createSpeechRecognizer(context);
        recognizer.setRecognitionListener(new RecognitionListener() {
            @Override public void onReadyForSpeech(Bundle params) { }
            @Override public void onBeginningOfSpeech() { }
            @Override public void onRmsChanged(float rmsdB) { if (ticket == generation) callback.level(Math.max(0, Math.min(1, rmsdB / 12))); }
            @Override public void onBufferReceived(byte[] buffer) { }
            @Override public void onEndOfSpeech() { if (ticket == generation) callback.processing(); }
            @Override public void onError(int error) {
                if (ticket != generation) return;
                listening = false;
                handler.removeCallbacks(systemTimeout);
                callback.error(error == SpeechRecognizer.ERROR_NETWORK || error == SpeechRecognizer.ERROR_NETWORK_TIMEOUT
                        ? "语音识别网络不通，请稍后再试" : "没听清，您再说一遍");
            }
            @Override public void onResults(Bundle results) {
                if (ticket != generation) return;
                listening = false;
                handler.removeCallbacks(systemTimeout);
                ArrayList<String> texts = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                if (texts == null || texts.isEmpty()) callback.error("没听清，您再说一遍"); else callback.result(texts.get(0));
            }
            @Override public void onPartialResults(Bundle partialResults) { }
            @Override public void onEvent(int eventType, Bundle params) { }
        });
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN");
        intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
        if (manualStop) {
            intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 60000L);
            intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 60000L);
        }
        try { recognizer.startListening(intent); handler.postDelayed(systemTimeout, manualStop ? 65000 : 30000); }
        catch (RuntimeException ignored) { fail("系统语音识别暂时不可用，请改用云端识别"); }
    }

    private void fail(String message) { Callback target = callback; cancel(); if (target != null) target.error(message); }
    void cancel() {
        generation++; listening = false; handler.removeCallbacks(meter); handler.removeCallbacks(systemTimeout);
        if (api != null) { api.cancel(); api = null; }
        if (recognizer != null) { recognizer.cancel(); recognizer.destroy(); recognizer = null; }
        if (recorder != null) { try { recorder.stop(); } catch (RuntimeException ignored) { } recorder.release(); recorder = null; }
        if (recording != null) { recording.delete(); recording = null; }
    }
    void close() { cancel(); worker.shutdownNow(); }
    static String friendly(Exception e) {
        if (e instanceof java.net.SocketTimeoutException) return "等得有点久，请稍后再试";
        if (e instanceof java.io.IOException) return "网络没连好，请稍后再试";
        return e.getMessage() == null ? "这次没成功，请再试一次" : e.getMessage();
    }
}
