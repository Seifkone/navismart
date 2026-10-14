package com.navismart.economy;

import static com.navismart.economy.VehicleProfiles.*;
import java.io.*;
import java.time.Instant;
import java.util.List;

public final class ProfileTransferTests
{
  private ProfileTransferTests() {}
  public static void run()
  {
    try
    {
      var at = Instant.parse("2026-01-01T12:00:00Z");
      var profile = new Profile(Simulator.combustion(10), new TripModel.Policy(5, .5, .25), Characteristics.unknown(), at);
      var catalog = new Catalog(List.of(profile), profile.vehicle().id());
      var bytes = new ByteArrayOutputStream(); ProfileTransfer.write(catalog, bytes);
      var restored = ProfileTransfer.read(new ByteArrayInputStream(bytes.toByteArray()));
      if (!catalog.equals(restored) || !at.equals(restored.active().stockObservedAt())) throw new AssertionError("Backup changed observations");
      System.out.println("PASS profile backup round trip preserves catalog and stock dates");
      expectInvalid(new byte[]{(byte)0xC3, (byte)0x28});
      System.out.println("PASS malformed UTF-8 backup rejected");
      expectInvalid(("#" + "x".repeat(1_000_000)).getBytes(java.nio.charset.StandardCharsets.UTF_8));
      System.out.println("PASS oversized profile backup rejected");
      expectInvalid("format.version=99".getBytes(java.nio.charset.StandardCharsets.UTF_8));
      System.out.println("PASS unsupported profile backup rejected");
      System.out.println("PASS: 4 profile transfer scenarios");
    }
    catch (IOException error) { throw new AssertionError(error); }
  }
  private static void expectInvalid(byte[] bytes)
  { try { ProfileTransfer.read(new ByteArrayInputStream(bytes)); } catch (IOException expected) { return; } throw new AssertionError("Invalid backup accepted"); }
}
