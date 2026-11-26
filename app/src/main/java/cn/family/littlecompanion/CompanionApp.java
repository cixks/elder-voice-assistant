package cn.family.littlecompanion;

import android.app.Application;
import java.io.File;

public final class CompanionApp extends Application {
    private String lastAnswer = "";

    @Override public void onCreate() {
        super.onCreate();
        // Only our own transient audio files; never retain recordings between process launches.
        File[] files = getCacheDir().listFiles((dir, name) -> name.startsWith("voice-") && name.endsWith(".m4a"));
        if (files != null) for (File file : files) file.delete();
    }

    public synchronized String lastAnswer() { return lastAnswer; }
    public synchronized void remember(String text) { lastAnswer = text; }
}
