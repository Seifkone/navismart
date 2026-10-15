package com.navismart.toll;

import com.navismart.economy.Estimate;
import java.io.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;

/** Local directed tariff matrix. No extrapolation, inferred reverse prices or missing-as-zero. */
public final class TollCatalog
{
  public record Gate(String id, String name) {}
  public record Pair(String entry, String exit) {}
  public record Source(String network, String url, LocalDate effectiveFrom, Instant receivedAt) {
    public Source {
      if (network == null || network.isBlank() || url == null || !url.startsWith("https://")
          || effectiveFrom == null || receivedAt == null) throw new IllegalArgumentException("Tariff provenance required");
    }
  }
  private final Source source;
  private final Map<String, Gate> gates;
  private final Map<Pair, List<Long>> prices;
  private TollCatalog(Source source, Map<String, Gate> gates, Map<Pair, List<Long>> prices) {
    this.source = source; this.gates = Map.copyOf(gates); this.prices = Map.copyOf(prices);
  }
  public Source source() { return source; }
  public int pairCount() { return prices.size(); }
  public List<Gate> gates() { return gates.values().stream().sorted(Comparator.comparing(Gate::name).thenComparing(Gate::id)).toList(); }
  public List<Gate> exits(String entry) { return gates().stream().filter(g -> prices.containsKey(new Pair(entry, g.id()))).toList(); }
  public Estimate quote(String entry, String exit, int vehicleClass, Instant now) {
    if (vehicleClass < 1 || vehicleClass > 5) return Estimate.missing(Estimate.State.UNKNOWN, "Explicit toll class required");
    var row = prices.get(new Pair(entry, exit));
    if (row == null || row.get(vehicleClass - 1) == null) return Estimate.missing(Estimate.State.UNKNOWN, "Tariff relation absent");
    LocalDate day = now.atZone(ZoneId.of("Europe/Paris")).toLocalDate();
    var state = day.isBefore(source.effectiveFrom()) || now.isBefore(source.receivedAt())
        ? Estimate.State.INVALID : Duration.between(source.receivedAt(), now).compareTo(Duration.ofDays(7)) > 0
          || day.getYear() > source.effectiveFrom().getYear() ? Estimate.State.STALE : Estimate.State.FRESH;
    // Amount is a reported tariff, not a guarantee about gate matching, supplements or later revisions.
    return new Estimate(row.get(vehicleClass - 1) / 100.0, null, null, state,
        source.network() + " / class " + vehicleClass + " / " + entry + " -> " + exit + " / " + source.url(),
        source.effectiveFrom().atStartOfDay(ZoneId.of("Europe/Paris")).toInstant());
  }
  public static TollCatalog read(InputStream input, Source source) throws IOException {
    var decoder = StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT);
    var reader = new InputStreamReader(input, decoder);
    List<String> header = row(reader, new int[]{0});
    var expected = List.of("id_gare_entree", "libelle_gare_entree", "id_gare_sortie", "libelle_gare_sortie",
        "distance_tarifaire_km", "tarif_classe_1", "tarif_classe_2", "tarif_classe_3", "tarif_classe_4", "tarif_classe_5");
    if (header != null && !header.isEmpty()) header.set(0, header.get(0).replace("\uFEFF", ""));
    boolean namesOnly = List.of("gare_entree", "gare_sortie", "distance_tarifaire_km", "tarif_classe_1",
        "tarif_classe_2", "tarif_classe_3", "tarif_classe_4", "tarif_classe_5").equals(header);
    if (!expected.equals(header) && !namesOnly) throw new IOException("Unsupported tariff CSV header");
    Map<String, Gate> gates = new HashMap<>(); Map<Pair, List<Long>> prices = new HashMap<>(); int[] size = {0};
    List<String> row;
    while ((row = row(reader, size)) != null) {
      if (namesOnly) {
        if (row.size()!=8) throw new IOException("Invalid named tariff row");
        row = List.of(row.get(0),row.get(0),row.get(1),row.get(1),row.get(2),row.get(3),row.get(4),row.get(5),row.get(6),row.get(7));
      }
      if (row.size() != 10 || prices.size() >= 100_000) throw new IOException("Invalid tariff row count or columns");
      try {
        gate(gates, row.get(0), row.get(1)); gate(gates, row.get(2), row.get(3));
        if (row.get(0).equals(row.get(2))) throw new IOException("Self tariff relation");
        decimal(row.get(4), 5000);
        List<Long> amounts = new ArrayList<>();
        for (int i=5; i<10; i++) amounts.add(row.get(i).isBlank() ? null : decimal(row.get(i), 10000).movePointRight(2).longValueExact());
        if (amounts.stream().allMatch(Objects::isNull)) throw new IOException("Empty tariff relation");
        if (prices.put(new Pair(row.get(0), row.get(2)), Collections.unmodifiableList(amounts)) != null)
          throw new IOException("Duplicate directed tariff relation");
      } catch (IllegalArgumentException | ArithmeticException e) { throw new IOException("Invalid tariff value", e); }
    }
    if (prices.isEmpty() || gates.size() > 2000) throw new IOException("Empty or oversized toll catalog");
    return new TollCatalog(source, gates, prices);
  }
  private static BigDecimal decimal(String text, int max) {
    if (!text.matches("[0-9]+(?:\\.[0-9]{1,2})?")) throw new IllegalArgumentException("Decimal expected");
    BigDecimal value = new BigDecimal(text);
    if (value.compareTo(BigDecimal.valueOf(max)) > 0) throw new IllegalArgumentException("Value too large");
    return value;
  }
  private static void gate(Map<String, Gate> gates, String id, String name) throws IOException {
    if (id.isBlank() || id.length()>200 || id.chars().anyMatch(Character::isISOControl)
        || name.isBlank() || name.length() > 200 || name.chars().anyMatch(Character::isISOControl))
      throw new IOException("Invalid gate");
    Gate previous = gates.putIfAbsent(id, new Gate(id, name));
    if (previous != null && !previous.name().equals(name)) throw new IOException("Conflicting gate name");
  }
  /** Bounded RFC 4180 reader, including quoted commas; caller owns input. */
  static List<String> row(Reader in, int[] size) throws IOException {
    var fields = new ArrayList<String>(); var field = new StringBuilder();
    boolean quoted = false, closed = false, seen = false;
    for (;;) {
      int c = in.read(); if (++size[0] > 8_000_000) throw new IOException("Oversized tariff CSV");
      if (c == -1) {
        if (quoted) throw new IOException("Unclosed CSV quote");
        if (!seen) return null;
        fields.add(field.toString()); return fields;
      }
      seen = true;
      if (c == '"') {
        if (quoted) { quoted = false; closed = true; }
        else if (closed) { field.append('"'); quoted = true; closed = false; }
        else if (field.length() == 0) quoted = true;
        else throw new IOException("Unexpected CSV quote");
      } else if (!quoted && (c == ',' || c == '\n')) {
        fields.add(field.toString()); field.setLength(0); closed = false;
        if (fields.size() > 10) throw new IOException("Too many CSV columns");
        if (c == '\n') return fields;
      } else if (!quoted && c == '\r') { /* CRLF; LF terminates the row. */ }
      else {
        if (closed) throw new IOException("Unexpected text after CSV quote");
        field.append((char)c);
        if (field.length() > 1024) throw new IOException("Oversized CSV field");
      }
    }
  }
}
