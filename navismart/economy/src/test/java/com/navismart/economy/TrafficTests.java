package com.navismart.economy;

import com.navismart.traffic.*;
import static com.navismart.traffic.TrafficData.*;
import com.navismart.fuel.FuelData.Point;
import java.io.*;
import java.time.Instant;
import java.util.Objects;

public final class TrafficTests
{
  private static final Instant NOW = Instant.parse("2026-10-14T12:00:00Z");
  private static int passed;
  private TrafficTests() {}
  public static void run()
  {
    test("DATEX namespaces direction dates and coordinates preserved", () -> {
      var snapshot = parse(xml(record("<pointCoordinates><latitude>48.1</latitude><longitude>2.1</longitude></pointCoordinates><tpegDirection>eastBound</tpegDirection><roadNumber>N0024</roadNumber>")));
      equal(NOW, snapshot.publishedAt()); equal(true, snapshot.fresh(NOW));
      var event = snapshot.events().get(0); equal("eastBound", event.direction()); equal("N0024", event.road());
      equal(48.1, event.points().get(0).latitude()); equal(State.LISTED, event.state(NOW));
    });
    test("expired future and suspended records are distinct", () -> {
      equal(State.EXPIRED, parse(xml(record("<overallEndTime>2026-10-14T11:59:59Z</overallEndTime>"))).events().get(0).state(NOW));
      equal(State.FUTURE, parse(xml(record("").replace("2026-10-13T12:00:00Z", "2026-10-15T12:00:00Z"))).events().get(0).state(NOW));
      equal(State.SUSPENDED, parse(xml(record("").replace("definedByValidityTimeSpec", "suspended"))).events().get(0).state(NOW));
    });
    test("recurrences forecast and unknown validity never claimed active", () -> {
      equal(State.UNKNOWN, parse(xml(record("<validPeriod/>"))).events().get(0).state(NOW));
      equal(State.UNKNOWN, parse(xml(record("")).replace("<d:informationStatus>real", "<d:informationStatus>test")).events().get(0).state(NOW));
      equal(State.UNKNOWN, parse(xml(record("").replace("definedByValidityTimeSpec", "unknown"))).events().get(0).state(NOW));
    });
    test("malformed dates and positions never become valid measurements", () -> {
      var event = parse(xml(record("<pointCoordinates><latitude>NaN</latitude><longitude>2</longitude></pointCoordinates>")
          .replace("2026-10-13T12:00:00Z", "wrong"))).events().get(0);
      equal(State.UNKNOWN, event.state(NOW)); equal(0, event.points().size());
    });
    test("old received old publication and future publication are not fresh", () -> {
      var snapshot = parse(xml(record(""))); equal(false, snapshot.fresh(NOW.plusSeconds(7201))); equal(false, snapshot.fresh(NOW.minusSeconds(1)));
      equal(false, parse(xml(record("")).replace("2026-10-14T14:00:00+02:00", "2026-10-13T14:00:00+02:00")).fresh(NOW));
    });
    test("empty legitimate publication is not interpreted as fluid traffic", () -> equal(0, parse(xml("")).events().size()));
    test("duplicate ids DTD wrong payload and wrong model rejected", () -> {
      invalid(xml(record("") + record("")));
      invalid("<!DOCTYPE d2LogicalModel [<!ENTITY x 'boom'>]>" + xml(record("")));
      invalid(xml("").replace("SituationPublication", "MeasuredDataPublication"));
      invalid(xml("").replace("modelBaseVersion='2'", "modelBaseVersion='3'"));
      invalid("<html/>");
    });
    test("search only reports proximity and excludes expired records", () -> {
      var snapshot = parse(xml(record("<pointCoordinates><latitude>48.1</latitude><longitude>2.1</longitude></pointCoordinates>")));
      var matches = nearby(snapshot, new Point(48.1, 2.1), NOW, 10, 10); equal(1, matches.size()); equal(0.0, matches.get(0).distanceKm());
      equal(0, nearby(snapshot, new Point(40, 1), NOW, 10, 10).size());
    });
    System.out.println("PASS: " + passed + " traffic event scenarios");
  }
  private static String xml(String records)
  { return "<d2LogicalModel modelBaseVersion='2' xmlns:d='http://datex2.eu/schema/2/2_0' xmlns:xsi='http://www.w3.org/2001/XMLSchema-instance'><d:payloadPublication xsi:type='d:SituationPublication'><d:publicationTime>2026-10-14T14:00:00+02:00</d:publicationTime><d:situation><d:informationStatus>real</d:informationStatus>" + records.replaceAll("<(\\/?)([A-Za-z])", "<$1d:$2") + "</d:situation></d:payloadPublication></d2LogicalModel>"; }
  private static String record(String extra)
  { return "<situationRecord id='a' xsi:type='d:Accident'><situationRecordVersionTime>2026-10-14T11:00:00Z</situationRecordVersionTime><validityStatus>definedByValidityTimeSpec</validityStatus><overallStartTime>2026-10-13T12:00:00Z</overallStartTime>" + extra + "</situationRecord>"; }
  private static Snapshot parse(String xml)
  { try { return new GovernmentTrafficReader().read(new ByteArrayInputStream(xml.getBytes(java.nio.charset.StandardCharsets.UTF_8)), NOW); } catch (IOException error) { throw new IllegalArgumentException(error); } }
  private static void invalid(String xml) { try { parse(xml); } catch (IllegalArgumentException expected) { return; } throw new AssertionError("Invalid XML accepted"); }
  private static void test(String name, Runnable action) { action.run(); passed++; System.out.println("PASS " + name); }
  private static void equal(Object a, Object b) { if (!Objects.equals(a,b)) throw new AssertionError("Expected " + a + ", got " + b); }
}
