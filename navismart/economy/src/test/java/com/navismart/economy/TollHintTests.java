package com.navismart.economy;

import com.navismart.fuel.FuelData.Point;
import com.navismart.toll.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;

final class TollHintTests {
  private static final Instant NOW=Instant.parse("2026-10-14T17:00:00Z");
  private static final String HEADER="id_gare_entree,libelle_gare_entree,id_gare_sortie,libelle_gare_sortie,distance_tarifaire_km,tarif_classe_1,tarif_classe_2,tarif_classe_3,tarif_classe_4,tarif_classe_5\n";
  private static final List<Point> ROUTE=List.of(new Point(48,2),new Point(48.02,2));
  private static final List<TollGateHints.Location> LOCATIONS=List.of(new TollGateHints.Location("Éntry",new Point(48.005,2)),new TollGateHints.Location("Exit",new Point(48.015,2)));
  private static TollCatalog catalog(String extra) {
    try {return TollCatalog.read(new ByteArrayInputStream((HEADER+"1,ENTRY,2,EXIT,2.00,3.50,4.00,5.00,6.00,2.00\n"+extra).getBytes(StandardCharsets.UTF_8)),
        new TollCatalog.Source("TEST","https://example.org/tariffs",LocalDate.of(2026,2,1),NOW));}
    catch(IOException e){throw new UncheckedIOException(e);}
  }
  private static List<TollGateHints.Hint> find(List<TollGateHints.Location> locations,List<Point> route,int cl,Instant now) {
    return TollGateHints.find(catalog(""),locations,route,cl,now);
  }
  static void run() {
    check("projected passages ordered along geometry",()-> {
      var found=find(LOCATIONS,ROUTE,1,NOW);if(found.size()!=1 || !found.get(0).entry().id().equals("1") || found.get(0).price().expected()!=3.5)throw new AssertionError();
    });
    check("reverse direction requires a reverse tariff",()->empty(find(LOCATIONS,List.of(ROUTE.get(1),ROUTE.get(0)),1,NOW)));
    check("single physical station cannot identify a pair",()->empty(find(LOCATIONS.subList(0,1),ROUTE,1,NOW)));
    check("nearby distant road outside corridor rejected",()->empty(find(LOCATIONS,List.of(new Point(48,2.01),new Point(48.02,2.01)),1,NOW)));
    check("duplicate booth coordinates do not duplicate price",()-> {
      var positions=new ArrayList<>(LOCATIONS);positions.add(LOCATIONS.get(0));if(find(positions,ROUTE,1,NOW).size()!=1)throw new AssertionError();
    });
    check("expired tariff cannot become usable suggestion",()->empty(find(LOCATIONS,ROUTE,1,NOW.plusSeconds(8*86400))));
    check("unspecified vehicle class yields no quote",()->empty(find(LOCATIONS,ROUTE,0,NOW)));
    check("multiple matching logical stations rejected",()-> {
      var positions=new ArrayList<>(LOCATIONS);positions.add(new TollGateHints.Location("THIRD",new Point(48.01,2)));
      var c=catalog("1,ENTRY,3,THIRD,1.00,1,2,3,4,5\n");empty(TollGateHints.find(c,positions,ROUTE,1,NOW));
    });
    check("ambiguous station labels rejected",()-> {
      var c=catalog("3,ENTRY,2,EXIT,1.00,1,2,3,4,5\n");empty(TollGateHints.find(c,LOCATIONS,ROUTE,1,NOW));
    });
    check("strict government geography reader",()-> {
      try {if(TollGateHints.read(new StringReader("name,latitude,longitude\nENTRY,48,2\n")).size()!=1)throw new AssertionError();}
      catch(IOException e){throw new UncheckedIOException(e);}
    });
    check("invalid government position rejected",()-> {
      try {TollGateHints.read(new StringReader("name,latitude,longitude\nENTRY,NaN,2\n"));throw new AssertionError();}
      catch(IOException expected){}
    });
    System.out.println("PASS: 11 toll geography scenarios");
  }
  private static void empty(List<?> values){if(!values.isEmpty())throw new AssertionError("Unexpected hint");}
  private static void check(String name,Runnable task){try{task.run();System.out.println("PASS "+name);}catch(Throwable e){throw new AssertionError(name,e);}}
}
