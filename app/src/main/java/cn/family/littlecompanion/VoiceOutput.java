package cn.family.littlecompanion;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import java.util.Locale;

final class VoiceOutput {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final AudioManager audio;
    private final AudioFocusRequest focus;
    private TextToSpeech tts;
    private boolean ready, failed, closed;
    private int sequence;
    private String pendingText;
    private Runnable pendingDone, onDone;
    private final java.util.function.Consumer<String> error;

    VoiceOutput(Context context, Settings settings, java.util.function.Consumer<String> error, Runnable interrupted) {
        this.error = error;
        audio = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        AudioAttributes attributes = new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build();
        focus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(attributes).setOnAudioFocusChangeListener(change -> {
                    if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
                        stop(); interrupted.run();
                    }
                }, handler).build();
        tts = new TextToSpeech(context, status -> handler.post(() -> {
            if (closed) return;
            if (status != TextToSpeech.SUCCESS) { fail(); return; }
            int language = tts.setLanguage(Locale.SIMPLIFIED_CHINESE);
            if (language == TextToSpeech.LANG_MISSING_DATA || language == TextToSpeech.LANG_NOT_SUPPORTED) { fail(); return; }
            tts.setSpeechRate(settings.speechRate());
            tts.setAudioAttributes(attributes);
            ready = true;
            if (pendingText != null) {
                String text = pendingText; Runnable done = pendingDone;
                pendingText = null; pendingDone = null; say(text, done);
            }
        }));
        tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override public void onStart(String id) { }
            @Override public void onDone(String id) { handler.post(() -> complete(id)); }
            @Override public void onError(String id) { handler.post(() -> { if (id.equals(String.valueOf(sequence))) fail(); }); }
        });
    }

    void say(String text, Runnable done) {
        stop();
        if (closed) return;
        if (failed) { error.accept("手机中文语音不可用，请让家人检查系统语音设置"); return; }
        if (!ready) { pendingText = text; pendingDone = done; return; }
        if (audio.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            error.accept("现在无法播放语音，请稍后再试"); return;
        }
        onDone = done;
        if (tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, String.valueOf(sequence)) == TextToSpeech.ERROR) fail();
    }

    private void complete(String id) {
        if (closed || !id.equals(String.valueOf(sequence))) return;
        Runnable done = onDone; onDone = null;
        audio.abandonAudioFocusRequest(focus);
        if (done != null) done.run();
    }
    private void fail() { failed = true; stop(); error.accept("手机中文语音不可用，请让家人检查系统语音设置"); }
    void stop() {
        sequence++; pendingText = null; pendingDone = null; onDone = null;
        if (tts != null) tts.stop();
        audio.abandonAudioFocusRequest(focus);
    }
    void close() { closed = true; stop(); if (tts != null) tts.shutdown(); }
}
