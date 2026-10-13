package com.navismart.economy;

import static com.navismart.economy.TripModel.*;
import static com.navismart.economy.EconomyEngine.*;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Executable acceptance tests with no external test framework or Android dependency. */
public final class EconomyTests
{
  private static final EconomyEngine ENGINE = new EconomyEngine();
  private static final Policy POLICY = new Policy(5, 0.5, 0.25);
  private static int passed;
  private EconomyTests() {}

  public static void main(String[] args)
  {
    test("shortest is not cheapest", () -> {
      var result = ENGINE.compare(Simulator.combustion(40), List.of(
          Simulator.route("fast", 100, 100, 10), Simulator.route("eco", 120, 105, 0)), POLICY, null);
      equal("eco", result.recommendedRouteId()); equal(Choice.SELECTED, result.choice());
    });
    test("percentage applied once with displayed minutes", () -> {
      var result = ENGINE.compare(Simulator.combustion(40), List.of(
          Simulator.route("fast", 100, 40, 10), Simulator.route("late", 90, 43, 0)), POLICY, null);
      near(2, result.allowedExtraMinutes()); equal("fast", result.recommendedRouteId());
      equal(TimeStatus.EXCEEDS, result.timeStatuses().get("late"));
    });
    test("time boundary inclusive", () -> {
      var result = ENGINE.compare(Simulator.combustion(40), List.of(
          Simulator.route("fast", 100, 40, 10), Simulator.route("eco", 90, 42, 0)), POLICY, null);
      equal("eco", result.recommendedRouteId());
    });
    test("energy consumed differs from immediate expense", () -> {
      var result = ENGINE.evaluate(Simulator.combustion(40), Simulator.route("a", 100, 100, 2));
      near(10.8, result.consumedEnergyCostEuro().expected());
      near(2, result.immediateExpenseEuro().expected()); near(12.8, result.economicCostEuro().expected());
    });
    test("FIFO and remaining stock avoid double counting purchases", () -> {
      var result = ENGINE.evaluate(Simulator.combustion(10), withRefuel(100, 200, 12));
      near(30.4, result.consumedEnergyCostEuro().expected()); near(18.6, result.energyPurchasesEuro().expected());
      near(4, result.remainingStock().get("tank")); near(6.2, result.remainingInventoryValueEuro());
      near(18 + 18.6 - 6.2, result.economicCostEuro().expected());
    });
    test("purchase fee included once in each distinct cost view", () -> {
      var route = new Route("a", "a", List.of(
          new Purchase("start", "tank", 10, 10, Estimate.exact(1.5), Estimate.exact(2), Estimate.exact(5)),
          leg(100, 60)), Estimate.exact(3), Estimate.exact(0));
      var result = ENGINE.evaluate(Simulator.combustion(0), route);
      near(14, result.economicCostEuro().expected()); near(20, result.immediateExpenseEuro().expected());
    });
    test("unknown tolls never become zero", () -> {
      var route = new Route("unknown", "unknown", List.of(leg(100, 100)),
          Estimate.missing(Estimate.State.UNKNOWN, "not provided"), Estimate.exact(0));
      var result = ENGINE.compare(Simulator.combustion(40), List.of(route, Simulator.route("known", 110, 105, 10)), POLICY, null);
      equal(Choice.INDETERMINATE, result.choice());
      equal(null, result.assessments().get(0).economicCostEuro().expected());
    });
    test("confirmed zero tolls remain a usable value", () -> {
      var result = ENGINE.evaluate(Simulator.combustion(40), Simulator.route("a", 100, 60, 0));
      near(10.8, result.economicCostEuro().expected());
      equal(Estimate.State.FRESH, result.economicCostEuro().state());
    });
    test("station must be reachable before refuelling", () -> {
      var result = ENGINE.evaluate(Simulator.combustion(10), withRefuel(150, 100, 20));
      equal(Feasibility.INFEASIBLE, result.feasibility());
    });
    test("negative stock is not silently clamped to zero", () -> {
      var result = ENGINE.evaluate(Simulator.combustion(3), Simulator.route("a", 100, 60, 0));
      equal(Feasibility.INFEASIBLE, result.feasibility()); near(-3, result.remainingStock().get("tank"));
      equal(null, result.economicCostEuro().expected());
    });
    test("capacity checked at every purchase", () -> {
      var result = ENGINE.evaluate(Simulator.combustion(40), withRefuel(10, 10, 20));
      equal(Feasibility.INFEASIBLE, result.feasibility());
    });
    test("reserve preserved exactly", () -> {
      var result = ENGINE.evaluate(Simulator.combustion(8), Simulator.route("a", 100, 60, 0));
      equal(Feasibility.FEASIBLE, result.feasibility()); near(2, result.remainingStock().get("tank"));
    });
    test("zero starting stock is known and can be replenished before driving", () -> {
      var result = ENGINE.evaluate(Simulator.combustion(0), new Route("a", "a", List.of(
          new Purchase("start", "tank", 10, 10, Estimate.exact(1.5), Estimate.exact(0), Estimate.exact(5)),
          leg(100, 60)), Estimate.exact(0), Estimate.exact(0)));
      equal(Feasibility.FEASIBLE, result.feasibility()); near(9, result.economicCostEuro().expected());
    });
    test("unknown stock is not a full tank", () -> {
      var vehicle = new Vehicle("v", "v", Powertrain.COMBUSTION, List.of(
          new Store("tank", Energy.DIESEL, 50.0, null, 2.0, Estimate.exact(6), Estimate.exact(1.8))));
      var result = ENGINE.evaluate(vehicle, Simulator.route("a", 100, 60, 0));
      equal(Feasibility.UNCERTAIN, result.feasibility()); equal(null, result.remainingStock().get("tank"));
      var choice = ENGINE.compare(vehicle, List.of(Simulator.route("a", 100, 60, 0)), POLICY, null);
      equal(Choice.INDETERMINATE, choice.choice());
    });
    test("unknown stock plus purchase has unknown consumed acquisition cost", () -> {
      var vehicle = new Vehicle("v", "v", Powertrain.COMBUSTION, List.of(
          new Store("tank", Energy.DIESEL, 50.0, null, 2.0, Estimate.exact(6), Estimate.exact(1.8))));
      equal(null, ENGINE.evaluate(vehicle, withRefuel(10, 100, 10)).economicCostEuro().expected());
    });
    test("electric losses separate bought and stored energy", () -> {
      var vehicle = new Vehicle("ev", "EV", Powertrain.ELECTRIC, List.of(
          new Store("battery", Energy.ELECTRICITY, 60.0, 30.0, 5.0, Estimate.exact(18), Estimate.exact(0.25))));
      var route = new Route("ev", "ev", List.of(
          new Purchase("charge", "battery", 22, 20, Estimate.exact(0.5), Estimate.exact(0), Estimate.exact(20)),
          leg(200, 120)), Estimate.exact(0), Estimate.exact(0));
      var result = ENGINE.evaluate(vehicle, route);
      near(11, result.immediateExpenseEuro().expected()); near(10.8, result.economicCostEuro().expected());
      near(14, result.remainingStock().get("battery")); equal(Unit.KWH, Energy.ELECTRICITY.unit);
    });
    test("hybrid split must be explicit", () -> {
      var result = ENGINE.evaluate(hybrid(), Simulator.route("a", 100, 60, 0));
      equal(null, result.economicCostEuro().expected()); equal(Feasibility.UNCERTAIN, result.feasibility());
    });
    test("explicit hybrid split sums litres and kWh as costs", () -> {
      var route = new Route("a", "a", List.of(new Leg("mix", Estimate.exact(100), Estimate.exact(60),
          Map.of("tank", Estimate.exact(2), "battery", Estimate.exact(10)))), Estimate.exact(0), Estimate.exact(0));
      near(6.1, ENGINE.evaluate(hybrid(), route).economicCostEuro().expected());
    });
    test("measured E85 consumption has no invented multiplier", () -> {
      var vehicle = new Vehicle("flex", "Flexfuel", Powertrain.FLEXFUEL, List.of(
          new Store("tank", Energy.E85, 50.0, 40.0, 2.0, Estimate.exact(8), Estimate.exact(0.8))));
      near(6.4, ENGINE.evaluate(vehicle, Simulator.route("a", 100, 60, 0)).economicCostEuro().expected());
    });
    test("overlapping cost ranges prevent oscillation", () -> {
      var vehicle = Simulator.combustion(40);
      var a = uncertainRate("a", 5.5, 6, 6.5, 100);
      var b = uncertainRate("b", 5, 5.5, 6.2, 102);
      var result = ENGINE.compare(vehicle, List.of(a, b), POLICY, "a");
      equal("a", result.recommendedRouteId()); equal(Choice.KEEP_CURRENT, result.choice());
    });
    test("time uncertainty prevents a guaranteed switch", () -> {
      var a = new Route("a", "a", List.of(new Leg("a", Estimate.exact(100),
          Estimate.range(90, 100, 110, "scenario"), Map.of())), Estimate.exact(10), Estimate.exact(0));
      var b = new Route("b", "b", List.of(new Leg("b", Estimate.exact(110),
          Estimate.range(98, 104, 115, "scenario"), Map.of())), Estimate.exact(0), Estimate.exact(0));
      var result = ENGINE.compare(Simulator.combustion(40), List.of(a, b), POLICY, "a");
      equal(TimeStatus.UNCERTAIN, result.timeStatuses().get("b")); equal("a", result.recommendedRouteId());
    });
    test("unquantified uncertainty is not a zero-width interval", () -> {
      var a = new Route("a", "a", List.of(new Leg("a", Estimate.exact(100),
          Estimate.nominal(60, "routing baseline"), Map.of())), Estimate.exact(0), Estimate.exact(0));
      var b = Simulator.route("b", 100, 62, 0);
      var result = ENGINE.compare(Simulator.combustion(40), List.of(a, b), POLICY, null);
      equal(TimeStatus.UNCERTAIN, result.timeStatuses().get("b"));
    });
    test("minimum saving and switching penalty form hysteresis", () -> {
      var result = ENGINE.compare(Simulator.combustion(40), List.of(
          Simulator.route("a", 100, 100, 0.6), Simulator.route("b", 100, 100, 0)), POLICY, "a");
      equal("a", result.recommendedRouteId());
    });
    test("stale price is visible and not used for a definitive choice", () -> {
      Instant now = Instant.parse("2026-10-13T12:00:00Z");
      Estimate price = DataFreshness.check(Estimate.exact(1.8).withObservation(Estimate.State.FRESH,
          "government sample", now.minus(Duration.ofDays(3))), now, Duration.ofDays(1));
      var vehicle = new Vehicle("v", "v", Powertrain.COMBUSTION, List.of(
          new Store("tank", Energy.SP95, 50.0, 40.0, 2.0, Estimate.exact(6), price)));
      var result = ENGINE.compare(vehicle, List.of(Simulator.route("a", 100, 60, 0)), POLICY, null);
      equal(Choice.INDETERMINATE, result.choice());
      equal(Estimate.State.STALE, result.assessments().get(0).economicCostEuro().state());
    });
    test("future timestamp is invalid", () -> {
      Instant now = Instant.parse("2026-10-13T12:00:00Z");
      equal(Estimate.State.INVALID, DataFreshness.check(Estimate.exact(1).withObservation(
          Estimate.State.FRESH, "sample", now.plusSeconds(1)), now, Duration.ofHours(1)).state());
    });
    test("consumption range crossing reserve is uncertain", () -> {
      var result = ENGINE.evaluate(Simulator.combustion(8), uncertainRate("a", 5, 6, 7, 60));
      equal(Feasibility.UNCERTAIN, result.feasibility());
    });
    test("all impossible routes return no feasible route", () -> {
      var result = ENGINE.compare(Simulator.combustion(3), List.of(Simulator.route("a", 100, 60, 0)), POLICY, null);
      equal(Choice.NO_FEASIBLE_ROUTE, result.choice()); equal(null, result.recommendedRouteId());
    });
    test("missing fee and provider failure remain distinct states", () -> {
      var route = new Route("a", "a", List.of(leg(100, 60)),
          Estimate.missing(Estimate.State.UNAVAILABLE, "provider failed"), Estimate.exact(0));
      equal(Estimate.State.UNAVAILABLE, ENGINE.evaluate(Simulator.combustion(40), route).economicCostEuro().state());
    });
    test("time for a refuelling stop counts in the route budget", () -> {
      var result = ENGINE.evaluate(Simulator.combustion(10), withRefuel(100, 200, 12));
      near(185, result.durationMinutes().expected());
    });
    test("duplicate routes rejected", () -> invalid(() -> ENGINE.compare(Simulator.combustion(40),
        List.of(Simulator.route("a", 10, 10, 0), Simulator.route("a", 20, 20, 0)), POLICY, null)));
    test("negative, infinite and NaN inputs rejected", () -> {
      invalid(() -> Estimate.exact(-1)); invalid(() -> Estimate.exact(Double.NaN));
      invalid(() -> Estimate.exact(Double.POSITIVE_INFINITY)); invalid(() -> new Policy(-5, 0, 0));
      invalid(() -> Estimate.range(5, 3, 10, "bad"));
    });
    test("inconsistent stock and reserve rejected", () -> {
      invalid(() -> new Store("s", Energy.DIESEL, 50.0, 60.0, 5.0, Estimate.exact(6), Estimate.exact(1)));
      invalid(() -> new Store("s", Energy.DIESEL, 50.0, 40.0, 60.0, Estimate.exact(6), Estimate.exact(1)));
    });
    test("unknown consumption source rejected instead of ignored", () -> {
      var route = new Route("a", "a", List.of(new Leg("bad", Estimate.exact(10), Estimate.exact(10),
          Map.of("unknown-store", Estimate.exact(1)))), Estimate.exact(0), Estimate.exact(0));
      invalid(() -> ENGINE.evaluate(Simulator.combustion(40), route));
    });
    test("tests independent of candidate input order", () -> {
      var a = Simulator.route("a", 100, 100, 10); var b = Simulator.route("b", 110, 102, 0);
      var first = ENGINE.compare(Simulator.combustion(40), List.of(a, b), POLICY, null);
      var second = ENGINE.compare(Simulator.combustion(40), List.of(b, a), POLICY, null);
      equal(first.recommendedRouteId(), second.recommendedRouteId()); equal(first.choice(), second.choice());
    });
    test("unknown purchase price cannot produce a definitive recommendation", () -> {
      var route = new Route("a", "a", List.of(leg(10, 10), new Purchase("end refill", "tank", 5, 5,
          Estimate.missing(Estimate.State.UNKNOWN, "no quote"), Estimate.exact(0), Estimate.exact(5))),
          Estimate.exact(0), Estimate.exact(0));
      var result = ENGINE.compare(Simulator.combustion(40), List.of(route), POLICY, null);
      equal(Choice.INDETERMINATE, result.choice());
      near(1.08, result.assessments().get(0).economicCostEuro().expected());
      equal(null, result.assessments().get(0).immediateExpenseEuro().expected());
    });
    test("source without observation date has unknown freshness", () -> {
      equal(Estimate.State.UNKNOWN, DataFreshness.check(Estimate.exact(1.8),
          Instant.parse("2026-10-13T12:00:00Z"), Duration.ofDays(1)).state());
    });
    test("configurable scenario preserves unknown fees and unquantified uncertainty", () -> {
      var scenario = readScenario("");
      equal(Estimate.State.UNKNOWN, scenario.routes().get(0).tolls().state());
      equal(false, scenario.vehicle().stores().get(0).consumptionPer100Km().hasBounds());
      near(6.5, scenario.vehicle().stores().get(0).consumptionPer100Km().expected());
    });
    test("configured timestamp is the observation date, not import time", () -> {
      var scenario = readScenario("store.tank.price.observedAt=2026-10-10T12:00:00Z\n" +
                                  "store.tank.price.maxAgeSeconds=86400\n");
      equal(Estimate.State.STALE, scenario.vehicle().stores().get(0).referencePrice().state());
      equal(Instant.parse("2026-10-10T12:00:00Z"), scenario.vehicle().stores().get(0).referencePrice().observedAt());
    });
    test("dated quote without a source lifetime stays conditional", () -> {
      var scenario = readScenario("store.tank.price.observedAt=2026-10-13T11:00:00Z\n");
      equal(Estimate.State.UNKNOWN, scenario.vehicle().stores().get(0).referencePrice().state());
    });
    System.out.println("PASS: " + passed + " acceptance scenarios");
  }

