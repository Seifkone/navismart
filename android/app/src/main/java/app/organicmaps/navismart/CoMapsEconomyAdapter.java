package app.organicmaps.navismart;

import app.organicmaps.sdk.util.Distance;
import com.navismart.economy.EconomyEngine;
import com.navismart.economy.Estimate;
import com.navismart.economy.TripModel.*;
import com.navismart.economy.VehicleProfiles.Profile;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;

/** Initial route preview bridge. No network, text parsing, invented toll tariff or automatic reroute. */
public final class CoMapsEconomyAdapter
{
  private CoMapsEconomyAdapter() {}
  public static double kilometres(Distance distance)
  {
    if (distance == null || !Double.isFinite(distance.mDistance) || distance.mDistance < 0)
      throw new IllegalArgumentException("A valid route distance is required");
    return distance.mDistance * switch (distance.mUnits)
    {
      case Meters -> 0.001;
      case Kilometers -> 1.0;
      case Feet -> 0.0003048;
      case Miles -> 1.609344;
    };
  }
  public static Route route(Distance distance, int seconds)
  {
    if (seconds < 0) throw new IllegalArgumentException("A valid route duration is required");
    // FollowingInfo distance is formatted by CoMaps. Its precision is not an exact measurement.
    return new Route("comaps-preview", "CoMaps route preview", List.of(new Leg("CoMaps route",
        Estimate.nominal(kilometres(distance), "CoMaps offline route distance"),
        Estimate.nominal(seconds / 60.0, "CoMaps offline route duration"), Map.of())),
        Estimate.missing(Estimate.State.UNKNOWN, "toll tariffs not supplied"),
        Estimate.missing(Estimate.State.UNKNOWN, "other fees not supplied"));
  }
  public static EconomyEngine.Assessment assess(Profile profile, Distance distance, int seconds, Instant now)
  {
    return new EconomyEngine().evaluate(profile.forTrip(now, Duration.ofDays(1)), route(distance, seconds));
  }
  public static List<Leg> legs(double[] metrics)
  {
    if (metrics == null || metrics.length == 0 || metrics.length % 2 != 0 || metrics.length > 100)
      throw new IllegalArgumentException("Route subroute metrics required");
    var legs = new ArrayList<Leg>();
    for (int i = 0; i < metrics.length; i += 2)
    {
      Estimate.checkNumber(metrics[i]); Estimate.checkNumber(metrics[i+1]);
      legs.add(new Leg("CoMaps road leg " + (i/2 + 1),
          Estimate.nominal(metrics[i] / 1000.0, "CoMaps offline road distance"),
          Estimate.nominal(metrics[i+1] / 60.0, "CoMaps offline modelled duration"), Map.of()));
    }
    return List.copyOf(legs);
  }
  public static Route route(double[] metrics)
  {
    return new Route("comaps-preview", "CoMaps road route", new ArrayList<Event>(legs(metrics)),
        Estimate.missing(Estimate.State.UNKNOWN, "toll tariffs not supplied"),
        Estimate.missing(Estimate.State.UNKNOWN, "other fees not supplied"));
  }
  public static EconomyEngine.Assessment assess(Profile profile, double[] metrics, Instant now)
  { return new EconomyEngine().evaluate(profile.forTrip(now, Duration.ofDays(1)), route(metrics)); }
}
