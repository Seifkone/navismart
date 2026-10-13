package com.navismart.economy;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** A non-negative quantity. Missing bounds mean unknown uncertainty, never a zero margin. */
public record Estimate(Double expected, Double lower, Double upper, State state,
                       String source, Instant observedAt)
{
  public enum State { FRESH, STALE, UNKNOWN, UNAVAILABLE, INVALID }

  public Estimate
  {
    Objects.requireNonNull(state);
    Objects.requireNonNull(source);
    if ((lower == null) != (upper == null))
      throw new IllegalArgumentException("Both bounds must be provided or omitted");
    if (expected != null)
      checkNumber(expected);
    if (lower != null)
    {
      checkNumber(lower);
      checkNumber(upper);
      if (expected == null || lower > expected || expected > upper)
        throw new IllegalArgumentException("Expected value must be inside its bounds");
    }
    if (state == State.FRESH && expected == null)
      throw new IllegalArgumentException("A fresh value must exist");
  }

  public static Estimate exact(double value)
  {
    return range(value, value, value, "manual / scenario");
  }

  public static Estimate range(double lower, double expected, double upper, String source)
  {
    return new Estimate(expected, lower, upper, State.FRESH, source, null);
  }

  public static Estimate nominal(double expected, String source)
  {
    return new Estimate(expected, null, null, State.FRESH, source, null);
  }

  public static Estimate missing(State state, String source)
  {
    return new Estimate(null, null, null, state, source, null);
  }

  public Estimate withObservation(State state, String source, Instant observedAt)
  {
    return new Estimate(expected, lower, upper, state, source, observedAt);
  }

  public boolean hasBounds() { return lower != null; }
  public boolean isFresh() { return state == State.FRESH && expected != null; }

  public static void checkNumber(double value)
  {
    if (!Double.isFinite(value) || value < 0)
      throw new IllegalArgumentException("Expected a finite, non-negative value");
  }

  public static Estimate sum(List<Estimate> values)
  {
    double expected = 0, lower = 0, upper = 0;
    boolean complete = true, bounded = true;
    State state = State.FRESH;
    Instant oldest = null;
    for (Estimate value : values)
    {
      if (value.state.ordinal() > state.ordinal()) state = value.state;
      complete &= value.expected != null;
      bounded &= value.hasBounds();
      if (value.expected != null) expected += value.expected;
      if (value.hasBounds()) { lower += value.lower; upper += value.upper; }
      if (value.observedAt != null && (oldest == null || value.observedAt.isBefore(oldest)))
        oldest = value.observedAt;
    }
    return new Estimate(complete ? expected : null, complete && bounded ? lower : null,
                        complete && bounded ? upper : null, state, "sum", oldest);
  }

  public Estimate scale(double factor)
  {
    checkNumber(factor);
    return new Estimate(expected == null ? null : expected * factor,
                        lower == null ? null : lower * factor,
                        upper == null ? null : upper * factor, state, source, observedAt);
  }
}