  private static Vehicle hybrid()
  {
    return new Vehicle("hybrid", "Hybride simulé", Powertrain.PLUG_IN_HYBRID, List.of(
        new Store("tank", Energy.SP95_E10, 50.0, 40.0, 2.0, Estimate.exact(6), Estimate.exact(1.8)),
        new Store("battery", Energy.ELECTRICITY, 20.0, 18.0, 2.0, Estimate.exact(18), Estimate.exact(0.25))));
  }

  private static Leg leg(double km, double minutes)
  {
    return new Leg("Travel", Estimate.exact(km), Estimate.exact(minutes), Map.of());
  }

  private static Route withRefuel(double beforeKm, double afterKm, double added)
  {
    return new Route("refuel", "refuel", List.of(leg(beforeKm, 60),
        new Purchase("station", "tank", added, added, Estimate.exact(1.55), Estimate.exact(0), Estimate.exact(5)),
        leg(afterKm, 120)), Estimate.exact(0), Estimate.exact(0));
  }

  private static Route uncertainRate(String id, double low, double expected, double high, double minutes)
  {
    return new Route(id, id, List.of(new Leg(id, Estimate.exact(100), Estimate.exact(minutes),
        Map.of("tank", Estimate.range(low, expected, high, "synthetic consumption uncertainty")))),
        Estimate.exact(0), Estimate.exact(0));
  }

