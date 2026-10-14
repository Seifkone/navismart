package app.organicmaps.navismart;

import app.organicmaps.sdk.Framework;
import app.organicmaps.sdk.routing.RouteMarkData;
import app.organicmaps.sdk.routing.RouteMarkType;
import app.organicmaps.sdk.routing.RoutingController;
import com.navismart.economy.Estimate;
import com.navismart.economy.TripModel.Energy;
import com.navismart.fuel.FuelData;
import java.time.Instant;
import java.util.List;

/** Ephemeral baseline: never persisted and invalidated by any subsequent route revision. UI thread only. */
public final class FuelStopComparison
{
  private FuelStopComparison() {}
  public record Pending(FuelData.Station station, Energy energy, Instant snapshotAt,
                        List<com.navismart.economy.TripModel.Leg> directLegs, int expectedRevision,
                        double startLat, double startLon, double endLat, double endLon, Instant createdAt) {}
  private static Pending sPending;
  public static void capture(FuelData.Station station, Energy energy, Instant snapshotAt)
  {
    sPending = null;
    var routing = RoutingController.get();
    var points = Framework.nativeGetRoutePoints();
    double[] metrics = Framework.nativeGetRouteLegMetrics();
    if (!routing.isPlanning() || !routing.isBuilt() || !routing.isVehicleRouterType()
        || points == null || points.length != 2 || metrics == null || metrics.length != 2) return;
    RouteMarkData start = point(points, RouteMarkType.Start), end = point(points, RouteMarkType.Finish);
    if (start == null || end == null) return;
    sPending = new Pending(station, energy, snapshotAt, CoMapsEconomyAdapter.legs(metrics),
        routing.getRouteGeometry().mRevision + 1, start.mLat, start.mLon, end.mLat, end.mLon, Instant.now());
  }
  public static Pending current()
  {
    if (sPending == null) return null;
    var routing = RoutingController.get();
    if (!routing.isPlanning() || !routing.isBuilt() || !routing.isVehicleRouterType()) return null;
    var pending = sPending;
    var points = Framework.nativeGetRoutePoints();
    if (!matches(pending, points, routing.getRouteGeometry().mRevision, Instant.now())) { sPending = null; return null; }
    return pending;
  }
  public static boolean matches(Pending p, RouteMarkData[] points, int revision, Instant now)
  {
    if (points == null || points.length != 3 || revision != p.expectedRevision()
        || now.isBefore(p.createdAt()) || now.isAfter(p.createdAt().plusSeconds(1800))) return false;
    var start = point(points, RouteMarkType.Start); var end = point(points, RouteMarkType.Finish);
    var middle = point(points, RouteMarkType.Intermediate);
    return start != null && end != null && middle != null
        && same(start, p.startLat(), p.startLon()) && same(end, p.endLat(), p.endLon())
        && same(middle, p.station().point().latitude(), p.station().point().longitude());
  }
  private static RouteMarkData point(RouteMarkData[] points, RouteMarkType type)
  {
    RouteMarkData found = null;
    for (var p : points) if (p != null && p.mPointType == type) { if (found != null) return null; found = p; }
    return found;
  }
  private static boolean same(RouteMarkData p, double lat, double lon)
  { return Math.abs(p.mLat - lat) < 1e-7 && Math.abs(p.mLon - lon) < 1e-7; }
}
