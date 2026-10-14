package com.navismart.fuel;

import static com.navismart.fuel.FuelData.*;
import com.navismart.economy.Estimate;
import com.navismart.economy.TripModel.Energy;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipInputStream;
import javax.xml.parsers.SAXParserFactory;
import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.ext.DefaultHandler2;

/** Streaming parser for the current national feed, not historical archives. No external XML resources. */
public final class GovernmentFuelReader
{
  public static final int MAX_XML_BYTES = 40_000_000;
  private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss")
      .withResolverStyle(java.time.format.ResolverStyle.STRICT);
  private static final ZoneId ZONE = ZoneId.of("Europe/Paris");

  public Snapshot readZip(InputStream input, Instant receivedAt) throws IOException
  {
    var zip = new ZipInputStream(input);
    for (int i = 0; i < 10; i++)
    {
      var entry = zip.getNextEntry();
      if (entry == null) break;
      if (!entry.isDirectory() && entry.getName().toLowerCase(java.util.Locale.ROOT).endsWith(".xml"))
        return readXml(zip, receivedAt);
    }
    throw new IOException("No XML in government fuel archive");
  }
  public Snapshot readXml(InputStream input, Instant receivedAt) throws IOException
  {
    try
    {
      var factory = SAXParserFactory.newInstance();
      factory.setNamespaceAware(true);
      var reader = factory.newSAXParser().getXMLReader();
      reader.setFeature("http://xml.org/sax/features/external-general-entities", false);
      reader.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
      var handler = new Handler();
      reader.setContentHandler(handler); reader.setErrorHandler(handler); reader.setEntityResolver(handler);
      // Required on Android and desktop: reject every DTD, including internal entity expansion.
      reader.setProperty("http://xml.org/sax/properties/lexical-handler", handler);
      var limited = new FilterInputStream(input)
      {
        private int remaining = MAX_XML_BYTES;
        @Override public int read() throws IOException
        {
          int b = super.read(); if (b >= 0 && --remaining < 0) throw new IOException("Fuel XML exceeds size limit"); return b;
        }
        @Override public int read(byte[] b, int off, int len) throws IOException
        {
          int n = in.read(b, off, Math.min(len, Math.max(1, remaining + 1)));
          if (n > 0 && (remaining -= n) < 0) throw new IOException("Fuel XML exceeds size limit"); return n;
        }
        @Override public void close() {} // Caller owns the archive or HTTP stream.
      };
      reader.parse(new InputSource(limited));
      if (!handler.root || handler.stations.isEmpty()) throw new IOException("Empty or unexpected government fuel document");
      return new Snapshot(receivedAt, handler.stations, handler.invalid);
    }
    catch (javax.xml.parsers.ParserConfigurationException | SAXException | IllegalArgumentException error)
    { throw new IOException("Invalid government fuel XML", error); }
  }
  private static Energy energy(String id)
  {
    if (id == null) return null;
    return switch (id)
    {
      case "1" -> Energy.DIESEL; case "2" -> Energy.SP95; case "3" -> Energy.E85;
      case "4" -> Energy.LPG; case "5" -> Energy.SP95_E10; case "6" -> Energy.SP98; default -> null;
    };
  }
  private static Instant date(String value)
  {
    if (value == null || value.isBlank()) return null;
    try
    {
      var local = LocalDateTime.parse(value.trim().replace('T', ' '), DATE);
      var offsets = ZONE.getRules().getValidOffsets(local);
      return offsets.size() == 1 ? local.toInstant(offsets.get(0)) : null;
    }
    catch (java.time.DateTimeException error) { return null; }
  }
  private static Period period(Attributes a)
  {
    String endText = a.getValue("fin");
    Instant start = date(a.getValue("debut")), end = date(endText);
    return new Period(start, end, start != null && (endText == null || endText.isBlank() || end != null));
  }
  private static final class Handler extends DefaultHandler2
  {
    final List<Station> stations = new ArrayList<>();
    final HashSet<String> ids = new HashSet<>();
    final StringBuilder text = new StringBuilder();
    Map<Energy, Estimate> prices;
    Map<Energy, List<Period>> outages;
    List<Period> closures;
    String id, address, town, postcode, capturing;
    Point point;
    boolean motorway, automatic, root;
    int depth, stationDepth, invalid;
    @Override public void startDTD(String name, String publicId, String systemId) throws SAXException
    { throw new SAXException("DTD forbidden"); }
    @Override public InputSource resolveEntity(String publicId, String systemId) throws SAXException
    { throw new SAXException("External entities forbidden"); }
    @Override public void error(org.xml.sax.SAXParseException error) throws SAXException { throw error; }
    @Override public void fatalError(org.xml.sax.SAXParseException error) throws SAXException { throw error; }
    @Override public void startElement(String uri, String local, String qName, Attributes a) throws SAXException
    {
      if (++depth > 32) throw new SAXException("Excessive XML nesting");
      String name = local.isEmpty() ? qName : local;
      if (depth == 1) { if (!name.equals("pdv_liste")) throw new SAXException("Unexpected root"); root = true; }
      if (name.equals("pdv"))
      {
        if (id != null || stations.size() >= 50_000) throw new SAXException("Invalid station nesting/count");
        stationDepth = depth; id = a.getValue("id");
        if (id == null || id.isBlank() || !ids.add(id)) throw new SAXException("Missing or duplicate station id");
        address = ""; town = ""; postcode = a.getValue("cp") == null ? "" : a.getValue("cp");
        motorway = "A".equals(a.getValue("pop")); automatic = false;
        prices = new EnumMap<>(Energy.class); outages = new EnumMap<>(Energy.class); closures = new ArrayList<>(); point = null;
        try
        {
          double lat = Double.parseDouble(a.getValue("latitude")) / 100000.0;
          double lon = Double.parseDouble(a.getValue("longitude")) / 100000.0;
          if (lat != 0 || lon != 0) point = new Point(lat, lon);
        }
        catch (RuntimeException error) { invalid++; }
      }
      else if (id != null)
      {
        if (name.equals("adresse") || name.equals("ville")) { capturing = name; text.setLength(0); }
        if (name.equals("horaires")) automatic = "1".equals(a.getValue("automate-24-24"));
        if (name.equals("fermeture")) closures.add(period(a));
        if (name.equals("rupture"))
        {
          Energy fuel = energy(a.getValue("id"));
          if (fuel != null) outages.computeIfAbsent(fuel, ignored -> new ArrayList<>()).add(period(a));
        }
        if (name.equals("prix"))
        {
          Energy fuel = energy(a.getValue("id"));
          if (fuel == null) { invalid++; return; }
          Instant observed = date(a.getValue("maj"));
          Estimate price;
          try
          {
            double value = Double.parseDouble(a.getValue("valeur"));
            if (value <= 0 || !Double.isFinite(value)) throw new IllegalArgumentException("Nonpositive price");
            String expectedName = switch (fuel)
            {
              case DIESEL -> "Gazole"; case SP95 -> "SP95"; case E85 -> "E85";
              case LPG -> "GPLc"; case SP95_E10 -> "E10"; case SP98 -> "SP98"; default -> "";
            };
            if (a.getValue("nom") != null && !a.getValue("nom").equals(expectedName))
              throw new IllegalArgumentException("Inconsistent fuel id and name");
            price = new Estimate(value, null, null, observed == null ? Estimate.State.UNKNOWN : Estimate.State.FRESH,
                                 SOURCE + "#" + id, observed);
          }
          catch (RuntimeException error) { price = Estimate.missing(Estimate.State.INVALID, SOURCE + "#" + id); invalid++; }
          Estimate previous = prices.putIfAbsent(fuel, price);
          if (previous != null)
          {
            invalid++;
            prices.put(fuel, Estimate.missing(Estimate.State.INVALID, SOURCE + "#duplicate-price-" + id));
          }
        }
      }
    }
    @Override public void characters(char[] chars, int start, int length) throws SAXException
    {
      if (capturing != null)
      {
        if (text.length() + length > 4096) throw new SAXException("Oversized station label");
        text.append(chars, start, length);
      }
    }
    @Override public void endElement(String uri, String local, String qName)
    {
      String name = local.isEmpty() ? qName : local;
      if (name.equals(capturing))
      {
        if (name.equals("adresse")) address = text.toString().trim(); else town = text.toString().trim();
        capturing = null;
      }
      if (id != null && depth == stationDepth && name.equals("pdv"))
      {
        stations.add(new Station(id, point, address, town, postcode, motorway, automatic, prices, outages, closures));
        id = null;
      }
      depth--;
    }
  }
}
