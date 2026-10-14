package app.organicmaps.navismart;

import static org.junit.Assert.*;
import app.organicmaps.sdk.routing.RouteMarkData;
import app.organicmaps.sdk.routing.RouteMarkType;
import com.navismart.economy.TripModel.Energy;
import com.navismart.fuel.FuelData;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.Test;

public class FuelStopComparisonTest
{
  private static final Instant NOW = Instant.parse("2026-10-14T12:00:00Z");
  private static FuelStopComparison.Pending pending()
  {
    var station = new FuelData.Station("s", new FuelData.Point(48.1, 2.1), "address", "town", "75000", false, false, Map.of(), Map.of(), List.of());
    return new FuelStopComparison.Pending(station, Energy.E85, NOW,
        CoMapsEconomyAdapter.legs(new double[]{10000, 600}), 5, 48, 2, 49, 3, NOW);
  }
  private static RouteMarkData mark(RouteMarkType type, double lat, double lon)
  { return new RouteMarkData("", "", type, 0, true, false, false, lat, lon); }
  private static RouteMarkData[] points()
  { return new RouteMarkData[]{mark(RouteMarkType.Start, 48, 2), mark(RouteMarkType.Intermediate, 48.1, 2.1), mark(RouteMarkType.Finish, 49, 3)}; }
  @Test public void unchangedExpectedRouteMatches()
  { assertTrue(FuelStopComparison.matches(pending(), points(), 5, NOW.plusSeconds(120))); }
  @Test public void routeRevisionExpirationAndFutureBaselineInvalidate()
  {
    assertFalse(FuelStopComparison.matches(pending(), points(), 6, NOW));
    assertFalse(FuelStopComparison.matches(pending(), points(), 5, NOW.plusSeconds(1801)));
    assertFalse(FuelStopComparison.matches(pending(), points(), 5, NOW.minusSeconds(1)));
  }
  @Test public void endpointsStationAndAdditionalStopsAreVerified()
  {
    var points = points(); points[2] = mark(RouteMarkType.Finish, 49.1, 3);
    assertFalse(FuelStopComparison.matches(pending(), points, 5, NOW));
    points = points(); points[1] = mark(RouteMarkType.Intermediate, 48.2, 2.1);
    assertFalse(FuelStopComparison.matches(pending(), points, 5, NOW));
    assertFalse(FuelStopComparison.matches(pending(), new RouteMarkData[]{points[0], points[2]}, 5, NOW));
    assertFalse(FuelStopComparison.matches(pending(), null, 5, NOW));
  }
  @Test public void duplicatePointRolesAreRejected()
  {
    var points = points(); points[2] = mark(RouteMarkType.Start, 49, 3);
    assertFalse(FuelStopComparison.matches(pending(), points, 5, NOW));
  }
}
