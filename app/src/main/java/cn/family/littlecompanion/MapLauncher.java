package cn.family.littlecompanion;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;

final class MapLauncher {
    static final String[] PACKAGES = {"com.autonavi.minimap", "com.baidu.BaiduMap", "com.tencent.map"};
    static final String[] NAMES = {"高德地图", "百度地图", "腾讯地图"};

    static boolean installed(android.content.Context context, String packageName) {
        try { context.getPackageManager().getPackageInfo(packageName, 0); return true; }
        catch (android.content.pm.PackageManager.NameNotFoundException ignored) { return false; }
    }

    static void open(Activity activity, Settings settings, String destination) throws Exception {
        String app = settings.get("map_package", PACKAGES[0]);
        if (!installed(activity, app)) throw new Exception("没有找到设置中的地图，请让家人检查");
        Uri uri;
        if (PACKAGES[0].equals(app)) {
            // A named destination needs the map's own geocoding/selection before walking navigation.
            uri = new Uri.Builder().scheme("androidamap").authority("poi")
                    .appendQueryParameter("sourceApplication", "小伴语音助手")
                    .appendQueryParameter("keywords", destination).build();
        } else if (PACKAGES[1].equals(app)) {
            uri = new Uri.Builder().scheme("baidumap").authority("map").path("/direction")
                    .appendQueryParameter("destination", "name:" + destination)
                    .appendQueryParameter("mode", "walking")
                    .appendQueryParameter("src", "cn.family.littlecompanion").build();
        } else {
            uri = new Uri.Builder().scheme("qqmap").authority("map").path("/routeplan")
                    .appendQueryParameter("type", "walk").appendQueryParameter("from", "我的位置")
                    .appendQueryParameter("to", destination)
                    .appendQueryParameter("referer", "小伴语音助手").build();
        }
        try { activity.startActivity(new Intent(Intent.ACTION_VIEW, uri).setPackage(app)); }
        catch (android.content.ActivityNotFoundException ignored) { throw new Exception("这个地图版本暂不支持此入口"); }
    }
}
