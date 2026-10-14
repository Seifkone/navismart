package app.organicmaps.navismart;

import app.organicmaps.sdk.routing.RouteMarkData;
import com.navismart.economy.*;
import java.time.*;
import java.util.*;

/** Ephemeral alternatives. Route edits, class changes and expiry invalidate all retained prices. */
public final class TripCostSession
{
  public record Variant(boolean avoidTolls, List<TripModel.Leg> legs, Estimate tolls, Estimate otherFees, Instant capturedAt) {
    public Variant { legs = List.copyOf(legs); }
    public TripModel.Route route() { return new TripModel.Route(avoidTolls ? "without-tolls" : "standard", "",
        new ArrayList<TripModel.Event>(legs), tolls, otherFees); }
  }
  private static String identity = "";
  private static Instant started;
  private static final Map<Boolean, Variant> variants = new LinkedHashMap<>();
  private TripCostSession() {}
  public static void scope(RouteMarkData[] points, int vehicleClass, String options, Instant now) {
    StringBuilder key = new StringBuilder(options).append('/').append(vehicleClass);
    if (points == null || points.length < 2) { clear(); return; }
    for (var p : points) {
      if (p == null) { clear(); return; }
      key.append('/').append(p.mPointType).append(':').append(p.mLat).append(':').append(p.mLon);
    }
    String next = key.toString();
    if (!next.equals(identity) || started == null || now.isBefore(started) || now.isAfter(started.plusSeconds(1800))) {
      clear(); identity = next; started = now;
    }
  }
  public static void put(Variant variant) { variants.put(variant.avoidTolls(), variant); }
  public static List<Variant> variants() { return List.copyOf(variants.values()); }
  public static void clear() { variants.clear(); identity=""; started=null; }
}
