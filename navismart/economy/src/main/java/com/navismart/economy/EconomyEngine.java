package com.navismart.economy;

import static com.navismart.economy.TripModel.*;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Pure local calculation. Routes and stop schedules are supplied, not invented by this engine. */
public final class EconomyEngine
{
  private static final double EPSILON = 1e-8;
  public enum Feasibility { FEASIBLE, UNCERTAIN, INFEASIBLE }
  public enum TimeStatus { WITHIN, UNCERTAIN, EXCEEDS, UNKNOWN }
  public enum Choice { SELECTED, KEEP_CURRENT, INDETERMINATE, NO_FEASIBLE_ROUTE }

  public record Assessment(Route route, Estimate distanceKm, Estimate durationMinutes,
                           Estimate consumedEnergyCostEuro, Estimate energyPurchasesEuro,
                           Estimate immediateExpenseEuro, Estimate economicCostEuro,
                           Map<String, Double> consumedUnits, Map<String, Double> remainingStock,
                           Double remainingInventoryValueEuro, Feasibility feasibility,
                           List<String> explanations)
  {
    public Assessment
    {
      consumedUnits = Collections.unmodifiableMap(new LinkedHashMap<>(consumedUnits));
      remainingStock = Collections.unmodifiableMap(new LinkedHashMap<>(remainingStock));
      explanations = List.copyOf(explanations);
    }
  }

  public record Decision(Choice choice, String recommendedRouteId, String referenceRouteId,
                         Double allowedExtraMinutes, List<Assessment> assessments,
                         Map<String, TimeStatus> timeStatuses, List<String> explanations)
  {
    public Decision
    {
      assessments = List.copyOf(assessments);
      timeStatuses = Map.copyOf(timeStatuses);
      explanations = List.copyOf(explanations);
    }
  }

  private enum Scenario { LOWER, CENTRAL, UPPER }
  private static final class Lot
  {
    double units;
    final Estimate price;
    Lot(double units, Estimate price) { this.units = units; this.price = price; }
  }

  private record Run(Double costEuro, Estimate.State state, boolean bounded,
                     Map<String, Double> consumed, Map<String, Double> remaining,
                     Double remainingValueEuro, Feasibility feasibility, List<String> issues) {}

  public Assessment evaluate(Vehicle vehicle, Route route)
  {
    Objects.requireNonNull(vehicle);
    Objects.requireNonNull(route);
    Run central = run(vehicle, route, Scenario.CENTRAL);
    Run lower = run(vehicle, route, Scenario.LOWER);
    Run upper = run(vehicle, route, Scenario.UPPER);
    boolean bounds = central.bounded && lower.costEuro != null && upper.costEuro != null;
    Estimate energy = new Estimate(central.costEuro, bounds ? lower.costEuro : null,
                                   bounds ? upper.costEuro : null, central.state,
                                   "FIFO acquisition cost of consumed energy", null);

    List<Estimate> distance = new ArrayList<>(), duration = new ArrayList<>(), purchases = new ArrayList<>();
    List<Estimate> fees = new ArrayList<>(List.of(route.tolls(), route.otherFees()));
    for (Event event : route.events())
    {
      if (event instanceof Leg leg)
      {
        distance.add(leg.distanceKm());
        duration.add(leg.durationMinutes());
      }
      else if (event instanceof Purchase purchase)
      {
        duration.add(purchase.durationMinutes());
        purchases.add(purchase.pricePerPurchasedUnit().scale(purchase.purchasedUnits()));
        fees.add(purchase.fixedFee());
      }
    }
    Estimate spent = Estimate.sum(purchases), charges = Estimate.sum(fees);
    Feasibility feasibility = central.feasibility;
    List<String> issues = new ArrayList<>(central.issues);
    if (feasibility == Feasibility.FEASIBLE &&
        (lower.feasibility != Feasibility.FEASIBLE || upper.feasibility != Feasibility.FEASIBLE))
    {
      feasibility = Feasibility.UNCERTAIN;
      issues.add("Energy reserve or capacity is uncertain inside the supplied bounds");
    }
    if (!energy.isFresh()) issues.add("Consumed energy cost is missing, stale or invalid");
    if (!charges.isFresh()) issues.add("Some route-specific fees are missing, stale or invalid");
    if (!Estimate.sum(duration).hasBounds()) issues.add("Travel time uncertainty is not quantified");
    return new Assessment(route, Estimate.sum(distance), Estimate.sum(duration), energy, spent,
                          Estimate.sum(List.of(spent, charges)), Estimate.sum(List.of(energy, charges)),
                          central.consumed, central.remaining, central.remainingValueEuro, feasibility,
                          issues.stream().distinct().toList());
  }

