package com.navismart.economy;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** SI-adjacent explicit units: km, minutes, euros, litres and usable battery kWh. */
public final class TripModel
{
  private TripModel() {}

  public enum Unit { LITRE, KWH }
  public enum Energy
  {
    SP95_E10(Unit.LITRE), SP95(Unit.LITRE), SP98(Unit.LITRE), DIESEL(Unit.LITRE),
    E85(Unit.LITRE), LPG(Unit.LITRE), ELECTRICITY(Unit.KWH);
    public final Unit unit;
    Energy(Unit unit) { this.unit = unit; }
  }
  public enum Powertrain { COMBUSTION, FLEXFUEL, HYBRID, PLUG_IN_HYBRID, ELECTRIC }

  /** Reference price is the acquisition value per stored unit of the starting stock. */
  public record Store(String id, Energy energy, Double capacity, Double initialStock, Double reserve,
                      Estimate consumptionPer100Km, Estimate referencePrice)
  {
    public Store
    {
      requireId(id);
      Objects.requireNonNull(energy);
      Objects.requireNonNull(consumptionPer100Km);
      Objects.requireNonNull(referencePrice);
      if (capacity != null && (!Double.isFinite(capacity) || capacity <= 0))
        throw new IllegalArgumentException("Capacity must be positive");
      if (initialStock != null) Estimate.checkNumber(initialStock);
      if (reserve != null) Estimate.checkNumber(reserve);
      if (capacity != null && ((initialStock != null && initialStock > capacity)
                              || (reserve != null && reserve > capacity)))
        throw new IllegalArgumentException("Stock or reserve exceeds capacity");
    }
  }

  public record Vehicle(String id, String name, Powertrain powertrain, List<Store> stores)
  {
    public Vehicle
    {
      requireId(id);
      Objects.requireNonNull(name);
      Objects.requireNonNull(powertrain);
      stores = List.copyOf(stores);
      if (stores.isEmpty()) throw new IllegalArgumentException("At least one energy store is required");
      Set<String> ids = new HashSet<>();
      for (Store store : stores)
        if (!ids.add(store.id)) throw new IllegalArgumentException("Duplicate energy store id");
    }
  }

  public sealed interface Event permits Leg, Purchase {}

  /** Overrides contain measured/modelled consumption rates per store, not arbitrary fuel factors. */
  public record Leg(String name, Estimate distanceKm, Estimate durationMinutes,
                    Map<String, Estimate> consumptionOverrides) implements Event
  {
    public Leg
    {
      Objects.requireNonNull(name);
      Objects.requireNonNull(distanceKm);
      Objects.requireNonNull(durationMinutes);
      consumptionOverrides = Map.copyOf(consumptionOverrides);
    }
  }

  /** Purchased units may exceed stored units, e.g. electricity charging losses. */
  public record Purchase(String name, String storeId, double purchasedUnits, double storedUnits,
                         Estimate pricePerPurchasedUnit, Estimate fixedFee,
                         Estimate durationMinutes) implements Event
  {
    public Purchase
    {
      Objects.requireNonNull(name);
      requireId(storeId);
      Estimate.checkNumber(purchasedUnits);
      Estimate.checkNumber(storedUnits);
      if (purchasedUnits <= 0 || storedUnits <= 0 || storedUnits > purchasedUnits)
        throw new IllegalArgumentException("Expected 0 < stored units <= purchased units");
      Objects.requireNonNull(pricePerPurchasedUnit);
      Objects.requireNonNull(fixedFee);
      Objects.requireNonNull(durationMinutes);
    }
  }

  /** Every detour is a real leg in the timeline. Fees belong to this route, not its alternatives. */
  public record Route(String id, String name, List<Event> events, Estimate tolls, Estimate otherFees)
  {
    public Route
    {
      requireId(id);
      Objects.requireNonNull(name);
      events = List.copyOf(events);
      if (events.stream().noneMatch(event -> event instanceof Leg))
        throw new IllegalArgumentException("A route needs at least one travel leg");
      Objects.requireNonNull(tolls);
      Objects.requireNonNull(otherFees);
    }
  }

  public record Policy(double extraTimePercent, double minimumSavingsEuro, double switchingPenaltyEuro)
  {
    public Policy
    {
      Estimate.checkNumber(extraTimePercent);
      Estimate.checkNumber(minimumSavingsEuro);
      Estimate.checkNumber(switchingPenaltyEuro);
    }
    public double extraMinutes(double referenceMinutes) { return referenceMinutes * extraTimePercent / 100; }
    public double maximumMinutes(double referenceMinutes) { return referenceMinutes + extraMinutes(referenceMinutes); }
  }

  private static void requireId(String id)
  {
    if (id == null || id.isBlank()) throw new IllegalArgumentException("An id is required");
  }
}
