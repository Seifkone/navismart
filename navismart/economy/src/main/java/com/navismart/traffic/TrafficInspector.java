package com.navismart.traffic;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

public final class TrafficInspector
{
  private TrafficInspector() {}
  public static void main(String[] args) throws Exception
  {
    if (args.length != 1) throw new IllegalArgumentException("Usage: TrafficInspector traffic.xml");
    try (var input = Files.newInputStream(Path.of(args[0])))
    {
      var snapshot = new GovernmentTrafficReader().read(input, Instant.now());
      System.out.println("published=" + snapshot.publishedAt() + ", records=" + snapshot.events().size()
          + ", located=" + snapshot.events().stream().filter(e -> !e.points().isEmpty()).count());
      for (var state : TrafficData.State.values()) System.out.println(state + "=" + snapshot.events().stream().filter(e -> e.state(Instant.now()) == state).count());
    }
  }
}
