package cn.family.littlecompanion;

import android.location.Location;
import com.amap.api.navi.model.AMapNaviLink;
import com.amap.api.navi.model.AMapNaviLocation;
import com.amap.api.navi.model.AMapNaviPath;
import com.amap.api.navi.model.AMapNaviStep;
import com.amap.api.navi.model.NaviLatLng;
import java.util.List;

/** Uses matched walking-route geometry, never the direct bearing to the destination. */
final class WalkRouteDirection {
    final float bearing;
    final int meters;

    private WalkRouteDirection(float bearing, double meters) {
        this.bearing = bearing;
        this.meters = Math.max(0, (int) Math.round(meters));
    }

    static WalkRouteDirection calculate(AMapNaviPath path, AMapNaviLocation location) {
        if (path == null || location == null || !location.isMatchNaviPath()) return null;
        List<AMapNaviStep> steps = path.getSteps();
        int stepIndex = location.getCurStepIndex();
        if (steps == null || stepIndex < 0 || stepIndex >= steps.size()) return null;
        List<AMapNaviLink> links = steps.get(stepIndex).getLinks();
        int linkIndex = location.getCurLinkIndex(), pointIndex = location.getCurPointIndex();
        if (links == null || linkIndex < 0 || linkIndex >= links.size()) return null;
        NaviLatLng position = location.getCoord();
        if (!valid(position)) return null;
        List<NaviLatLng> points = links.get(linkIndex).getCoords();
        if (points == null || pointIndex < 0 || pointIndex >= points.size() - 1) return null;
        NaviLatLng start = points.get(pointIndex), end = points.get(pointIndex + 1);
        if (!valid(start) || !valid(end)) return null;
        // Project onto the SDK-matched segment; reject implausible index/location combinations.
        double scale = Math.cos(Math.toRadians(position.getLatitude()));
        double dx = (end.getLongitude() - start.getLongitude()) * scale;
        double dy = end.getLatitude() - start.getLatitude();
        double denominator = dx * dx + dy * dy;
        if (denominator < 1e-16) return null;
        double t = ((position.getLongitude() - start.getLongitude()) * scale * dx
                + (position.getLatitude() - start.getLatitude()) * dy) / denominator;
        t = Math.max(0, Math.min(1, t));
        NaviLatLng projected = new NaviLatLng(start.getLatitude() + t * dy,
                start.getLongitude() + t * (end.getLongitude() - start.getLongitude()));
        if (distance(position, projected) > 35) return null;
        float heading = bearing(start, end);
        double remaining = distance(projected, end);
        // Distance is along the route until a significant bend or this navigation step ends.
        NaviLatLng previous = end;
        for (int l = linkIndex; l < links.size(); l++) {
            List<NaviLatLng> coords = links.get(l).getCoords();
            if (coords == null) return null;
            int first = l == linkIndex ? pointIndex + 2 : 0;
            for (int p = first; p < coords.size(); p++) {
                NaviLatLng next = coords.get(p);
                if (!valid(next)) return null;
                double length = distance(previous, next);
                if (length < 0.5) { previous = next; continue; }
                if (Math.abs(delta(bearing(previous, next) - heading)) > 30) {
                    return new WalkRouteDirection(heading, remaining);
                }
                remaining += length;
                previous = next;
            }
        }
        return new WalkRouteDirection(heading, remaining);
    }

    static float delta(float angle) { return ((angle % 360) + 540) % 360 - 180; }

    private static boolean valid(NaviLatLng p) {
        return p != null && Double.isFinite(p.getLatitude()) && Double.isFinite(p.getLongitude())
                && Math.abs(p.getLatitude()) <= 90 && Math.abs(p.getLongitude()) <= 180;
    }

    private static double distance(NaviLatLng a, NaviLatLng b) {
        float[] result = new float[1];
        Location.distanceBetween(a.getLatitude(), a.getLongitude(), b.getLatitude(), b.getLongitude(), result);
        return result[0];
    }

    private static float bearing(NaviLatLng a, NaviLatLng b) {
        float[] result = new float[2];
        Location.distanceBetween(a.getLatitude(), a.getLongitude(), b.getLatitude(), b.getLongitude(), result);
        return result[1];
    }
}
