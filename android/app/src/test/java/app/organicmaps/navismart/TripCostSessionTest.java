package app.organicmaps.navismart;

import static org.junit.Assert.*;
import app.organicmaps.sdk.routing.RouteMarkData;
import app.organicmaps.sdk.routing.RouteMarkType;
import com.navismart.economy.Estimate;
import java.time.Instant;
import org.junit.Before;
import org.junit.Test;

public class TripCostSessionTest {
  private static final Instant NOW=Instant.parse("2026-10-14T17:00:00Z");
  private static RouteMarkData[] points(double end) { return new RouteMarkData[]{
      new RouteMarkData("","",RouteMarkType.Start,0,true,false,false,48,2),
      new RouteMarkData("","",RouteMarkType.Finish,0,true,false,false,49,end)}; }
  private static TripCostSession.Variant variant(boolean avoid) { return new TripCostSession.Variant(avoid,
      CoMapsEconomyAdapter.legs(new double[]{100000,5400}),Estimate.nominal(5,"test"),Estimate.exact(0),NOW); }
  @Before public void reset(){TripCostSession.clear();}
  @Test public void sameTripRetainsBothOptions() {
    TripCostSession.scope(points(3),1,"vehicle",NOW);TripCostSession.put(variant(false));TripCostSession.put(variant(true));
    TripCostSession.scope(points(3),1,"vehicle",NOW.plusSeconds(60));assertEquals(2,TripCostSession.variants().size());
  }
  @Test public void destinationClassVehicleAndOptionsInvalidate() {
    for(int i=0;i<4;i++) {
      TripCostSession.scope(points(3),1,"v",NOW);TripCostSession.put(variant(false));
      TripCostSession.scope(points(i==0?4:3),i==1?2:1,i==2?"other":i==3?"v[Motorway]":"v",NOW);
      assertTrue(TripCostSession.variants().isEmpty());
    }
  }
  @Test public void timeLimitAndBackwardsClockInvalidate() {
    for(var at:new Instant[]{NOW.plusSeconds(1801),NOW.minusSeconds(1)}) {
      TripCostSession.scope(points(3),1,"v",NOW);TripCostSession.put(variant(false));TripCostSession.scope(points(3),1,"v",at);
      assertTrue(TripCostSession.variants().isEmpty());
    }
  }
  @Test public void snapshotListCannotBeMutatedAndNullPointsClear() {
    TripCostSession.scope(points(3),1,"v",NOW);TripCostSession.put(variant(false));
    assertThrows(UnsupportedOperationException.class,()->TripCostSession.variants().clear());
    TripCostSession.scope(null,1,"v",NOW);assertTrue(TripCostSession.variants().isEmpty());
  }
}
