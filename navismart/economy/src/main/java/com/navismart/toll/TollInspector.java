package com.navismart.toll;

import java.nio.file.*;
import java.time.*;

/** Offline data import check; does not certify operator coverage or match a road route. */
public final class TollInspector {
  private TollInspector() {}
  public static void main(String[] args) throws Exception {
    if (args.length!=2) throw new IllegalArgumentException("Usage: TollInspector NETWORK CSV");
    try (var in=Files.newInputStream(Path.of(args[1]))) {
      var c=TollCatalog.read(in,new TollCatalog.Source(args[0],"https://www.data.gouv.fr/datasets/tarifs-autoroutes-aprr",LocalDate.of(2026,2,1),Instant.now()));
      System.out.println(args[0]+": "+c.gates().size()+" gates, "+c.pairCount()+" directed tariff pairs");
    }
  }
}