  public Decision compare(Vehicle vehicle, List<Route> routes, Policy policy, String currentRouteId)
  {
    Objects.requireNonNull(policy);
    Map<String, Assessment> byId = new LinkedHashMap<>();
    for (Route route : routes)
      if (byId.putIfAbsent(route.id(), evaluate(vehicle, route)) != null)
        throw new IllegalArgumentException("Duplicate route id: " + route.id());
    if (currentRouteId != null && !byId.containsKey(currentRouteId))
      throw new IllegalArgumentException("Current route is not in the candidate list");
    List<Assessment> assessments = new ArrayList<>(byId.values());
    List<Assessment> possible = assessments.stream()
        .filter(a -> a.feasibility != Feasibility.INFEASIBLE).toList();
    if (possible.isEmpty())
      return new Decision(Choice.NO_FEASIBLE_ROUTE, null, null, null, assessments, Map.of(),
                          List.of("No supplied route preserves the energy reserve and capacity constraints"));
    Assessment reference = possible.stream().filter(a -> a.durationMinutes.isFresh())
        .min(Comparator.comparingDouble(a -> a.durationMinutes.expected())).orElse(null);
    if (reference == null)
      return new Decision(Choice.INDETERMINATE, null, null, null, assessments, Map.of(),
                          List.of("No fresh reference duration: time constraint cannot be evaluated"));

    Map<String, TimeStatus> time = new LinkedHashMap<>();
    for (Assessment assessment : assessments)
      time.put(assessment.route.id(), timeStatus(assessment, reference, policy));
    List<Assessment> comparable = possible.stream()
        .filter(a -> a.economicCostEuro.isFresh() && a.immediateExpenseEuro.isFresh() && a.durationMinutes.isFresh())
        .filter(a -> time.get(a.route.id()) != TimeStatus.EXCEEDS)
        .sorted(Comparator.comparingDouble((Assessment a) -> a.economicCostEuro.expected())
                .thenComparingDouble(a -> a.durationMinutes.expected()).thenComparing(a -> a.route.id()))
        .toList();
    Double allowance = policy.extraMinutes(reference.durationMinutes.expected());
    if (comparable.isEmpty())
      return new Decision(Choice.INDETERMINATE, null, reference.route.id(), allowance, assessments, time,
                          List.of("No complete, fresh economic cost inside the nominal time budget"));

    Assessment best = comparable.get(0);
    Assessment baseline = byId.get(currentRouteId == null ? reference.route.id() : currentRouteId);
    boolean baselineUsable = baseline != null && baseline.feasibility != Feasibility.INFEASIBLE
        && baseline.economicCostEuro.isFresh() && baseline.immediateExpenseEuro.isFresh()
        && baseline.durationMinutes.isFresh()
        && time.get(baseline.route.id()) != TimeStatus.EXCEEDS;
    List<String> explanation = new ArrayList<>();
    Choice choice = Choice.SELECTED;
    if (baselineUsable && !best.route.id().equals(baseline.route.id()))
    {
      double threshold = policy.minimumSavingsEuro() + policy.switchingPenaltyEuro();
      double nominalGain = baseline.economicCostEuro.expected() - best.economicCostEuro.expected();
      boolean robustGain = baseline.economicCostEuro.hasBounds() && best.economicCostEuro.hasBounds()
          && baseline.economicCostEuro.lower() - best.economicCostEuro.upper() > threshold + EPSILON;
      if (nominalGain <= threshold + EPSILON || !robustGain ||
          time.get(best.route.id()) != TimeStatus.WITHIN || best.feasibility != Feasibility.FEASIBLE)
      {
        explanation.add("Keep current/reference route: gain, time or energy feasibility is not sufficiently established");
        best = baseline;
        choice = Choice.KEEP_CURRENT;
      }
      else
        explanation.add("Savings exceed the uncertainty, minimum gain and switching penalty");
    }
    else if (baselineUsable && best.route.id().equals(baseline.route.id()))
    {
      choice = Choice.KEEP_CURRENT;
      explanation.add("Current/reference route is already the least expensive comparable route");
    }
    if (best.feasibility != Feasibility.FEASIBLE || time.get(best.route.id()) != TimeStatus.WITHIN ||
        !best.economicCostEuro.hasBounds())
    {
      choice = Choice.INDETERMINATE;
      explanation.add("Nominal candidate only: uncertainty prevents a definitive recommendation");
    }
    if (assessments.stream().anyMatch(a -> a.feasibility != Feasibility.INFEASIBLE
                                          && (!a.economicCostEuro.isFresh() || !a.immediateExpenseEuro.isFresh())))
    {
      choice = Choice.INDETERMINATE;
      explanation.add("Some possible routes have unknown costs: the overall cheapest route cannot be established");
    }
    return new Decision(choice, best.route.id(), reference.route.id(), allowance, assessments, time, explanation);
  }

