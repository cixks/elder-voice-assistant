package cn.family.littlecompanion;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;

/** Local-only diagnostics. Never include the API key itself in a report or clipboard. */
final class AmapDiagnostics {
    private static volatile String initializedKeyFingerprint;

    static void recordInitialization(String key) {
        initializedKeyFingerprint = fingerprint(key);
    }

    static String fingerprint(String key) {
        if (key == null || key.trim().isEmpty()) return "未配置";
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(key.trim().getBytes(StandardCharsets.UTF_8));
            StringBuilder value = new StringBuilder();
            for (int i = 0; i < 8; i++) value.append(String.format(Locale.US, "%02X", digest[i] & 0xff));
            return "SHA256前16位：" + value;
        } catch (Exception ignored) { return "读取失败"; }
    }

    @SuppressWarnings("deprecation")
    static String report(Context context, Settings settings) {
        StringBuilder text = new StringBuilder("高德本机诊断（不含API密钥）\n");
        text.append("实际包名：").append(context.getPackageName()).append('\n');
        try {
            int flags = Build.VERSION.SDK_INT >= 28 ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES;
            PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), flags);
            text.append("版本：").append(info.versionName).append("（")
                    .append(Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode).append("）\n");
            Signature[] current = Build.VERSION.SDK_INT >= 28 && info.signingInfo != null
                    ? info.signingInfo.getApkContentsSigners() : info.signatures;
            appendCertificates(text, "当前安装签名", current);
            if (Build.VERSION.SDK_INT >= 28 && info.signingInfo != null && !info.signingInfo.hasMultipleSigners()) {
                Signature[] history = info.signingInfo.getSigningCertificateHistory();
                if (history != null && history.length > 1) appendCertificates(text, "历史签名（勿当作当前签名）", history);
            }
        } catch (Exception ignored) { text.append("读取安装包签名失败\n"); }
        text.append("系统：Android ").append(Build.VERSION.RELEASE).append(" / API ").append(Build.VERSION.SDK_INT).append('\n');
        text.append("设备：").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n');
        text.append("精确定位权限：").append(context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED ? "已允许" : "未允许").append('\n');
        text.append("大致定位权限：").append(context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
                == PackageManager.PERMISSION_GRANTED ? "已允许" : "未允许").append('\n');
        text.append("高德隐私同意：").append(AmapNavigator.privacyAgreed(settings) ? "已同意" : "未同意").append('\n');
        String saved = fingerprint(settings.readKey("amap_api_key"));
        String initialized = initializedKeyFingerprint;
        text.append("已保存Key指纹：").append(saved).append('\n');
        text.append("本进程最近完成SDK配置的Key指纹：").append(initialized == null ? "尚未完成配置" : initialized).append('\n');
        if (initialized != null && !initialized.equals(saved)) text.append("注意：已保存Key与最近SDK配置不同，请强行停止应用再打开。\n");
        text.append("指纹是Key的散列摘要，不是Key内容，也不是证书SHA1。\n")
                .append("请核对控制台同一条Android Key的包名和上面的当前签名SHA1。\n")
                .append("SDK配置记录不代表服务器鉴权成功，也不能证明SDK内部缓存已更新。\n")
                .append("本报告不发起网络请求；复制后由您自行决定是否分享。");
        return text.toString();
    }

    private static void appendCertificates(StringBuilder text, String label, Signature[] signatures) throws Exception {
        if (signatures == null || signatures.length == 0) { text.append(label).append("：未获取到\n"); return; }
        for (int index = 0; index < signatures.length; index++) {
            byte[] digest = MessageDigest.getInstance("SHA-1").digest(signatures[index].toByteArray());
            StringBuilder compact = new StringBuilder(), colon = new StringBuilder();
            for (byte b : digest) {
                String hex = String.format(Locale.US, "%02X", b & 0xff);
                compact.append(hex);
                if (colon.length() > 0) colon.append(':');
                colon.append(hex);
            }
            text.append(label).append(' ').append(index + 1).append(" SHA1：\n").append(colon)
                    .append("\n无冒号形式：\n").append(compact).append('\n');
        }
    }
}
