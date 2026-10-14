package com.navismart.fuel;

/** Optional local verification against a downloaded government archive; never a network-dependent test. */
public final class FuelInspector
{
  public static void main(String[] args) throws Exception
  {
    if (args.length != 1) throw new IllegalArgumentException("Usage: FuelInspector feed.zip");
    try (var input = java.nio.file.Files.newInputStream(java.nio.file.Path.of(args[0])))
    {
      var snapshot = new GovernmentFuelReader().readZip(input, java.time.Instant.now());
      long prices = snapshot.stations().stream().mapToLong(s -> s.prices().size()).sum();
      long positioned = snapshot.stations().stream().filter(s -> s.point() != null).count();
      System.out.println("stations=" + snapshot.stations().size() + ", located=" + positioned
          + ", prices=" + prices + ", invalidFields=" + snapshot.invalidRecords());
    }
  }
}
