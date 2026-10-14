package com.navismart.obd;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Give motion and reported fuel signals priority without starving slower diagnostics. */
public final class ObdPollSchedule
{
  private final List<ObdProtocol.Signal> fast, slow;
  private int fastIndex, slowIndex;
  private long nextSlowAt = 2000, previousTime;
  public ObdPollSchedule(Set<ObdProtocol.Signal> supported)
  {
    var priority = List.of(ObdProtocol.Signal.RPM, ObdProtocol.Signal.SPEED,
        ObdProtocol.Signal.THROTTLE, ObdProtocol.Signal.FUEL_RATE);
    var quick = new ArrayList<ObdProtocol.Signal>(); var other = new ArrayList<ObdProtocol.Signal>();
    for (var signal : priority) if (supported.contains(signal)) quick.add(signal);
    for (var signal : ObdProtocol.Signal.values()) if (supported.contains(signal) && !priority.contains(signal)) other.add(signal);
    fast = List.copyOf(quick); slow = List.copyOf(other);
    if (fast.isEmpty() && slow.isEmpty()) throw new IllegalArgumentException("No supported sensors");
  }
  public ObdProtocol.Signal next(long elapsedMillis)
  {
    if (elapsedMillis < previousTime) throw new IllegalArgumentException("Monotonic schedule time required");
    previousTime = elapsedMillis;
    if (!slow.isEmpty() && (fast.isEmpty() || (fastIndex == 0 && elapsedMillis >= nextSlowAt)))
    {
      nextSlowAt = elapsedMillis + 2000;
      var signal = slow.get(slowIndex); slowIndex = (slowIndex + 1) % slow.size(); return signal;
    }
    var signal = fast.get(fastIndex); fastIndex = (fastIndex + 1) % fast.size(); return signal;
  }
}
