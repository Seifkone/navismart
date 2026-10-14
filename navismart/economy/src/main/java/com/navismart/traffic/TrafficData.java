package com.navismart.traffic;

import com.navismart.fuel.FuelData.Point;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/** Government event notices with partial coverage. No event is not evidence of free-flowing traffic. */
public final class TrafficData
{
  private TrafficData() {}
  public static final String SOURCE = "https://tipi.bison-fute.gouv.fr/bison-fute-ouvert/publicationsDIR/Evenementiel-DIR/grt/RRN/content.xml";
  public enum State { LISTED, FUTURE, EXPIRED, SUSPENDED, UNKNOWN }
  public record Event(String id, String type, String details, String direction, String road,
                      List<Point> points, Instant updatedAt, Instant startsAt, Instant endsAt,
                      String validity, boolean complexSchedule, boolean real)
  {
    public Event { points = List.copyOf(points); }
    public State state(Instant now)
    {
      if ("suspended".equals(validity)) return State.SUSPENDED;
      if (endsAt != null && !now.isBefore(endsAt)) return State.EXPIRED;
      if (startsAt != null && now.isBefore(startsAt)) return State.FUTURE;
      if (!real || complexSchedule || startsAt == null || !"definedByValidityTimeSpec".equals(validity)
          || updatedAt == null || updatedAt.isAfter(now)
          || (endsAt != null && endsAt.isBefore(startsAt))) return State.UNKNOWN;
      return State.LISTED;
    }
  }
  public record Snapshot(Instant receivedAt, Instant publishedAt, List<Event> events)
  {
    public Snapshot { events = List.copyOf(events); }
    public boolean fresh(Instant now)
    {
      return publishedAt != null && !publishedAt.isAfter(now) && !receivedAt.isAfter(now)
          && Duration.between(publishedAt, now).compareTo(Duration.ofHours(2)) <= 0
          && Duration.between(receivedAt, now).compareTo(Duration.ofHours(2)) <= 0;
    }
  }
  public record Match(Event event, double distanceKm) {}
  /** Proximity to reported points only: neither road matching nor carriageway direction is inferred. */
  public static List<Match> nearby(Snapshot snapshot, Point origin, Instant now, double radiusKm, int limit)
  {
    if (!Double.isFinite(radiusKm) || radiusKm <= 0 || limit < 1 || limit > 200) throw new IllegalArgumentException("Invalid traffic search bounds");
    return snapshot.events().stream().filter(e -> e.state(now) != State.EXPIRED && e.state(now) != State.SUSPENDED)
        .map(e -> new Match(e, e.points().stream().mapToDouble(p -> p.distanceKm(origin)).min().orElse(Double.POSITIVE_INFINITY)))
        .filter(m -> m.distanceKm() <= radiusKm)
        .sorted(java.util.Comparator.comparingDouble(Match::distanceKm).thenComparing(m -> m.event().id())).limit(limit).toList();
  }
}
