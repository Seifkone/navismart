package com.navismart.fuel;

import com.navismart.economy.DataFreshness;
import com.navismart.economy.Estimate;
import com.navismart.economy.TripModel.Energy;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Government observations. A published price is not a guarantee of stock or opening at arrival. */
public final class FuelData
{
  public static final String SOURCE = "https://donnees.roulez-eco.fr/opendata/instantane_ruptures";
  private FuelData() {}
  public record Point(double latitude, double longitude)
  {
    public Point
    {
      if (!Double.isFinite(latitude) || !Double.isFinite(longitude) || Math.abs(latitude) > 90 || Math.abs(longitude) > 180)
        throw new IllegalArgumentException("Invalid geographical coordinates");
    }
    public double distanceKm(Point other)
    {
      double a = Math.pow(Math.sin(Math.toRadians(other.latitude - latitude) / 2), 2)
          + Math.cos(Math.toRadians(latitude)) * Math.cos(Math.toRadians(other.latitude))
          * Math.pow(Math.sin(Math.toRadians(other.longitude - longitude) / 2), 2);
      return 6371.0088 * 2 * Math.asin(Math.sqrt(Math.max(0, Math.min(1, a))));
    }
  }
  public enum Availability { REPORTED, OUTAGE, CLOSED, UNKNOWN }
  public record Period(Instant start, Instant end, boolean valid)
  {
    public boolean uncertain() { return !valid || start == null || (end != null && end.isBefore(start)); }
    public boolean active(Instant now)
    { return !uncertain() && !start.isAfter(now) && (end == null || end.isAfter(now)); }
  }
  public record Station(String id, Point point, String address, String town, String postcode,
                        boolean motorway, boolean automatic24h, Map<Energy, Estimate> prices,
                        Map<Energy, List<Period>> outages, List<Period> closures)
  {
    public Station
    {
      if (id == null || id.isBlank()) throw new IllegalArgumentException("Station id required");
      Objects.requireNonNull(address); Objects.requireNonNull(town); Objects.requireNonNull(postcode);
      prices = Map.copyOf(prices);
      var copy = new java.util.EnumMap<Energy, List<Period>>(Energy.class);
      outages.forEach((energy, periods) -> copy.put(energy, List.copyOf(periods)));
      outages = Map.copyOf(copy); closures = List.copyOf(closures);
    }
    public Estimate price(Energy energy, Instant now, Duration maxAge)
    {
      Estimate price = prices.get(energy);
      return price == null ? Estimate.missing(Estimate.State.UNKNOWN, SOURCE + "#" + id)
                           : price.state() == Estimate.State.FRESH ? DataFreshness.check(price, now, maxAge) : price;
    }
    public Availability availability(Energy energy, Instant now, Instant receivedAt, Duration maxSnapshotAge)
    {
      if (receivedAt.isAfter(now) || Duration.between(receivedAt, now).compareTo(maxSnapshotAge) > 0)
        return Availability.UNKNOWN;
      if (closures.stream().anyMatch(p -> p.active(now))) return Availability.CLOSED;
      List<Period> fuelOutages = outages.getOrDefault(energy, List.of());
      if (fuelOutages.stream().anyMatch(p -> p.active(now))) return Availability.OUTAGE;
      if (closures.stream().anyMatch(Period::uncertain) || fuelOutages.stream().anyMatch(Period::uncertain))
        return Availability.UNKNOWN;
      return prices.containsKey(energy) ? Availability.REPORTED : Availability.UNKNOWN;
    }
    public String label() { return address + (town.isBlank() ? "" : ", " + town); }
  }
  public record Snapshot(Instant receivedAt, List<Station> stations, int invalidRecords)
  {
    public Snapshot
    {
      Objects.requireNonNull(receivedAt); stations = List.copyOf(stations);
      if (invalidRecords < 0) throw new IllegalArgumentException("Invalid rejection count");
    }
  }
}
