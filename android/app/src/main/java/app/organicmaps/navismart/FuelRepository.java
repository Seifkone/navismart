package app.organicmaps.navismart;

import android.content.Context;
import android.util.AtomicFile;
import com.navismart.fuel.FuelData;
import com.navismart.fuel.GovernmentFuelReader;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.time.Duration;
import java.time.Instant;

/** National government download, without user coordinates. Validate first, atomically replace second. */
public final class FuelRepository
{
  private static final int MAGIC = 0x4E534631;
  private static final int MAX_ZIP_BYTES = 8_000_000;
  private final AtomicFile mFile;
  private final android.content.SharedPreferences mPreferences;
  public FuelRepository(Context context)
  {
    mFile = new AtomicFile(new File(context.getFilesDir(), "navismart-fuel-v1.bin"));
    mPreferences = context.getSharedPreferences("navismart_fuel", Context.MODE_PRIVATE);
  }
  public FuelData.Snapshot load() throws IOException
  {
    if (!mFile.getBaseFile().exists() && !new File(mFile.getBaseFile().getPath() + ".bak").exists()) return null;
    try (var input = new DataInputStream(mFile.openRead()))
    {
      if (input.readInt() != MAGIC) throw new IOException("Unsupported fuel cache");
      Instant received = Instant.ofEpochMilli(input.readLong());
      return new GovernmentFuelReader().readZip(input, received);
    }
  }
  public synchronized FuelData.Snapshot refresh(Instant now) throws IOException
  {
    FuelData.Snapshot cached = null;
    try { cached = load(); } catch (IOException ignored) { /* Keep corrupt data until a valid replacement exists. */ }
    if (cached != null && !cached.receivedAt().isAfter(now)
        && Duration.between(cached.receivedAt(), now).compareTo(Duration.ofMinutes(10)) < 0) return cached;
    long previous = mPreferences.getLong("last_attempt", 0);
    if (previous > 0 && previous <= now.toEpochMilli() && now.toEpochMilli() - previous < 120_000)
      throw new IOException("Fuel download backoff active");
    mPreferences.edit().putLong("last_attempt", now.toEpochMilli()).apply();
    var connection = (HttpURLConnection) new URL(FuelData.SOURCE).openConnection();
    connection.setConnectTimeout(15_000); connection.setReadTimeout(20_000);
    connection.setInstanceFollowRedirects(false);
    connection.setRequestProperty("Accept", "application/zip,application/octet-stream");
    connection.setRequestProperty("User-Agent", "NaviSmart/0.3");
    byte[] bytes;
    try
    {
      if (connection.getResponseCode() != 200) throw new IOException("Government fuel download unavailable");
      if (connection.getContentLength() > MAX_ZIP_BYTES) throw new IOException("Oversized fuel archive");
      try (InputStream input = connection.getInputStream(); var output = new ByteArrayOutputStream())
      {
        long deadline = System.nanoTime() + 120_000_000_000L;
        byte[] buffer = new byte[16384]; int count;
        while ((count = input.read(buffer)) != -1)
        {
          if (Thread.currentThread().isInterrupted() || System.nanoTime() >= deadline) throw new IOException("Fuel download cancelled or timed out");
          if (output.size() + count > MAX_ZIP_BYTES) throw new IOException("Oversized fuel archive");
          output.write(buffer, 0, count);
        }
        bytes = output.toByteArray();
      }
    }
    finally { connection.disconnect(); }
    FuelData.Snapshot snapshot = new GovernmentFuelReader().readZip(new ByteArrayInputStream(bytes), now);
    FileOutputStream output = null;
    try
    {
      output = mFile.startWrite();
      var data = new DataOutputStream(output);
      data.writeInt(MAGIC); data.writeLong(now.toEpochMilli()); data.write(bytes); data.flush();
      mFile.finishWrite(output); return snapshot;
    }
    catch (IOException error) { if (output != null) mFile.failWrite(output); throw error; }
  }
}
