package com.navismart.economy;

import com.navismart.fuel.*;
import static com.navismart.fuel.FuelData.*;
import static com.navismart.economy.TripModel.*;
import java.time.*;
import java.util.*;
import java.io.*;

public final class FuelTests
{
  private static final Instant NOW = Instant.parse("2026-10-14T10:00:00Z");
  private static int passed;
  private FuelTests() {}
  public static void run()
  {
    test("government coordinates and euro prices preserve their units", () -> {
      var s = parse(xml("<prix id='3' nom='E85' maj='2026-10-14 11:00:00' valeur='0.936'/>" )).stations().get(0);
      near(48.12345, s.point().latitude()); near(2.12345, s.point().longitude());
      near(0.936, s.price(Energy.E85, NOW, Duration.ofDays(7)).expected());
      equal(Instant.parse("2026-10-14T09:00:00Z"), s.prices().get(Energy.E85).observedAt());
      equal(false, s.prices().get(Energy.E85).hasBounds());
    });
    test("missing price is unknown, not zero", () -> equal(null, parse(xml("<services/>" )).stations().get(0)
        .price(Energy.E85, NOW, Duration.ofDays(7)).expected()));
    test("stale price keeps original date", () -> equal(Estimate.State.STALE, parse(xml(
        "<prix id='3' maj='2026-09-14 11:00:00' valeur='1.2'/>" )).stations().get(0)
        .price(Energy.E85, NOW, Duration.ofDays(7)).state()));
    test("future and undated prices cannot be fresh", () -> {
      equal(Estimate.State.INVALID, parse(xml("<prix id='3' maj='2027-01-01 12:00:00' valeur='1.2'/>" )).stations().get(0)
          .price(Energy.E85, NOW, Duration.ofDays(7)).state());
      equal(Estimate.State.UNKNOWN, parse(xml("<prix id='3' maj='' valeur='1.2'/>" )).stations().get(0).prices().get(Energy.E85).state());
    });
    test("ambiguous daylight-saving time remains unknown", () -> equal(Estimate.State.UNKNOWN, parse(xml(
        "<prix id='3' maj='2026-10-25 02:30:00' valeur='1.2'/>" )).stations().get(0).prices().get(Energy.E85).state()));
    test("zero negative malformed and nonfinite prices rejected", () -> {
      for (String value : List.of("0", "-1", "NaN", "Infinity", "bad"))
        equal(Estimate.State.INVALID, parse(xml("<prix id='3' valeur='" + value + "'/>" )).stations().get(0).prices().get(Energy.E85).state());
    });
    test("active outage and closure override price", () -> {
      var s = parse(xml("<prix id='3' maj='2026-10-14 11:00:00' valeur='1.2'/><rupture id='3' debut='2026-10-14 11:30:00' fin=''/>" )).stations().get(0);
      equal(Availability.OUTAGE, s.availability(Energy.E85, NOW, NOW, Duration.ofMinutes(30)));
      var closed = parse(xml("<fermeture debut='2026-10-14 11:30:00' fin=''/>" )).stations().get(0);
      equal(Availability.CLOSED, closed.availability(Energy.E85, NOW, NOW, Duration.ofMinutes(30)));
    });
    test("past outages end and malformed intervals remain uncertain", () -> {
      var s = parse(xml("<prix id='3' maj='2026-10-14 11:00:00' valeur='1.2'/><rupture id='3' debut='2026-10-13 12:00:00' fin='2026-10-14 11:00:00'/>" )).stations().get(0);
      equal(Availability.REPORTED, s.availability(Energy.E85, NOW, NOW, Duration.ofMinutes(30)));
      var unknown = parse(xml("<rupture id='3' debut='bad'/>" )).stations().get(0);
      equal(Availability.UNKNOWN, unknown.availability(Energy.E85, NOW, NOW, Duration.ofMinutes(30)));
    });
    test("offline old snapshot does not promise stock availability", () -> {
      var s = parse(xml("<prix id='3' maj='2026-10-14 11:00:00' valeur='1.2'/>" )).stations().get(0);
      equal(Availability.UNKNOWN, s.availability(Energy.E85, NOW, NOW.minusSeconds(1900), Duration.ofMinutes(30)));
    });
    test("invalid coordinates never locate a station at zero", () -> {
      equal(null, parse(xml("").replace("4812345", "bad")).stations().get(0).point());
      equal(null, parse(xml("").replace("4812345", "0").replace("212345", "0")).stations().get(0).point());
    });
    test("duplicate station and fuel identifiers fail safely", () -> {
      invalid(() -> parse(xml("").replace("</pdv_liste>", "<pdv id='s1'/></pdv_liste>")));
      equal(Estimate.State.INVALID, parse(xml("<prix id='3' valeur='1.0'/><prix id='3' valeur='2.0'/>" )).stations().get(0).prices().get(Energy.E85).state());
    });
    test("external entities internal DTD and unexpected roots rejected", () -> {
      invalid(() -> parse("<!DOCTYPE pdv_liste [<!ENTITY x SYSTEM 'file:///private'>]>" + xml("&x;")));
      invalid(() -> parse("<!DOCTYPE pdv_liste [<!ENTITY x 'boom'>]>" + xml("&x;")));
      invalid(() -> parse("<something><pdv id='x'/></something>"));
      invalid(() -> parse("<pdv_liste/>"));
    });
    test("ZIP with XML and non-UTF8 declaration parsed without guessing charset", () -> {
      try
      {
        var bytes = new ByteArrayOutputStream();
        try (var zip = new java.util.zip.ZipOutputStream(bytes))
        {
          zip.putNextEntry(new java.util.zip.ZipEntry("feed.xml"));
          zip.write(("<?xml version='1.0' encoding='ISO-8859-1'?>" + xml("").replace("Paris", "Bréhan"))
              .getBytes(java.nio.charset.StandardCharsets.ISO_8859_1)); zip.closeEntry();
        }
        equal("Bréhan", new GovernmentFuelReader().readZip(new ByteArrayInputStream(bytes.toByteArray()), NOW).stations().get(0).town());
      }
      catch (IOException error) { throw new AssertionError(error); }
    });
    test("geographical shortlist is deterministic and not a road route", () -> {
      var snapshot = parse(xml("<prix id='3' maj='2026-10-14 11:00:00' valeur='1.2'/>"));
      var matches = StationSearch.nearby(snapshot, new Point(48.12345, 2.12345), Energy.E85, NOW, 10, 10, Duration.ofDays(7));
      equal(1, matches.size()); near(0, matches.get(0).straightLineKm());
      equal(0, StationSearch.nearby(snapshot, new Point(43, 5), Energy.E85, NOW, 1, 10, Duration.ofDays(7)).size());
    });
    test("refuel plans respect reachability capacity and time", () -> {
      var before = new Leg("before", Estimate.exact(150), Estimate.exact(90), Map.of());
      var after = new Leg("after", Estimate.exact(100), Estimate.exact(60), Map.of());
      var stop = new RefuelPlanner.StopOption("s", "station", "tank", before, after, Estimate.exact(1.4),
          Estimate.exact(5), Estimate.exact(0), Estimate.exact(0), Estimate.exact(0), List.of(10.0, 50.0));
      var result = RefuelPlanner.compare(Simulator.combustion(5), Simulator.route("direct", 200, 140, 0), List.of(stop), new Policy(5, .5, .25));
      equal(0, result.affordablePlans().size());
      equal(EconomyEngine.Choice.NO_FEASIBLE_ROUTE, result.decision().choice());
    });
    test("station selection retains its original snapshot date during a refresh", () -> {
      var snapshot = parse(xml("<prix id='3' maj='2026-10-14 11:00:00' valeur='1.2'/>"));
      var original = StationSearch.nearby(snapshot, new Point(48.12345, 2.12345), Energy.E85, NOW, 10, 10, Duration.ofDays(7)).get(0);
      var refreshed = new Snapshot(NOW.plusSeconds(600), snapshot.stations(), 0);
      equal(NOW, original.sourceReceivedAt()); equal(NOW.plusSeconds(600), refreshed.receivedAt());
      equal(Availability.UNKNOWN, original.station().availability(Energy.E85, NOW.plusSeconds(1801), original.sourceReceivedAt(), Duration.ofMinutes(30)));
    });
    test("discarded ZIP entries cannot bypass decompression limit", () -> {
      try
      {
        var bytes = new ByteArrayOutputStream();
        try (var zip = new java.util.zip.ZipOutputStream(bytes))
        {
          zip.putNextEntry(new java.util.zip.ZipEntry("unexpected.bin")); zip.write(new byte[65537]); zip.closeEntry();
          zip.putNextEntry(new java.util.zip.ZipEntry("prices.xml")); zip.write(xml("").getBytes()); zip.closeEntry();
        }
        try { new GovernmentFuelReader().readZip(new ByteArrayInputStream(bytes.toByteArray()), NOW); throw new AssertionError("Oversized skipped entry accepted"); }
        catch (IOException expected) {}
      } catch (IOException error) { throw new AssertionError(error); }
    });
    test("reachable partial purchase keeps consumed and purchased costs separate", () -> {
      var before = new Leg("before", Estimate.exact(100), Estimate.exact(50), Map.of());
      var after = new Leg("after", Estimate.exact(100), Estimate.exact(50), Map.of());
      var stop = new RefuelPlanner.StopOption("s", "station", "tank", before, after, Estimate.exact(1.4),
          Estimate.exact(5), Estimate.exact(0), Estimate.exact(0), Estimate.exact(0), List.of(10.0, 50.0));
      var result = RefuelPlanner.compare(Simulator.combustion(10), Simulator.route("direct", 200, 100, 0), List.of(stop), new Policy(5, .5, .25));
      var plan = result.decision().assessments().get(1);
      equal(EconomyEngine.Feasibility.FEASIBLE, plan.feasibility()); near(20.8, plan.consumedEnergyCostEuro().expected());
      near(14, plan.energyPurchasesEuro().expected()); near(8, plan.remainingStock().get("tank"));
      equal(EconomyEngine.Feasibility.INFEASIBLE, result.decision().assessments().get(2).feasibility());
    });
    System.out.println("PASS: " + passed + " fuel and refuelling scenarios");
  }
  private static String xml(String inner)
  { return "<pdv_liste><pdv id='s1' latitude='4812345' longitude='212345' cp='75000' pop='R'><adresse>Rue test</adresse><ville>Paris</ville>" + inner + "</pdv></pdv_liste>"; }
  private static Snapshot parse(String xml)
  {
    try { return new GovernmentFuelReader().readXml(new ByteArrayInputStream(xml.getBytes(java.nio.charset.StandardCharsets.UTF_8)), NOW); }
    catch (IOException error) { throw new IllegalArgumentException(error); }
  }
  private static void test(String name, Runnable action) { action.run(); passed++; System.out.println("PASS " + name); }
  private static void equal(Object a, Object b) { if (!Objects.equals(a,b)) throw new AssertionError("Expected " + a + ", got " + b); }
  private static void near(double a, Double b) { if (b == null || Math.abs(a-b)>1e-7) throw new AssertionError("Expected " + a + ", got " + b); }
  private static void invalid(Runnable action) { try { action.run(); } catch (IllegalArgumentException expected) { return; } throw new AssertionError("Invalid input accepted"); }
}
