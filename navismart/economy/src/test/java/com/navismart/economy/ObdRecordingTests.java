package com.navismart.economy;

import com.navismart.obd.*;
import static com.navismart.obd.ObdRecordingState.*;
import java.time.Instant;
import java.util.Objects;

public final class ObdRecordingTests
{
  private ObdRecordingTests() {}
  public static void run()
  {
    var state = new ObdRecordingState(); equal(false, state.snapshot().active());
    long old = state.begin(); equal(true, state.snapshot().active()); equal(Status.CONNECTING, state.snapshot().status());
    equal(false, state.sample(old, sample(10)));
    state.status(old, Status.DISCOVERING); state.status(old, Status.READING); equal(true, state.sample(old, sample(10)));
    equal(false, state.status(old, Status.CONNECTING));
    System.out.println("PASS recording lifecycle rejects samples before discovery and backwards status");
    var immutable = state.snapshot(); state.sample(old, sample(20)); equal(10L, immutable.samples().get(ObdProtocol.Signal.SPEED).elapsedMillis());
    equal(false, state.sample(old, sample(19)));
    System.out.println("PASS recording snapshots are immutable and out-of-order samples rejected");
    state.stop(); equal(false, state.snapshot().active()); equal(1, state.snapshot().samples().size());
    equal(false, state.sample(old, sample(30))); equal(false, state.status(old, Status.READING));
    System.out.println("PASS stop retains historical values and rejects late callbacks");
    long current = state.begin(); equal(0, state.snapshot().samples().size()); equal(false, state.status(old, Status.FAILED));
    state.status(current, Status.NO_SENSORS); equal(false, state.snapshot().active()); equal(false, state.status(current, Status.READING));
    System.out.println("PASS replacement session discards previous data and terminal states remain terminal");
    System.out.println("PASS: 4 OBD recording lifecycle scenarios");
  }
  private static ObdProtocol.Sample sample(long elapsed)
  { return ObdProtocol.decode(ObdProtocol.Signal.SPEED, "410D20", Instant.parse("2026-10-14T12:00:00Z"), elapsed); }
  private static void equal(Object a, Object b) { if (!Objects.equals(a,b)) throw new AssertionError("Expected " + a + ", got " + b); }
}
