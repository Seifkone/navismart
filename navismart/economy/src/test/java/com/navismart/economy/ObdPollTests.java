package com.navismart.economy;

import com.navismart.obd.ObdPollSchedule;
import static com.navismart.obd.ObdProtocol.Signal.*;
import java.util.Set;
import java.util.Objects;

public final class ObdPollTests
{
  private ObdPollTests() {}
  public static void run()
  {
    var schedule = new ObdPollSchedule(Set.of(SPEED, RPM, COOLANT, ETHANOL));
    equal(RPM, schedule.next(0)); equal(SPEED, schedule.next(200)); equal(RPM, schedule.next(400)); equal(SPEED, schedule.next(600));
    System.out.println("PASS motion sensors have deterministic priority");
    equal(COOLANT, schedule.next(2000)); equal(RPM, schedule.next(2200)); equal(SPEED, schedule.next(2400));
    equal(ETHANOL, schedule.next(4000)); equal(RPM, schedule.next(4200));
    System.out.println("PASS slower diagnostics are sampled fairly between motion rounds");
    var slowOnly = new ObdPollSchedule(Set.of(COOLANT, ETHANOL)); equal(COOLANT, slowOnly.next(0)); equal(ETHANOL, slowOnly.next(200));
    var fastOnly = new ObdPollSchedule(Set.of(FUEL_RATE)); equal(FUEL_RATE, fastOnly.next(0)); equal(FUEL_RATE, fastOnly.next(20000));
    System.out.println("PASS limited vehicles only request supported sensors");
    invalid(() -> new ObdPollSchedule(Set.of())); invalid(() -> fastOnly.next(19999));
    System.out.println("PASS empty support and backwards scheduler time rejected");
    System.out.println("PASS: 4 OBD poll scheduling scenarios");
  }
  private static void equal(Object a, Object b) { if (!Objects.equals(a,b)) throw new AssertionError("Expected " + a + ", got " + b); }
  private static void invalid(Runnable action) { try { action.run(); } catch (IllegalArgumentException expected) { return; } throw new AssertionError("Invalid schedule accepted"); }
}
