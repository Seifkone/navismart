package com.navismart.obd;

import java.util.EnumMap;
import java.util.Map;

/** Serial session state. Generation tokens reject callbacks from a stopped or replaced connection. */
public final class ObdRecordingState
{
  public enum Status { CONNECTING, DISCOVERING, READING, NO_SENSORS, STOPPED, FAILED, LIMIT_REACHED }
  public record Snapshot(long generation, Status status, Map<ObdProtocol.Signal, ObdProtocol.Sample> samples)
  { public Snapshot { samples = Map.copyOf(samples); } public boolean active() { return status.ordinal() < Status.NO_SENSORS.ordinal(); } }
  private long generation;
  private Status status = Status.STOPPED;
  private final EnumMap<ObdProtocol.Signal, ObdProtocol.Sample> samples = new EnumMap<>(ObdProtocol.Signal.class);
  public long begin() { generation++; status = Status.CONNECTING; samples.clear(); return generation; }
  public void stop() { generation++; status = Status.STOPPED; }
  public boolean status(long token, Status next)
  {
    if (token != generation || !snapshot().active()) return false;
    if (next.ordinal() < Status.NO_SENSORS.ordinal() && next.ordinal() < status.ordinal()) return false;
    status = next; return true;
  }
  public boolean sample(long token, ObdProtocol.Sample sample)
  {
    if (token != generation || status != Status.READING) return false;
    var previous = samples.get(sample.signal());
    if (previous != null && previous.elapsedMillis() >= sample.elapsedMillis()) return false;
    samples.put(sample.signal(), sample); return true;
  }
  public Snapshot snapshot() { return new Snapshot(generation, status, samples); }
}
