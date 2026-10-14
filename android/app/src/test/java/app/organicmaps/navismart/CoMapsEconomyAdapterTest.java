package app.organicmaps.navismart;

import static org.junit.Assert.*;
import app.organicmaps.sdk.util.Distance;
import com.navismart.economy.Estimate;
import com.navismart.economy.TripModel.*;
import com.navismart.economy.VehicleProfiles.*;
import java.time.Instant;
import java.util.List;
import org.junit.Test;

public class CoMapsEconomyAdapterTest
{
  @Test public void metricAndImperialUnitsConvertFromNumbers()
  {
    assertEquals(1, CoMapsEconomyAdapter.kilometres(distance(1000, Distance.Units.Meters)), 1e-10);
    assertEquals(1.23, CoMapsEconomyAdapter.kilometres(distance(1.23, Distance.Units.Kilometers)), 1e-10);
    assertEquals(1.609344, CoMapsEconomyAdapter.kilometres(distance(5280, Distance.Units.Feet)), 1e-10);
    assertEquals(1.609344, CoMapsEconomyAdapter.kilometres(distance(1, Distance.Units.Miles)), 1e-10);
  }
  @Test public void localizedDisplayTextIsNeverParsed()
  {
    assertEquals(12.5, CoMapsEconomyAdapter.kilometres(new Distance(12.5, "12,5 km • texte", (byte) 1)), 1e-10);
  }
  @Test public void durationConvertsOnceAndFeesStayUnknown()
  {
    var route = CoMapsEconomyAdapter.route(distance(100, Distance.Units.Kilometers), 90);
    assertEquals(1.5, ((Leg) route.events().get(0)).durationMinutes().expected(), 1e-10);
    assertNull(route.tolls().expected()); assertNull(route.otherFees().expected());
    assertFalse(((Leg) route.events().get(0)).distanceKm().hasBounds());
  }
  @Test public void energyPreviewDoesNotInventFullTripCost()
  {
    var now = Instant.parse("2026-10-13T12:00:00Z");
    var p = new Profile(new Vehicle("v", "v", Powertrain.COMBUSTION, List.of(new Store("tank", Energy.DIESEL,
        50.0, 40.0, 2.0, Estimate.exact(6), Estimate.exact(1.8)))), new Policy(5, 0.5, 0.25),
        Characteristics.unknown(), now);
    var result = CoMapsEconomyAdapter.assess(p, distance(100, Distance.Units.Kilometers), 3600, now);
    assertEquals(10.8, result.consumedEnergyCostEuro().expected(), 1e-10);
    assertNull(result.economicCostEuro().expected());
  }
  @Test public void invalidNativeValuesAreRejected()
  {
    assertThrows(IllegalArgumentException.class, () -> CoMapsEconomyAdapter.kilometres(null));
    assertThrows(IllegalArgumentException.class, () -> CoMapsEconomyAdapter.kilometres(distance(Double.NaN, Distance.Units.Meters)));
    assertThrows(IllegalArgumentException.class, () -> CoMapsEconomyAdapter.kilometres(distance(-1, Distance.Units.Meters)));
    assertThrows(IllegalArgumentException.class, () -> CoMapsEconomyAdapter.route(distance(10, Distance.Units.Meters), -1));
  }
  @Test public void nativeSubroutesKeepRoadDistancesAndTimeWithoutRounding()
  {
    var route = CoMapsEconomyAdapter.route(new double[]{1234.567, 123.45, 2000, 180});
    assertEquals(2, route.events().size());
    assertEquals(1.234567, ((Leg) route.events().get(0)).distanceKm().expected(), 1e-10);
    assertEquals(123.45 / 60, ((Leg) route.events().get(0)).durationMinutes().expected(), 1e-10);
    assertFalse(((Leg) route.events().get(0)).distanceKm().hasBounds());
    assertNull(route.tolls().expected());
  }
  @Test public void malformedNativeSubrouteMetricsRejected()
  {
    for (double[] metrics : new double[][]{null, {}, {1}, {-1, 1}, {1, Double.NaN}, {Double.POSITIVE_INFINITY, 1}})
      assertThrows(IllegalArgumentException.class, () -> CoMapsEconomyAdapter.route(metrics));
  }
  private static Distance distance(double value, Distance.Units unit)
  { return new Distance(value, "unused", (byte) unit.ordinal()); }
}
