package com.navismart.fuel;

import static com.navismart.fuel.FuelData.*;
import com.navismart.economy.Estimate;
import com.navismart.economy.TripModel.Energy;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;

/** Geographical shortlist only. Straight-line distance is never presented as a road detour. */
public final class StationSearch
{
  private StationSearch() {}
  public record Match(Station station, Estimate price, Availability availability, double straightLineKm,
                      Instant sourceReceivedAt) {}
  public static List<Match> nearby(Snapshot snapshot, Point origin, Energy energy, Instant now,
                                   double radiusKm, int limit, Duration priceAge)
  {
    if (!Double.isFinite(radiusKm) || radiusKm <= 0 || limit < 1 || limit > 200)
      throw new IllegalArgumentException("Invalid station search limits");
    return snapshot.stations().stream().filter(s -> s.point() != null)
        .map(s -> new Match(s, s.price(energy, now, priceAge), s.availability(energy, now, snapshot.receivedAt(),
                                        Duration.ofMinutes(30)), origin.distanceKm(s.point()), snapshot.receivedAt()))
        .filter(m -> m.straightLineKm() <= radiusKm && m.price().expected() != null)
        .sorted(Comparator.comparingInt((Match m) -> m.availability() == Availability.CLOSED || m.availability() == Availability.OUTAGE ? 1 : 0)
            .thenComparingInt(m -> m.price().isFresh() ? 0 : 1)
            .thenComparingDouble(m -> m.price().expected()).thenComparingDouble(Match::straightLineKm)
            .thenComparing(m -> m.station().id()))
        .limit(limit).toList();
  }
}
