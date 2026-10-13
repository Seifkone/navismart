package com.navismart.economy;

import static com.navismart.economy.TripModel.*;

import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/** UTF-8 properties input: a small, dependency-free laboratory format, not an Android storage format. */
public final class ScenarioReader
{
  public record Scenario(Vehicle vehicle, List<Route> routes, Policy policy, String currentRouteId) {}
  private final Properties values = new Properties();
  private java.time.Instant clock;

  public Scenario read(Reader input) throws IOException
  {
    return read(input, java.time.Instant.now());
  }

  public Scenario read(Reader input, java.time.Instant now) throws IOException
  {
    clock = java.util.Objects.requireNonNull(now);
    values.clear();
    values.load(input);
    List<Store> stores = new ArrayList<>();
    for (String id : ids("vehicle.stores"))
    {
      String key = "store." + id + ".";
      stores.add(new Store(id, Energy.valueOf(required(key + "energy")), number(key + "capacity"),
                           number(key + "stock"), number(key + "reserve"), estimate(key + "consumption"),
                           estimate(key + "price")));
    }
    Vehicle vehicle = new Vehicle(required("vehicle.id"), required("vehicle.name"),
                                  Powertrain.valueOf(required("vehicle.powertrain")), stores);
    List<Route> routes = new ArrayList<>();
    for (String id : ids("routes"))
    {
      String key = "route." + id + ".";
      List<Event> events = new ArrayList<>();
      for (String eventId : ids(key + "events"))
      {
        String prefix = key + "event." + eventId + ".";
        String name = values.getProperty(prefix + "name", eventId);
        switch (required(prefix + "kind"))
        {
        case "leg":
          Map<String, Estimate> rates = new LinkedHashMap<>();
          for (Store store : stores)
          {
            String ratePrefix = prefix + "consumption." + store.id();
            if (values.containsKey(ratePrefix + ".value") || values.containsKey(ratePrefix + ".state"))
              rates.put(store.id(), estimate(ratePrefix));
          }
          events.add(new Leg(name, estimate(prefix + "distance"), estimate(prefix + "duration"), rates));
          break;
        case "purchase":
          events.add(new Purchase(name, required(prefix + "store"), requiredNumber(prefix + "purchased"),
                                  requiredNumber(prefix + "stored"), estimate(prefix + "price"),
                                  estimate(prefix + "fee"), estimate(prefix + "duration")));
          break;
        default:
          throw new IllegalArgumentException("Unknown event kind: " + prefix);
        }
      }
      routes.add(new Route(id, values.getProperty(key + "name", id), events, estimate(key + "tolls"),
                           estimate(key + "fees")));
    }
    return new Scenario(vehicle, List.copyOf(routes),
        new Policy(requiredNumber("policy.extraTimePercent"), requiredNumber("policy.minimumSavingsEuro"),
                   requiredNumber("policy.switchingPenaltyEuro")), values.getProperty("currentRoute"));
  }

  private Estimate estimate(String key)
  {
    Double value = number(key + ".value"), lower = number(key + ".lower"), upper = number(key + ".upper");
    Estimate.State state = Estimate.State.valueOf(values.getProperty(key + ".state",
        value == null ? "UNKNOWN" : "FRESH"));
    String timestamp = values.getProperty(key + ".observedAt");
    Estimate result = new Estimate(value, lower, upper, state, values.getProperty(key + ".source", "scenario file"),
                                   timestamp == null ? null : java.time.Instant.parse(timestamp));
    if (timestamp != null && state == Estimate.State.FRESH)
    {
      if (result.observedAt().isAfter(clock))
        return result.withObservation(Estimate.State.INVALID, result.source(), result.observedAt());
      Double lifetime = number(key + ".maxAgeSeconds");
      if (lifetime == null)
        return result.withObservation(Estimate.State.UNKNOWN, result.source(), result.observedAt());
      if (!Double.isFinite(lifetime) || lifetime <= 0 || lifetime > Long.MAX_VALUE / 1000.0)
        throw new IllegalArgumentException("Invalid source lifetime: " + key);
      return DataFreshness.check(result, clock, java.time.Duration.ofMillis((long) (lifetime * 1000)));
    }
    return result;
  }

  private List<String> ids(String key)
  {
    return java.util.Arrays.stream(required(key).split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
  }

  private String required(String key)
  {
    String value = values.getProperty(key);
    if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing field: " + key);
    return value.trim();
  }

  private Double number(String key)
  {
    String value = values.getProperty(key);
    if (value == null || value.isBlank()) return null;
    return Double.parseDouble(value.trim().replace(',', '.'));
  }

  private double requiredNumber(String key)
  {
    required(key);
    return number(key);
  }
}
