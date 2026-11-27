package cn.family.littlecompanion;

import android.content.Context;
import android.location.Location;
import android.os.Handler;
import android.os.Looper;
import com.amap.api.location.AMapLocationClient;
import com.amap.api.location.AMapLocationClientOption;
import com.amap.api.maps.MapsInitializer;
import com.amap.api.navi.AMapNavi;
import com.amap.api.navi.NaviSetting;
import com.amap.api.services.core.AMapException;
import com.amap.api.services.core.LatLonPoint;
import com.amap.api.services.core.PoiItemV2;
import com.amap.api.services.core.ServiceSettings;
import com.amap.api.services.poisearch.PoiResultV2;
import com.amap.api.services.poisearch.PoiSearchV2;
import com.amap.api.services.poisearch.VisualSearchResult;
import java.util.ArrayList;
import java.util.List;

/** Amap configuration, current-city location bias, and POI destination search without map UI. */
final class AmapNavigator {
    static final class Candidate {
        final String title, address, city, poiId;
        final double lat, lng;
        final int distanceMeters;

        Candidate(String title, String address, String city, String poiId, double lat, double lng, int distanceMeters) {
            this.title = title;
            this.address = address;
            this.city = city;
            this.poiId = poiId;
            this.lat = lat;
            this.lng = lng;
            this.distanceMeters = distanceMeters;
        }

        String label(int index) {
            StringBuilder text = new StringBuilder();
            text.append(index).append(". ").append(title);
            if (distanceMeters >= 0) text.append("（直线约").append(formatDistance(distanceMeters)).append("）");
            if (!address.isEmpty()) text.append("\n").append(address);
            return text.toString();
        }
    }

    static final class SearchResult {
        final boolean hasOrigin;
        final double originLat, originLng;
        final List<Candidate> candidates;

        SearchResult(boolean hasOrigin, double originLat, double originLng, List<Candidate> candidates) {
            this.hasOrigin = hasOrigin;
            this.originLat = originLat;
            this.originLng = originLng;
            this.candidates = candidates;
        }
    }

    interface SearchCallback {
        void onSuccess(SearchResult result);
        void onError(String message);
    }

    static boolean configured(Settings settings) {
        return !settings.readKey("amap_api_key").trim().isEmpty();
    }

    static boolean privacyAgreed(Settings settings) {
        return settings.bool("amap_privacy_agreed", false);
    }

    static void prepare(Context context, Settings settings) throws Exception {
        String key = settings.readKey("amap_api_key").trim();
        if (key.isEmpty()) throw new Exception("还没填写高德导航密钥，请让家人在设置里填写");
        if (!privacyAgreed(settings)) throw new Exception("需要先同意高德定位和导航隐私说明");
        Context app = context.getApplicationContext();
        MapsInitializer.updatePrivacyShow(app, true, true);
        MapsInitializer.updatePrivacyAgree(app, true);
        ServiceSettings.updatePrivacyShow(app, true, true);
        ServiceSettings.updatePrivacyAgree(app, true);
        NaviSetting.updatePrivacyShow(app, true, true);
        NaviSetting.updatePrivacyAgree(app, true);
        AMapLocationClient.updatePrivacyShow(app, true, true);
        AMapLocationClient.updatePrivacyAgree(app, true);
        MapsInitializer.setApiKey(key);
        ServiceSettings.getInstance().setApiKey(key);
        AMapNavi.setApiKey(app, key);
        AMapLocationClient.setApiKey(key);
        AmapDiagnostics.recordInitialization(key);
    }

    static void searchDestination(Context context, Settings settings, String keyword, SearchCallback callback) {
        try {
            prepare(context, settings);
        } catch (Exception e) {
            callback.onError(e.getMessage());
            return;
        }
        Context app = context.getApplicationContext();
        Handler handler = new Handler(Looper.getMainLooper());
        final boolean[] completed = {false};
        AMapLocationClient locationClient;
        try {
            locationClient = new AMapLocationClient(app);
        } catch (Exception e) {
            callback.onError("没能获取当前位置，请检查定位开关后再试");
            return;
        }
        AMapLocationClientOption option = new AMapLocationClientOption();
        option.setLocationMode(AMapLocationClientOption.AMapLocationMode.Hight_Accuracy);
        option.setOnceLocation(true);
        option.setOnceLocationLatest(true);
        option.setLocationCacheEnable(false);
        option.setNeedAddress(true);
        option.setHttpTimeOut(10000);
        locationClient.setLocationOption(option);
        Runnable timeout = () -> {
            if (completed[0]) return;
            completed[0] = true;
            destroyClient(locationClient);
            callback.onError("当前位置还没定位好，请到信号较好的地方再试，不会改为全国搜索");
        };
        handler.postDelayed(timeout, 12000);
        locationClient.setLocationListener(location -> {
            if (completed[0]) return;
            completed[0] = true;
            handler.removeCallbacks(timeout);
            destroyClient(locationClient);
            if (location != null && location.getErrorCode() == 0 && location.hasAccuracy()
                    && location.getAccuracy() > 0 && location.getAccuracy() <= 1000
                    && System.currentTimeMillis() - location.getTime() >= -5000
                    && System.currentTimeMillis() - location.getTime() <= 120000) {
                String city = location.getCity() != null ? location.getCity() : "";
                searchPoi(app, keyword, city, true, location.getLatitude(), location.getLongitude(), callback);
            } else {
                callback.onError("当前位置不够准确，请检查定位并到信号较好的地方再试");
            }
        });
        locationClient.startLocation();
    }

