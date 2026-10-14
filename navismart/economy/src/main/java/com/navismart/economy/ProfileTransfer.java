package com.navismart.economy;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.FilterReader;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/** Explicit user backup transfer. Import retains observation dates and rejects malformed UTF-8. */
public final class ProfileTransfer
{
  private ProfileTransfer() {}
  public static VehicleProfiles.Catalog read(InputStream input) throws IOException
  {
    var utf8 = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT);
    var reader = new FilterReader(new InputStreamReader(input, utf8)) {
      private int remaining = 1_000_000;
      @Override public int read() throws IOException { int ch = in.read(); if (ch >= 0 && --remaining < 0) throw new IOException("Oversized profile backup"); return ch; }
      @Override public int read(char[] chars, int offset, int length) throws IOException
      { int count = in.read(chars, offset, Math.min(length, Math.max(1, remaining + 1))); if (count > 0 && (remaining -= count) < 0) throw new IOException("Oversized profile backup"); return count; }
      @Override public void close() {} // Caller owns the selected document stream.
    };
    try { return new ProfileCodec().read(reader); }
    catch (IllegalArgumentException error) { throw new IOException("Invalid profile backup", error); }
  }
  public static void write(VehicleProfiles.Catalog catalog, OutputStream output) throws IOException
  {
    var buffer = new java.io.StringWriter(); new ProfileCodec().write(catalog, buffer);
    if (buffer.getBuffer().length() > 1_000_000) throw new IOException("Oversized profile backup");
    var writer = new OutputStreamWriter(output, StandardCharsets.UTF_8); writer.write(buffer.toString()); writer.flush();
  }
}
