package cn.family.littlecompanion;

import android.net.Uri;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CancellationException;

/** Small OpenAI-compatible HTTPS client; no SDK and no logging of private content. */
final class ApiClient {
    private final Settings settings;
    private volatile HttpURLConnection active;
    private volatile boolean cancelled;

    ApiClient(Settings settings) { this.settings = settings; }
    void cancel() { cancelled = true; HttpURLConnection connection = active; if (connection != null) connection.disconnect(); }

    private HttpURLConnection open(String path) throws Exception {
        if (cancelled) throw new CancellationException();
        Uri uri = Uri.parse(settings.baseUrl());
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null
                || uri.getQuery() != null || uri.getFragment() != null) {
            throw new Exception("请在设置中填写有效的HTTPS接口地址");
        }
        if (settings.apiKey().trim().isEmpty()) throw new Exception("请先让家人配置API密钥");
        HttpURLConnection connection = (HttpURLConnection) new URL(settings.baseUrl() + path).openConnection();
        active = connection;
        if (cancelled) { connection.disconnect(); throw new CancellationException(); }
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(45000);
        connection.setInstanceFollowRedirects(false);
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setRequestProperty("Authorization", "Bearer " + settings.apiKey());
        return connection;
    }

    private JSONObject response(HttpURLConnection connection) throws Exception {
        int status = connection.getResponseCode();
        if (status < 200 || status >= 300) {
            if (status == 401 || status == 403) throw new Exception("接口密钥或权限有问题，请让家人检查设置");
            if (status == 429) throw new Exception("接口次数或额度不足，稍后再试");
            throw new Exception("服务暂时不可用，请稍后再试（" + status + "）");
        }
        try (InputStream input = connection.getInputStream(); ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096]; int n;
            while ((n = input.read(buffer)) != -1) {
                if (cancelled) throw new CancellationException();
                if (bytes.size() + n > 1024 * 1024) throw new Exception("服务返回内容过大");
                bytes.write(buffer, 0, n);
            }
            return new JSONObject(bytes.toString("UTF-8"));
        }
    }

    String chat(String system, String user, String imageBase64) throws Exception {
        if (settings.model().trim().isEmpty()) throw new Exception("请先让家人配置模型名称");
        JSONArray messages = new JSONArray().put(new JSONObject().put("role", "system").put("content", system));
        Object content = user;
        if (imageBase64 != null) {
            content = new JSONArray()
                    .put(new JSONObject().put("type", "text").put("text", user))
                    .put(new JSONObject().put("type", "image_url").put("image_url",
                            new JSONObject().put("url", "data:image/jpeg;base64," + imageBase64)));
        }
        messages.put(new JSONObject().put("role", "user").put("content", content));
        JSONObject request = new JSONObject().put("model", imageBase64 == null ? settings.model() : settings.visionModel())
                .put("messages", messages).put("max_tokens", 400);
        HttpURLConnection connection = open("/chat/completions");
        try {
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            byte[] body = request.toString().getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(body.length);
            try (OutputStream out = connection.getOutputStream()) { out.write(body); }
            JSONObject result = response(connection);
            String answer = result.getJSONArray("choices").getJSONObject(0).getJSONObject("message").optString("content", "").trim();
            if (answer.isEmpty() || "null".equals(answer)) throw new Exception("没有收到有效回答，请再试一次");
            return answer;
        } finally { connection.disconnect(); active = null; }
    }

    String transcribe(File file) throws Exception {
        HttpURLConnection connection = open("/audio/transcriptions");
        String boundary = "CompanionBoundary" + System.nanoTime();
        try {
            connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
            connection.setChunkedStreamingMode(4096);
            try (OutputStream out = connection.getOutputStream()) {
                field(out, boundary, "model", settings.get("asr_model", "whisper-1"));
                field(out, boundary, "language", "zh");
                String hints = settings.get("speech_hints", "");
                if (!hints.trim().isEmpty()) field(out, boundary, "prompt", hints);
                write(out, "--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"voice.m4a\"\r\nContent-Type: audio/mp4\r\n\r\n");
                try (InputStream input = new FileInputStream(file)) {
                    byte[] buffer = new byte[4096]; int n;
                    while ((n = input.read(buffer)) != -1) {
                        if (cancelled) throw new CancellationException();
                        out.write(buffer, 0, n);
                    }
                }
                write(out, "\r\n--" + boundary + "--\r\n");
            }
            String text = response(connection).optString("text", "").trim();
            if (text.isEmpty()) throw new Exception("没听清，您再说一遍");
            return text;
        } finally { connection.disconnect(); active = null; }
    }

    private static void field(OutputStream out, String boundary, String name, String value) throws Exception {
        write(out, "--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + name + "\"\r\n\r\n" + value + "\r\n");
    }
    private static void write(OutputStream out, String value) throws Exception { out.write(value.getBytes(StandardCharsets.UTF_8)); }
}
