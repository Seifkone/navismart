package com.navismart.economy;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/** Caller-supplied clock and TTL keep freshness checks deterministic and source-specific. */
public final class DataFreshness
{
  private DataFreshness() {}

  public static Estimate check(Estimate value, Instant now, Duration maximumAge)
  {
    Objects.requireNonNull(now);
    Objects.requireNonNull(maximumAge);
    if (maximumAge.isNegative() || maximumAge.isZero())
      throw new IllegalArgumentException("Freshness lifetime must be positive");
    if (value.state() != Estimate.State.FRESH) return value;
    if (value.observedAt() == null)
      return value.withObservation(Estimate.State.UNKNOWN, value.source(), null);
    if (value.observedAt().isAfter(now))
      return value.withObservation(Estimate.State.INVALID, value.source(), value.observedAt());
    if (Duration.between(value.observedAt(), now).compareTo(maximumAge) > 0)
      return value.withObservation(Estimate.State.STALE, value.source(), value.observedAt());
    return value;
  }
}
