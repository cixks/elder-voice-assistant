package cn.family.littlecompanion;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

public final class Settings {
    private static final String ALIAS = "little-companion-api-key";
    private final SharedPreferences prefs;

    public Settings(Context context) { prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE); }
    public String get(String key, String fallback) { return prefs.getString(key, fallback); }
    public void put(String key, String value) { prefs.edit().putString(key, value).apply(); }
    public boolean bool(String key, boolean fallback) { return prefs.getBoolean(key, fallback); }
    public void putBool(String key, boolean value) { prefs.edit().putBoolean(key, value).apply(); }
    public int number(String key, int fallback) { return prefs.getInt(key, fallback); }
    public void putNumber(String key, int value) { prefs.edit().putInt(key, value).apply(); }
    public String baseUrl() { return get("base_url", "https://api.openai.com/v1").replaceAll("/+$", ""); }
    public String model() { return get("model", ""); }
    public String visionModel() { return get("vision_model", "").trim().isEmpty() ? model() : get("vision_model", ""); }
    public boolean cloudSpeech() { return bool("cloud_speech", true); }
    public float speechRate() { return number("speech_rate", 90) / 100f; }
    public boolean configured() { return !model().trim().isEmpty() && !apiKey().trim().isEmpty(); }

    private SecretKey secretKey() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (!store.containsAlias(ALIAS)) {
            KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
            generator.init(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
            generator.generateKey();
        }
        return (SecretKey) store.getKey(ALIAS, null);
    }

    public void saveApiKey(String value) throws Exception {
        if (value.trim().isEmpty()) { prefs.edit().remove("api_key").apply(); return; }
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, secretKey());
        String encrypted = Base64.encodeToString(cipher.doFinal(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)), Base64.NO_WRAP);
        put("api_key", Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP) + ":" + encrypted);
    }

    public String apiKey() {
        try {
            String[] parts = get("api_key", "").split(":");
            if (parts.length != 2) return "";
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), new GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)));
            return new String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception ignored) { return ""; }
    }
}
