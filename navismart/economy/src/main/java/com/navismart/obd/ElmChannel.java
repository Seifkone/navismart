package com.navismart.obd;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.function.BooleanSupplier;

/** Prompt framing with bounded memory and monotonic deadlines. A timeout ends the session. */
public final class ElmChannel
{
  private final InputStream input;
  private final OutputStream output;
  public ElmChannel(InputStream input, OutputStream output) { this.input = input; this.output = output; }
  public String request(String command, long timeoutMillis, BooleanSupplier cancelled) throws IOException
  {
    if (!ObdProtocol.allowedCommand(command)) throw new IllegalArgumentException("Command is not read-only whitelisted");
    if (timeoutMillis <= 0 || timeoutMillis > 30000) throw new IllegalArgumentException("Invalid timeout");
    if (cancelled.getAsBoolean()) throw new IOException("Cancelled");
    output.write((command + "\r").getBytes(StandardCharsets.US_ASCII)); output.flush();
    long deadline = System.nanoTime() + timeoutMillis * 1_000_000;
    StringBuilder reply = new StringBuilder();
    while (!cancelled.getAsBoolean() && !Thread.currentThread().isInterrupted())
    {
      if (System.nanoTime() >= deadline) throw new IOException("ELM prompt timeout");
      if (input.available() == 0)
      {
        try { Thread.sleep(10); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IOException("Cancelled", interrupted); }
        continue;
      }
      int value = input.read();
      if (value == -1) throw new IOException("Connection closed");
      if (value == '>') return reply.toString();
      if (value > 127 || (value < 32 && value != '\r' && value != '\n' && value != '\t'))
        throw new IOException("Invalid adapter byte");
      if (reply.length() >= 16384) throw new IOException("Adapter response too long");
      reply.append((char)value);
    }
    throw new IOException("Cancelled");
  }
}
