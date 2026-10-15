package com.navismart.toll;

import com.navismart.fuel.FuelData.Point;
import com.navismart.economy.Estimate;
import java.io.*;
import java.text.Normalizer;
import java.time.Instant;
import java.util.*;

/** Geographical suggestions only: proximity does not establish carriageway or tariff coverage. */
public final class TollGateHints {
  private TollGateHints() {}
  public record Location(String name,Point point) {}
  public record Hint(TollCatalog.Gate entry,TollCatalog.Gate exit,Estimate price) {}
  private record Hit(TollCatalog.Gate gate,double along) {}
  public static List<Location> read(Reader reader)throws IOException {
    int[] size={0};var header=TollCatalog.row(reader,size);
    if(!List.of("name","latitude","longitude").equals(header))throw new IOException("Unsupported gate geography");
    var result=new ArrayList<Location>();List<String> row;
    while((row=TollCatalog.row(reader,size))!=null) {
      if(row.size()!=3 || result.size()>=3000 || row.get(0).isBlank() || row.get(0).length()>200)throw new IOException("Invalid gate geography row");
      try { result.add(new Location(row.get(0),new Point(Double.parseDouble(row.get(1)),Double.parseDouble(row.get(2))))); }
      catch(IllegalArgumentException e){throw new IOException("Invalid gate position",e);}
    }
    if(result.isEmpty())throw new IOException("Empty gate geography");return List.copyOf(result);
  }
  public static List<Hint> find(TollCatalog catalog,List<Location> locations,List<Point> route,int vehicleClass,Instant now) {
    if(route.size()<2 || route.size()>200000 || locations.size()>3000)return List.of();
    double[] distances=new double[route.size()];
    for(int i=1;i<route.size();i++)distances[i]=distances[i-1]+route.get(i-1).distanceKm(route.get(i))*1000;
    Map<String,List<TollCatalog.Gate>> names=new HashMap<>();
    for(var gate:catalog.gates())names.computeIfAbsent(normalize(gate.name()),k->new ArrayList<>()).add(gate);
    Map<String,Hit> hits=new HashMap<>();
    for(var location:locations) {
      if(Thread.currentThread().isInterrupted())return List.of();
      var matched=names.get(normalize(location.name()));if(matched==null || matched.size()!=1)continue;
      double first=Double.POSITIVE_INFINITY;
      for(int i=1;i<route.size();i++) {
        Point a=route.get(i-1),b=route.get(i);double length=distances[i]-distances[i-1];double along=distances[i-1];
        double latitude=location.point().latitude(),longitude=location.point().longitude();
        double scale=Math.cos(Math.toRadians(latitude))*111320;
        // Reject very long segments and require a small corridor around the actual polyline.
        if(length<=20000 && latitude>=Math.min(a.latitude(),b.latitude())-.001 && latitude<=Math.max(a.latitude(),b.latitude())+.001
            && longitude>=Math.min(a.longitude(),b.longitude())-.002 && longitude<=Math.max(a.longitude(),b.longitude())+.002) {
          double ax=(a.longitude()-longitude)*scale,ay=(a.latitude()-latitude)*111320;
          double bx=(b.longitude()-longitude)*scale,by=(b.latitude()-latitude)*111320;
          double dx=bx-ax,dy=by-ay,den=dx*dx+dy*dy;
          double t=den==0?0:Math.max(0,Math.min(1,-(ax*dx+ay*dy)/den));
          double x=ax+t*dx,y=ay+t*dy;
          if(x*x+y*y<=80*80)first=Math.min(first,along+t*length);
        }
      }
      if(Double.isFinite(first)) {
        var gate=matched.get(0);var previous=hits.get(gate.id());
        if(previous==null || first<previous.along())hits.put(gate.id(),new Hit(gate,first));
      }
    }
    // Multiple logical stations or a single one cannot identify a unique directed pair.
    if(hits.size()!=2)return List.of();
    var ordered=hits.values().stream().sorted(Comparator.comparingDouble(Hit::along)).toList();
    if(ordered.get(1).along()-ordered.get(0).along()<200)return List.of();
    var entry=ordered.get(0).gate();var exit=ordered.get(1).gate();var quote=catalog.quote(entry.id(),exit.id(),vehicleClass,now);
    return quote.isFresh()?List.of(new Hint(entry,exit,quote)):List.of();
  }
  static String normalize(String name) {
    return Normalizer.normalize(name,Normalizer.Form.NFD).replaceAll("\\p{M}","").toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]","");
  }
}
