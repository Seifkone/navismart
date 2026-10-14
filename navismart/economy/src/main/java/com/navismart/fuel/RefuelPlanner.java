package com.navismart.fuel;

import static com.navismart.economy.TripModel.*;
import com.navismart.economy.EconomyEngine;
import com.navismart.economy.Estimate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/** Evaluates caller-supplied real road legs and purchase quantities. Does not invent detour distances. */
public final class RefuelPlanner
{
  private RefuelPlanner() {}
  public record StopOption(String id, String name, String storeId, Leg before, Leg after,
                           Estimate pricePerUnit, Estimate stopMinutes, Estimate fixedFee,
                           Estimate tolls, Estimate otherFees, List<Double> purchaseQuantities)
  {
    public StopOption
    {
      if (id == null || id.isBlank()) throw new IllegalArgumentException("Stop id required");
      purchaseQuantities = List.copyOf(purchaseQuantities);
      if (purchaseQuantities.isEmpty() || purchaseQuantities.size() > 100) throw new IllegalArgumentException("Purchase candidates required");
      for (double value : purchaseQuantities)
        if (!Double.isFinite(value) || value <= 0) throw new IllegalArgumentException("Positive purchase quantity required");
    }
  }
  public record Result(EconomyEngine.Decision decision, List<EconomyEngine.Assessment> affordablePlans) {}
  public static Result compare(Vehicle vehicle, Route direct, List<StopOption> options, Policy policy)
  {
    if (options.size() > 100) throw new IllegalArgumentException("Too many stop candidates");
    List<Route> routes = new ArrayList<>(); routes.add(direct);
    for (StopOption stop : options)
    {
      Store store = vehicle.stores().stream().filter(s -> s.id().equals(stop.storeId())).findFirst()
          .orElseThrow(() -> new IllegalArgumentException("Unknown energy store"));
      if (store.energy().unit != Unit.LITRE)
        throw new IllegalArgumentException("Charging requires a separate loss and duration model");
      for (int i = 0; i < stop.purchaseQuantities().size(); i++)
      {
        double quantity = stop.purchaseQuantities().get(i);
        routes.add(new Route("stop:" + stop.id() + ":" + i, stop.name(), List.of(stop.before(),
            new Purchase(stop.name(), store.id(), quantity, quantity, stop.pricePerUnit(), stop.fixedFee(), stop.stopMinutes()),
            stop.after()), stop.tolls(), stop.otherFees()));
      }
    }
    var decision = new EconomyEngine().compare(vehicle, routes, policy, direct.id());
    // A conditional list for user review, never a claim that a guaranteed optimum was found.
    var plans = decision.assessments().stream().filter(a -> a.feasibility() != EconomyEngine.Feasibility.INFEASIBLE)
        .filter(a -> a.economicCostEuro().expected() != null)
        .filter(a -> decision.timeStatuses().get(a.route().id()) != EconomyEngine.TimeStatus.EXCEEDS)
        .sorted(Comparator.comparingDouble(a -> a.economicCostEuro().expected())).toList();
    return new Result(decision, plans);
  }
}
