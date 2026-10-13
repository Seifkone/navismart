package com.navismart.economy;

import static com.navismart.economy.TripModel.*;
import static com.navismart.economy.VehicleProfiles.*;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/** Versioned UTF-8 profile storage; reading invalid data never silently creates an empty catalog. */
public final class ProfileCodec
{
  public Catalog read(Reader reader) throws IOException
  {
    Properties fields = new Properties()
    {
      @Override public synchronized Object put(Object key, Object value)
      {
        if (containsKey(key)) throw new IllegalArgumentException("Duplicate profile field: " + key);
        return super.put(key, value);
      }
    };
    fields.load(reader);
    if (!"1".equals(required(fields, "format.version")))
      throw new IllegalArgumentException("Unsupported profile format version");
    int count = count(fields, "profiles.count", 0, 100);
    var profiles = new ArrayList<Profile>();
    for (int i = 0; i < count; i++)
    {
      String p = "profile." + i + ".";
      var stores = new ArrayList<Store>();
      for (int j = 0, n = count(fields, p + "stores.count", 1, 8); j < n; j++)
      {
        String s = p + "store." + j + ".";
        stores.add(new Store(required(fields, s + "id"), Energy.valueOf(required(fields, s + "energy")),
            number(fields, s + "capacity"), number(fields, s + "stock"), number(fields, s + "reserve"),
            estimate(fields, s + "consumption."), estimate(fields, s + "price.")));
      }
      var ratios = new ArrayList<Double>();
      for (int j = 0, n = count(fields, p + "gears.count", 0, 20); j < n; j++)
        ratios.add(Double.parseDouble(required(fields, p + "gear." + j)));
      profiles.add(new Profile(new Vehicle(required(fields, p + "id"), required(fields, p + "name"),
          Powertrain.valueOf(required(fields, p + "powertrain")), stores),
          new Policy(Double.parseDouble(required(fields, p + "extraTimePercent")),
                     Double.parseDouble(required(fields, p + "minimumSavingsEuro")),
                     Double.parseDouble(required(fields, p + "switchingPenaltyEuro"))),
          new Characteristics(number(fields, p + "massKg"), Gearbox.valueOf(required(fields, p + "gearbox")), ratios),
          instant(fields, p + "stockObservedAt")));
    }
    return new Catalog(profiles, fields.getProperty("activeId"));
  }

  public void write(Catalog catalog, Writer writer) throws IOException
  {
    if (catalog.profiles().size() > 100) throw new IllegalArgumentException("Too many profiles");
    var fields = new Properties();
    set(fields, "format.version", 1);
    set(fields, "profiles.count", catalog.profiles().size());
    set(fields, "activeId", catalog.activeId());
    for (int i = 0; i < catalog.profiles().size(); i++)
    {
      Profile profile = catalog.profiles().get(i);
      Vehicle vehicle = profile.vehicle();
      if (vehicle.stores().size() > 8 || profile.characteristics().gearRatios().size() > 20)
        throw new IllegalArgumentException("Too many stores or gears");
      String p = "profile." + i + ".";
      set(fields, p + "id", vehicle.id());
      set(fields, p + "name", vehicle.name());
      set(fields, p + "powertrain", vehicle.powertrain());
      set(fields, p + "extraTimePercent", profile.policy().extraTimePercent());
      set(fields, p + "minimumSavingsEuro", profile.policy().minimumSavingsEuro());
      set(fields, p + "switchingPenaltyEuro", profile.policy().switchingPenaltyEuro());
      set(fields, p + "stockObservedAt", profile.stockObservedAt());
      set(fields, p + "massKg", profile.characteristics().massKg());
      set(fields, p + "gearbox", profile.characteristics().gearbox());
      List<Double> ratios = profile.characteristics().gearRatios();
      set(fields, p + "gears.count", ratios.size());
      for (int j = 0; j < ratios.size(); j++) set(fields, p + "gear." + j, ratios.get(j));
      set(fields, p + "stores.count", vehicle.stores().size());
      for (int j = 0; j < vehicle.stores().size(); j++)
      {
        Store store = vehicle.stores().get(j);
        String s = p + "store." + j + ".";
        set(fields, s + "id", store.id());
        set(fields, s + "energy", store.energy());
        set(fields, s + "capacity", store.capacity());
        set(fields, s + "stock", store.initialStock());
        set(fields, s + "reserve", store.reserve());
        writeEstimate(fields, s + "consumption.", store.consumptionPer100Km());
        writeEstimate(fields, s + "price.", store.referencePrice());
      }
    }
    fields.store(writer, "NaviSmart local vehicle profiles - schema 1");
  }

  private static Estimate estimate(Properties fields, String p)
  {
    return new Estimate(number(fields, p + "value"), number(fields, p + "lower"), number(fields, p + "upper"),
        Estimate.State.valueOf(required(fields, p + "state")), required(fields, p + "source"),
        instant(fields, p + "observedAt"));
  }
  private static void writeEstimate(Properties fields, String p, Estimate estimate)
  {
    set(fields, p + "value", estimate.expected());
    set(fields, p + "lower", estimate.lower());
    set(fields, p + "upper", estimate.upper());
    set(fields, p + "state", estimate.state());
    set(fields, p + "source", estimate.source());
    set(fields, p + "observedAt", estimate.observedAt());
  }
  private static void set(Properties fields, String key, Object value)
  {
    if (value != null) fields.setProperty(key, value.toString());
  }
  private static String required(Properties fields, String key)
  {
    String value = fields.getProperty(key);
    if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing profile field: " + key);
    return value;
  }
  private static Double number(Properties fields, String key)
  {
    String value = fields.getProperty(key);
    return value == null ? null : Double.valueOf(value);
  }
  private static Instant instant(Properties fields, String key)
  {
    String value = fields.getProperty(key);
    return value == null ? null : Instant.parse(value);
  }
  private static int count(Properties fields, String key, int min, int max)
  {
    int value = Integer.parseInt(required(fields, key));
    if (value < min || value > max) throw new IllegalArgumentException("Invalid profile count: " + key);
    return value;
  }
}
