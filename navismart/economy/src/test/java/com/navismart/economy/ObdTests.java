package com.navismart.economy;

import com.navismart.obd.*;
import static com.navismart.obd.ObdProtocol.*;
import java.io.*;
import java.time.Instant;
import java.util.Objects;

public final class ObdTests
{
  private static int passed;
  private static final Instant NOW = Instant.parse("2026-10-14T12:00:00Z");
  private ObdTests() {}
  public static void run()
  {
    test("OBD RPM example matches ELM datasheet", () -> near(1726, decode(Signal.RPM, "010C\rSEARCHING...\r41 0C 1A F8\r>", NOW, 10).value()));
    test("zero RPM is a measurement, not missing data", () -> near(0, decode(Signal.RPM, "410C0000", NOW, 0).value()));
    test("negative temperatures and trims are retained", () -> {
      near(-40, decode(Signal.COOLANT, "410500", NOW, 0).value());
      near(-100, decode(Signal.LONG_TRIM, "410700", NOW, 0).value());
      near(0, decode(Signal.SHORT_TRIM, "410680", NOW, 0).value());
    });
    test("standard sensor units remain explicit", () -> {
      near(100, decode(Signal.SPEED, "410D64", NOW, 0).value());
      near(12.34, decode(Signal.MAF, "411004D2", NOW, 0).value());
      near(10, decode(Signal.FUEL_RATE, "415E00C8", NOW, 0).value());
      near(100, decode(Signal.FUEL_LEVEL, "412FFF", NOW, 0).value());
      near(100, decode(Signal.ETHANOL, "4152FF", NOW, 0).value());
    });
    test("NO DATA stays unavailable", () -> {
      var sample = decode(Signal.RPM, "NO DATA\r>", NOW, 200);
      equal(State.UNAVAILABLE, sample.state()); equal(null, sample.value()); equal(NOW, sample.observedAt()); equal(200L, sample.elapsedMillis());
    });
    test("conflicting ECUs never select arbitrary engine value", () -> {
      equal(State.AMBIGUOUS, decode(Signal.SPEED, "410D30\r410D31", NOW, 0).state());
      near(48, decode(Signal.SPEED, "410D30\r410D30", NOW, 0).value());
    });
    test("malformed truncated unrelated and headered data rejected", () -> {
      for (String reply : new String[]{"410C1A", "410C1AZZ", "410D30", "7E804410C1AF8", "?", "BUS ERROR", "410C1AF8\rSTOPPED"})
        equal(State.INVALID, decode(Signal.RPM, reply, NOW, 0).state());
    });
    test("discovery masks union ECUs and preserve continuation bits", () -> {
      var support = support(0, "410000100001\r410000080000");
      equal(true, support.valid()); equal(true, support.pids().contains(12)); equal(true, support.pids().contains(13));
      equal(true, support.pids().contains(32)); equal(false, support.pids().contains(11));
      equal(2, signals(support.pids()).size());
      equal(false, support(0x20, "NODATA").valid());
    });
    test("all-zero support is valid but no measurements invented", () -> {
      var support = support(0, "410000000000"); equal(true, support.valid()); equal(0, support.pids().size());
    });
    test("outgoing whitelist excludes ECU writes and VIN", () -> {
      for (String command : new String[]{"04", "08", "2EF190", "0902", "ATSH7E0", "010C\r04", ""}) equal(false, allowedCommand(command));
      for (Signal s : Signal.values()) equal(true, allowedCommand(s.command()));
    });
    test("prompt framing preserves next response", () -> {
      try
      {
        var output = new ByteArrayOutputStream(); var channel = new ElmChannel(new ByteArrayInputStream("41 0C 1A F8\r>410D00>".getBytes(java.nio.charset.StandardCharsets.US_ASCII)), output);
        equal("41 0C 1A F8\r", channel.request("010C", 100, () -> false));
        equal("410D00", channel.request("010D", 100, () -> false)); equal("010C\r010D\r", output.toString());
      } catch (IOException error) { throw new AssertionError(error); }
    });
    test("timeouts cancellation response limits and command guard", () -> {
      failsIo(() -> new ElmChannel(new ByteArrayInputStream(new byte[0]), new ByteArrayOutputStream()).request("010C", 20, () -> false));
      failsIo(() -> new ElmChannel(new ByteArrayInputStream(new byte[0]), new ByteArrayOutputStream()).request("010C", 20, () -> true));
      failsIo(() -> new ElmChannel(new ByteArrayInputStream("A".repeat(16385).getBytes()), new ByteArrayOutputStream()).request("010C", 100, () -> false));
      var output = new ByteArrayOutputStream();
      try { new ElmChannel(new ByteArrayInputStream(new byte[0]), output).request("04", 100, () -> false); throw new AssertionError("Write command accepted"); }
      catch (IllegalArgumentException expected) {} catch (IOException error) { throw new AssertionError(error); }
      equal(0, output.size());
    });
    test("CSV keeps per-sample UTC elapsed time unit and missing state", () -> {
      try
      {
        var writer = new StringWriter(); ObdCsv.header(writer); ObdCsv.write(writer, decode(Signal.RPM, "NODATA", NOW, 123));
        equal("schema,utc,elapsed_ms,pid,signal,value,unit,state\n1,2026-10-14T12:00:00Z,123,0C,RPM,,rpm,UNAVAILABLE\n", writer.toString());
      } catch (IOException error) { throw new AssertionError(error); }
    });
    System.out.println("PASS: " + passed + " OBD scenarios");
  }
  @FunctionalInterface private interface IoAction { void run() throws IOException; }
  private static void failsIo(IoAction action) { try { action.run(); } catch (IOException expected) { return; } throw new AssertionError("Invalid transport accepted"); }
  private static void test(String name, Runnable action) { action.run(); passed++; System.out.println("PASS " + name); }
  private static void equal(Object a, Object b) { if (!Objects.equals(a,b)) throw new AssertionError("Expected " + a + ", got " + b); }
  private static void near(double a, Double b) { if (b == null || Math.abs(a-b)>1e-7) throw new AssertionError("Expected " + a + ", got " + b); }
}
