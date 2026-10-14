package com.navismart.obd;

import java.io.IOException;
import java.io.Writer;
import java.util.Locale;

/** Local per-sample timestamps, with no VIN, Bluetooth address or location in the log. */
public final class ObdCsv
{
  private ObdCsv() {}
  public static void header(Writer writer) throws IOException
  { writer.write("schema,utc,elapsed_ms,pid,signal,value,unit,state\n"); }
  public static void write(Writer writer, ObdProtocol.Sample sample) throws IOException
  {
    writer.write("1," + sample.observedAt() + "," + sample.elapsedMillis() + ","
        + String.format(Locale.ROOT, "%02X", sample.signal().pid) + "," + sample.signal().name() + ","
        + (sample.value() == null ? "" : Double.toString(sample.value())) + "," + sample.signal().unit + "," + sample.state() + "\n");
  }
}
