package com.navismart.obd;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Standard Mode 01 only. No diagnostic reset, actuator command, VIN or brand-specific query. */
public final class ObdProtocol
{
  private ObdProtocol() {}
  public enum State { AVAILABLE, UNAVAILABLE, INVALID, AMBIGUOUS }
  public enum Signal
  {
    LOAD(0x04, 1, "%"), COOLANT(0x05, 1, "°C"), SHORT_TRIM(0x06, 1, "%"),
    LONG_TRIM(0x07, 1, "%"), MANIFOLD_PRESSURE(0x0B, 1, "kPa"), RPM(0x0C, 2, "rpm"),
    SPEED(0x0D, 1, "km/h"), INTAKE_TEMPERATURE(0x0F, 1, "°C"), MAF(0x10, 2, "g/s"),
    THROTTLE(0x11, 1, "%"), FUEL_LEVEL(0x2F, 1, "%"), ETHANOL(0x52, 1, "%"), FUEL_RATE(0x5E, 2, "L/h");
    public final int pid, bytes;
    public final String unit;
    Signal(int pid, int bytes, String unit) { this.pid = pid; this.bytes = bytes; this.unit = unit; }
    public String command() { return String.format(Locale.ROOT, "01%02X", pid); }
    double value(int[] data)
    {
      int a = data[0], word = bytes == 2 ? a * 256 + data[1] : a;
      return switch (this)
      {
        case RPM -> word / 4.0;
        case MAF -> word / 100.0;
        case FUEL_RATE -> word / 20.0;
        case COOLANT, INTAKE_TEMPERATURE -> a - 40.0;
        case SHORT_TRIM, LONG_TRIM -> (a - 128) * 100.0 / 128;
        case LOAD, THROTTLE, FUEL_LEVEL, ETHANOL -> a * 100.0 / 255;
        case SPEED, MANIFOLD_PRESSURE -> a;
      };
    }
  }
  public record Sample(Signal signal, Double value, State state, Instant observedAt, long elapsedMillis)
  {
    public Sample
    {
      if (signal == null || state == null || observedAt == null || elapsedMillis < 0
          || (value != null && !Double.isFinite(value)) || (state == State.AVAILABLE) != (value != null))
        throw new IllegalArgumentException("Invalid OBD sample");
    }
  }
  public record Support(Set<Integer> pids, boolean valid)
  { public Support { pids = Set.copyOf(pids); } }

  /** ECU support masks are unioned; conflicting actual measurements remain ambiguous. */
  public static Support support(int base, String reply)
  {
    if (base != 0 && base != 0x20 && base != 0x40) throw new IllegalArgumentException("Unsupported discovery page");
    Frames frames = frames(base, 4, reply);
    Set<Integer> pids = new java.util.HashSet<>();
    if (frames.bad || frames.values.isEmpty()) return new Support(pids, false);
    for (int[] bytes : frames.values)
      for (int bit = 0; bit < 32; bit++)
        if ((bytes[bit / 8] & (0x80 >> (bit % 8))) != 0) pids.add(base + bit + 1);
    return new Support(pids, true);
  }
  public static Set<Signal> signals(Set<Integer> pids)
  {
    Set<Signal> result = EnumSet.noneOf(Signal.class);
    for (Signal s : Signal.values()) if (pids.contains(s.pid)) result.add(s);
    return result;
  }
  public static Sample decode(Signal signal, String reply, Instant at, long elapsedMillis)
  {
    Frames frames = frames(signal.pid, signal.bytes, reply);
    if (frames.bad) return new Sample(signal, null, State.INVALID, at, elapsedMillis);
    if (frames.values.isEmpty()) return new Sample(signal, null, State.UNAVAILABLE, at, elapsedMillis);
    double value = signal.value(frames.values.get(0));
    for (int[] bytes : frames.values)
      if (value != signal.value(bytes)) return new Sample(signal, null, State.AMBIGUOUS, at, elapsedMillis);
    return new Sample(signal, value, State.AVAILABLE, at, elapsedMillis);
  }
  /** Restricts every outgoing byte sequence, including adapter setup, to this finite read-only list. */
  public static boolean allowedCommand(String command)
  {
    if (Set.of("ATZ", "ATE0", "ATL0", "ATS1", "ATH0", "ATSP0", "0100", "0120", "0140").contains(command)) return true;
    for (Signal s : Signal.values()) if (s.command().equals(command)) return true;
    return false;
  }
  private record Frames(List<int[]> values, boolean bad) {}
  private static Frames frames(int pid, int bytes, String reply)
  {
    var result = new ArrayList<int[]>();
    if (reply == null || reply.length() > 16384) return new Frames(result, true);
    String header = String.format(Locale.ROOT, "41%02X", pid);
    String echo = String.format(Locale.ROOT, "01%02X", pid);
    boolean bad = false;
    for (String line : reply.toUpperCase(Locale.ROOT).replace('>', '\r').split("[\r\n]+"))
    {
      String compact = line.replace(" ", "").replace("\t", "").trim();
      // SEARCHING may share a line with the first response on some adapters.
      if (compact.startsWith("SEARCHING...")) compact = compact.substring(12);
      if (compact.isEmpty() || compact.equals(echo) || compact.equals("NODATA")) continue;
      if (!compact.startsWith(header) || compact.length() != 4 + bytes * 2 || !compact.matches("[0-9A-F]+"))
      { bad = true; continue; }
      int[] data = new int[bytes];
      for (int i = 0; i < bytes; i++) data[i] = Integer.parseInt(compact.substring(4 + 2*i, 6 + 2*i), 16);
      result.add(data);
    }
    return new Frames(result, bad);
  }
}
