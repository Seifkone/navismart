package com.navismart.traffic;

import com.navismart.fuel.FuelData.Point;
import static com.navismart.traffic.TrafficData.*;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import javax.xml.parsers.SAXParserFactory;
import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.ext.DefaultHandler2;

/** Bounded streaming DATEX II v2 SituationPublication reader, including its SOAP wrapper. */
public final class GovernmentTrafficReader
{
  private static final String NS = "http://datex2.eu/schema/2/2_0";
  public Snapshot read(InputStream input, Instant receivedAt) throws IOException
  {
    try
    {
      var factory = SAXParserFactory.newInstance(); factory.setNamespaceAware(true);
      var reader = factory.newSAXParser().getXMLReader();
      reader.setFeature("http://xml.org/sax/features/external-general-entities", false);
      reader.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
      var handler = new Handler(); reader.setContentHandler(handler); reader.setErrorHandler(handler); reader.setEntityResolver(handler);
      reader.setProperty("http://xml.org/sax/properties/lexical-handler", handler);
      var limited = new FilterInputStream(input) {
        private int remaining = 20_000_000;
        @Override public int read() throws IOException { int value = in.read(); if (value >= 0 && --remaining < 0) throw new IOException("Oversized traffic XML"); return value; }
        @Override public int read(byte[] buffer, int offset, int length) throws IOException
        { int count = in.read(buffer, offset, Math.min(length, Math.max(1, remaining + 1))); if (count > 0 && (remaining -= count) < 0) throw new IOException("Oversized traffic XML"); return count; }
        @Override public void close() {}
      };
      reader.parse(new InputSource(limited));
      if (!handler.model || !handler.payload || handler.publication == null) throw new IOException("Unsupported traffic document");
      return new Snapshot(receivedAt, handler.publication, handler.events);
    }
    catch (javax.xml.parsers.ParserConfigurationException | SAXException | IllegalArgumentException error)
    { throw new IOException("Invalid government traffic XML", error); }
  }
  private static Instant date(String value)
  { try { return OffsetDateTime.parse(value.trim()).toInstant(); } catch (RuntimeException invalid) { return null; } }
  private static final class Handler extends DefaultHandler2
  {
    final ArrayList<Event> events = new ArrayList<>(); final HashSet<String> ids = new HashSet<>();
    final StringBuilder text = new StringBuilder();
    int depth; boolean model, payload, real, complex; Instant publication;
    String id, type, validity, details, direction, road, latitude, longitude; Instant updated, start, end;
    boolean comment, coordinates; final ArrayList<Point> points = new ArrayList<>();
    @Override public void startDTD(String name, String publicId, String systemId) throws SAXException { throw new SAXException("DTD forbidden"); }
    @Override public InputSource resolveEntity(String publicId, String systemId) throws SAXException { throw new SAXException("External entities forbidden"); }
    @Override public void error(org.xml.sax.SAXParseException error) throws SAXException { throw error; }
    @Override public void fatalError(org.xml.sax.SAXParseException error) throws SAXException { throw error; }
    @Override public void startElement(String uri, String local, String qualified, Attributes a) throws SAXException
    {
      if (++depth > 80) throw new SAXException("Traffic XML nesting limit"); text.setLength(0);
      if (local.equals("d2LogicalModel"))
      { if (model || !"2".equals(a.getValue("modelBaseVersion"))) throw new SAXException("Unsupported DATEX model"); model = true; }
      if (!NS.equals(uri)) return;
      if (local.equals("payloadPublication"))
      {
        String kind = a.getValue("http://www.w3.org/2001/XMLSchema-instance", "type");
        if (!model || payload || kind == null || !kind.substring(kind.indexOf(':') + 1).equals("SituationPublication")) throw new SAXException("Unsupported traffic payload");
        payload = true;
      }
      if (local.equals("situation")) real = false;
      if (local.equals("situationRecord"))
      {
        if (!payload || id != null || events.size() >= 20000) throw new SAXException("Invalid traffic record");
        id = a.getValue("id"); type = a.getValue("http://www.w3.org/2001/XMLSchema-instance", "type");
        if (id == null || id.isBlank() || id.length() > 256 || !ids.add(id)) throw new SAXException("Duplicate or missing traffic id");
        type = type == null ? "unknown" : type.substring(type.indexOf(':') + 1);
        validity = ""; details = ""; direction = ""; road = ""; start = null; end = null; updated = null; complex = false; points.clear();
      }
      if (id == null) return;
      if (local.equals("generalPublicComment")) comment = true;
      if (local.equals("pointCoordinates")) { coordinates = true; latitude = null; longitude = null; }
      if (local.equals("validPeriod") || local.equals("exceptionPeriod") || local.equals("recurringTimePeriodOfDay")) complex = true;
    }
    @Override public void characters(char[] chars, int offset, int length) throws SAXException
    { if (text.length() + length > 16384) throw new SAXException("Traffic field too long"); text.append(chars, offset, length); }
    @Override public void endElement(String uri, String local, String qualified) throws SAXException
    {
      String value = text.toString().trim();
      if (NS.equals(uri))
      {
        if (local.equals("publicationTime")) { if (publication != null) throw new SAXException("Duplicate publication time"); publication = date(value); }
        if (local.equals("informationStatus")) real = value.equals("real");
        if (id != null)
        {
          switch (local)
          {
            case "situationRecordVersionTime" -> updated = date(value);
            case "overallStartTime" -> start = date(value);
            case "overallEndTime" -> { end = date(value); if (end == null) complex = true; }
            case "validityStatus" -> validity = value;
            case "tpegDirection" -> direction = value;
            case "roadNumber" -> road = value;
            case "value" -> { if (comment && !value.isBlank() && details.length() < 3500) details += (details.isEmpty() ? "" : "\n") + value; }
            case "generalPublicComment" -> comment = false;
            case "latitude" -> { if (coordinates) latitude = value; }
            case "longitude" -> { if (coordinates) longitude = value; }
            case "pointCoordinates" -> {
              coordinates = false;
              try { if (points.size() < 10) points.add(new Point(Double.parseDouble(latitude), Double.parseDouble(longitude))); }
              catch (RuntimeException ignored) {}
            }
            case "situationRecord" -> {
              events.add(new Event(id, type, details, direction, road, points, updated, start, end, validity, complex, real));
              id = null;
            }
            default -> {}
          }
        }
      }
      text.setLength(0); depth--;
    }
  }
}
