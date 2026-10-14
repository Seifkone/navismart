package com.navismart.economy;

import com.navismart.toll.TollCatalog;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;

final class TollTests
{
  private static final Instant NOW=Instant.parse("2026-10-14T17:00:00Z");
  private static final TollCatalog.Source SOURCE=new TollCatalog.Source("TEST","https://example.org/tariffs",LocalDate.of(2026,2,1),NOW);
  private static final String HEADER="id_gare_entree,libelle_gare_entree,id_gare_sortie,libelle_gare_sortie,distance_tarifaire_km,tarif_classe_1,tarif_classe_2,tarif_classe_3,tarif_classe_4,tarif_classe_5\r\n";
  private static final String ROW="1,ENTRY,2,EXIT,24.0,3.5,5.5,7.6,10.2,1.6\r\n";
  private static int passed;
  static void run() {
    test("directed tariff class 1 nominal",()->near(3.5,read(ROW).quote("1","2",1,NOW).expected()));
    test("class 5 distinct",()->near(1.6,read(ROW).quote("1","2",5,NOW).expected()));
    test("reverse not invented",()->equal(null,read(ROW).quote("2","1",1,NOW).expected()));
    test("unknown class not car default",()->equal(null,read(ROW).quote("1","2",0,NOW).expected()));
    test("missing class not zero",()->equal(null,read(ROW.replace("3.5,5.5",",5.5")).quote("1","2",1,NOW).expected()));
    test("explicit zero preserved",()->near(0,read(ROW.replace("3.5,5.5","0,5.5")).quote("1","2",1,NOW).expected()));
    test("stale download remains stale",()->equal(Estimate.State.STALE,read(ROW).quote("1","2",1,NOW.plusSeconds(8*86400)).state()));
    test("new tariff year requires update",()->equal(Estimate.State.STALE,read(ROW).quote("1","2",1,Instant.parse("2027-02-01T10:00:00Z")).state()));
    test("future received time invalid",()->equal(Estimate.State.INVALID,read(ROW).quote("1","2",1,NOW.minusSeconds(1)).state()));
    test("quoted comma gate name",()->equal("ENTRY, NORTH",read(ROW.replace("ENTRY","\"ENTRY, NORTH\"")).gates().get(0).name()));
    test("escaped quote gate name",()->equal("ENTRY \"N\"",read(ROW.replace("ENTRY","\"ENTRY \"\"N\"\"\"")).gates().get(0).name()));
    test("duplicate pair rejected",()->invalid(ROW+ROW));
    test("conflicting gate name rejected",()->invalid(ROW+"1,OTHER,3,THIRD,5,1,2,3,4,5\n"));
    test("negative money rejected",()->invalid(ROW.replace("3.5,5.5","-3.5,5.5")));
    test("too many money decimals rejected",()->invalid(ROW.replace("3.5,5.5","3.555,5.5")));
    test("non finite money rejected",()->invalid(ROW.replace("3.5,5.5","NaN,5.5")));
    test("unclosed quoted name rejected",()->invalid(ROW.replace("ENTRY","\"ENTRY")));
    test("empty matrix rejected",()->invalid(""));
    test("combined tolls add once",()->near(7,Estimate.sum(List.of(read(ROW).quote("1","2",1,NOW),read(ROW).quote("1","2",1,NOW))).expected()));
    test("partial coverage cannot become total",()->equal(null,Estimate.sum(List.of(read(ROW).quote("1","2",1,NOW),Estimate.missing(Estimate.State.UNKNOWN,"missing section"))).expected()));
    System.out.println("PASS: "+passed+" tariff scenarios");
  }
  private static TollCatalog read(String rows) {
    try { return TollCatalog.read(new ByteArrayInputStream((HEADER+rows).getBytes(StandardCharsets.UTF_8)),SOURCE); }
    catch(IOException e) { throw new UncheckedIOException(e); }
  }
  private static void invalid(String rows) { try {read(rows);} catch(UncheckedIOException e){return;} throw new AssertionError("Accepted invalid tariff CSV"); }
  private static void equal(Object a,Object b) {if(!Objects.equals(a,b))throw new AssertionError(a+" != "+b);}
  private static void near(double a,Double b) {if(b==null || Math.abs(a-b)>1e-9)throw new AssertionError(a+" != "+b);}
  private static void test(String name,Runnable task) {try{task.run();passed++;System.out.println("PASS toll "+name);}catch(Throwable e){throw new AssertionError(name,e);}}
}