    private static void destroyClient(AMapLocationClient client) {
        try { client.stopLocation(); client.onDestroy(); } catch (Exception ignored) { }
    }

    private static void searchPoi(Context app, String keyword, String city, boolean hasOrigin, double originLat, double originLng, SearchCallback callback) {
        searchNearby(app, keyword, city, hasOrigin, originLat, originLng, 5000, callback);
    }

    private static void searchNearby(Context app, String keyword, String city, boolean hasOrigin, double originLat,
                                    double originLng, int radius, SearchCallback callback) {
        if (!hasOrigin) { callback.onError("请先获取当前位置，再搜索附近地点"); return; }
        PoiSearchV2.Query query = new PoiSearchV2.Query(keyword, "", city);
        query.setPageSize(25);
        query.setPageNum(1);
        try {
            PoiSearchV2 search = new PoiSearchV2(app, query);
            search.setBound(new PoiSearchV2.SearchBound(new LatLonPoint(originLat, originLng), radius, true));
            search.setOnPoiSearchListener(new PoiSearchV2.OnPoiSearchListener() {
                @Override public void onPoiSearched(PoiResultV2 result, int rCode) {
                    if (rCode != AMapException.CODE_AMAP_SUCCESS) {
                        callback.onError(friendlySearchError(rCode));
                        return;
                    }
                    List<PoiItemV2> raw = result != null ? result.getPois() : null;
                    List<Candidate> candidates = new ArrayList<>();
                    if (raw != null) {
                        for (PoiItemV2 item : raw) {
                            LatLonPoint pt = item.getLatLonPoint();
                            if (pt == null) continue;
                            int dist = -1;
                            if (hasOrigin) {
                                float[] out = new float[1];
                                Location.distanceBetween(originLat, originLng, pt.getLatitude(), pt.getLongitude(), out);
                                dist = Math.round(out[0]);
                            }
                            String title = safe(item.getTitle());
                            if (title.isEmpty()) continue;
                            if (dist > radius) continue;
                            candidates.add(new Candidate(title, safe(item.getSnippet()), safe(item.getCityName()),
                                    safe(item.getPoiId()), pt.getLatitude(), pt.getLongitude(), dist));
                        }
                    }
                    if (candidates.isEmpty() && radius == 5000) {
                        searchNearby(app, keyword, city, hasOrigin, originLat, originLng, 10000, callback);
                        return;
                    }
                    candidates.sort((left, right) -> Integer.compare(left.distanceMeters, right.distanceMeters));
                    if (candidates.isEmpty()) {
                        callback.onError("附近10公里没找到匹配地点，请换个称呼或说出具体名称");
                        return;
                    }
                    if (candidates.size() > 3) candidates = new ArrayList<>(candidates.subList(0, 3));
                    callback.onSuccess(new SearchResult(hasOrigin, originLat, originLng, candidates));
                }

                @Override public void onPoiItemSearched(PoiItemV2 item, int rCode) { }
                @Override public void onVisualSearched(VisualSearchResult result, int rCode) { }
            });
            search.searchPOIAsyn();
        } catch (Exception e) {
            callback.onError("高德地点搜索启动失败，请检查高德密钥或网络");
        }
    }

    private static String friendlySearchError(int code) {
        if (code == 1008 || code == 1009 || code == 1012) return "高德密钥或签名验证未通过（错误码 " + code + "），请检查密钥与SHA1绑定";
        if (code == 1802 || code == 1804 || code == 1806) return "网络不太顺畅，没能搜索到地点（错误码 " + code + "）";
        return "搜索地点失败（错误码 " + code + "），请稍后再试";
    }

    static String formatDistance(int meters) {
        if (meters < 1000) return Math.max(0, meters) + "米";
        return String.format(java.util.Locale.CHINA, "%.1f公里", meters / 1000.0);
    }

    private static String safe(String value) { return value == null ? "" : value.trim(); }
}