  private TimeStatus timeStatus(Assessment candidate, Assessment reference, Policy policy)
  {
    Estimate time = candidate.durationMinutes;
    if (!time.isFresh()) return TimeStatus.UNKNOWN;
    if (candidate.route.id().equals(reference.route.id())) return TimeStatus.WITHIN;
    if (time.expected() > policy.maximumMinutes(reference.durationMinutes.expected()) + EPSILON)
      return TimeStatus.EXCEEDS;
    if (!time.hasBounds() || !reference.durationMinutes.hasBounds()) return TimeStatus.UNCERTAIN;
    return time.upper() <= policy.maximumMinutes(reference.durationMinutes.lower()) + EPSILON
        ? TimeStatus.WITHIN : TimeStatus.UNCERTAIN;
  }

  private Run run(Vehicle vehicle, Route route, Scenario scenario)
  {
    Map<String, Store> stores = new LinkedHashMap<>();
    Map<String, ArrayDeque<Lot>> lots = new LinkedHashMap<>();
    Map<String, Double> consumed = new LinkedHashMap<>(), stock = new LinkedHashMap<>();
    Map<String, Boolean> unknownInitial = new HashMap<>();
    List<String> issues = new ArrayList<>();
    Feasibility feasibility = Feasibility.FEASIBLE;
    Estimate.State state = Estimate.State.FRESH;
    boolean completeCost = true, bounded = true;
    double cost = 0;
    for (Store store : vehicle.stores())
    {
      stores.put(store.id(), store);
      consumed.put(store.id(), 0.0);
      stock.put(store.id(), store.initialStock());
      unknownInitial.put(store.id(), store.initialStock() == null);
      ArrayDeque<Lot> queue = new ArrayDeque<>();
      if (store.initialStock() == null || store.initialStock() > 0)
        queue.add(new Lot(store.initialStock() == null ? Double.POSITIVE_INFINITY : store.initialStock(),
                          store.referencePrice()));
      lots.put(store.id(), queue);
      if (store.capacity() == null || store.initialStock() == null || store.reserve() == null)
      {
        feasibility = Feasibility.UNCERTAIN;
        issues.add("Unknown capacity, initial stock or reserve for " + store.id());
      }
    }

    for (Event event : route.events())
    {
      if (event instanceof Purchase purchase)
      {
        Store store = stores.get(purchase.storeId());
        if (store == null) throw new IllegalArgumentException("Unknown energy store: " + purchase.storeId());
        Double previousStock = stock.get(store.id());
        if (previousStock != null)
        {
          double after = previousStock + purchase.storedUnits();
          stock.put(store.id(), after);
          if (store.capacity() != null && after > store.capacity() + EPSILON)
          {
            feasibility = Feasibility.INFEASIBLE;
            issues.add("Purchase exceeds capacity at " + purchase.name());
          }
        }
        else
        {
          completeCost = false;
          state = merge(state, Estimate.State.UNKNOWN);
          issues.add("Unknown starting inventory prevents valuing energy from purchases");
        }
        lots.get(store.id()).add(new Lot(purchase.storedUnits(), purchase.pricePerPurchasedUnit()
            .scale(purchase.purchasedUnits() / purchase.storedUnits())));
      }
      else if (event instanceof Leg leg)
      {
        for (String id : leg.consumptionOverrides().keySet())
          if (!stores.containsKey(id)) throw new IllegalArgumentException("Unknown consumption store: " + id);
        for (Store store : stores.values())
        {
          Estimate rate = leg.consumptionOverrides().get(store.id());
          if (rate == null)
            rate = vehicle.stores().size() == 1 ? store.consumptionPer100Km()
                : Estimate.missing(Estimate.State.UNKNOWN, "explicit hybrid energy split required");
          bounded &= leg.distanceKm().hasBounds() && rate.hasBounds();
          state = merge(state, merge(leg.distanceKm().state(), rate.state()));
          if ((!leg.distanceKm().isFresh() || !rate.isFresh()) && feasibility != Feasibility.INFEASIBLE)
            feasibility = Feasibility.UNCERTAIN;
          if (leg.distanceKm().expected() == null || rate.expected() == null)
          {
            if (feasibility != Feasibility.INFEASIBLE) feasibility = Feasibility.UNCERTAIN;
            completeCost = false;
            stock.put(store.id(), null);
            consumed.put(store.id(), null);
            issues.add("Missing distance or consumption in " + leg.name() + " / " + store.id());
            continue;
          }
          double demand = value(leg.distanceKm(), scenario) * value(rate, scenario) / 100;
          if (!Double.isFinite(demand)) throw new IllegalArgumentException("Energy demand overflow");
          Double previousConsumed = consumed.get(store.id());
          consumed.put(store.id(), previousConsumed == null ? null : previousConsumed + demand);
          Double previousStock = stock.get(store.id());
          if (previousStock != null)
          {
            double after = previousStock - demand;
            stock.put(store.id(), after);
            if (after < -EPSILON || (store.reserve() != null && after < store.reserve() - EPSILON))
            {
              feasibility = Feasibility.INFEASIBLE;
              issues.add("Energy reserve violated before the next stop in " + leg.name() + " / " + store.id());
            }
          }
          ArrayDeque<Lot> queue = lots.get(store.id());
          double needed = demand;
          while (needed > EPSILON && !queue.isEmpty())
          {
            Lot lot = queue.peek();
            double used = Math.min(needed, lot.units);
            bounded &= lot.price.hasBounds();
            state = merge(state, lot.price.state());
            if (lot.price.expected() == null) completeCost = false;
            else cost += used * value(lot.price, scenario);
            needed -= used;
            lot.units -= used;
            if (lot.units < EPSILON) queue.remove();
          }
          if (needed > EPSILON) { completeCost = false; state = merge(state, Estimate.State.UNKNOWN); }
        }
      }
    }
    Double remainingValue = 0.0;
    for (Map.Entry<String, ArrayDeque<Lot>> entry : lots.entrySet())
    {
      if (unknownInitial.get(entry.getKey()) || stock.get(entry.getKey()) == null) remainingValue = null;
      for (Lot lot : entry.getValue())
      {
        if (lot.price.expected() == null || !lot.price.isFresh()) remainingValue = null;
        else if (remainingValue != null) remainingValue += lot.units * value(lot.price, scenario);
      }
    }
    if (!completeCost && state == Estimate.State.FRESH) state = Estimate.State.UNKNOWN;
    return new Run(completeCost ? cost : null, state, bounded, consumed, stock, remainingValue,
                   feasibility, issues);
  }

  private static Estimate.State merge(Estimate.State first, Estimate.State second)
  {
    return first.ordinal() >= second.ordinal() ? first : second;
  }

  private static double value(Estimate value, Scenario scenario)
  {
    if (!value.hasBounds() || scenario == Scenario.CENTRAL) return value.expected();
    return scenario == Scenario.LOWER ? value.lower() : value.upper();
  }
}
