package com.navismart.economy;

import static com.navismart.economy.TripModel.*;
import static com.navismart.economy.VehicleProfiles.*;

import java.io.StringReader;
import java.io.StringWriter;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

public final class ProfileTests
{
  private static final Instant NOW = Instant.parse("2026-10-13T12:00:00Z");
  private static final ProfileCodec CODEC = new ProfileCodec();
  private static int passed;
  private ProfileTests() {}
  public static void run()
  {
    test("empty catalog is explicit", () -> equal(Catalog.empty(), decode(encode(Catalog.empty()))));
    test("profile round trip keeps unicode, units, uncertainty and provenance", () -> {
      var profile = thermal("rio", "Kia — essai E85 éè\nligne=2", Energy.E85, 7.8);
      var catalog = new Catalog(List.of(profile), "rio");
      equal(catalog, decode(encode(catalog)));
    });
    test("all supported powertrains survive storage", () -> {
      var profiles = List.of(thermal("petrol", "Essence", Energy.SP95_E10, 6),
          thermal("diesel", "Diesel", Energy.DIESEL, 5), thermal("lpg", "GPL", Energy.LPG, 8),
          thermal("e85", "Flexfuel", Energy.E85, 8), electric(), hybrid(Powertrain.HYBRID),
          hybrid(Powertrain.PLUG_IN_HYBRID));
      var catalog = new Catalog(profiles, "diesel");
      equal(catalog, decode(encode(catalog)));
    });
    test("missing fields remain unknown and zero stock remains known", () -> {
      var p = new Profile(new Vehicle("empty", "Sans mesures", Powertrain.COMBUSTION,
          List.of(new Store("tank", Energy.DIESEL, null, 0.0, null,
              Estimate.missing(Estimate.State.UNKNOWN, "not measured"), Estimate.nominal(1.8, "manual assumption")))),
          new Policy(5, 0, 0), Characteristics.unknown(), NOW);
      var restored = decode(encode(new Catalog(List.of(p), "empty"))).active();
      equal(null, restored.vehicle().stores().get(0).capacity());
      equal(0.0, restored.vehicle().stores().get(0).initialStock());
      equal(null, restored.vehicle().stores().get(0).reserve());
      equal(false, restored.vehicle().stores().get(0).referencePrice().hasBounds());
    });
    test("duplicate ids and unknown active vehicle rejected", () -> {
      var p = thermal("a", "a", Energy.DIESEL, 5);
      invalid(() -> new Catalog(List.of(p, p), "a"));
      invalid(() -> new Catalog(List.of(p), "ghost"));
    });
    test("adding editing and selection preserve other profiles", () -> {
      var a = thermal("a", "a", Energy.DIESEL, 5);
      var b = electric();
      var edited = thermal("a", "renamed", Energy.DIESEL, 7);
      var catalog = Catalog.empty().put(a).put(b).activate("ev").put(edited);
      equal(List.of(edited, b), catalog.profiles()); equal(b, catalog.active());
    });
    test("removal selects a remaining profile and last removal empties selection", () -> {
      var catalog = Catalog.empty().put(electric()).put(thermal("a", "a", Energy.DIESEL, 5));
      equal("a", catalog.remove("ev").activeId());
      equal(Catalog.empty(), catalog.remove("ev").remove("a"));
      invalid(() -> catalog.remove("ghost"));
    });
    test("stale or undated stock never becomes a full tank", () -> {
      var p = electric();
      equal(null, p.forTrip(NOW.plus(Duration.ofDays(2)), Duration.ofDays(1)).stores().get(0).initialStock());
      var undated = new Profile(p.vehicle(), p.policy(), p.characteristics(), null);
      equal(null, undated.forTrip(NOW, Duration.ofDays(1)).stores().get(0).initialStock());
    });
    test("stock date boundary is inclusive and future dates invalidated", () -> {
      var p = electric();
      equal(40.0, p.forTrip(NOW.plus(Duration.ofDays(1)), Duration.ofDays(1)).stores().get(0).initialStock());
      equal(null, p.forTrip(NOW.minusSeconds(1), Duration.ofDays(1)).stores().get(0).initialStock());
      invalid(() -> p.forTrip(NOW, Duration.ZERO));
    });
    test("stored external price ages using its observation timestamp", () -> {
      var original = thermal("a", "a", Energy.DIESEL, 5);
      var s = original.vehicle().stores().get(0);
      var dated = new Store(s.id(), s.energy(), s.capacity(), s.initialStock(), s.reserve(), s.consumptionPer100Km(),
          s.referencePrice().withObservation(Estimate.State.FRESH, "dated quote", NOW.minus(Duration.ofDays(2))));
      var p = new Profile(new Vehicle("a", "a", Powertrain.COMBUSTION, List.of(dated)), original.policy(),
                          original.characteristics(), NOW);
      equal(Estimate.State.STALE, p.forTrip(NOW, Duration.ofDays(1)).stores().get(0).referencePrice().state());
    });
    test("vehicle comparison uses distinct units and consumption", () -> {
      var catalog = new Catalog(List.of(thermal("diesel", "Diesel", Energy.DIESEL, 5), electric()), "diesel");
      var results = VehicleProfiles.compare(catalog, List.of(Simulator.route("a", 100, 60, 0)), NOW, Duration.ofDays(1));
      near(9, results.get(0).decision().assessments().get(0).economicCostEuro().expected());
      near(4.5, results.get(1).decision().assessments().get(0).economicCostEuro().expected());
    });
    test("profile policy retained in comparison", () -> {
      var p = electric();
      var strict = new Profile(p.vehicle(), new Policy(0, 0.5, 0.25), p.characteristics(), NOW);
      var result = VehicleProfiles.compare(new Catalog(List.of(strict), "ev"), List.of(
          Simulator.route("fast", 100, 100, 10), Simulator.route("eco", 120, 105, 0)), NOW, Duration.ofDays(1));
      equal(EconomyEngine.TimeStatus.EXCEEDS, result.get(0).decision().timeStatuses().get("eco"));
    });
    test("hybrid without route energy split remains uncertain", () -> {
      var p = hybrid(Powertrain.PLUG_IN_HYBRID);
      var result = VehicleProfiles.compare(new Catalog(List.of(p), p.vehicle().id()),
          List.of(Simulator.route("a", 100, 60, 0)), NOW, Duration.ofDays(1));
      equal(null, result.get(0).decision().assessments().get(0).consumedEnergyCostEuro().expected());
    });
    test("incompatible powertrain and energy rejected", () -> {
      var diesel = thermal("a", "a", Energy.DIESEL, 5);
      invalid(() -> new Profile(new Vehicle("a", "a", Powertrain.ELECTRIC, diesel.vehicle().stores()),
          diesel.policy(), diesel.characteristics(), NOW));
      invalid(() -> new Profile(new Vehicle("a", "a", Powertrain.FLEXFUEL, diesel.vehicle().stores()),
          diesel.policy(), diesel.characteristics(), NOW));
    });
    test("unknown gear and mass are not invented", () -> {
      equal(null, Characteristics.unknown().massKg()); equal(List.of(), Characteristics.unknown().gearRatios());
      invalid(() -> new Characteristics(0.0, Gearbox.UNKNOWN, List.of()));
      invalid(() -> new Characteristics(null, Gearbox.MANUAL, List.of(Double.NaN)));
      var p = thermal("a", "a", Energy.E85, 8);
      var measured = new Profile(p.vehicle(), p.policy(), new Characteristics(1100.0, Gearbox.MANUAL, List.of(3.5, 2.1)), NOW);
      equal(measured, decode(encode(new Catalog(List.of(measured), "a"))).active());
    });
    test("unsupported version and corrupt content do not reset saved data", () -> {
      invalid(() -> decode("format.version=2\nprofiles.count=0\n"));
      invalid(() -> decode("format.version=1\nprofiles.count=-1\n"));
      invalid(() -> decode("format.version=1\nprofiles.count=101\n"));
      invalid(() -> decode("format.version=1\nprofiles.count=1\n"));
    });
    test("duplicate persistence fields rejected", () -> invalid(() -> decode(
        "format.version=1\nprofiles.count=0\nprofiles.count=1\n")));
    test("invalid observation date is a controlled format error", () -> invalid(() -> decode(
        encode(new Catalog(List.of(electric()), "ev")).replace("2026-10-13", "bad-date"))));
    test("observation lifetimes can be set by the calling adapter", () -> {
      var p = electric();
      equal(null, p.forTrip(NOW.plusSeconds(60), new Validity(Duration.ofSeconds(30), Duration.ofDays(1),
          Duration.ofHours(1))).stores().get(0).initialStock());
      invalid(() -> new Validity(Duration.ofDays(1), Duration.ZERO, Duration.ofDays(1)));
    });
    test("empty name and nonfinite consumption rejected", () -> {
      var p = electric();
      invalid(() -> new Profile(new Vehicle("ev", " ", Powertrain.ELECTRIC, p.vehicle().stores()),
          p.policy(), p.characteristics(), NOW));
      invalid(() -> Estimate.nominal(Double.POSITIVE_INFINITY, "manual"));
    });
    System.out.println("PASS: " + passed + " vehicle profile scenarios");
  }
  private static Profile thermal(String id, String name, Energy energy, double rate)
  {
    return new Profile(new Vehicle(id, name, energy == Energy.E85 ? Powertrain.FLEXFUEL : Powertrain.COMBUSTION,
        List.of(new Store("tank", energy, 50.0, 40.0, 2.0,
            Estimate.range(rate - 0.5, rate, rate + 0.5, "synthetic measurement"), Estimate.exact(1.8)))),
        new Policy(5, 0.5, 0.25), Characteristics.unknown(), NOW);
  }
  private static Profile electric()
  {
    return new Profile(new Vehicle("ev", "Électrique", Powertrain.ELECTRIC,
        List.of(new Store("battery", Energy.ELECTRICITY, 60.0, 40.0, 5.0,
            Estimate.range(16, 18, 20, "synthetic measurement"), Estimate.exact(0.25)))),
        new Policy(5, 0.5, 0.25), Characteristics.unknown(), NOW);
  }
  private static Profile hybrid(Powertrain type)
  {
    return new Profile(new Vehicle(type.name(), "Hybride", type,
        List.of(thermal("fuel", "fuel", Energy.SP95_E10, 5).vehicle().stores().get(0),
                electric().vehicle().stores().get(0))), new Policy(5, 0.5, 0.25), Characteristics.unknown(), NOW);
  }
  private static String encode(Catalog catalog)
  {
    try { var writer = new StringWriter(); CODEC.write(catalog, writer); return writer.toString(); }
    catch (java.io.IOException error) { throw new AssertionError(error); }
  }
  private static Catalog decode(String text)
  {
    try { return CODEC.read(new StringReader(text)); }
    catch (java.io.IOException error) { throw new AssertionError(error); }
  }
  private static void test(String name, Runnable action)
  {
    action.run(); passed++; System.out.println("PASS " + name);
  }
  private static void equal(Object expected, Object actual)
  {
    if (!java.util.Objects.equals(expected, actual)) throw new AssertionError("Expected " + expected + ", got " + actual);
  }
  private static void near(double expected, Double actual)
  {
    if (actual == null || Math.abs(expected - actual) > 1e-8) throw new AssertionError("Expected " + expected + ", got " + actual);
  }
  private static void invalid(Runnable action)
  {
    try { action.run(); } catch (IllegalArgumentException expected) { return; }
    throw new AssertionError("Invalid input was accepted");
  }
}
