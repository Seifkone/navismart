package com.navismart.economy;

import static com.navismart.economy.TripModel.*;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/** Local profiles. A saved stock is a dated observation, never a permanently full tank. */
public final class VehicleProfiles
{
  private VehicleProfiles() {}
  public enum Gearbox { UNKNOWN, MANUAL, AUTOMATIC, CVT }

  public record Validity(Duration stock, Duration consumption, Duration price)
  {
    public Validity
    {
      for (Duration duration : List.of(stock, consumption, price))
        if (duration.isZero() || duration.isNegative()) throw new IllegalArgumentException("Lifetime must be positive");
    }
  }

  public record Characteristics(Double massKg, Gearbox gearbox, List<Double> gearRatios)
  {
    public Characteristics
    {
      if (massKg != null && (!Double.isFinite(massKg) || massKg <= 0))
        throw new IllegalArgumentException("Mass must be positive");
      Objects.requireNonNull(gearbox);
      gearRatios = List.copyOf(gearRatios);
      for (double ratio : gearRatios)
        if (!Double.isFinite(ratio) || ratio <= 0)
          throw new IllegalArgumentException("Gear ratios must be positive");
    }
    public static Characteristics unknown() { return new Characteristics(null, Gearbox.UNKNOWN, List.of()); }
  }

  public record Profile(Vehicle vehicle, Policy policy, Characteristics characteristics,
                        Instant stockObservedAt)
  {
    public Profile
    {
      Objects.requireNonNull(vehicle);
      Objects.requireNonNull(policy);
      Objects.requireNonNull(characteristics);
      if (vehicle.name().isBlank()) throw new IllegalArgumentException("A vehicle name is required");
      long electric = vehicle.stores().stream().filter(s -> s.energy() == Energy.ELECTRICITY).count();
      long liquid = vehicle.stores().size() - electric;
      boolean compatible = switch (vehicle.powertrain())
      {
        case ELECTRIC -> electric == 1 && liquid == 0;
        case COMBUSTION -> electric == 0 && liquid >= 1;
        case FLEXFUEL -> electric == 0 && liquid == 1 && switch (vehicle.stores().get(0).energy())
        {
          case SP95_E10, SP95, SP98, E85 -> true;
          default -> false;
        };
        case HYBRID, PLUG_IN_HYBRID -> electric == 1 && liquid == 1;
      };
      if (!compatible) throw new IllegalArgumentException("Energy stores do not match the powertrain");
    }

    /** The caller supplies the stock validity policy; stale, undated and future stocks become unknown. */
    public Vehicle forTrip(Instant now, Duration stockLifetime)
    {
      return forTrip(now, new Validity(stockLifetime, Duration.ofDays(365), Duration.ofDays(1)));
    }

    public Vehicle forTrip(Instant now, Validity validity)
    {
      Objects.requireNonNull(now);
      Objects.requireNonNull(validity);
      boolean usable = stockObservedAt != null && !stockObservedAt.isAfter(now)
          && Duration.between(stockObservedAt, now).compareTo(validity.stock()) <= 0;
      return new Vehicle(vehicle.id(), vehicle.name(), vehicle.powertrain(), vehicle.stores().stream()
          .map(s -> new Store(s.id(), s.energy(), s.capacity(), usable ? s.initialStock() : null, s.reserve(),
                              dated(s.consumptionPer100Km(), now, validity.consumption()),
                              dated(s.referencePrice(), now, validity.price()))).toList());
    }

    private static Estimate dated(Estimate value, Instant now, Duration lifetime)
    {
      // Undated manual assumptions remain visibly manual; provider observations require their real date.
      return value.observedAt() == null || value.state() != Estimate.State.FRESH
          ? value : DataFreshness.check(value, now, lifetime);
    }
  }

  public record Catalog(List<Profile> profiles, String activeId)
  {
    public Catalog
    {
      profiles = List.copyOf(profiles);
      var ids = new HashSet<String>();
      for (Profile profile : profiles)
        if (!ids.add(profile.vehicle().id())) throw new IllegalArgumentException("Duplicate vehicle id");
      if (activeId != null && !ids.contains(activeId)) throw new IllegalArgumentException("Unknown active vehicle");
    }
    public static Catalog empty() { return new Catalog(List.of(), null); }
    public Profile get(String id)
    {
      return profiles.stream().filter(p -> p.vehicle().id().equals(id)).findFirst()
          .orElseThrow(() -> new IllegalArgumentException("Unknown vehicle: " + id));
    }
    public Profile active() { return activeId == null ? null : get(activeId); }
    public Catalog activate(String id) { return new Catalog(profiles, id); }
    public Catalog put(Profile profile)
    {
      var updated = new ArrayList<>(profiles);
      for (int i = 0; i < updated.size(); i++)
        if (updated.get(i).vehicle().id().equals(profile.vehicle().id()))
        {
          updated.set(i, profile);
          return new Catalog(updated, activeId);
        }
      updated.add(profile);
      return new Catalog(updated, activeId == null ? profile.vehicle().id() : activeId);
    }
    public Catalog remove(String id)
    {
      get(id);
      var remaining = profiles.stream().filter(p -> !p.vehicle().id().equals(id)).toList();
      return new Catalog(remaining, Objects.equals(id, activeId)
          ? (remaining.isEmpty() ? null : remaining.get(0).vehicle().id()) : activeId);
    }
  }

  public record Comparison(Profile profile, EconomyEngine.Decision decision) {}

  /** Same route candidates; each vehicle retains its own energy inventory and time policy. */
  public static List<Comparison> compare(Catalog catalog, List<Route> routes, Instant now, Duration stockLifetime)
  {
    var engine = new EconomyEngine();
    return catalog.profiles().stream().map(p -> new Comparison(p,
        engine.compare(p.forTrip(now, stockLifetime), routes, p.policy(), null))).toList();
  }
}