  private static void test(String name, Runnable action)
  {
    try { action.run(); passed++; System.out.println("PASS " + name); }
    catch (Throwable error) { throw new AssertionError("FAIL " + name, error); }
  }

  private static void near(double expected, Double actual)
  {
    if (actual == null || Math.abs(expected - actual) > 1e-7)
      throw new AssertionError("Expected " + expected + ", got " + actual);
  }

  private static void equal(Object expected, Object actual)
  {
    if (!java.util.Objects.equals(expected, actual))
      throw new AssertionError("Expected " + expected + ", got " + actual);
  }

  private static void invalid(Runnable action)
  {
    try { action.run(); } catch (IllegalArgumentException expected) { return; }
    throw new AssertionError("Invalid input was accepted");
  }

  private static ScenarioReader.Scenario readScenario(String extra)
  {
    String properties = """
        vehicle.id=v
        vehicle.name=Test vehicle
        vehicle.powertrain=COMBUSTION
        vehicle.stores=tank
        store.tank.energy=SP95_E10
        store.tank.capacity=50
        store.tank.stock=40
        store.tank.reserve=5
        store.tank.consumption.value=6,5
        store.tank.price.value=1.8
        policy.extraTimePercent=5
        policy.minimumSavingsEuro=0.5
        policy.switchingPenaltyEuro=0.25
        routes=a
        route.a.events=drive
        route.a.event.drive.kind=leg
        route.a.event.drive.distance.value=100
        route.a.event.drive.duration.value=60
        """;
    try
    {
      return new ScenarioReader().read(new java.io.StringReader(properties + extra),
                                       Instant.parse("2026-10-13T12:00:00Z"));
    }
    catch (java.io.IOException error) { throw new AssertionError(error); }
  }
}
